package ibuprofen.skills.nav;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Pure-Java voxel terrain adapter for {@link LocalPathfinder}.
 * Allows full headless 3D A* unit testing without booting Minecraft.
 */
public interface VoxelNavWorld {

    boolean isPassable(int x, int y, int z);

    boolean isSolidFloor(int x, int y, int z);

    boolean isHazard(int x, int y, int z);

    double floorTopY(int x, int y, int z);

    default boolean isClimbable(int x, int y, int z) {
        return false;
    }

    default boolean isVoidBelow(int x, int y, int z) {
        for (int dy = 0; dy <= 5; dy++) {
            if (isSolidFloor(x, y - dy, z)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Snaps a floating POI coordinate (like a Diamond/Emerald hologram 3 blocks in the air)
     * down to the closest walkable feet Y cell within 6 blocks below.
     */
    default int snapToWalkableY(int x, int y, int z) {
        if (isSolidFloor(x, y - 1, z) && isPassable(x, y, z) && isPassable(x, y + 1, z)) {
            return y;
        }
        for (int dy = 1; dy <= 6; dy++) {
            int cy = y - dy;
            if (isSolidFloor(x, cy - 1, z) && isPassable(x, cy, z) && isPassable(x, cy + 1, z)) {
                return cy;
            }
        }
        // Also check 1-2 blocks above in case the goal is embedded in a floor block (like clicking a floor)
        for (int dy = 1; dy <= 2; dy++) {
            int cy = y + dy;
            if (isSolidFloor(x, cy - 1, z) && isPassable(x, cy, z) && isPassable(x, cy + 1, z)) {
                return cy;
            }
        }
        return y;
    }

    /**
     * Simple voxel line-of-sight check between two 3D points (used to prevent picking shop stances behind walls).
     */
    default boolean hasLineOfSight(double x0, double y0, double z0, double x1, double y1, double z1) {
        double dx = x1 - x0;
        double dy = y1 - y0;
        double dz = z1 - z0;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 0.1) {
            return true;
        }
        int steps = Math.max(2, (int) Math.ceil(dist / 0.25));
        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            int bx = (int) Math.floor(x0 + dx * t);
            int by = (int) Math.floor(y0 + dy * t);
            int bz = (int) Math.floor(z0 + dz * t);
            if (!isPassable(bx, by, bz)) {
                return false;
            }
        }
        return true;
    }

    static VoxelNavWorld fromClientLevel(ClientLevel level) {
        return new ClientLevelNavWorld(level);
    }

    final class ClientLevelNavWorld implements VoxelNavWorld {
        private final ClientLevel level;
        private final BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();

        ClientLevelNavWorld(ClientLevel level) {
            this.level = level;
        }

        @Override
        public boolean isPassable(int x, int y, int z) {
            if (level == null || !level.hasChunk(x >> 4, z >> 4)) {
                return false;
            }
            mut.set(x, y, z);
            BlockState state = level.getBlockState(mut);
            if (isHazardBlock(state)) {
                return false;
            }
            if (state.isAir()) {
                return true;
            }
            Block b = state.getBlock();
            if (b == Blocks.LADDER || b == Blocks.VINE) {
                return true;
            }
            if (!state.getFluidState().isEmpty()) {
                return false;
            }
            VoxelShape shape = state.getCollisionShape(level, mut);
            return shape.isEmpty() || shape.max(Direction.Axis.Y) <= 0.15;
        }

        @Override
        public boolean isSolidFloor(int x, int y, int z) {
            if (level == null || !level.hasChunk(x >> 4, z >> 4)) {
                return false;
            }
            mut.set(x, y, z);
            BlockState state = level.getBlockState(mut);
            if (state.isAir() || !state.getFluidState().isEmpty() || isHazardBlock(state)) {
                return false;
            }
            VoxelShape shape = state.getCollisionShape(level, mut);
            if (shape.isEmpty()) {
                return false;
            }
            double top = shape.max(Direction.Axis.Y);
            return top > 0.15 && top <= 1.05;
        }

        @Override
        public boolean isClimbable(int x, int y, int z) {
            if (level == null || !level.hasChunk(x >> 4, z >> 4)) {
                return false;
            }
            Block b = level.getBlockState(mut.set(x, y, z)).getBlock();
            return b == Blocks.LADDER || b == Blocks.VINE;
        }

        @Override
        public boolean isHazard(int x, int y, int z) {
            if (level == null || !level.hasChunk(x >> 4, z >> 4)) {
                return true;
            }
            mut.set(x, y, z);
            return isHazardBlock(level.getBlockState(mut));
        }

        @Override
        public double floorTopY(int x, int y, int z) {
            if (level == null || !level.hasChunk(x >> 4, z >> 4)) {
                return y + 1.0;
            }
            mut.set(x, y, z);
            BlockState state = level.getBlockState(mut);
            VoxelShape shape = state.getCollisionShape(level, mut);
            if (shape.isEmpty()) {
                return y + 1.0;
            }
            return y + shape.max(Direction.Axis.Y);
        }

        private static boolean isHazardBlock(BlockState state) {
            Block b = state.getBlock();
            return b == Blocks.LAVA
                    || b == Blocks.FIRE
                    || b == Blocks.SOUL_FIRE
                    || b == Blocks.MAGMA_BLOCK
                    || b == Blocks.COBWEB
                    || b == Blocks.CACTUS;
        }
    }
}
