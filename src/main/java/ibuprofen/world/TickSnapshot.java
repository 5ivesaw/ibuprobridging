package ibuprofen.world;

import ibuprofen.world.BedwarsMapCore.TeamColor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.hurtingprojectile.Fireball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Single-pass per-tick snapshot of entities, true position-delta velocities, and player resources.
 * No other class in Ibuprofen is allowed to sweep {@code level.entitiesForRendering()}.
 */
public final class TickSnapshot {

    public static final class TrackedPlayer {
        public final int entityId;
        public final String name;
        public final boolean teammate;
        public final double x, y, z;
        public final double vx, vy, vz;
        public final double horizontalSpeed;
        public final double distSq;
        public final float health;
        public final boolean onGround;

        public TrackedPlayer(
                int entityId, String name, boolean teammate,
                double x, double y, double z,
                double vx, double vy, double vz,
                double distSq, float health, boolean onGround) {
            this.entityId = entityId;
            this.name = name;
            this.teammate = teammate;
            this.x = x;
            this.y = y;
            this.z = z;
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;
            this.horizontalSpeed = Math.hypot(vx, vz);
            this.distSq = distSq;
            this.health = health;
            this.onGround = onGround;
        }
    }

    public static final class ShopKeeperInfo {
        public final int entityId;
        public final BlockPos pos;
        public final boolean isUpgrades;
        public final double distSq;

        public ShopKeeperInfo(int entityId, BlockPos pos, boolean isUpgrades, double distSq) {
            this.entityId = entityId;
            this.pos = pos;
            this.isUpgrades = isUpgrades;
            this.distSq = distSq;
        }
    }

    public static final class HologramInfo {
        public final BlockPos pos;
        public final String text;

        public HologramInfo(BlockPos pos, String text) {
            this.pos = pos;
            this.text = text;
        }
    }

    private static final class VelSample {
        double lastX, lastY, lastZ;
        double emaVx, emaVy, emaVz;
        long lastSeenTick;
    }

    private static final double VEL_EMA_ALPHA = 0.55;
    private static final long STALE_VEL_TICKS = 100L;

    private final Map<Integer, VelSample> velHistory = new HashMap<>();

    public final List<TrackedPlayer> enemies = new ArrayList<>();
    public final List<TrackedPlayer> teammates = new ArrayList<>();
    public final List<ShopKeeperInfo> shopKeepers = new ArrayList<>();
    public final List<HologramInfo> holograms = new ArrayList<>();
    public final List<BlockPos> droppedResources = new ArrayList<>();
    public final List<Vec3> incomingFireballs = new ArrayList<>();

    public long tick = 0;
    public int ironCount = 0;
    public int goldCount = 0;
    public int diamondCount = 0;
    public int emeraldCount = 0;
    public int blockCount = 0;
    public TrackedPlayer nearestEnemy = null;

    public void clear() {
        velHistory.clear();
        resetLists();
    }

    private void resetLists() {
        enemies.clear();
        teammates.clear();
        shopKeepers.clear();
        holograms.clear();
        droppedResources.clear();
        incomingFireballs.clear();
        nearestEnemy = null;
        ironCount = 0;
        goldCount = 0;
        diamondCount = 0;
        emeraldCount = 0;
        blockCount = 0;
    }

    public void update(ClientLevel level, LocalPlayer self, BedwarsMatchState matchState, long currentTick) {
        this.tick = currentTick;
        resetLists();
        if (level == null || self == null) {
            return;
        }

        // 1. Inventory resource sweep
        for (ItemStack stack : self.getInventory().getNonEquipmentItems()) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
            int count = stack.getCount();
            if ("iron_ingot".equals(id)) {
                ironCount += count;
            } else if ("gold_ingot".equals(id)) {
                goldCount += count;
            } else if ("diamond".equals(id)) {
                diamondCount += count;
            } else if ("emerald".equals(id)) {
                emeraldCount += count;
            } else if (id.endsWith("_wool") || id.equals("end_stone") || id.endsWith("_planks")
                    || id.endsWith("_terracotta") || id.equals("obsidian")) {
                blockCount += count;
            }
        }

        // 2. Single entity sweep
        double sx = self.getX();
        double sy = self.getY();
        double sz = self.getZ();
        double bestEnemyDistSq = Double.MAX_VALUE;
        TeamColor myTeam = matchState != null ? matchState.getHomeTeam() : TeamColor.UNKNOWN;

        for (Entity e : level.entitiesForRendering()) {
            if (e == null || e == self || !e.isAlive()) {
                continue;
            }
            double ex = e.getX();
            double ey = e.getY();
            double ez = e.getZ();
            double dSq = (ex - sx) * (ex - sx) + (ey - sy) * (ey - sy) + (ez - sz) * (ez - sz);

            if (e instanceof Player other) {
                if (other.isSpectator() || other.isInvisible()) {
                    continue;
                }
                String name = other.getName().getString();
                if (name == null || name.isBlank() || name.startsWith("CIT-") || name.contains("[NPC]")) {
                    // Server NPC player entity (often used for Hypixel/Pika shopkeepers)
                    if (dSq < 64.0 * 64.0) {
                        boolean isUpgrades = name != null && name.toLowerCase(Locale.ROOT).contains("upgrade");
                        shopKeepers.add(new ShopKeeperInfo(e.getId(), e.blockPosition(), isUpgrades, dSq));
                    }
                    continue;
                }
                VelSample vel = updateTrueVelocity(e.getId(), ex, ey, ez, currentTick);
                boolean isTeammate = isSameTeam(self, other, myTeam);
                TrackedPlayer tp = new TrackedPlayer(
                        e.getId(), name, isTeammate,
                        ex, ey, ez,
                        vel.emaVx, vel.emaVy, vel.emaVz,
                        dSq, other.getHealth(), other.onGround());
                if (isTeammate) {
                    teammates.add(tp);
                } else {
                    enemies.add(tp);
                    if (dSq < bestEnemyDistSq) {
                        bestEnemyDistSq = dSq;
                        nearestEnemy = tp;
                    }
                }
            } else if (e instanceof Villager || e instanceof WanderingTrader) {
                String label = e.getDisplayName() != null ? e.getDisplayName().getString().toLowerCase(Locale.ROOT) : "";
                boolean isUpgrades = label.contains("upgrade") || label.contains("team");
                shopKeepers.add(new ShopKeeperInfo(e.getId(), e.blockPosition(), isUpgrades, dSq));
            } else if (e instanceof ArmorStand stand) {
                if (stand.hasCustomName() && stand.getCustomName() != null) {
                    String text = stripFormatting(stand.getCustomName().getString());
                    if (!text.isBlank()) {
                        holograms.add(new HologramInfo(stand.blockPosition(), text));
                    }
                }
            } else if (e instanceof Display.TextDisplay textDisplay) {
                String text = textDisplay.getText() != null ? stripFormatting(textDisplay.getText().getString()) : "";
                if (!text.isBlank()) {
                    holograms.add(new HologramInfo(textDisplay.blockPosition(), text));
                }
            } else if (e instanceof ItemEntity itemEntity) {
                String id = BuiltInRegistries.ITEM.getKey(itemEntity.getItem().getItem()).getPath();
                if ("iron_ingot".equals(id) || "gold_ingot".equals(id)
                        || "diamond".equals(id) || "emerald".equals(id)) {
                    droppedResources.add(itemEntity.blockPosition());
                }
            } else if (e instanceof Fireball) {
                incomingFireballs.add(e.position());
            }
        }

        // 3. Prune stale velocity entries periodically so map never leaks
        if ((currentTick & 31L) == 0L && !velHistory.isEmpty()) {
            Iterator<Map.Entry<Integer, VelSample>> it = velHistory.entrySet().iterator();
            while (it.hasNext()) {
                if (currentTick - it.next().getValue().lastSeenTick > STALE_VEL_TICKS) {
                    it.remove();
                }
            }
        }
    }

    private VelSample updateTrueVelocity(int entityId, double x, double y, double z, long currentTick) {
        VelSample sample = velHistory.get(entityId);
        if (sample == null) {
            sample = new VelSample();
            sample.lastX = x;
            sample.lastY = y;
            sample.lastZ = z;
            sample.lastSeenTick = currentTick;
            velHistory.put(entityId, sample);
            return sample;
        }
        long dt = Math.max(1L, currentTick - sample.lastSeenTick);
        if (dt <= 10L) {
            double rawVx = (x - sample.lastX) / dt;
            double rawVy = (y - sample.lastY) / dt;
            double rawVz = (z - sample.lastZ) / dt;
            // Clamp teleports so a respawn doesn't spike velocity
            if (Math.abs(rawVx) < 3.0 && Math.abs(rawVy) < 3.0 && Math.abs(rawVz) < 3.0) {
                sample.emaVx = VEL_EMA_ALPHA * rawVx + (1.0 - VEL_EMA_ALPHA) * sample.emaVx;
                sample.emaVy = VEL_EMA_ALPHA * rawVy + (1.0 - VEL_EMA_ALPHA) * sample.emaVy;
                sample.emaVz = VEL_EMA_ALPHA * rawVz + (1.0 - VEL_EMA_ALPHA) * sample.emaVz;
            }
        } else {
            sample.emaVx = 0;
            sample.emaVy = 0;
            sample.emaVz = 0;
        }
        sample.lastX = x;
        sample.lastY = y;
        sample.lastZ = z;
        sample.lastSeenTick = currentTick;
        return sample;
    }

    private static boolean isSameTeam(LocalPlayer self, Player other, TeamColor myTeam) {
        if (self.getTeam() != null && other.getTeam() != null) {
            return self.getTeam().getName().equals(other.getTeam().getName());
        }
        if (myTeam != null && myTeam != TeamColor.UNKNOWN && other.getDisplayName() != null) {
            String disp = other.getDisplayName().getString().toLowerCase(Locale.ROOT);
            return disp.contains(myTeam.name().toLowerCase(Locale.ROOT));
        }
        return false;
    }

    private static String stripFormatting(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("§[0-9a-fk-orA-FK-OR]", "").trim();
    }
}
