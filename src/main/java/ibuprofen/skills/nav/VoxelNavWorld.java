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

    /**
     * True if a player's feet or head can occupy block cell (x, y, z) without colliding or taking hazard damage.
     */
    boolean isPassable(int x, int y, int z);

    /**
     * True if block cell (x, y, z) provides walkable solid support (not void, not lava, not 1.5-tall fence).
     */
    boolean isSolidFloor(int x, int y, int z);

    /**
     * True if block cell (x, y, z) is hazardous (lava, fire, magma, cobweb).
     */
    boolean isHazard(int x, int y, int z);

    /**
     * Exact world Y coordinate of the top walkable surface of floor cell (x, y, z) (e.g., y+0.5 for bottom slab).
     */
    double floorTopY(int x, int y, int z);

    /**
     * True if stepping off into (x, y, z) drops into void or a lethal (>5 block) fall.
     */
    default boolean isVoidBelow(int x, int y, int z) {
        for (int dy = 0; dy <= 5; dy++) {
            if (isSolidFloor(x, y - dy, z)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Live Minecraft 26.3 ClientLevel implementation using exact VoxelShape collision bounds.
     */
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
            // Reject fences/walls (height 1.5) as normal floors and reject ultra-thin carpets (<= 0.15)
            return top > 0.15 && top <= 1.05;
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
