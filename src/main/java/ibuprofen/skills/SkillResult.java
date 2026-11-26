package ibuprofen.skills;

/**
 * Result returned each tick by a Layer 2 {@link Skill}.
 */
public record SkillResult(SkillOutcome outcome, String reason) {

    private static final SkillResult RUNNING_INSTANCE = new SkillResult(SkillOutcome.RUNNING, "");

    public static SkillResult running() {
        return RUNNING_INSTANCE;
    }

    public static SkillResult done(String reason) {
        return new SkillResult(SkillOutcome.DONE, reason != null ? reason : "done");
    }

    public static SkillResult blocked(String reason) {
        return new SkillResult(SkillOutcome.BLOCKED, reason != null ? reason : "blocked");
    }

    public static SkillResult interrupted(String reason) {
        return new SkillResult(SkillOutcome.INTERRUPTED, reason != null ? reason : "interrupted");
    }

    public static SkillResult timeout(String reason) {
        return new SkillResult(SkillOutcome.TIMEOUT, reason != null ? reason : "timeout");
    }

    public boolean isTerminal() {
        return outcome != SkillOutcome.RUNNING;
    }
}
