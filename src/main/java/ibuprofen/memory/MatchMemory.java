package ibuprofen.memory;

import ibuprofen.event.MatchEvent;
import ibuprofen.event.MatchEventBus;
import ibuprofen.world.BedwarsDefensePlanner;
import ibuprofen.world.BedwarsDefensePlanner.BedShellScan;
import ibuprofen.world.BedwarsDefensePlanner.BlockKindClassifier;
import ibuprofen.world.BedwarsDefensePlanner.DefenseBlockKind;
import ibuprofen.world.BedwarsDefensePlanner.ToolProfile;
import ibuprofen.world.BedwarsMapCore;
import ibuprofen.world.BedwarsMapCore.BedPOI;
import ibuprofen.world.BedwarsMapCore.GenType;
import ibuprofen.world.BedwarsMapCore.TeamColor;
import ibuprofen.world.BedwarsMatchState;
import ibuprofen.world.TickSnapshot;
import ibuprofen.world.TickSnapshot.TrackedPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Persistent match-wide mental map subscribed to {@link MatchEventBus}.
 * Remembers enemy bed defenses (X-ray scanned), enemy gear/utilities, generator clocks,
 * and derives a live {@link ShoppingWishList} that survives chunk unloads and respawns.
 */
public final class MatchMemory {

    public record GeneratorClock(GenType type, int x, int z, int lastReportedSec, long updatedMs) {
        public double estimatedSecondsLeft(long nowMs) {
            double elapsed = Math.max(0.0, (nowMs - updatedMs) / 1000.0);
            return Math.max(0.0, lastReportedSec - elapsed);
        }
    }

    private static final int BED_SCAN_INTERVAL_TICKS = 20;

    private final Map<TeamColor, TeamDossier> teamDossiers = new EnumMap<>(TeamColor.class);
    private final Map<String, EnemyDossier> enemyDossiers = new LinkedHashMap<>();
    private final Map<String, GeneratorClock> genClocks = new LinkedHashMap<>();
    private final Map<Long, BedShellScan> lastScanByBedPos = new HashMap<>();

    private TeamColor homeTeam = TeamColor.UNKNOWN;
    private long lastBedScanTick = -BED_SCAN_INTERVAL_TICKS;

    public synchronized void reset() {
        teamDossiers.clear();
        enemyDossiers.clear();
        genClocks.clear();
        lastScanByBedPos.clear();
        homeTeam = TeamColor.UNKNOWN;
        lastBedScanTick = -BED_SCAN_INTERVAL_TICKS;
    }

    /**
     * Updates persistent memory from typed events on {@link MatchEventBus}.
     */
    public synchronized void onEvent(MatchEvent event) {
        if (event == null) {
            return;
        }
        switch (event) {
            case MatchEvent.TeamAssignedEvent t -> {
                if (t.team() != null && t.team() != TeamColor.UNKNOWN) {
                    homeTeam = t.team();
                    dossierFor(t.team());
                }
            }
            case MatchEvent.BedBrokenEvent b -> {
                if (b.team() != null && b.team() != TeamColor.UNKNOWN) {
                    TeamDossier td = dossierFor(b.team());
                    td.bedAlive = false;
                }
            }
            case MatchEvent.BedDefenseObservedEvent obs -> {
                if (obs.team() != null && obs.team() != TeamColor.UNKNOWN) {
                    TeamDossier td = dossierFor(obs.team());
                    Map<DefenseBlockKind, Integer> counts = new EnumMap<>(DefenseBlockKind.class);
                    if (obs.wool() > 0) counts.put(DefenseBlockKind.WOOL, obs.wool());
                    if (obs.wood() > 0) counts.put(DefenseBlockKind.WOOD, obs.wood());
                    if (obs.endstone() > 0) counts.put(DefenseBlockKind.ENDSTONE, obs.endstone());
                    if (obs.glass() > 0) counts.put(DefenseBlockKind.GLASS, obs.glass());
                    if (obs.obsidian() > 0) counts.put(DefenseBlockKind.OBSIDIAN, obs.obsidian());
                    BedShellScan scan = new BedShellScan(
                            obs.layers(), Map.copyOf(counts), obs.hardest(), obs.clearTicks(), 0);
                    if (!sameScan(td.lastDefenseScan, scan)) {
                        td.updateDefense(scan, obs.tick());
                    }
                }
            }
            case MatchEvent.PlayerKillEvent k -> {
                if (k.victim() != null && !k.victim().isBlank()) {
                    EnemyDossier ed = enemyFor(k.victim());
                    ed.deaths++;
                    ed.respawnUntilTick = k.isFinal() ? Long.MAX_VALUE : (k.tick() + 100L);
                }
                if (k.killer() != null && !k.killer().isBlank()) {
                    EnemyDossier kd = enemyFor(k.killer());
                    kd.kills++;
                }
            }
            case MatchEvent.MatchLifecycleEvent lc -> {
                if ("world_reset".equals(lc.action())) {
                    reset();
                }
                // Note: "respawn" intentionally preserves team/enemy/generator memory!
            }
            default -> {
                // Other event types do not mutate dossiers directly.
            }
        }
    }

    /**
     * Periodically X-ray scans loaded enemy beds and updates enemy gear dossiers from TickSnapshot.
     */
    public synchronized void tickUpdate(
            ClientLevel level,
            BedwarsMapCore mapCore,
            BedwarsMatchState matchState,
            TickSnapshot snapshot,
            long tick,
            MatchEventBus bus) {

        if (matchState != null) {
            TeamColor mt = matchState.getHomeTeam();
            if (mt != null && mt != TeamColor.UNKNOWN) {
                homeTeam = mt;
            }
            for (BedwarsMatchState.TeamStatusSnapshot ts : matchState.getDiscoveredTeams()) {
                if (ts.team() != TeamColor.UNKNOWN) {
                    TeamDossier td = dossierFor(ts.team());
                    td.bedAlive = ts.bedStatus() != BedwarsMatchState.BedStatus.DESTROYED;
                    td.eliminated = ts.eliminated();
                }
            }
        }

        // 1. Update enemy dossiers from live TickSnapshot (publish only on first sight or gear upgrade)
        if (snapshot != null) {
            for (TrackedPlayer ep : snapshot.enemies) {
                EnemyDossier ed = enemyFor(ep.name);
                boolean firstSight = ed.lastSeenTick < 0;
                boolean gearChanged = ep.armorTier > ed.armorTier || ep.swordTier > ed.swordTier;
                boolean newUtil = (ep.holdingFireball && !ed.seenFireball)
                        || (ep.holdingPearl && !ed.seenPearl)
                        || (ep.holdingBow && !ed.seenBow)
                        || (ep.holdingTnt && !ed.seenTnt);

                ed.lastX = ep.x;
                ed.lastY = ep.y;
                ed.lastZ = ep.z;
                ed.lastVx = ep.vx;
                ed.lastVz = ep.vz;
                ed.lastSeenTick = tick;
                ed.armorTier = Math.max(ed.armorTier, ep.armorTier);
                ed.swordTier = Math.max(ed.swordTier, ep.swordTier);
                ed.seenFireball |= ep.holdingFireball;
                ed.seenPearl |= ep.holdingPearl;
                ed.seenBow |= ep.holdingBow;
                ed.seenGapple |= ep.holdingGapple;
                ed.seenTnt |= ep.holdingTnt;

                if (bus != null && (firstSight || gearChanged || newUtil)) {
                    bus.publish(new MatchEvent.EnemySpottedEvent(
                            tick, ep.name, ep.x, ep.y, ep.z, ed.armorTier, ed.swordTier, ed.utilitySummary()));
                }
            }
        }

        // 2. X-ray scan loaded beds every 1 Hz (20 ticks)
        if (level != null && mapCore != null && (tick - lastBedScanTick) >= BED_SCAN_INTERVAL_TICKS) {
            lastBedScanTick = tick;
            scanLoadedBeds(level, mapCore, tick, bus);
        }
    }

    public synchronized void recordGeneratorTimer(GenType type, int x, int z, int seconds, long nowMs) {
        if (type == null || seconds < 0) {
            return;
        }
        String key = type.name() + ":" + (x >> 2) + ":" + (z >> 2);
        genClocks.put(key, new GeneratorClock(type, x, z, seconds, nowMs));
    }

    public synchronized List<ShoppingWishList.WishItem> computeWishList() {
        return ShoppingWishList.compute(homeTeam, teamDossiers, enemyDossiers);
    }

    public synchronized TeamDossier getTeamDossier(TeamColor team) {
        return teamDossiers.get(team);
    }

    public synchronized Map<TeamColor, TeamDossier> getAllTeamDossiers() {
        return Collections.unmodifiableMap(new EnumMap<>(teamDossiers));
    }

    public synchronized Map<String, EnemyDossier> getAllEnemyDossiers() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(enemyDossiers));
    }

    public synchronized Map<String, GeneratorClock> getGeneratorClocks() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(genClocks));
    }

    public synchronized TeamColor homeTeam() {
        return homeTeam;
    }

    private void scanLoadedBeds(ClientLevel level, BedwarsMapCore mapCore, long tick, MatchEventBus bus) {
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        BlockKindClassifier classifier = (bx, by, bz) -> classifyBlock(level, mapCore, mut.set(bx, by, bz));
        Set<TeamColor> scannedTeamsThisPass = EnumSet.noneOf(TeamColor.class);

        for (BedPOI bed : mapCore.getBeds()) {
            if (bed.team() == TeamColor.UNKNOWN || bed.destroyed()) {
                if (bed.team() != TeamColor.UNKNOWN && bed.destroyed()) {
                    dossierFor(bed.team()).bedAlive = false;
                }
                continue;
            }
            if (!level.hasChunk(bed.x() >> 4, bed.z() >> 4)) {
                continue;
            }
            TeamDossier td = dossierFor(bed.team());
            // Verify a BedBlock still physically exists here so broken/ghost beds never produce phantom 0L AIR scans
            if (!(level.getBlockState(mut.set(bed.x(), bed.y(), bed.z())).getBlock() instanceof BedBlock)) {
                mapCore.markBedDestroyedAt(bed.x(), bed.y(), bed.z());
                td.bedAlive = false;
                continue;
            }
            if (!scannedTeamsThisPass.add(bed.team())) {
                continue;
            }

            td.updateBedPos(bed.x(), bed.y(), bed.z(), bed.footX(), bed.footY(), bed.footZ());
            BedShellScan scan = BedwarsDefensePlanner.scanBedDefenseShell(
                    bed.x(), bed.y(), bed.z(),
                    bed.footX(), bed.footY(), bed.footZ(),
                    4, ToolProfile.bareHand(), classifier);

            long posKey = packPos(bed.x(), bed.y(), bed.z());
            BedShellScan prev = lastScanByBedPos.get(posKey);
            if (!sameScan(prev, scan)) {
                lastScanByBedPos.put(posKey, scan);
                td.updateDefense(scan, tick);
                if (bus != null) {
                    bus.publish(new MatchEvent.BedDefenseObservedEvent(
                            tick,
                            bed.team(),
                            scan.layersDetected(),
                            scan.hardestBlock(),
                            scan.counts().getOrDefault(DefenseBlockKind.WOOL, 0),
                            scan.counts().getOrDefault(DefenseBlockKind.WOOD, 0),
                            scan.counts().getOrDefault(DefenseBlockKind.ENDSTONE, 0),
                            scan.counts().getOrDefault(DefenseBlockKind.GLASS, 0),
                            scan.counts().getOrDefault(DefenseBlockKind.OBSIDIAN, 0),
                            scan.estimatedClearTicks()));
                }
            }
        }
    }

    private static boolean sameScan(BedShellScan a, BedShellScan b) {
        if (a == null || b == null) {
            return false;
        }
        return a.layersDetected() == b.layersDetected()
                && a.hardestBlock() == b.hardestBlock()
                && a.counts().equals(b.counts());
    }

    private static long packPos(int x, int y, int z) {
        return (((long) x & 0x3FFFFFFL) << 38) | (((long) z & 0x3FFFFFFL) << 12) | ((long) y & 0xFFFL);
    }

    private static DefenseBlockKind classifyBlock(ClientLevel level, BedwarsMapCore mapCore, BlockPos.MutableBlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return DefenseBlockKind.AIR;
        }
        if (!state.getFluidState().isEmpty()) {
            return DefenseBlockKind.WATER_COVERED;
        }
        Block block = state.getBlock();
        if (block == Blocks.OBSIDIAN) {
            return DefenseBlockKind.OBSIDIAN;
        }
        if (block == Blocks.END_STONE) {
            return DefenseBlockKind.ENDSTONE;
        }
        if (block == Blocks.LADDER) {
            return DefenseBlockKind.LADDER_SLAB;
        }
        String path = BuiltInRegistries.BLOCK.getKey(block).getPath().toLowerCase(Locale.ROOT);
        if (path.endsWith("_wool")) return DefenseBlockKind.WOOL;
        if (path.endsWith("_planks") || path.endsWith("_log") || path.endsWith("_wood")) return DefenseBlockKind.WOOD;
        if (path.contains("glass")) return DefenseBlockKind.GLASS;
        if (path.endsWith("_terracotta") || path.equals("terracotta")) return DefenseBlockKind.TERRACOTTA;
        if (path.endsWith("_slab") || path.endsWith("_stairs")) return DefenseBlockKind.LADDER_SLAB;
        if (mapCore != null && mapCore.isOriginalMapBlock(pos.getX(), pos.getY(), pos.getZ())) {
            return DefenseBlockKind.ORIGINAL_MAP;
        }
        return DefenseBlockKind.WOOL;
    }

    private TeamDossier dossierFor(TeamColor team) {
        return teamDossiers.computeIfAbsent(team, TeamDossier::new);
    }

    private EnemyDossier enemyFor(String name) {
        return enemyDossiers.computeIfAbsent(name, EnemyDossier::new);
    }
}
