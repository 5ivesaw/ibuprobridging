package ibuprofen.world;

import ibuprofen.event.MatchEvent;
import ibuprofen.event.MatchEventBus;
import ibuprofen.memory.MatchMemory;
import ibuprofen.telemetry.DebugRecorder;
import ibuprofen.world.BedwarsMapCore.GenPOI;
import ibuprofen.world.BedwarsMapCore.GenType;
import ibuprofen.world.BedwarsMapCore.ShopNPC;
import ibuprofen.world.BedwarsMapCore.TeamColor;
import ibuprofen.world.BedwarsMatchState.MatchPhase;
import ibuprofen.world.BedwarsMatchState.ParsedChatOutcome;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Layer 1 Shared Blackboard.
 * Updated ONCE at the start of every client tick. Publishes typed {@link MatchEvent}s
 * onto {@link MatchEventBus} and preserves arena/match memory across mid-match respawns.
 */
public final class WorldState {

    private static final Pattern KILL_LINE_PATTERN = Pattern.compile(
            "^(?:\\[[^\\]]+\\]\\s*)?([A-Za-z0-9_]{2,16})\\s+(?:has been killed by|was killed by|was slain by|was knocked into the void by|was thrown into the void by|was pushed into the void by|was shot by|was blown up by)\\s+([A-Za-z0-9_]{2,16})",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SOLO_DEATH_PATTERN = Pattern.compile(
            "^(?:\\[[^\\]]+\\]\\s*)?([A-Za-z0-9_]{2,16})\\s+(?:fell into the void|died|burnt|drowned)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern BUYER_PATTERN = Pattern.compile(
            "(?i)([A-Za-z0-9_]{2,16})\\s+(?:purchased|unlocked|bought)\\s+(.+)$"
    );

    private BedwarsMapCore mapCore = createCenteredMapCore(0, 0);
    private final BedwarsMatchState matchState = new BedwarsMatchState();
    private final TickSnapshot snapshot = new TickSnapshot();
    private final ArenaScanner scanner = new ArenaScanner();

    private final Queue<String> pendingChat = new ConcurrentLinkedQueue<>();
    private final Queue<String> pendingTitles = new ConcurrentLinkedQueue<>();
    private final Queue<String> pendingSubtitles = new ConcurrentLinkedQueue<>();
    private final Queue<String> pendingActionBars = new ConcurrentLinkedQueue<>();
    private final Queue<String[]> pendingTabList = new ConcurrentLinkedQueue<>();
    private final Queue<BlockChange> pendingBlocks = new ConcurrentLinkedQueue<>();

    private long tickCount = 0;
    private long matchStartTick = -1L;
    private boolean lastMatchStarted = false;
    private String lastPhaseName = "";
    private TeamColor lastTeam = TeamColor.UNKNOWN;
    private String lastPublishedTitle = "";
    private long lastPublishedTitleTick = -100L;
    private boolean autoRecord = true;

    private record BlockChange(BlockPos pos, BlockState state) {}

    private static BedwarsMapCore createCenteredMapCore(int centerX, int centerZ) {
        return new BedwarsMapCore(
                BedwarsMapCore.createCenteredBounds(centerX, 0, centerZ, 136, 160),
                centerX, centerZ, 24, 144);
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

    /**
     * Handles {@code setLevel}: mid-match respawns preserve map & match memory;
     * real lobby/hub changes stop any active debug log and perform a full reset.
     */
    public void onLevelSwap(MatchEventBus bus, DebugRecorder recorder) {
        TeamColor home = matchState.getHomeTeam();
        boolean activeAliveMatch = matchState.isMatchStarted()
                && !matchState.isMatchEnded()
                && (home == TeamColor.UNKNOWN || !matchState.isTeamEliminated(home))
                && matchState.getNextPhase() != MatchPhase.PRE_GAME;
        if (activeAliveMatch) {
            snapshot.clearTransientVelocities();
            pendingBlocks.clear();
            if (bus != null) {
                bus.publish(new MatchEvent.MatchLifecycleEvent(tickCount, "respawn", "mid_match_respawn"));
            }
            return;
        }
        if (recorder != null && recorder.isRecording()) {
            recorder.stopSession("level_change");
        }
        onFullReset(bus, "level_change");
    }

    public void onFullReset(MatchEventBus bus, String reason) {
        mapCore = createCenteredMapCore(0, 0);
        matchState.reset();
        snapshot.clear();
        scanner.reset();
        pendingChat.clear();
        pendingTitles.clear();
        pendingSubtitles.clear();
        pendingActionBars.clear();
        pendingTabList.clear();
        pendingBlocks.clear();
        matchStartTick = -1L;
        lastMatchStarted = false;
        lastPhaseName = "";
        lastTeam = TeamColor.UNKNOWN;
        lastPublishedTitle = "";
        lastPublishedTitleTick = -100L;
        if (bus != null) {
            bus.publish(new MatchEvent.MatchLifecycleEvent(tickCount, "world_reset", reason));
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

    public void enqueueSubtitle(String cleanText) {
        if (cleanText != null && !cleanText.isBlank()) {
            pendingSubtitles.offer(cleanText);
        }
    }

    public void enqueueActionBar(String cleanText) {
        if (cleanText != null && !cleanText.isBlank()) {
            pendingActionBars.offer(cleanText);
        }
    }

    public void enqueueTabList(String header, String footer) {
        pendingTabList.offer(new String[]{header != null ? header : "", footer != null ? footer : ""});
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

    public void tick(Minecraft mc, MatchEventBus bus, DebugRecorder recorder, MatchMemory matchMemory) {
        tickCount++;
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) {
            return;
        }

        long startNs = System.nanoTime();
        long nowMs = System.currentTimeMillis();

        // Recenter mapCore bounds if the arena is located far from (0, 0)
        BlockPos pPos = player.blockPosition();
        if (!mapCore.bounds().contains(pPos.getX(), Math.max(1, Math.min(150, pPos.getY())), pPos.getZ())) {
            mapCore = createCenteredMapCore(pPos.getX(), pPos.getZ());
            scanner.reset();
        }

        // 1. Drain chat / title / subtitle / actionbar / tablist packets into MatchState & MatchEventBus
        drainChat(bus, recorder, nowMs);
        drainHudPackets(bus);

        // 2. Drain single-block updates
        BlockChange bc;
        int blockLimit = 256;
        while (blockLimit-- > 0 && (bc = pendingBlocks.poll()) != null) {
            scanner.onBlockChanged(level, bc.pos(), bc.state(), mapCore);
        }

        // 3. Refresh scoreboard at 4 Hz
        if ((tickCount % 5L) == 0L) {
            refreshScoreboard(level, bus, recorder);
        }

        // 4. Single-pass entity + true-velocity + gear + inventory snapshot
        snapshot.update(level, player, matchState, tickCount);

        // 5. Feed generator holograms into MatchState, MapCore (ground-snapped), and MatchMemory clocks
        feedGeneratorHolograms(level, nowMs, matchMemory);

        // 6. Register discovered shopkeepers in mapCore
        for (TickSnapshot.ShopKeeperInfo sk : snapshot.shopKeepers) {
            mapCore.addOrUpdateShopNpc(new ShopNPC(sk.role + ":NPC", sk.pos.getX(), sk.pos.getY(), sk.pos.getZ()));
        }

        // 7. Fast incremental chunk scan (budgeted: 2 chunks/tick max, skips air sections)
        scanner.tickScan(level, mapCore, pPos);

        // 8. Detect match transitions & auto-record
        detectTransitions(bus, recorder);

        double elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0;
        if (elapsedMs > 20.0 && bus != null) {
            bus.publish(new MatchEvent.SlowTickEvent(tickCount, elapsedMs, "worldstate_tick"));
        }
    }

    private void drainChat(MatchEventBus bus, DebugRecorder recorder, long nowMs) {
        String line;
        while ((line = pendingChat.poll()) != null) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lastMatchStarted && matchStartTick >= 0 && (tickCount - matchStartTick) > 100L
                    && isNewMatchOrLobbyMessage(lower)) {
                if (recorder != null && recorder.isRecording()) {
                    recorder.stopSession("new_match");
                }
                onFullReset(bus, "new_match_chat");
            }
            ParsedChatOutcome outcome = matchState.onChatMessage(line, nowMs);
            if (bus == null) {
                continue;
            }
            if (outcome.destroyedBedTeam() != TeamColor.UNKNOWN) {
                mapCore.markTeamBedDestroyed(outcome.destroyedBedTeam());
                bus.publish(new MatchEvent.BedBrokenEvent(tickCount, outcome.destroyedBedTeam(), line));
                continue;
            }
            if (BedwarsMatchState.isTrapTriggeredText(lower)) {
                bus.publish(new MatchEvent.TrapTriggeredEvent(tickCount, line));
                continue;
            }
            Matcher buyMatcher = BUYER_PATTERN.matcher(line);
            if (buyMatcher.find() && (lower.contains("upgrade") || lower.contains("trap")
                    || lower.contains("armor") || lower.contains("sword")
                    || lower.contains("heal pool") || lower.contains("forge") || lower.contains("miner"))) {
                bus.publish(new MatchEvent.UpgradePurchasedEvent(tickCount, buyMatcher.group(1), buyMatcher.group(2).trim()));
                continue;
            }
            MatchEvent.PlayerKillEvent killEv = parseKillEvent(line, lower, outcome.finalKillEvent());
            if (killEv != null) {
                bus.publish(killEv);
                continue;
            }
            if (outcome.matchStarted() || outcome.countdownActive()
                    || lower.contains("eliminated") || lower.contains("generators are now")
                    || lower.contains("tier ii") || lower.contains("tier iii")
                    || lower.contains("victory") || lower.contains("game over")) {
                bus.publish(new MatchEvent.ChatNoticeEvent(tickCount, line));
            }
        }
    }

    private MatchEvent.PlayerKillEvent parseKillEvent(String line, String lower, boolean isFinal) {
        boolean isVoid = lower.contains("void");
        Matcher km = KILL_LINE_PATTERN.matcher(line);
        if (km.find()) {
            return new MatchEvent.PlayerKillEvent(tickCount, km.group(1), km.group(2), isFinal, isVoid, line);
        }
        Matcher sm = SOLO_DEATH_PATTERN.matcher(line);
        if (sm.find()) {
            return new MatchEvent.PlayerKillEvent(tickCount, sm.group(1), "", isFinal, isVoid, line);
        }
        return null;
    }

    private void drainHudPackets(MatchEventBus bus) {
        String title;
        while ((title = pendingTitles.poll()) != null) {
            matchState.onTitle(title);
            if (bus != null && (!title.equals(lastPublishedTitle) || (tickCount - lastPublishedTitleTick) >= 60L)) {
                lastPublishedTitle = title;
                lastPublishedTitleTick = tickCount;
                bus.publish(new MatchEvent.TitleChangedEvent(tickCount, title));
            }
        }
        String sub;
        while ((sub = pendingSubtitles.poll()) != null) {
            matchState.onSubtitle(sub);
        }
        String bar;
        while ((bar = pendingActionBars.poll()) != null) {
            matchState.onActionBar(bar);
        }
        String[] tab;
        while ((tab = pendingTabList.poll()) != null) {
            matchState.onTabListHeaderFooter(tab[0], tab[1]);
        }
    }

    private void feedGeneratorHolograms(ClientLevel level, long nowMs, MatchMemory matchMemory) {
        if (snapshot.holograms.isEmpty()) {
            return;
        }
        for (TickSnapshot.HologramInfo h : snapshot.holograms) {
            if (h.lowerText.contains("diamond")) {
                int floorY = snapHologramToFloorY(level, h.pos);
                matchState.onGeneratorHologram(h.pos.getX(), h.pos.getZ(), GenType.DIAMOND, h.text, nowMs);
                mapCore.ensureGeneratorIndexed(GenType.DIAMOND, h.pos.getX(), floorY, h.pos.getZ(), "DIAMOND");
                recordGenClockIfTimer(GenType.DIAMOND, h, nowMs, matchMemory);
            } else if (h.lowerText.contains("emerald")) {
                int floorY = snapHologramToFloorY(level, h.pos);
                matchState.onGeneratorHologram(h.pos.getX(), h.pos.getZ(), GenType.EMERALD, h.text, nowMs);
                mapCore.ensureGeneratorIndexed(GenType.EMERALD, h.pos.getX(), floorY, h.pos.getZ(), "EMERALD");
                recordGenClockIfTimer(GenType.EMERALD, h, nowMs, matchMemory);
            }
        }
        GenPOI[] gens = mapCore.getGensSnapshot();
        if (gens.length == 0) {
            return;
        }
        for (TickSnapshot.HologramInfo h : snapshot.holograms) {
            if (h.lowerText.contains("diamond") || h.lowerText.contains("emerald")) {
                continue;
            }
            if (!h.lowerText.contains("spawn") && !h.lowerText.contains("tier") && !h.lowerText.contains("sec")) {
                continue;
            }
            GenType bestType = null;
            double bestDistSq = 16.0;
            for (GenPOI gp : gens) {
                if (gp.type() != GenType.DIAMOND && gp.type() != GenType.EMERALD) {
                    continue;
                }
                double dx = h.x - (gp.x() + 0.5);
                double dz = h.z - (gp.z() + 0.5);
                double dSq = dx * dx + dz * dz;
                if (dSq <= bestDistSq) {
                    bestDistSq = dSq;
                    bestType = gp.type();
                }
            }
            if (bestType != null) {
                matchState.onGeneratorHologram(h.pos.getX(), h.pos.getZ(), bestType, h.text, nowMs);
                recordGenClockIfTimer(bestType, h, nowMs, matchMemory);
            }
        }
    }

    private static int snapHologramToFloorY(ClientLevel level, BlockPos holoPos) {
        if (level == null || holoPos == null) {
            return holoPos != null ? holoPos.getY() : 64;
        }
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos(holoPos.getX(), holoPos.getY(), holoPos.getZ());
        for (int dy = 1; dy <= 6; dy++) {
            mut.setY(holoPos.getY() - dy);
            BlockState st = level.getBlockState(mut);
            if (!st.isAir() && st.getFluidState().isEmpty() && !st.getCollisionShape(level, mut).isEmpty()) {
                return mut.getY() + 1;
            }
        }
        return holoPos.getY();
    }

    private static void recordGenClockIfTimer(GenType type, TickSnapshot.HologramInfo h, long nowMs, MatchMemory mem) {
        if (mem == null) {
            return;
        }
        int secs = BedwarsMatchState.extractSeconds(h.text);
        if (secs >= 0) {
            mem.recordGeneratorTimer(type, h.pos.getX(), h.pos.getZ(), secs, nowMs);
        }
    }

    private static boolean isNewMatchOrLobbyMessage(String lower) {
        return lower.contains("has joined! (")
                || lower.contains("will start in")
                || lower.contains("game starts in")
                || lower.contains("starting in")
                || lower.contains("you are now in team")
                || lower.contains("goodluck with your bedwars game")
                || lower.contains("the game has started");
    }

    private void refreshScoreboard(ClientLevel level, MatchEventBus bus, DebugRecorder recorder) {
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
        boolean wasStarted = matchState.isMatchStarted();
        matchState.updateScoreboard(title, lines);
        if (wasStarted && (matchState.getNextPhase() == MatchPhase.PRE_GAME
                || !title.toLowerCase(Locale.ROOT).contains("bed"))) {
            if (recorder != null && recorder.isRecording()) {
                recorder.stopSession("lobby_transition");
            }
            onFullReset(bus, "lobby_transition");
        }
    }

    private void detectTransitions(MatchEventBus bus, DebugRecorder recorder) {
        boolean started = matchState.isMatchStarted();
        String phase = matchState.getNextPhase().name();
        TeamColor team = matchState.getHomeTeam();

        if (started && !lastMatchStarted) {
            matchStartTick = tickCount;
            if (autoRecord && recorder != null && !recorder.isRecording()) {
                recorder.startSession(team != TeamColor.UNKNOWN ? team.name().toLowerCase(Locale.ROOT) : "bedwars");
            }
            if (bus != null) {
                bus.publish(new MatchEvent.MatchLifecycleEvent(tickCount, "match_start", "team=" + team));
            }
        }
        lastMatchStarted = started;

        if (!phase.equals(lastPhaseName)) {
            if (bus != null) {
                bus.publish(new MatchEvent.PhaseChangedEvent(tickCount, lastPhaseName, phase));
            }
            lastPhaseName = phase;
        }

        if (team != lastTeam) {
            if (bus != null && team != TeamColor.UNKNOWN) {
                bus.publish(new MatchEvent.TeamAssignedEvent(tickCount, team));
            }
            lastTeam = team;
        }
    }

    public static String stripColors(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("§[0-9a-fk-orA-FK-OR]", "").trim();
    }
}
