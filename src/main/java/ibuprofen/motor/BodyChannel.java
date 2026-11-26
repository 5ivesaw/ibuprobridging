package ibuprofen.motor;

/**
 * The 5 independent body channels managed by InputArbiter.
 * Separate skills can own different channels on the same tick without wiping each other's inputs.
 */
public enum BodyChannel {
    MOVEMENT,
    LOOK,
    LMB,
    RMB,
    HOTBAR_GUI
}
