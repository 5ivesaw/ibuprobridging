package ibuprofen.world;

import ibuprofen.world.BedwarsMapCore.TeamColor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;

/**
 * Incremental, tick-budgeted arena block scanner.
 * Scans newly loaded chunks in small slices so arena indexing never causes a frame/tick spike.
 */
public final class ArenaScanner {

    private static final int MAX_CHUNKS_PER_TICK = 1;
    private static final int SCAN_MIN_Y = 32;
    private static final int SCAN_MAX_Y = 128;

    private final Queue<Long> pendingChunks = new ArrayDeque<>();
    private final Set<Long> scannedChunks = new HashSet<>();
    private int bedsDiscovered = 0;

    public void reset() {
        pendingChunks.clear();
        scannedChunks.clear();
        bedsDiscovered = 0;
    }

    public void onChunkLoaded(int chunkX, int chunkZ) {
        long key = chunkKey(chunkX, chunkZ);
        if (scannedChunks.add(key)) {
            pendingChunks.offer(key);
        }
    }

    public void onChunkUnloaded(int chunkX, int chunkZ) {
        scannedChunks.remove(chunkKey(chunkX, chunkZ));
    }

    /**
     * Processes at most {@value #MAX_CHUNKS_PER_TICK} queued chunk per tick to protect tick time.
     */
    public void tickScan(ClientLevel level, BedwarsMapCore mapCore, BlockPos playerPos) {
        if (level == null || mapCore == null || playerPos == null) {
            return;
        }
        int processed = 0;
        while (processed < MAX_CHUNKS_PER_TICK && !pendingChunks.isEmpty()) {
            Long key = pendingChunks.poll();
            if (key == null) {
                break;
            }
            int cx = unpackX(key);
            int cz = unpackZ(key);
            // Skip distant chunks beyond 8 chunks from player
            int pcx = playerPos.getX() >> 4;
            int pcz = playerPos.getZ() >> 4;
            if (Math.abs(cx - pcx) > 8 || Math.abs(cz - pcz) > 8) {
                continue;
            }
            scanSingleChunk(level, mapCore, cx, cz);
            processed++;
        }
    }

    public void onBlockChanged(BlockPos pos, BlockState state, BedwarsMapCore mapCore) {
        if (pos == null || state == null || mapCore == null) {
            return;
        }
        boolean solid = isSolidForBedwars(state);
        mapCore.setPlayerPlaced(pos.getX(), pos.getY(), pos.getZ(), solid);
        if (state.getBlock() instanceof BedBlock) {
            TeamColor team = teamFromBedBlock(state.getBlock());
            mapCore.addBed(new BedwarsMapCore.BedPOI(
                    team, pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ(), false, false));
        }
    }

    public int pendingChunkCount() {
        return pendingChunks.size();
    }

    public int bedsDiscovered() {
        return bedsDiscovered;
    }

    private void scanSingleChunk(ClientLevel level, BedwarsMapCore mapCore, int cx, int cz) {
        int baseX = cx << 4;
        int baseZ = cz << 4;
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < 16; dx++) {
            int wx = baseX + dx;
            for (int dz = 0; dz < 16; dz++) {
                int wz = baseZ + dz;
                for (int wy = SCAN_MIN_Y; wy <= SCAN_MAX_Y; wy++) {
                    mut.set(wx, wy, wz);
                    BlockState state = level.getBlockState(mut);
                    if (state.isAir()) {
                        continue;
                    }
                    if (isSolidForBedwars(state)) {
                        mapCore.setOriginalBlock(wx, wy, wz, true, false);
                    }
                    if (state.getBlock() instanceof BedBlock) {
                        TeamColor team = teamFromBedBlock(state.getBlock());
                        mapCore.addBed(new BedwarsMapCore.BedPOI(team, wx, wy, wz, wx, wy, wz, false, false));
                        bedsDiscovered++;
                    }
                }
            }
        }
        mapCore.markChunkScanned(cx, cz);
    }

    private static boolean isSolidForBedwars(BlockState state) {
        return !state.isAir() && state.blocksMotion();
    }

    private static TeamColor teamFromBedBlock(Block block) {
        String path = BuiltInRegistries.BLOCK.getKey(block).getPath().toLowerCase(Locale.ROOT);
        if (path.startsWith("red_")) return TeamColor.RED;
        if (path.startsWith("blue_")) return TeamColor.BLUE;
        if (path.startsWith("green_") || path.startsWith("lime_")) return TeamColor.GREEN;
        if (path.startsWith("yellow_")) return TeamColor.YELLOW;
        if (path.startsWith("cyan_") || path.startsWith("light_blue_")) return TeamColor.AQUA;
        if (path.startsWith("white_")) return TeamColor.WHITE;
        if (path.startsWith("pink_") || path.startsWith("magenta_")) return TeamColor.PINK;
        if (path.startsWith("gray_") || path.startsWith("light_gray_")) return TeamColor.GRAY;
        return TeamColor.UNKNOWN;
    }

    private static long chunkKey(int x, int z) {
        return (((long) x) << 32) | (z & 0xffffffffL);
    }

    private static int unpackX(long key) {
        return (int) (key >> 32);
    }

    private static int unpackZ(long key) {
        return (int) key;
    }
}
