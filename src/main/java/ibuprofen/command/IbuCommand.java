package ibuprofen.command;

import ibuprofen.IbuprofenMod;
import ibuprofen.memory.EnemyDossier;
import ibuprofen.memory.MatchMemory;
import ibuprofen.memory.ShoppingWishList;
import ibuprofen.memory.TeamDossier;
import ibuprofen.motor.InputArbiter;
import ibuprofen.skills.nav.WalkSkill;
import ibuprofen.telemetry.DebugRecorder;
import ibuprofen.world.BedwarsMapCore;
import ibuprofen.world.BedwarsMapCore.BedPOI;
import ibuprofen.world.BedwarsMapCore.GenPOI;
import ibuprofen.world.BedwarsMapCore.ShopNPC;
import ibuprofen.world.BedwarsMatchState;
import ibuprofen.world.TickSnapshot;
import ibuprofen.world.WorldState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.Comparator;
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
            case "test" -> handleTest(mod, parts);
            case "stop" -> {
                mod.stopActiveSkill("manual_stop");
                say("Stopped. All 5 body channels returned to human control.");
            }
            case "rec", "record" -> handleRecord(mod, parts);
            case "help" -> showHelp();
            default -> say("Unknown command: " + sub + ". Try #ibu help");
        }
        return true;
    }

    private static void handleTest(IbuprofenMod mod, String[] parts) {
        if (parts.length < 2) {
            say("Usage: #ibu test walk <shop | gen | bed | x y z>");
            return;
        }
        String skillArg = parts[1].toLowerCase(Locale.ROOT);
        if (!"walk".equals(skillArg)) {
            say("Only Phase 1 walk test is active right now: #ibu test walk <shop|gen|bed|x y z>");
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc != null ? mc.player : null;
        if (p == null) {
            say("Not in a world.");
            return;
        }
        if (parts.length == 5) {
            try {
                int x = Integer.parseInt(parts[2]);
                int y = Integer.parseInt(parts[3]);
                int z = Integer.parseInt(parts[4]);
                mod.startSkill(new WalkSkill(x, y, z, 0.85, x + "," + y + "," + z));
                say("Walking to (" + x + ", " + y + ", " + z + ")...");
            } catch (NumberFormatException e) {
                say("Invalid coords. Use integers: #ibu test walk <x> <y> <z>");
            }
            return;
        }
        String target = parts.length >= 3 ? parts[2].toLowerCase(Locale.ROOT) : "shop";
        BedwarsMapCore map = mod.worldState().mapCore();
        TickSnapshot snap = mod.worldState().snapshot();

        switch (target) {
            case "shop" -> {
                TickSnapshot.ShopKeeperInfo liveShop = snap.shopKeepers.stream()
                        .min(Comparator.comparingDouble(s -> s.distSq)).orElse(null);
                if (liveShop != null) {
                    mod.startSkill(new WalkSkill(
                            liveShop.pos.getX(), liveShop.pos.getY(), liveShop.pos.getZ(), 2.2, "shop"));
                    say("Walking to nearest shopkeeper (" + liveShop.role + ")...");
                    return;
                }
                ShopNPC cached = map.getShopNpcs().stream()
                        .min(Comparator.comparingDouble(s -> distSq(p, s.x(), s.y(), s.z()))).orElse(null);
                if (cached != null) {
                    mod.startSkill(new WalkSkill(cached.x(), cached.y(), cached.z(), 2.2, "shop"));
                    say("Walking to remembered shopkeeper at (" + cached.x() + "," + cached.y() + "," + cached.z() + ")...");
                } else {
                    say("No shopkeeper discovered yet.");
                }
            }
            case "gen" -> {
                GenPOI gen = map.getGens().stream()
                        .min(Comparator.comparingDouble(g -> distSq(p, g.x(), g.y(), g.z()))).orElse(null);
                if (gen != null) {
                    mod.startSkill(new WalkSkill(gen.x(), gen.y(), gen.z(), 1.2, gen.type().name()));
                    say("Walking to nearest " + gen.type() + " generator...");
                } else {
                    say("No Diamond/Emerald generator discovered yet.");
                }
            }
            case "bed" -> {
                BedPOI bed = map.getBeds().stream()
                        .filter(b -> !b.destroyed())
                        .min(Comparator.comparingDouble(b -> distSq(p, b.x(), b.y(), b.z()))).orElse(null);
                if (bed != null) {
                    mod.startSkill(new WalkSkill(bed.x(), bed.y(), bed.z(), 2.0, bed.team() + "_bed"));
                    say("Walking to nearest bed (" + bed.team() + ")...");
                } else {
                    say("No living bed discovered yet.");
                }
            }
            default -> say("Unknown walk target: " + target + ". Use shop | gen | bed | <x> <y> <z>");
        }
    }

    private static double distSq(LocalPlayer p, int x, int y, int z) {
        double dx = (x + 0.5) - p.getX();
        double dy = y - p.getY();
        double dz = (z + 0.5) - p.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static void showStatus(IbuprofenMod mod) {
        WorldState ws = mod.worldState();
        BedwarsMatchState ms = ws.matchState();
        BedwarsMapCore map = ws.mapCore();
        TickSnapshot snap = ws.snapshot();
        InputArbiter arbiter = mod.inputArbiter();
        DebugRecorder rec = mod.debugRecorder();
        Minecraft mc = Minecraft.getInstance();
        int ping = IbuprofenMod.queryPingMs(mc, mc != null ? mc.player : null);
        int fps = mc != null ? mc.getFps() : -1;

        String mapName = ms.getMapName() != null && !ms.getMapName().isBlank() ? ms.getMapName() : "unknown";
        say("=== Ibuprobridging Phase 1 Status ===");
        say("Match: " + (ms.isMatchStarted() ? "LIVE" : "LOBBY/IDLE")
                + " | Map: " + mapName
                + " | Team: " + ms.getHomeTeam()
                + " | Next: " + ms.getNextPhase());
        say("Client: ping=" + ping + "ms | fps=" + fps + " | ActiveSkill: " + mod.activeSkillName());
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
        say("=== Ibuprobridging Commands (Phase 1) ===");
        say("#ibu status                - Show map, ping, fps, active skill, beds/shops & channels");
        say("#ibu mem                   - Show remembered bed defenses, enemy gear & wish list");
        say("#ibu test walk shop|gen|bed- Walk cleanly to nearest shopkeeper, gen, or bed");
        say("#ibu test walk <x> <y> <z> - Walk cleanly to exact coordinates");
        say("#ibu rec start|stop|auto   - Control lightweight .jsonl.gz debug log");
        say("#ibu stop                  - Emergency stop (returns all 5 channels to you)");
    }

    public static void say(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.gui != null && mc.gui.hud != null && mc.gui.hud.getChat() != null) {
            mc.gui.hud.getChat().addPlayerMessage(Component.literal("[Ibu] " + text), null, null);
        }
    }
}
