package ibuprofen.memory;

import ibuprofen.world.BedwarsMapCore.TeamColor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java counter-item recommender driven by persistent {@link MatchMemory}.
 * Example: if the bot spotted Green's wood+endstone bed earlier and walked away,
 * this keeps TNT + Axe + Pickaxe on the shopping wish list until Green's bed breaks.
 */
public final class ShoppingWishList {

    public record WishItem(String itemId, int priority, String reason) {}

    private ShoppingWishList() {}

    public static List<WishItem> compute(
            TeamColor homeTeam,
            Map<TeamColor, TeamDossier> teamDossiers,
            Map<String, EnemyDossier> enemyDossiers) {

        Map<String, WishItem> bestByItem = new LinkedHashMap<>();

        if (teamDossiers != null) {
            for (TeamDossier td : teamDossiers.values()) {
                if (td == null || td.team == TeamColor.UNKNOWN || td.team == homeTeam) {
                    continue;
                }
                if (!td.bedAlive || td.eliminated || td.lastDefenseScan == null) {
                    continue;
                }
                boolean hasGlass = td.hasGlass();
                if (td.hasObsidian()) {
                    offer(bestByItem, "DIAMOND_PICKAXE", 95, td.team + " bed has obsidian");
                }
                if (td.layersDetected() >= 2 || (td.hasWood() && td.hasEndstone())) {
                    if (hasGlass) {
                        offer(bestByItem, "TNT", 45, td.team + " has blastproof glass (low TNT value)");
                    } else {
                        offer(bestByItem, "TNT", 85,
                                td.team + " bed has " + td.layersDetected() + "-layer " + td.hardestBlock() + " defense");
                    }
                }
                if (td.hasWood()) {
                    offer(bestByItem, "AXE", hasGlass ? 85 : 75, td.team + " bed has wood");
                }
                if (td.hasEndstone() || td.hasTerracotta()) {
                    offer(bestByItem, "PICKAXE", hasGlass ? 85 : 75, td.team + " bed has endstone/clay");
                }
                if (td.hasWool() || td.layersDetected() <= 1) {
                    offer(bestByItem, "SHEARS", 60, td.team + " bed has wool");
                }
            }
        }

        if (enemyDossiers != null) {
            for (EnemyDossier ed : enemyDossiers.values()) {
                if (ed == null) {
                    continue;
                }
                if (ed.armorTier >= 2) {
                    offer(bestByItem, "IRON_ARMOR", 70, ed.name + " has iron/diamond armor");
                }
                if (ed.swordTier >= 2) {
                    offer(bestByItem, "IRON_SWORD", 68, ed.name + " has iron/diamond sword");
                }
                if (ed.seenFireball || ed.seenBow) {
                    offer(bestByItem, "FIREBALL", 55, ed.name + " has ranged/fireball pressure");
                }
            }
        }

        List<WishItem> sorted = new ArrayList<>(bestByItem.values());
        sorted.sort(Comparator.comparingInt(WishItem::priority).reversed()
                .thenComparing(WishItem::itemId));
        return List.copyOf(sorted);
    }

    private static void offer(Map<String, WishItem> map, String itemId, int priority, String reason) {
        WishItem existing = map.get(itemId);
        if (existing == null || priority > existing.priority()) {
            map.put(itemId, new WishItem(itemId, priority, reason));
        } else if (priority == existing.priority() && !existing.reason().contains(reason)) {
            map.put(itemId, new WishItem(itemId, priority, existing.reason() + "; " + reason));
        }
    }
}
