package ibuprofen.memory;

import ibuprofen.world.BedwarsDefensePlanner.BedShellScan;
import ibuprofen.world.BedwarsDefensePlanner.DefenseBlockKind;
import ibuprofen.world.BedwarsMapCore.TeamColor;

import java.util.Map;

/**
 * Persistent memory dossier for one BedWars team.
 * Remembers bed status and X-ray bed defense layers even after chunks unload or the player dies.
 */
public final class TeamDossier {

    public final TeamColor team;
    public boolean bedAlive = true;
    public boolean eliminated = false;

    public boolean hasBedPos = false;
    public int bedHeadX, bedHeadY, bedHeadZ;
    public int bedFootX, bedFootY, bedFootZ;

    public BedShellScan lastDefenseScan = null;
    public long lastDefenseScanTick = -1L;

    public TeamDossier(TeamColor team) {
        this.team = team;
    }

    public void updateBedPos(int hx, int hy, int hz, int fx, int fy, int fz) {
        this.hasBedPos = true;
        this.bedHeadX = hx;
        this.bedHeadY = hy;
        this.bedHeadZ = hz;
        this.bedFootX = fx;
        this.bedFootY = fy;
        this.bedFootZ = fz;
    }

    public void updateDefense(BedShellScan scan, long tick) {
        this.lastDefenseScan = scan;
        this.lastDefenseScanTick = tick;
    }

    public int layersDetected() {
        return lastDefenseScan != null ? lastDefenseScan.layersDetected() : 0;
    }

    public DefenseBlockKind hardestBlock() {
        return lastDefenseScan != null ? lastDefenseScan.hardestBlock() : DefenseBlockKind.AIR;
    }

    public int countOf(DefenseBlockKind kind) {
        if (lastDefenseScan == null || lastDefenseScan.counts() == null) {
            return 0;
        }
        return lastDefenseScan.counts().getOrDefault(kind, 0);
    }

    public boolean hasWool() {
        return countOf(DefenseBlockKind.WOOL) > 0;
    }

    public boolean hasWood() {
        return countOf(DefenseBlockKind.WOOD) > 0;
    }

    public boolean hasEndstone() {
        return countOf(DefenseBlockKind.ENDSTONE) > 0;
    }

    public boolean hasTerracotta() {
        return countOf(DefenseBlockKind.TERRACOTTA) > 0;
    }

    public boolean hasGlass() {
        return countOf(DefenseBlockKind.GLASS) > 0;
    }

    public boolean hasObsidian() {
        return countOf(DefenseBlockKind.OBSIDIAN) > 0;
    }

    public boolean hasWater() {
        return countOf(DefenseBlockKind.WATER_COVERED) > 0;
    }

    public String summaryString(long currentTick) {
        if (eliminated) {
            return team + ": ELIMINATED";
        }
        if (!bedAlive) {
            return team + ": NO BED";
        }
        if (lastDefenseScan == null) {
            return team + ": bed alive (defense not yet scanned)";
        }
        long ageSec = Math.max(0L, (currentTick - lastDefenseScanTick) / 20L);
        StringBuilder layers = new StringBuilder();
        for (Map.Entry<DefenseBlockKind, Integer> e : lastDefenseScan.counts().entrySet()) {
            if (e.getValue() > 0 && e.getKey() != DefenseBlockKind.AIR && e.getKey() != DefenseBlockKind.ORIGINAL_MAP) {
                if (!layers.isEmpty()) {
                    layers.append(", ");
                }
                layers.append(e.getKey().name()).append('x').append(e.getValue());
            }
        }
        if (layers.isEmpty()) {
            layers.append("EXPOSED");
        }
        return team + ": " + layersDetected() + "L [" + layers + "] (seen " + ageSec + "s ago)";
    }
}
