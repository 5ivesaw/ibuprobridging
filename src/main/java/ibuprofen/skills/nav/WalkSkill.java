package ibuprofen.skills.nav;

import ibuprofen.event.MatchEventBus;
import ibuprofen.memory.MatchMemory;
import ibuprofen.motor.BodyInput;
import ibuprofen.motor.ChannelRequest;
import ibuprofen.motor.InputArbiter;
import ibuprofen.motor.KeyboardModel;
import ibuprofen.motor.Rotation;
import ibuprofen.skills.Skill;
import ibuprofen.skills.SkillResult;
import ibuprofen.skills.nav.LocalPathfinder.NavPath;
import ibuprofen.world.WorldState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Layer 2 Island Navigation Skill (Phase 1).
 * Walks smoothly along a {@link LocalPathfinder} path using ONLY {@code MOVEMENT} and {@code LOOK}
 * channels so higher-priority skills (healing, combat) can compose alongside walking.
 */
public final class WalkSkill implements Skill {

    private static final int STUCK_WINDOW_TICKS = 18;
    private static final double STUCK_MIN_DIST_SQ = 0.25 * 0.25;
    private static final int MAX_REPLANS = 3;

    private final int goalX, goalY, goalZ;
    private final double arrivalRadius;
    private final String label;
    private final KeyboardModel keyboard = new KeyboardModel();

    private List<NavStep> steps = List.of();
    private int stepIndex = 0;
    private int replans = 0;
    private int elapsedTicks = 0;
    private int timeoutTicks = 240;
    private boolean initialized = false;

    private double stuckAnchorX, stuckAnchorZ;
    private int stuckTicks = 0;

    public WalkSkill(int goalX, int goalY, int goalZ, double arrivalRadius, String label) {
        this.goalX = goalX;
        this.goalY = goalY;
        this.goalZ = goalZ;
        this.arrivalRadius = Math.max(0.65, arrivalRadius);
        this.label = (label != null && !label.isBlank()) ? label : (goalX + "," + goalY + "," + goalZ);
    }

    @Override
    public String name() {
        return "WalkSkill(" + label + ")";
    }

    @Override
    public SkillResult tick(
            Minecraft mc,
            WorldState world,
            MatchMemory memory,
            InputArbiter arbiter,
            MatchEventBus bus) {

        if (mc == null || mc.level == null || mc.player == null) {
            return SkillResult.interrupted("no_player");
        }
        LocalPlayer player = mc.player;
        VoxelNavWorld navWorld = VoxelNavWorld.fromClientLevel(mc.level);

        if (!initialized) {
            initialized = true;
            double dist = Math.hypot(player.getX() - (goalX + 0.5), player.getZ() - (goalZ + 0.5));
            timeoutTicks = Math.max(120, (int) Math.ceil(dist * 14.0) + 60);
            resetStuckAnchor(player);
            if (!planFromPlayer(navWorld, player)) {
                return SkillResult.blocked("unreachable_target");
            }
        }

        elapsedTicks++;
        if (elapsedTicks > timeoutTicks) {
            return SkillResult.timeout("walk_timeout");
        }

        // Check goal arrival first
        double goalDx = (goalX + 0.5) - player.getX();
        double goalDy = goalY - player.getY();
        double goalDz = (goalZ + 0.5) - player.getZ();
        if (goalDx * goalDx + goalDz * goalDz <= arrivalRadius * arrivalRadius && Math.abs(goalDy) <= 1.6) {
            return SkillResult.done("arrived");
        }

        if (steps.isEmpty() || stepIndex >= steps.size()) {
            if (replans >= MAX_REPLANS || !planFromPlayer(navWorld, player)) {
                return SkillResult.blocked("path_exhausted");
            }
            replans++;
        }

        // Advance past waypoints we already reached
        while (stepIndex < steps.size()) {
            NavStep s = steps.get(stepIndex);
            double dx = s.centerX() - player.getX();
            double dz = s.centerZ() - player.getZ();
            double dy = Math.abs(s.y() - player.getY());
            double tol = (s.moveType() == NavMoveType.PARKOUR_JUMP) ? 0.50 : 0.62;
            if (dx * dx + dz * dz <= tol * tol && dy <= 0.95) {
                stepIndex++;
            } else {
                break;
            }
        }

        if (stepIndex >= steps.size()) {
            // Re-check arrival or replan if final partial path ended before goal
            if (goalDx * goalDx + goalDz * goalDz <= arrivalRadius * arrivalRadius && Math.abs(goalDy) <= 1.6) {
                return SkillResult.done("arrived");
            }
            if (replans >= MAX_REPLANS || !planFromPlayer(navWorld, player)) {
                return SkillResult.blocked("partial_path_blocked");
            }
            replans++;
        }

        NavStep current = steps.get(stepIndex);

        // Void / fall safety abort
        if (player.getY() < current.y() - 4.5) {
            return SkillResult.interrupted("fell_off_path");
        }

        // Stuck watchdog -> immediate A* replan
        if (checkStuck(player)) {
            if (replans >= MAX_REPLANS) {
                return SkillResult.blocked("stuck_max_replans");
            }
            replans++;
            if (!planFromPlayer(navWorld, player)) {
                return SkillResult.blocked("stuck_unreachable");
            }
            current = steps.get(Math.min(stepIndex, steps.size() - 1));
        }

        // Compute desired yaw toward current waypoint (or look slightly ahead for smooth curves)
        NavStep lookTarget = (stepIndex + 1 < steps.size()
                && current.moveType() != NavMoveType.PARKOUR_JUMP
                && Math.hypot(current.centerX() - player.getX(), current.centerZ() - player.getZ()) < 1.3)
                ? steps.get(stepIndex + 1)
                : current;

        float moveYaw = computeYawTo(player.getX(), player.getZ(), current.centerX(), current.centerZ());
        float lookYaw = computeYawTo(player.getX(), player.getZ(), lookTarget.centerX(), lookTarget.centerZ());
        float lookPitch = (current.moveType() == NavMoveType.DROP) ? 16.0f : 8.0f;

        arbiter.request(ChannelRequest.look(
                name(), ChannelRequest.PRIORITY_NORMAL, "walk_steer",
                new Rotation(lookYaw, lookPitch), false));

        // Build raw movement intent and pass through salvaged KeyboardModel for smooth WASD + sprint-cone
        EnumMap<BodyInput, Boolean> rawKeys = new EnumMap<>(BodyInput.class);
        rawKeys.put(BodyInput.MOVE_FORWARD, true);

        boolean sharpTurnNearVoid = isSharpTurnNearVoid(navWorld, player, current, lookTarget);
        boolean wantsSprint = !sharpTurnNearVoid
                && Math.abs(Rotation.normalizeYaw(moveYaw - player.getYRot())) < 42.0f;
        rawKeys.put(BodyInput.SPRINT, wantsSprint);

        boolean wantsJump = shouldJump(player, current);
        rawKeys.put(BodyInput.JUMP, wantsJump);

        keyboard.setDesiredMoveYaw(moveYaw);
        Map<BodyInput, Boolean> humanKeys = keyboard.step(rawKeys, player.getYRot(), arbiter.profile());
        arbiter.request(ChannelRequest.movement(
                name(), ChannelRequest.PRIORITY_NORMAL, current.moveType().name().toLowerCase(), humanKeys));

        return SkillResult.running();
    }

    @Override
    public void cancel(long tick, MatchEventBus bus, String reason) {
        keyboard.reset();
        steps = List.of();
        stepIndex = 0;
    }

    private boolean planFromPlayer(VoxelNavWorld navWorld, LocalPlayer player) {
        int sx = (int) Math.floor(player.getX());
        int sy = (int) Math.floor(player.getY() + 0.15);
        int sz = (int) Math.floor(player.getZ());

        NavPath path = LocalPathfinder.findPath(navWorld, sx, sy, sz, goalX, goalY, goalZ, arrivalRadius);
        if (!path.reachable() || path.steps().isEmpty()) {
            steps = List.of();
            stepIndex = 0;
            return false;
        }
        steps = path.steps();
        stepIndex = steps.size() > 1 ? 1 : 0;
        resetStuckAnchor(player);
        return true;
    }

    private boolean shouldJump(LocalPlayer player, NavStep step) {
        if (!player.onGround()) {
            return false;
        }
        double distXZ = Math.hypot(step.centerX() - player.getX(), step.centerZ() - player.getZ());
        if (step.moveType() == NavMoveType.STEP_UP) {
            // Only jump if step height exceeds Minecraft's 0.6 auto-step (slabs walk up without jumping)
            return (step.targetTopY() - player.getY()) > 0.60 && distXZ <= 1.65;
        }
        if (step.moveType() == NavMoveType.PARKOUR_JUMP) {
            // Jump right before leaving the takeoff block edge
            return distXZ >= 1.55 && distXZ <= 3.15;
        }
        return false;
    }

    private static boolean isSharpTurnNearVoid(VoxelNavWorld world, LocalPlayer player, NavStep cur, NavStep next) {
        if (cur == next) {
            return false;
        }
        float yaw1 = computeYawTo(player.getX(), player.getZ(), cur.centerX(), cur.centerZ());
        float yaw2 = computeYawTo(cur.centerX(), cur.centerZ(), next.centerX(), next.centerZ());
        if (Math.abs(Rotation.normalizeYaw(yaw2 - yaw1)) < 55.0f) {
            return false;
        }
        return world.isVoidBelow(cur.x() + 1, cur.y() - 1, cur.z())
                || world.isVoidBelow(cur.x() - 1, cur.y() - 1, cur.z())
                || world.isVoidBelow(cur.x(), cur.y() - 1, cur.z() + 1)
                || world.isVoidBelow(cur.x(), cur.y() - 1, cur.z() - 1);
    }

    private boolean checkStuck(LocalPlayer player) {
        double dx = player.getX() - stuckAnchorX;
        double dz = player.getZ() - stuckAnchorZ;
        if (dx * dx + dz * dz >= STUCK_MIN_DIST_SQ) {
            resetStuckAnchor(player);
            return false;
        }
        stuckTicks++;
        if (stuckTicks >= STUCK_WINDOW_TICKS) {
            resetStuckAnchor(player);
            return true;
        }
        return false;
    }

    private void resetStuckAnchor(LocalPlayer player) {
        stuckAnchorX = player.getX();
        stuckAnchorZ = player.getZ();
        stuckTicks = 0;
    }

    private static float computeYawTo(double fromX, double fromZ, double toX, double toZ) {
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        return (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
    }
}
