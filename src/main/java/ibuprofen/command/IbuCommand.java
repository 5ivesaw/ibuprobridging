package ibuprofen.command;

import ibuprofen.IbuprofenMod;
import ibuprofen.memory.EnemyDossier;
import ibuprofen.memory.MatchMemory;
import ibuprofen.memory.ShoppingWishList;
import ibuprofen.memory.TeamDossier;
import ibuprofen.motor.InputArbiter;
import ibuprofen.telemetry.DebugRecorder;
import ibuprofen.world.BedwarsMapCore;
import ibuprofen.world.BedwarsMatchState;
import ibuprofen.world.TickSnapshot;
import ibuprofen.world.WorldState;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Local client chat command router for {@code #ibu ...}.
 * Intercepted before sending to the server so it works safely on every BedWars server.
 */
public final class IbuCommand {

    private IbuCommand() {}

    public static boolean tryHandle(IbuprofenMod mod, String rawMessage) {
        if (rawMessage == null) {
            return false;
        }
        String trimmed = rawMessage.trim();
        if (!trimmed.toLowerCase(Locale.ROOT).startsWith("#ibu")) {
            return false;
        }
        String rest = trimmed.length() > 4 ? trimmed.substring(4).trim() : "";
        String[] parts = rest.isEmpty() ? new String[0] : rest.split("\\s+");
        String sub = parts.length > 0 ? parts[0].toLowerCase(Locale.ROOT) : "help";

        switch (sub) {
            case "status" -> showStatus(mod);
            case "mem", "memory" -> showMemory(mod);
            case "stop" -> {
                mod.inputArbiter().releaseAll(mod.worldState().tickCount(), mod.eventBus(), "manual_stop");
                say("Stopped. All 5 body channels returned to human control.");
            }
            case "rec", "record" -> handleRecord(mod, parts);
            case "help" -> showHelp();
            default -> say("Unknown command: " + sub + ". Try #ibu help");
        }
        return true;
    }

    private static void showStatus(IbuprofenMod mod) {
        WorldState ws = mod.worldState();
        BedwarsMatchState ms = ws.matchState();
        BedwarsMapCore map = ws.mapCore();
        TickSnapshot snap = ws.snapshot();
        InputArbiter arbiter = mod.inputArbiter();
        DebugRecorder rec = mod.debugRecorder();

        String mapName = ms.getMapName() != null && !ms.getMapName().isBlank() ? ms.getMapName() : "unknown";
        say("=== Ibuprobridging Phase 0.5 Status ===");
        say("Match: " + (ms.isMatchStarted() ? "LIVE" : "LOBBY/IDLE")
                + " | Map: " + mapName
                + " | Team: " + ms.getHomeTeam()
                + " | Next: " + ms.getNextPhase());
        say("World: beds=" + map.getBeds().size()
                + " gens=" + map.getGens().size()
                + " shops=" + map.getShopNpcs().size()
                + " scanQueue=" + ws.scanner().pendingChunkCount());
        say("Resources: iron=" + snap.ironCount
                + " gold=" + snap.goldCount
                + " dia=" + snap.diamondCount
                + " em=" + snap.emeraldCount
                + " blocks=" + snap.blockCount);
        if (snap.nearestEnemy != null) {
            double dist = Math.sqrt(snap.nearestEnemy.distSq);
            say(String.format(Locale.ROOT,
                    "Enemies: %d | Nearest: %s (%.1fm, speed=%.2f b/t)",
                    snap.enemies.size(), snap.nearestEnemy.name, dist, snap.nearestEnemy.horizontalSpeed));
        } else {
            say("Enemies: 0 nearby");
        }
        say("Channels: " + arbiter.formatActiveSummary());
        if (rec != null && rec.isRecording()) {
            long kb = rec.uncompressedBytes() / 1024L;
            say("DebugRec: ON (" + rec.lineCount() + " lines, ~" + kb + " KB raw, gzipped on disk)");
        } else {
            say("DebugRec: OFF (autoRecord=" + ws.isAutoRecord() + ")");
        }
    }

    private static void showMemory(IbuprofenMod mod) {
        MatchMemory mem = mod.matchMemory();
        long tick = mod.worldState().tickCount();
        say("=== Ibuprobridging MatchMemory ===");

        Map<BedwarsMapCore.TeamColor, TeamDossier> teams = mem.getAllTeamDossiers();
        if (teams.isEmpty()) {
            say("Beds remembered: none yet");
        } else {
            say("Beds remembered:");
            for (TeamDossier td : teams.values()) {
                say("  " + td.summaryString(tick));
            }
        }

        Map<String, EnemyDossier> enemies = mem.getAllEnemyDossiers();
        if (!enemies.isEmpty()) {
            say("Enemies remembered (" + enemies.size() + "):");
            int shown = 0;
            for (EnemyDossier ed : enemies.values()) {
                if (shown++ >= 5) {
                    say("  ... (+" + (enemies.size() - 5) + " more)");
                    break;
                }
                say("  " + ed.summaryString(tick));
            }
        }

        List<ShoppingWishList.WishItem> wish = mem.computeWishList();
        if (wish.isEmpty()) {
            say("Shopping Wish List: empty");
        } else {
            say("Shopping Wish List:");
            for (int i = 0; i < Math.min(5, wish.size()); i++) {
                ShoppingWishList.WishItem w = wish.get(i);
                say("  [" + w.priority() + "] " + w.itemId() + " <- " + w.reason());
            }
        }
    }

    private static void handleRecord(IbuprofenMod mod, String[] parts) {
        DebugRecorder rec = mod.debugRecorder();
        if (rec == null) {
            say("Debug recorder not initialized.");
            return;
        }
        String action = parts.length > 1 ? parts[1].toLowerCase(Locale.ROOT) : "status";
        switch (action) {
            case "start" -> {
                if (rec.isRecording()) {
                    say("Already recording: " + rec.currentFile().getFileName());
                    return;
                }
                String tag = parts.length > 2 ? parts[2] : "manual";
                Path p = rec.startSession(tag);
                if (p != null) {
                    say("Started lightweight debug log: " + p.getFileName());
                } else {
                    say("Failed to start debug log.");
                }
            }
            case "stop" -> {
                if (!rec.isRecording()) {
                    say("Debug recorder is not running.");
                    return;
                }
                Path file = rec.currentFile();
                long lines = rec.lineCount();
                rec.stopSession("manual_command");
                say("Stopped debug log (" + lines + " lines): " + (file != null ? file.getFileName() : "saved"));
            }
            case "auto" -> {
                boolean next = !mod.worldState().isAutoRecord();
                mod.worldState().setAutoRecord(next);
                say("Auto-record on match start: " + (next ? "ON" : "OFF"));
            }
            default -> {
                if (rec.isRecording()) {
                    say("Recording ON: " + rec.currentFile().getFileName()
                            + " (" + rec.lineCount() + " lines, dropped=" + rec.droppedLines() + ")");
                } else {
                    say("Recording OFF. Use #ibu rec start | stop | auto");
                }
            }
        }
    }

    private static void showHelp() {
        say("=== Ibuprobridging Commands (Phase 0.5) ===");
        say("#ibu status        - Show map, team, beds, gens, shops, enemies & channels");
        say("#ibu mem           - Show remembered bed defenses, enemy gear & shopping wish list");
        say("#ibu rec start     - Start lightweight .jsonl.gz debug log");
        say("#ibu rec stop      - Stop and flush debug log");
        say("#ibu rec auto      - Toggle auto-recording when a BedWars match starts");
        say("#ibu stop          - Emergency release of all 5 body channels");
    }

    public static void say(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.gui != null && mc.gui.hud != null && mc.gui.hud.getChat() != null) {
            mc.gui.hud.getChat().addPlayerMessage(Component.literal("[Ibu] " + text), null, null);
        }
    }
}
