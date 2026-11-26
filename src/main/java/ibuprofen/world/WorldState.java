package ibuprofen.world;

import ibuprofen.telemetry.DebugRecorder;
import ibuprofen.world.BedwarsMapCore.GenType;
import ibuprofen.world.BedwarsMapCore.ShopNPC;
import ibuprofen.world.BedwarsMapCore.TeamColor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Layer 1 Shared Blackboard.
 * Updated ONCE at the start of every client tick. Holds map memory, match state,
 * single-pass entity/velocity snapshot, and incremental chunk scanning.
 */
public final class WorldState {

    private BedwarsMapCore mapCore = createDefaultMapCore();
    private final BedwarsMatchState matchState = new BedwarsMatchState();
    private final TickSnapshot snapshot = new TickSnapshot();
    private final ArenaScanner scanner = new ArenaScanner();

    // Thread-safe packet ingestion queues (drained once per tick on main thread)
    private final Queue<String> pendingChat = new ConcurrentLinkedQueue<>();
    private final Queue<String> pendingTitles = new ConcurrentLinkedQueue<>();
    private final Queue<String> pendingActionBars = new ConcurrentLinkedQueue<>();
    private final Queue<BlockChange> pendingBlocks = new ConcurrentLinkedQueue<>();

    private long tickCount = 0;
    private boolean lastMatchStarted = false;
    private String lastPhaseName = "";
    private TeamColor lastTeam = TeamColor.UNKNOWN;
    private boolean autoRecord = true;

    private record BlockChange(BlockPos pos, BlockState state) {}

    private static BedwarsMapCore createDefaultMapCore() {
        return new BedwarsMapCore(
                BedwarsMapCore.createCenteredBounds(0, 0, 0, 136, 160),
                0.0, 0.0, 24, 128);
    }

    public BedwarsMapCore mapCore() {
        return mapCore;
    }

    public BedwarsMatchState matchState() {
        return matchState;
    }

    public TickSnapshot snapshot() {
        return snapshot;
    }

    public ArenaScanner scanner() {
        return scanner;
    }

    public long tickCount() {
        return tickCount;
    }

    public boolean isAutoRecord() {
        return autoRecord;
    }

    public void setAutoRecord(boolean autoRecord) {
        this.autoRecord = autoRecord;
    }

    public void onWorldReset(DebugRecorder recorder, String reason) {
        mapCore = createDefaultMapCore();
        matchState.reset();
        snapshot.clear();
        scanner.reset();
        pendingChat.clear();
        pendingTitles.clear();
        pendingActionBars.clear();
        pendingBlocks.clear();
        lastMatchStarted = false;
        lastPhaseName = "";
        lastTeam = TeamColor.UNKNOWN;
        if (recorder != null && recorder.isRecording()) {
            recorder.logEvent(tickCount, "world_reset", "World changed/reset", reason);
        }
    }

    public void enqueueChat(String cleanText) {
        if (cleanText != null && !cleanText.isBlank()) {
            pendingChat.offer(cleanText);
        }
    }

    public void enqueueTitle(String cleanText) {
        if (cleanText != null && !cleanText.isBlank()) {
            pendingTitles.offer(cleanText);
        }
    }

    public void enqueueActionBar(String cleanText) {
        if (cleanText != null && !cleanText.isBlank()) {
            pendingActionBars.offer(cleanText);
        }
    }

    public void enqueueBlockChange(BlockPos pos, BlockState state) {
        if (pos != null && state != null) {
            pendingBlocks.offer(new BlockChange(pos.immutable(), state));
        }
    }

    public void onChunkLoaded(int cx, int cz) {
        scanner.onChunkLoaded(cx, cz);
    }

    public void onChunkUnloaded(int cx, int cz) {
        scanner.onChunkUnloaded(cx, cz);
    }

    /**
     * Runs once at the start of every client tick.
     */
    public void tick(Minecraft mc, DebugRecorder recorder) {
        tickCount++;
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) {
            return;
        }

        long startNs = System.nanoTime();
        long nowMs = System.currentTimeMillis();

        // 1. Drain chat / title / actionbar packets into matchState
        String line;
        while ((line = pendingChat.poll()) != null) {
            matchState.onChatMessage(line, nowMs);
            if (recorder != null && recorder.isRecording() && isImportantChat(line)) {
                recorder.logEvent(tickCount, "chat", line, null);
            }
        }
        while ((line = pendingTitles.poll()) != null) {
            matchState.onTitle(line);
            if (recorder != null && recorder.isRecording()) {
                recorder.logEvent(tickCount, "title", line, null);
            }
        }
        while ((line = pendingActionBars.poll()) != null) {
            matchState.onActionBar(line);
        }

        // 2. Drain single-block updates
        BlockChange bc;
        int blockLimit = 256;
        while (blockLimit-- > 0 && (bc = pendingBlocks.poll()) != null) {
            scanner.onBlockChanged(bc.pos(), bc.state(), mapCore);
        }

        // 3. Refresh scoreboard at 4 Hz
        if ((tickCount % 5L) == 0L) {
            refreshScoreboard(level);
        }

        // 4. Single-pass entity + true-velocity + inventory snapshot
        snapshot.update(level, player, matchState, tickCount);

        // 5. Feed generator holograms into matchState & mapCore
        for (TickSnapshot.HologramInfo h : snapshot.holograms) {
            String lower = h.text.toLowerCase(Locale.ROOT);
            if (lower.contains("diamond")) {
                matchState.onGeneratorHologram(h.pos.getX(), h.pos.getZ(), GenType.DIAMOND, h.text, nowMs);
                mapCore.ensureGeneratorIndexed(GenType.DIAMOND, h.pos.getX(), h.pos.getY(), h.pos.getZ(), "DIAMOND");
            } else if (lower.contains("emerald")) {
                matchState.onGeneratorHologram(h.pos.getX(), h.pos.getZ(), GenType.EMERALD, h.text, nowMs);
                mapCore.ensureGeneratorIndexed(GenType.EMERALD, h.pos.getX(), h.pos.getY(), h.pos.getZ(), "EMERALD");
            }
        }

        // 6. Register discovered shopkeepers in mapCore
        for (TickSnapshot.ShopKeeperInfo sk : snapshot.shopKeepers) {
            String label = sk.isUpgrades ? "UPGRADES:NPC" : "ITEM_SHOP:NPC";
            mapCore.addOrUpdateShopNpc(new ShopNPC(label, sk.pos.getX(), sk.pos.getY(), sk.pos.getZ()));
        }

        // 7. Incremental chunk scan (budgeted: 1 chunk/tick max)
        scanner.tickScan(level, mapCore, player.blockPosition());

        // 8. Detect match transitions & auto-record
        detectTransitions(recorder);

        double elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0;
        if (elapsedMs > 20.0 && recorder != null && recorder.isRecording()) {
            recorder.logSlowTick(tickCount, elapsedMs, "worldstate_tick");
        }
    }

    private void refreshScoreboard(ClientLevel level) {
        Scoreboard sb = level.getScoreboard();
        Objective sidebar = sb.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (sidebar == null) {
            return;
        }
        String title = stripColors(sidebar.getDisplayName() != null ? sidebar.getDisplayName().getString() : "");
        List<PlayerScoreEntry> entries = new ArrayList<>(sb.listPlayerScores(sidebar));
        entries.sort(Comparator.comparingInt(PlayerScoreEntry::value).reversed());
        List<String> lines = new ArrayList<>(entries.size());
        for (PlayerScoreEntry entry : entries) {
            PlayerTeam team = sb.getPlayersTeam(entry.owner());
            String formatted = stripColors(PlayerTeam.formatNameForTeam(team, entry.ownerName()).getString());
            String tail = entry.display() != null ? stripColors(entry.display().getString()) : "";
            String full = tail.isBlank() ? formatted : (formatted + " " + tail).trim();
            if (!full.isBlank()) {
                lines.add(full);
            }
        }
        matchState.updateScoreboard(title, lines);
    }

    private void detectTransitions(DebugRecorder recorder) {
        boolean started = matchState.isMatchStarted();
        String phase = matchState.getNextPhase().name();
        TeamColor team = matchState.getHomeTeam();

        if (started && !lastMatchStarted) {
            if (autoRecord && recorder != null && !recorder.isRecording()) {
                recorder.startSession(team != TeamColor.UNKNOWN ? team.name().toLowerCase(Locale.ROOT) : "bedwars");
            }
            if (recorder != null && recorder.isRecording()) {
                recorder.logEvent(tickCount, "match_start", "BedWars match started", "team=" + team);
            }
        }
        lastMatchStarted = started;

        if (!phase.equals(lastPhaseName)) {
            if (recorder != null && recorder.isRecording()) {
                recorder.logEvent(tickCount, "phase", "Next phase -> " + phase, "prev=" + lastPhaseName);
            }
            lastPhaseName = phase;
        }

        if (team != lastTeam) {
            if (recorder != null && recorder.isRecording()) {
                recorder.logEvent(tickCount, "team", "Home team -> " + team, null);
            }
            lastTeam = team;
        }
    }

    private static boolean isImportantChat(String line) {
        String l = line.toLowerCase(Locale.ROOT);
        return l.contains("bed") || l.contains("eliminated") || l.contains("final kill")
                || l.contains("diamond") || l.contains("emerald") || l.contains("victory")
                || l.contains("game over") || l.contains("purchased");
    }

    public static String stripColors(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("§[0-9a-fk-orA-FK-OR]", "").trim();
    }
}
