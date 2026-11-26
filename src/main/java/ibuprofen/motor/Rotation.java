package ibuprofen.motor;

/**
 * Immutable player look rotation (yaw and pitch in degrees).
 */
public final class Rotation {

    private final float yaw;
    private final float pitch;

    public Rotation(float yaw, float pitch) {
        if (Float.isInfinite(yaw) || Float.isNaN(yaw) || Float.isInfinite(pitch) || Float.isNaN(pitch)) {
            throw new IllegalArgumentException("Invalid rotation: " + yaw + ", " + pitch);
        }
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public float getYaw() {
        return yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public Rotation add(Rotation other) {
        return new Rotation(this.yaw + other.yaw, this.pitch + other.pitch);
    }

    public Rotation subtract(Rotation other) {
        return new Rotation(this.yaw - other.yaw, this.pitch - other.pitch);
    }

    public Rotation clamp() {
        return new Rotation(normalizeYaw(yaw), clampPitch(pitch));
    }

    public Rotation normalize() {
        return new Rotation(normalizeYaw(yaw), pitch);
    }

    public Rotation normalizeAndClamp() {
        return new Rotation(normalizeYaw(yaw), clampPitch(pitch));
    }

    public static float normalizeYaw(float yaw) {
        float y = yaw % 360.0f;
        if (y >= 180.0f) {
            y -= 360.0f;
        }
        if (y < -180.0f) {
            y += 360.0f;
        }
        return y;
    }

    public static float clampPitch(float pitch) {
        return Math.max(-90.0f, Math.min(90.0f, pitch));
    }

    @Override
    public String toString() {
        return String.format("Rotation(yaw=%.2f, pitch=%.2f)", yaw, pitch);
    }
}
