package ibuprofen.memory;

/**
 * Persistent memory profile for one enemy player across sightings and respawns.
 */
public final class EnemyDossier {

    public final String name;
    public double lastX, lastY, lastZ;
    public double lastVx, lastVz;
    public long lastSeenTick = -1L;

    public int armorTier = 0; // 0=leather, 1=chain, 2=iron, 3=diamond
    public int swordTier = 0; // 0=wood, 1=stone, 2=iron, 3=diamond
    public boolean seenFireball = false;
    public boolean seenPearl = false;
    public boolean seenBow = false;
    public boolean seenGapple = false;
    public boolean seenTnt = false;

    public int kills = 0;
    public int deaths = 0;
    public long respawnUntilTick = -1L;

    public EnemyDossier(String name) {
        this.name = name;
    }

    public String utilitySummary() {
        StringBuilder sb = new StringBuilder();
        if (seenFireball) sb.append("FB,");
        if (seenPearl) sb.append("PEARL,");
        if (seenBow) sb.append("BOW,");
        if (seenGapple) sb.append("GAP,");
        if (seenTnt) sb.append("TNT,");
        if (!sb.isEmpty()) {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    public String summaryString(long currentTick) {
        long ageSec = lastSeenTick >= 0 ? Math.max(0L, (currentTick - lastSeenTick) / 20L) : -1L;
        String arm = switch (armorTier) {
            case 3 -> "DiaArm";
            case 2 -> "IronArm";
            case 1 -> "ChainArm";
            default -> "Leather";
        };
        String swd = switch (swordTier) {
            case 3 -> "DiaSwd";
            case 2 -> "IronSwd";
            case 1 -> "StoneSwd";
            default -> "WoodSwd";
        };
        String util = utilitySummary();
        String resp = respawnUntilTick > currentTick ? " [RESPAWNING]" : "";
        return name + " (" + arm + ", " + swd
                + (util.isEmpty() ? "" : ", " + util)
                + ", " + (ageSec >= 0 ? ageSec + "s ago" : "unseen") + ")" + resp;
    }
}
