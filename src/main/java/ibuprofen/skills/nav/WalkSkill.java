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
 * Layer 2 Humanized Island Navigation Skill (Phase 1).
 * Uses pure-pursuit continuous lookahead steering, MouseModel GCD aim, and KeyboardModel
 * octant-decoupled WASD so movement looks natural rather than robotic grid-snapping.
 * Owns ONLY {@code MOVEMENT} and {@code LOOK} channels so other skills compose freely.
 */
public final class WalkSkill implements Skill {

    private static final int STUCK_WINDOW_TICKS = 18;
    private static final double STUCK_MIN_DIST_SQ = 0.25 * 0.25;
    private static final int MAX_REPLANS = 3;

    private final int rawGoalX, rawGoalY, rawGoalZ;
    private int goalX, goalY, goalZ;
    private final double arrivalRadius;
    private final String label;
    private final KeyboardModel keyboard = new KeyboardModel();

    private volatile List<NavStep> steps = List.of();
    private volatile int stepIndex = 0;
    private int replans = 0;
    private int elapsedTicks = 0;
    private int timeoutTicks = 240;
    private boolean initialized = false;

    private double stuckAnchorX, stuckAnchorZ;
    private int stuckTicks = 0;

    public WalkSkill(int goalX, int goalY, int goalZ, double arrivalRadius, String label) {
        this.rawGoalX = goalX;
        this.rawGoalY = goalY;
        this.rawGoalZ = goalZ;
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

    public List<NavStep> activeSteps() {
        return steps;
    }

    public int currentStepIndex() {
        return stepIndex;
    }

    public int goalX() {
        return goalX;
    }

    public int goalY() {
        return goalY;
    }

    public int goalZ() {
        return goalZ;
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
            this.goalX = rawGoalX;
            this.goalY = navWorld.snapToWalkableY(rawGoalX, rawGoalY, rawGoalZ);
            this.goalZ = rawGoalZ;
            double dist = Math.hypot(player.getX() - (goalX + 0.5), player.getZ() - (goalZ + 0.5));
            timeoutTicks = Math.max(140, (int) Math.ceil(dist * 15.0) + 80);
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
        if (goalDx * goalDx + goalDz * goalDz <= arrivalRadius * arrivalRadius && Math.abs(goalDy) <= 1.8) {
            return SkillResult.done("arrived");
        }

        if (steps.isEmpty() || stepIndex >= steps.size()) {
            if (replans >= MAX_REPLANS || !planFromPlayer(navWorld, player)) {
                return SkillResult.blocked("path_exhausted");
            }
            replans++;
        }

        // Advance past reached waypoints
        while (stepIndex < steps.size()) {
            NavStep s = steps.get(stepIndex);
            double dx = s.centerX() - player.getX();
            double dz = s.centerZ() - player.getZ();
            double dy = Math.abs(s.y() - player.getY());
            double tol = (s.moveType() == NavMoveType.PARKOUR_JUMP || s.moveType() == NavMoveType.LADDER_UP)
                    ? 0.48 : 0.65;
            if (dx * dx + dz * dz <= tol * tol && dy <= 0.95) {
                stepIndex++;
            } else {
                break;
            }
        }

        if (stepIndex >= steps.size()) {
            if (goalDx * goalDx + goalDz * goalDz <= arrivalRadius * arrivalRadius && Math.abs(goalDy) <= 1.8) {
                return SkillResult.done("arrived");
            }
            if (replans >= MAX_REPLANS || !planFromPlayer(navWorld, player)) {
                return SkillResult.blocked("partial_path_blocked");
            }
            replans++;
        }

        NavStep current = steps.get(stepIndex);

        if (player.getY() < current.y() - 4.8) {
            return SkillResult.interrupted("fell_off_path");
        }

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

        // Pure-pursuit continuous lookahead point so turns feel fluid & human rather than robotic 90-degree snaps
        double[] steerPt = computeLookaheadPoint(player, current, navWorld);
        float moveYaw = computeYawTo(player.getX(), player.getZ(), steerPt[0], steerPt[1]);

        NavStep gazeStep = (stepIndex + 1 < steps.size() && isFlatMove(current.moveType()))
                ? steps.get(stepIndex + 1)
                : current;
        float lookYaw = computeYawTo(player.getX(), player.getZ(), gazeStep.centerX(), gazeStep.centerZ());
        float lookPitch = switch (current.moveType()) {
            case DROP -> 16.0f;
            case LADDER_UP -> -28.0f;
            case STEP_UP -> 2.0f;
            default -> 8.0f;
        };

        arbiter.request(ChannelRequest.look(
                name(), ChannelRequest.PRIORITY_NORMAL, "walk_steer",
                new Rotation(lookYaw, lookPitch), false));

        EnumMap<BodyInput, Boolean> rawKeys = new EnumMap<>(BodyInput.class);
        rawKeys.put(BodyInput.MOVE_FORWARD, true);

        boolean sharpTurnNearVoid = isSharpTurnNearVoid(navWorld, player, current, gazeStep);
        boolean wantsSprint = current.moveType() != NavMoveType.LADDER_UP
                && current.moveType() != NavMoveType.LADDER_DOWN
                && !sharpTurnNearVoid
                && Math.abs(Rotation.normalizeYaw(moveYaw - player.getYRot())) < 44.0f;
        rawKeys.put(BodyInput.SPRINT, wantsSprint);
        rawKeys.put(BodyInput.JUMP, shouldJump(player, current));

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

    private double[] computeLookaheadPoint(LocalPlayer player, NavStep current, VoxelNavWorld world) {
        if (!isFlatMove(current.moveType()) || stepIndex + 1 >= steps.size()) {
            return new double[]{current.centerX(), current.centerZ()};
        }
        NavStep next = steps.get(stepIndex + 1);
        if (!isFlatMove(next.moveType()) || next.y() != current.y()) {
            return new double[]{current.centerX(), current.centerZ()};
        }
        double distToCur = Math.hypot(current.centerX() - player.getX(), current.centerZ() - player.getZ());
        // Only blend toward next waypoint when close to current AND corridor has no void edge hazard
        if (distToCur < 1.15 && !isAdjacentVoid(world, current.x(), current.y(), current.z())) {
            double blend = Math.max(0.0, Math.min(0.45, (1.15 - distToCur) * 0.45));
            double tx = current.centerX() + (next.centerX() - current.centerX()) * blend;
            double tz = current.centerZ() + (next.centerZ() - current.centerZ()) * blend;
            return new double[]{tx, tz};
        }
        return new double[]{current.centerX(), current.centerZ()};
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
        if (step.moveType() == NavMoveType.LADDER_UP) {
            return true;
        }
        if (!player.onGround()) {
            return false;
        }
        double distXZ = Math.hypot(step.centerX() - player.getX(), step.centerZ() - player.getZ());
        if (step.moveType() == NavMoveType.STEP_UP) {
            return (step.targetTopY() - player.getY()) > 0.60 && distXZ <= 1.75;
        }
        if (step.moveType() == NavMoveType.PARKOUR_JUMP) {
            return distXZ >= 1.55 && distXZ <= 4.15;
        }
        return false;
    }

    private static boolean isFlatMove(NavMoveType m) {
        return m == NavMoveType.WALK || m == NavMoveType.DIAGONAL;
    }

    private static boolean isAdjacentVoid(VoxelNavWorld world, int x, int y, int z) {
        return world.isVoidBelow(x + 1, y - 1, z)
                || world.isVoidBelow(x - 1, y - 1, z)
                || world.isVoidBelow(x, y - 1, z + 1)
                || world.isVoidBelow(x, y - 1, z - 1);
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
        return isAdjacentVoid(world, cur.x(), cur.y(), cur.z());
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
