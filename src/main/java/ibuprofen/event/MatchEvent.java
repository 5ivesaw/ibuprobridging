package ibuprofen.event;

import ibuprofen.world.BedwarsDefensePlanner.DefenseBlockKind;
import ibuprofen.world.BedwarsMapCore.TeamColor;

import java.util.Locale;

/**
 * Typed gameplay, perception, and control events flowing through {@link MatchEventBus}.
 * Consumed simultaneously by MatchMemory, MatchPlanner (Phase 6), and DebugRecorder.
 */
public sealed interface MatchEvent permits
        MatchEvent.SessionEvent,
        MatchEvent.MatchLifecycleEvent,
        MatchEvent.PhaseChangedEvent,
        MatchEvent.TeamAssignedEvent,
        MatchEvent.BedBrokenEvent,
        MatchEvent.BedDefenseObservedEvent,
        MatchEvent.PlayerKillEvent,
        MatchEvent.TrapTriggeredEvent,
        MatchEvent.UpgradePurchasedEvent,
        MatchEvent.EnemySpottedEvent,
        MatchEvent.TitleChangedEvent,
        MatchEvent.ChannelChangedEvent,
        MatchEvent.SkillOutcomeEvent,
        MatchEvent.SlowTickEvent,
        MatchEvent.ChatNoticeEvent {

    long tick();

    String kind();

    String toJsonLine();

    record SessionEvent(long tick, boolean started, String tagOrReason) implements MatchEvent {
        @Override
        public String kind() {
            return started ? "session_start" : "session_end";
        }

        @Override
        public String toJsonLine() {
            return "{\"t\":" + tick + ",\"ts\":" + System.currentTimeMillis()
                    + ",\"k\":\"" + kind() + "\",\"msg\":\""
                    + JsonUtil.esc(started ? "Recording started" : "Recording stopped")
                    + "\",\"d\":\"" + JsonUtil.esc(tagOrReason) + "\"}";
        }
    }

    record MatchLifecycleEvent(long tick, String action, String detail) implements MatchEvent {
        @Override
        public String kind() {
            return "lifecycle";
        }

        @Override
        public String toJsonLine() {
            return "{\"t\":" + tick + ",\"ts\":" + System.currentTimeMillis()
                    + ",\"k\":\"lifecycle\",\"act\":\"" + JsonUtil.esc(action)
                    + "\",\"d\":\"" + JsonUtil.esc(detail) + "\"}";
        }
    }

    record PhaseChangedEvent(long tick, String fromPhase, String toPhase) implements MatchEvent {
        @Override
        public String kind() {
            return "phase";
        }

        @Override
        public String toJsonLine() {
            return "{\"t\":" + tick + ",\"k\":\"phase\",\"from\":\"" + JsonUtil.esc(fromPhase)
                    + "\",\"to\":\"" + JsonUtil.esc(toPhase) + "\"}";
        }
    }

    record TeamAssignedEvent(long tick, TeamColor team) implements MatchEvent {
        @Override
        public String kind() {
            return "team";
        }

        @Override
        public String toJsonLine() {
            return "{\"t\":" + tick + ",\"k\":\"team\",\"tm\":\"" + (team != null ? team.name() : "UNKNOWN") + "\"}";
        }
    }

    record BedBrokenEvent(long tick, TeamColor team, String rawMessage) implements MatchEvent {
        @Override
        public String kind() {
            return "bed_broken";
        }

        @Override
        public String toJsonLine() {
            return "{\"t\":" + tick + ",\"k\":\"bed_broken\",\"tm\":\""
                    + (team != null ? team.name() : "UNKNOWN") + "\",\"msg\":\"" + JsonUtil.esc(rawMessage) + "\"}";
        }
    }

    record BedDefenseObservedEvent(
            long tick,
            TeamColor team,
            int layers,
            DefenseBlockKind hardest,
            int wool,
            int wood,
            int endstone,
            int glass,
            int obsidian,
            double clearTicks) implements MatchEvent {

        @Override
        public String kind() {
            return "bed_scan";
        }

        @Override
        public String toJsonLine() {
            return String.format(Locale.ROOT,
                    "{\"t\":%d,\"k\":\"bed_scan\",\"tm\":\"%s\",\"lay\":%d,\"hard\":\"%s\",\"cnt\":[%d,%d,%d,%d,%d],\"clr\":%.1f}",
                    tick,
                    team != null ? team.name() : "UNKNOWN",
                    layers,
                    hardest != null ? hardest.name() : "AIR",
                    wool, wood, endstone, glass, obsidian,
                    clearTicks);
        }
    }

    record PlayerKillEvent(
            long tick,
            String victim,
            String killer,
            boolean isFinal,
            boolean isVoid,
            String rawMessage) implements MatchEvent {

        @Override
        public String kind() {
            return "kill";
        }

        @Override
        public String toJsonLine() {
            return "{\"t\":" + tick + ",\"k\":\"kill\",\"v\":\"" + JsonUtil.esc(victim)
                    + "\",\"killer\":\"" + JsonUtil.esc(killer)
                    + "\",\"fin\":" + isFinal
                    + ",\"void\":" + isVoid
                    + ",\"msg\":\"" + JsonUtil.esc(rawMessage) + "\"}";
        }
    }

    record TrapTriggeredEvent(long tick, String rawText) implements MatchEvent {
        @Override
        public String kind() {
            return "trap";
        }

        @Override
        public String toJsonLine() {
            return "{\"t\":" + tick + ",\"k\":\"trap\",\"msg\":\"" + JsonUtil.esc(rawText) + "\"}";
        }
    }

    record UpgradePurchasedEvent(long tick, String buyer, String upgradeText) implements MatchEvent {
        @Override
        public String kind() {
            return "upgrade";
        }

        @Override
        public String toJsonLine() {
            return "{\"t\":" + tick + ",\"k\":\"upgrade\",\"by\":\"" + JsonUtil.esc(buyer)
                    + "\",\"msg\":\"" + JsonUtil.esc(upgradeText) + "\"}";
        }
    }

    record EnemySpottedEvent(
            long tick,
            String name,
            double x,
            double y,
            double z,
            int armorTier,
            int swordTier,
            String utilitySummary) implements MatchEvent {

        @Override
        public String kind() {
            return "enemy";
        }

        @Override
        public String toJsonLine() {
            return String.format(Locale.ROOT,
                    "{\"t\":%d,\"k\":\"enemy\",\"n\":\"%s\",\"p\":[%.1f,%.1f,%.1f],\"arm\":%d,\"swd\":%d,\"ut\":\"%s\"}",
                    tick, JsonUtil.esc(name), x, y, z, armorTier, swordTier, JsonUtil.esc(utilitySummary));
        }
    }

    record TitleChangedEvent(long tick, String title) implements MatchEvent {
        @Override
        public String kind() {
            return "title";
        }

        @Override
        public String toJsonLine() {
            return "{\"t\":" + tick + ",\"k\":\"title\",\"msg\":\"" + JsonUtil.esc(title) + "\"}";
        }
    }

    record ChannelChangedEvent(
            long tick,
            String channel,
            String fromOwner,
            String toOwner,
            String reason) implements MatchEvent {

        @Override
        public String kind() {
            return "ch";
        }

        @Override
        public String toJsonLine() {
            return "{\"t\":" + tick + ",\"k\":\"ch\",\"ch\":\"" + JsonUtil.esc(channel)
                    + "\",\"from\":\"" + JsonUtil.esc(fromOwner != null ? fromOwner : "human")
                    + "\",\"to\":\"" + JsonUtil.esc(toOwner != null ? toOwner : "human")
                    + "\",\"why\":\"" + JsonUtil.esc(reason) + "\"}";
        }
    }

    record SkillOutcomeEvent(long tick, String skill, String outcome, String reason) implements MatchEvent {
        @Override
        public String kind() {
            return "skill";
        }

        @Override
        public String toJsonLine() {
            return "{\"t\":" + tick + ",\"k\":\"skill\",\"s\":\"" + JsonUtil.esc(skill)
                    + "\",\"out\":\"" + JsonUtil.esc(outcome)
                    + "\",\"why\":\"" + JsonUtil.esc(reason) + "\"}";
        }
    }

    record SlowTickEvent(long tick, double elapsedMs, String detail) implements MatchEvent {
        @Override
        public String kind() {
            return "slow";
        }

        @Override
        public String toJsonLine() {
            return String.format(Locale.ROOT,
                    "{\"t\":%d,\"k\":\"slow\",\"ms\":%.2f,\"d\":\"%s\"}",
                    tick, elapsedMs, JsonUtil.esc(detail));
        }
    }

    record ChatNoticeEvent(long tick, String text) implements MatchEvent {
        @Override
        public String kind() {
            return "chat";
        }

        @Override
        public String toJsonLine() {
            return "{\"t\":" + tick + ",\"k\":\"chat\",\"msg\":\"" + JsonUtil.esc(text) + "\"}";
        }
    }

    final class JsonUtil {
        private JsonUtil() {}

        public static String esc(String s) {
            if (s == null || s.isEmpty()) {
                return "";
            }
            StringBuilder out = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c >= 0x20) {
                            out.append(c);
                        }
                    }
                }
            }
            return out.toString();
        }
    }
}
