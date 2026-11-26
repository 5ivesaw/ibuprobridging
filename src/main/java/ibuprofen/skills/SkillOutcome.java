package ibuprofen.skills;

/**
 * Explicit outcome reported by every Layer 2 atomic skill.
 * Eliminates silent stalls by always telling the caller whether a skill finished or why it failed.
 */
public enum SkillOutcome {
    RUNNING,
    DONE,
    BLOCKED,
    INTERRUPTED,
    TIMEOUT
}
