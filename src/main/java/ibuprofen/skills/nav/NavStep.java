package ibuprofen.skills.nav;

/**
 * Single waypoint along a planned island path.
 * (x, y, z) is the feet cell to stand in; {@code targetTopY} is the exact top Y of the supporting floor block.
 */
public record NavStep(int x, int y, int z, NavMoveType moveType, double targetTopY) {

    public double centerX() {
        return x + 0.5;
    }

    public double centerZ() {
        return z + 0.5;
    }
}
