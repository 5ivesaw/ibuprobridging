package ibuprofen.skills.nav;

/**
 * Finds the best walkable standing block in front of a shopkeeper (or bed/chest)
 * with verified line-of-sight so the bot never stops behind a wall or off the island.
 */
public final class ShopStanceFinder {

    public record Stance(int x, int y, int z) {}

    private ShopStanceFinder() {}

    public static Stance findBestStance(
            VoxelNavWorld world,
            int npcX, int npcY, int npcZ,
            double playerX, double playerY, double playerZ) {

        if (world == null) {
            return new Stance(npcX, npcY, npcZ);
        }
        int baseY = world.snapToWalkableY(npcX, npcY, npcZ);
        Stance best = null;
        double bestScore = Double.MAX_VALUE;

        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                double npcDist = Math.hypot(dx, dz);
                if (npcDist < 1.15 || npcDist > 3.1) {
                    continue;
                }
                int cx = npcX + dx;
                int cz = npcZ + dz;
                for (int dy = -1; dy <= 1; dy++) {
                    int cy = baseY + dy;
                    if (!LocalPathfinder.canStandAt(world, cx, cy, cz)) {
                        continue;
                    }
                    // Verify unobstructed eye-level line-of-sight to the shopkeeper's upper torso
                    if (!world.hasLineOfSight(cx + 0.5, cy + 1.5, cz + 0.5, npcX + 0.5, baseY + 1.35, npcZ + 0.5)) {
                        continue;
                    }
                    double playerDist = Math.hypot((cx + 0.5) - playerX, (cz + 0.5) - playerZ);
                    double idealGapPenalty = Math.abs(npcDist - 2.0) * 1.4;
                    double voidPenalty = isAdjacentVoid(world, cx, cy, cz) ? 4.0 : 0.0;
                    double score = playerDist + idealGapPenalty + voidPenalty;
                    if (score < bestScore) {
                        bestScore = score;
                        best = new Stance(cx, cy, cz);
                    }
                }
            }
        }
        return best != null ? best : new Stance(npcX, baseY, npcZ);
    }

    private static boolean isAdjacentVoid(VoxelNavWorld world, int x, int y, int z) {
        return world.isVoidBelow(x + 1, y - 1, z)
                || world.isVoidBelow(x - 1, y - 1, z)
                || world.isVoidBelow(x, y - 1, z + 1)
                || world.isVoidBelow(x, y - 1, z - 1);
    }
}
