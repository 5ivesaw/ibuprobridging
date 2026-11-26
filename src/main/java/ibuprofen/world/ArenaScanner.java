package ibuprofen.world;

import ibuprofen.world.BedwarsMapCore.ArenaBounds;
import ibuprofen.world.BedwarsMapCore.BedPOI;
import ibuprofen.world.BedwarsMapCore.TeamColor;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Fast, tick-budgeted arena block scanner using direct LevelChunkSection reads.
 * Skips air-only sections, discovers already-loaded chunks around the player, deduplicates
 * bed head/foot pairs, and infers 1.8 ViaFabricPlus team colors from surrounding wool/terracotta.
 */
public final class ArenaScanner {

    private static final int MAX_CHUNKS_PER_TICK = 2;
    private static final int NEARBY_RADIUS_CHUNKS = 7;

    private final Queue<Long> pendingChunks = new ArrayDeque<>();
    private final Set<Long> queuedOrScanned = new HashSet<>();
    private int bedsDiscovered = 0;

    public void reset() {
        pendingChunks.clear();
        queuedOrScanned.clear();
        bedsDiscovered = 0;
    }

    public void onChunkLoaded(int chunkX, int chunkZ) {
        long key = chunkKey(chunkX, chunkZ);
        if (queuedOrScanned.add(key)) {
            pendingChunks.offer(key);
        }
    }

    public void onChunkUnloaded(int chunkX, int chunkZ) {
        queuedOrScanned.remove(chunkKey(chunkX, chunkZ));
    }

    /**
     * Scans up to {@value #MAX_CHUNKS_PER_TICK} chunks per tick, also discovering any already-loaded
     * chunks near the player that arrived before the scanner was ready.
     */
    public void tickScan(ClientLevel level, BedwarsMapCore mapCore, BlockPos playerPos) {
        if (level == null || mapCore == null || playerPos == null) {
            return;
        }
        int pcx = playerPos.getX() >> 4;
        int pcz = playerPos.getZ() >> 4;

        // Seed nearby already-loaded chunks if not yet queued
        if (pendingChunks.isEmpty()) {
            for (int dx = -NEARBY_RADIUS_CHUNKS; dx <= NEARBY_RADIUS_CHUNKS; dx++) {
                for (int dz = -NEARBY_RADIUS_CHUNKS; dz <= NEARBY_RADIUS_CHUNKS; dz++) {
                    int cx = pcx + dx;
                    int cz = pcz + dz;
                    if (!mapCore.isChunkScanned(cx, cz)) {
                        long key = chunkKey(cx, cz);
                        if (queuedOrScanned.add(key)) {
                            pendingChunks.offer(key);
                        }
                    }
                }
            }
        }

        ClientChunkCache chunkSource = level.getChunkSource();
        int processed = 0;
        while (processed < MAX_CHUNKS_PER_TICK && !pendingChunks.isEmpty()) {
            Long key = pendingChunks.poll();
            if (key == null) {
                break;
            }
            int cx = unpackX(key);
            int cz = unpackZ(key);
            if (Math.abs(cx - pcx) > NEARBY_RADIUS_CHUNKS + 2 || Math.abs(cz - pcz) > NEARBY_RADIUS_CHUNKS + 2) {
                queuedOrScanned.remove(key);
                continue;
            }
            LevelChunk chunk = chunkSource.getChunk(cx, cz, ChunkStatus.FULL, false);
            if (chunk == null || chunk.isEmpty()) {
                queuedOrScanned.remove(key);
                continue;
            }
            scanSingleChunk(level, chunk, mapCore, cx, cz);
            processed++;
        }
    }

    public void onBlockChanged(ClientLevel level, BlockPos pos, BlockState state, BedwarsMapCore mapCore) {
        if (level == null || pos == null || state == null || mapCore == null) {
            return;
        }
        boolean solid = isSolidForBedwars(level, pos, state);
        mapCore.setPlayerPlaced(pos.getX(), pos.getY(), pos.getZ(), solid);
        if (state.getBlock() instanceof BedBlock && state.getValue(BedBlock.PART) == BedPart.HEAD) {
            registerBedHead(level, pos, state, mapCore);
        }
    }

    public int pendingChunkCount() {
        return pendingChunks.size();
    }

    public int bedsDiscovered() {
        return bedsDiscovered;
    }

    private void scanSingleChunk(ClientLevel level, LevelChunk chunk, BedwarsMapCore mapCore, int cx, int cz) {
        ArenaBounds bounds = mapCore.bounds();
        int dimMinY = level.dimensionType().minY();
        int baseX = cx << 4;
        int baseZ = cz << 4;
        LevelChunkSection[] sections = chunk.getSections();

        for (int secIdx = 0; secIdx < sections.length; secIdx++) {
            LevelChunkSection sec = sections[secIdx];
            if (sec == null || sec.hasOnlyAir()) {
                continue;
            }
            int secBaseY = dimMinY + (secIdx << 4);
            if (secBaseY + 15 < bounds.minY() || secBaseY >= bounds.minY() + bounds.sizeY()) {
                continue;
            }
            for (int ly = 0; ly < 16; ly++) {
                int wy = secBaseY + ly;
                if (wy < bounds.minY() || wy >= bounds.minY() + bounds.sizeY()) {
                    continue;
                }
                for (int lz = 0; lz < 16; lz++) {
                    int wz = baseZ + lz;
                    for (int lx = 0; lx < 16; lx++) {
                        int wx = baseX + lx;
                        if (!bounds.contains(wx, wy, wz)) {
                            continue;
                        }
                        BlockState state = sec.getBlockState(lx, ly, lz);
                        Block block = state.getBlock();
                        if (block instanceof AirBlock) {
                            continue;
                        }
                        if (block instanceof BedBlock) {
                            mapCore.setSpecialNonAir(wx, wy, wz, true);
                            if (state.getValue(BedBlock.PART) == BedPart.HEAD) {
                                registerBedHead(level, new BlockPos(wx, wy, wz), state, mapCore);
                            }
                            continue;
                        }
                        if (block == Blocks.BARRIER || !state.getFluidState().isEmpty()) {
                            mapCore.setSpecialNonAir(wx, wy, wz, true);
                            continue;
                        }
                        mapCore.setOriginalBlock(wx, wy, wz, true, false);
                    }
                }
            }
        }
        mapCore.markChunkScanned(cx, cz);
    }

    private void registerBedHead(ClientLevel level, BlockPos head, BlockState headState, BedwarsMapCore mapCore) {
        for (BedPOI existing : mapCore.getBeds()) {
            if (existing.matchesCell(head.getX(), head.getY(), head.getZ())) {
                return;
            }
        }
        BlockPos foot = head.relative(headState.getValue(BedBlock.FACING).getOpposite());
        TeamColor team = inferBedTeamColor(level, head, headState.getBlock());
        mapCore.addBed(new BedPOI(
                team,
                head.getX(), head.getY(), head.getZ(),
                foot.getX(), foot.getY(), foot.getZ(),
                false, false));
        bedsDiscovered++;
    }

    /**
     * Infers team color around a bed using surrounding wool/terracotta/glass/concrete votes
     * so 1.8 ViaFabricPlus servers (where every bed is a red_bed) still identify team colors cleanly.
     */
    private static TeamColor inferBedTeamColor(ClientLevel level, BlockPos head, Block bedBlock) {
        TeamColor blockColor = colorFromBlockPath(BuiltInRegistries.BLOCK.getKey(bedBlock).getPath());
        Map<TeamColor, Integer> votes = new EnumMap<>(TeamColor.class);
        boolean hasWoolVote = false;
        boolean hasGrayWoolVote = false;
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();

        for (int dx = -6; dx <= 6; dx++) {
            for (int dy = -2; dy <= 4; dy++) {
                for (int dz = -6; dz <= 6; dz++) {
                    mut.set(head.getX() + dx, head.getY() + dy, head.getZ() + dz);
                    Block b = level.getBlockState(mut).getBlock();
                    if (b instanceof AirBlock || b instanceof BedBlock) {
                        continue;
                    }
                    String path = BuiltInRegistries.BLOCK.getKey(b).getPath();
                    TeamColor tc = colorFromBlockPath(path);
                    if (tc == TeamColor.UNKNOWN) {
                        continue;
                    }
                    int weight = path.endsWith("_wool") ? 4 : (path.endsWith("_terracotta") || path.endsWith("_concrete") ? 2 : 1);
                    if (path.endsWith("_wool")) {
                        hasWoolVote = true;
                        if (tc == TeamColor.GRAY) {
                            hasGrayWoolVote = true;
                        }
                    }
                    votes.merge(tc, weight, Integer::sum);
                }
            }
        }
        return BedwarsMapCore.selectBedTeamFromVotes(votes, blockColor, hasWoolVote, hasGrayWoolVote);
    }

    private static boolean isSolidForBedwars(ClientLevel level, BlockPos pos, BlockState state) {
        return !state.isAir() && state.getFluidState().isEmpty() && !state.getCollisionShape(level, pos).isEmpty();
    }

    private static TeamColor colorFromBlockPath(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.startsWith("red_")) return TeamColor.RED;
        if (p.startsWith("blue_")) return TeamColor.BLUE;
        if (p.startsWith("green_") || p.startsWith("lime_")) return TeamColor.GREEN;
        if (p.startsWith("yellow_")) return TeamColor.YELLOW;
        if (p.startsWith("cyan_") || p.startsWith("light_blue_")) return TeamColor.AQUA;
        if (p.startsWith("white_")) return TeamColor.WHITE;
        if (p.startsWith("pink_") || p.startsWith("magenta_")) return TeamColor.PINK;
        if (p.startsWith("gray_") || p.startsWith("light_gray_")) return TeamColor.GRAY;
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
