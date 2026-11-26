package ibuprofen.skills.nav;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Comprehensive BedWars-native 3D A* island navigator inspired by Baritone's tick-cost moves
 * and Pathadol's swept corridor smoother.
 * Supports: cardinal & diagonal walk, cardinal & diagonal step-up (+1Y), cardinal & diagonal
 * safe drop (-1..-3Y), 1/2/3-block gap parkour (+0/-1/+1Y), ladder climbing, floating-goal
 * ground snapping, void-edge avoidance, and 0.45m AABB corridor smoothing.
 */
public final class LocalPathfinder {

    public record NavPath(List<NavStep> steps, boolean complete, boolean reachable, int nodesExpanded) {
        public static NavPath unreachable(int expanded) {
            return new NavPath(List.of(), false, false, expanded);
        }
    }

    private static final double WALK_COST = 4.633;
    private static final double DIAG_COST = WALK_COST * 1.4142;
    private static final double STEP_UP_COST = 7.5;
    private static final double DIAG_STEP_UP_COST = 9.4;
    private static final double DROP_BASE_COST = 4.5;
    private static final double DROP_PER_BLOCK_COST = 1.8;
    private static final double PARKOUR_1_GAP_COST = 10.5;
    private static final double PARKOUR_2_GAP_COST = 14.5;
    private static final double PARKOUR_3_GAP_COST = 19.0;
    private static final double LADDER_UP_COST = 8.5;
    private static final double LADDER_DOWN_COST = 6.7;
    private static final double VOID_EDGE_PENALTY = 1.65;

    private static final int DEFAULT_MAX_EXPANSIONS = 12000;
    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] DIAGONALS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private LocalPathfinder() {}

    public static NavPath findPath(
            VoxelNavWorld world,
            int startX, int startY, int startZ,
            int goalX, int goalY, int goalZ,
            double arrivalRadius) {
        return findPath(world, startX, startY, startZ, goalX, goalY, goalZ, arrivalRadius, DEFAULT_MAX_EXPANSIONS);
    }

    public static NavPath findPath(
            VoxelNavWorld world,
            int startX, int startY, int startZ,
            int goalX, int goalY, int goalZ,
            double arrivalRadius,
            int maxExpansions) {

        if (world == null) {
            return NavPath.unreachable(0);
        }
        // Snap floating POI targets (e.g. Diamond/Emerald holograms) or clicked floor blocks to walkable Y
        int snappedGoalY = world.snapToWalkableY(goalX, goalY, goalZ);
        double radiusSq = Math.max(0.5, arrivalRadius) * Math.max(0.5, arrivalRadius);
        if (distSq3D(startX, startY, startZ, goalX, snappedGoalY, goalZ) <= radiusSq) {
            NavStep here = new NavStep(startX, startY, startZ, NavMoveType.WALK, world.floorTopY(startX, startY - 1, startZ));
            return new NavPath(List.of(here), true, true, 0);
        }

        PriorityQueue<Node> open = new PriorityQueue<>();
        Map<Long, Node> visited = new HashMap<>(1024);

        Node start = new Node(startX, startY, startZ, null, NavMoveType.WALK, 0.0,
                heuristic(startX, startY, startZ, goalX, snappedGoalY, goalZ));
        open.add(start);
        visited.put(pack(startX, startY, startZ), start);

        Node bestPartial = start;
        double bestPartialDistSq = distSq3D(startX, startY, startZ, goalX, snappedGoalY, goalZ);
        int expanded = 0;

        while (!open.isEmpty() && expanded < maxExpansions) {
            Node cur = open.poll();
            if (cur.closed) {
                continue;
            }
            cur.closed = true;
            expanded++;

            double dSq = distSq3D(cur.x, cur.y, cur.z, goalX, snappedGoalY, goalZ);
            if (dSq <= radiusSq) {
                List<NavStep> raw = reconstruct(cur, world);
                return new NavPath(smoothFlatCorridor(raw, world), true, true, expanded);
            }
            if (dSq < bestPartialDistSq) {
                bestPartialDistSq = dSq;
                bestPartial = cur;
            }

            // 1. Ladder climbing (up/down)
            if (world.isClimbable(cur.x, cur.y, cur.z)) {
                if (world.isPassable(cur.x, cur.y + 1, cur.z) && world.isPassable(cur.x, cur.y + 2, cur.z)) {
                    relax(open, visited, cur, cur.x, cur.y + 1, cur.z, NavMoveType.LADDER_UP,
                            LADDER_UP_COST, goalX, snappedGoalY, goalZ);
                }
                if (world.isPassable(cur.x, cur.y - 1, cur.z)
                        && (world.isClimbable(cur.x, cur.y - 1, cur.z) || world.isSolidFloor(cur.x, cur.y - 2, cur.z))) {
                    relax(open, visited, cur, cur.x, cur.y - 1, cur.z, NavMoveType.LADDER_DOWN,
                            LADDER_DOWN_COST, goalX, snappedGoalY, goalZ);
                }
            }

            // 2. Cardinal WALK, STEP_UP (+1Y), DROP (-1..-3Y), and 1-3 block PARKOUR
            for (int[] d : CARDINALS) {
                int nx = cur.x + d[0];
                int nz = cur.z + d[1];

                if (canStandAt(world, nx, cur.y, nz)) {
                    double cost = WALK_COST + voidEdgePenalty(world, nx, cur.y, nz);
                    relax(open, visited, cur, nx, cur.y, nz, NavMoveType.WALK, cost, goalX, snappedGoalY, goalZ);
                } else {
                    if (world.isPassable(cur.x, cur.y + 2, cur.z) && canStandAt(world, nx, cur.y + 1, nz)) {
                        double cost = STEP_UP_COST + voidEdgePenalty(world, nx, cur.y + 1, nz);
                        relax(open, visited, cur, nx, cur.y + 1, nz, NavMoveType.STEP_UP, cost, goalX, snappedGoalY, goalZ);
                    }
                    if (world.isPassable(nx, cur.y, nz) && world.isPassable(nx, cur.y + 1, nz)) {
                        for (int drop = 1; drop <= 3; drop++) {
                            int ly = cur.y - drop;
                            if (!world.isPassable(nx, ly, nz)) {
                                break;
                            }
                            if (world.isSolidFloor(nx, ly - 1, nz)) {
                                double cost = DROP_BASE_COST + DROP_PER_BLOCK_COST * drop
                                        + voidEdgePenalty(world, nx, ly, nz);
                                relax(open, visited, cur, nx, ly, nz, NavMoveType.DROP, cost, goalX, snappedGoalY, goalZ);
                                break;
                            }
                        }
                    }
                    tryParkour(open, visited, world, cur, d[0], d[1], goalX, snappedGoalY, goalZ);
                }
            }

            // 3. Diagonal WALK, Diagonal STEP_UP (+1Y), and Diagonal DROP (-1..-2Y) with strict corner clearance
            for (int[] d : DIAGONALS) {
                int nx = cur.x + d[0];
                int nz = cur.z + d[1];
                boolean sideAClear = world.isPassable(cur.x + d[0], cur.y, cur.z)
                        && world.isPassable(cur.x + d[0], cur.y + 1, cur.z);
                boolean sideBClear = world.isPassable(cur.x, cur.y, cur.z + d[1])
                        && world.isPassable(cur.x, cur.y + 1, cur.z + d[1]);
                if (!sideAClear || !sideBClear) {
                    continue;
                }
                boolean sideAFloor = world.isSolidFloor(cur.x + d[0], cur.y - 1, cur.z);
                boolean sideBFloor = world.isSolidFloor(cur.x, cur.y - 1, cur.z + d[1]);

                if (canStandAt(world, nx, cur.y, nz) && (sideAFloor || sideBFloor)) {
                    double cost = DIAG_COST + voidEdgePenalty(world, nx, cur.y, nz);
                    relax(open, visited, cur, nx, cur.y, nz, NavMoveType.DIAGONAL, cost, goalX, snappedGoalY, goalZ);
                } else if (world.isPassable(cur.x, cur.y + 2, cur.z)
                        && world.isPassable(cur.x + d[0], cur.y + 2, cur.z)
                        && world.isPassable(cur.x, cur.y + 2, cur.z + d[1])
                        && canStandAt(world, nx, cur.y + 1, nz)) {
                    double cost = DIAG_STEP_UP_COST + voidEdgePenalty(world, nx, cur.y + 1, nz);
                    relax(open, visited, cur, nx, cur.y + 1, nz, NavMoveType.STEP_UP, cost, goalX, snappedGoalY, goalZ);
                } else if (world.isPassable(nx, cur.y, nz) && world.isPassable(nx, cur.y + 1, nz)
                        && (sideAFloor || sideBFloor)) {
                    for (int drop = 1; drop <= 2; drop++) {
                        int ly = cur.y - drop;
                        if (!world.isPassable(nx, ly, nz)) {
                            break;
                        }
                        if (world.isSolidFloor(nx, ly - 1, nz)) {
                            double cost = DROP_BASE_COST + 1.5 + DROP_PER_BLOCK_COST * drop
                                    + voidEdgePenalty(world, nx, ly, nz);
                            relax(open, visited, cur, nx, ly, nz, NavMoveType.DROP, cost, goalX, snappedGoalY, goalZ);
                            break;
                        }
                    }
                }
            }
        }

        if (expanded >= maxExpansions && bestPartial != start) {
            List<NavStep> raw = reconstruct(bestPartial, world);
            return new NavPath(smoothFlatCorridor(raw, world), false, true, expanded);
        }
        return NavPath.unreachable(expanded);
    }

    private static void tryParkour(
            PriorityQueue<Node> open,
            Map<Long, Node> visited,
            VoxelNavWorld world,
            Node cur, int dx, int dz,
            int goalX, int goalY, int goalZ) {

        if (!world.isPassable(cur.x, cur.y + 2, cur.z)) {
            return;
        }
        int gx1 = cur.x + dx;
        int gz1 = cur.z + dz;
        if (world.isSolidFloor(gx1, cur.y - 1, gz1) || !isJumpArcClear(world, gx1, cur.y, gz1)) {
            return;
        }

        // 1-block gap (dist=2): same Y, Y-1, or +1Y ascending parkour
        int lx2 = cur.x + dx * 2;
        int lz2 = cur.z + dz * 2;
        if (canStandAt(world, lx2, cur.y, lz2) && world.isPassable(lx2, cur.y + 2, lz2)) {
            relax(open, visited, cur, lx2, cur.y, lz2, NavMoveType.PARKOUR_JUMP,
                    PARKOUR_1_GAP_COST + voidEdgePenalty(world, lx2, cur.y, lz2), goalX, goalY, goalZ);
            return;
        }
        if (canStandAt(world, lx2, cur.y + 1, lz2) && world.isPassable(gx1, cur.y + 3, gz1)) {
            relax(open, visited, cur, lx2, cur.y + 1, lz2, NavMoveType.PARKOUR_JUMP,
                    PARKOUR_1_GAP_COST + 2.5 + voidEdgePenalty(world, lx2, cur.y + 1, lz2), goalX, goalY, goalZ);
            return;
        }
        if (canStandAt(world, lx2, cur.y - 1, lz2) && world.isPassable(lx2, cur.y + 1, lz2)) {
            relax(open, visited, cur, lx2, cur.y - 1, lz2, NavMoveType.PARKOUR_JUMP,
                    PARKOUR_1_GAP_COST + 1.0 + voidEdgePenalty(world, lx2, cur.y - 1, lz2), goalX, goalY, goalZ);
            return;
        }

        // 2-block gap (dist=3): same Y or Y-1
        if (!isJumpArcClear(world, lx2, cur.y, lz2)) {
            return;
        }
        int lx3 = cur.x + dx * 3;
        int lz3 = cur.z + dz * 3;
        if (canStandAt(world, lx3, cur.y, lz3) && world.isPassable(lx3, cur.y + 2, lz3)) {
            relax(open, visited, cur, lx3, cur.y, lz3, NavMoveType.PARKOUR_JUMP,
                    PARKOUR_2_GAP_COST + voidEdgePenalty(world, lx3, cur.y, lz3), goalX, goalY, goalZ);
            return;
        }
        if (canStandAt(world, lx3, cur.y - 1, lz3) && world.isPassable(lx3, cur.y + 1, lz3)) {
            relax(open, visited, cur, lx3, cur.y - 1, lz3, NavMoveType.PARKOUR_JUMP,
                    PARKOUR_2_GAP_COST + 1.0 + voidEdgePenalty(world, lx3, cur.y - 1, lz3), goalX, goalY, goalZ);
            return;
        }

        // 3-block sprint-jump gap (dist=4): same Y or Y-1
        if (!isJumpArcClear(world, lx3, cur.y, lz3)) {
            return;
        }
        int lx4 = cur.x + dx * 4;
        int lz4 = cur.z + dz * 4;
        if (canStandAt(world, lx4, cur.y, lz4) && world.isPassable(lx4, cur.y + 2, lz4)) {
            relax(open, visited, cur, lx4, cur.y, lz4, NavMoveType.PARKOUR_JUMP,
                    PARKOUR_3_GAP_COST + voidEdgePenalty(world, lx4, cur.y, lz4), goalX, goalY, goalZ);
        } else if (canStandAt(world, lx4, cur.y - 1, lz4) && world.isPassable(lx4, cur.y + 1, lz4)) {
            relax(open, visited, cur, lx4, cur.y - 1, lz4, NavMoveType.PARKOUR_JUMP,
                    PARKOUR_3_GAP_COST + 1.0 + voidEdgePenalty(world, lx4, cur.y - 1, lz4), goalX, goalY, goalZ);
        }
    }

    private static boolean isJumpArcClear(VoxelNavWorld world, int x, int y, int z) {
        return world.isPassable(x, y, z) && world.isPassable(x, y + 1, z) && world.isPassable(x, y + 2, z);
    }

    public static boolean canStandAt(VoxelNavWorld world, int x, int y, int z) {
        return (world.isSolidFloor(x, y - 1, z) || world.isClimbable(x, y, z))
                && world.isPassable(x, y, z)
                && world.isPassable(x, y + 1, z);
    }

    private static double voidEdgePenalty(VoxelNavWorld world, int x, int y, int z) {
        for (int[] d : CARDINALS) {
            if (world.isVoidBelow(x + d[0], y - 1, z + d[1])) {
                return VOID_EDGE_PENALTY;
            }
        }
        return 0.0;
    }

    private static void relax(
            PriorityQueue<Node> open,
            Map<Long, Node> visited,
            Node cur, int nx, int ny, int nz,
            NavMoveType move, double stepCost,
            int goalX, int goalY, int goalZ) {

        double newG = cur.g + stepCost;
        long key = pack(nx, ny, nz);
        Node existing = visited.get(key);
        if (existing != null) {
            if (existing.closed || newG >= existing.g) {
                return;
            }
            existing.parent = cur;
            existing.moveType = move;
            existing.g = newG;
            existing.f = newG + existing.h;
            open.add(existing);
            return;
        }
        double h = heuristic(nx, ny, nz, goalX, goalY, goalZ);
        Node next = new Node(nx, ny, nz, cur, move, newG, h);
        visited.put(key, next);
        open.add(next);
    }

    public static List<NavStep> smoothFlatCorridor(List<NavStep> raw, VoxelNavWorld world) {
        if (raw == null || raw.size() <= 2) {
            return raw == null ? List.of() : raw;
        }
        List<NavStep> out = new ArrayList<>(raw.size());
        int i = 0;
        out.add(raw.get(0));
        while (i < raw.size() - 1) {
            NavStep anchor = raw.get(i);
            NavStep immediate = raw.get(i + 1);
            if (!isFlatWalk(immediate.moveType()) || immediate.y() != anchor.y()) {
                out.add(immediate);
                i++;
                continue;
            }
            int furthest = i + 1;
            int maxLook = Math.min(raw.size() - 1, i + 12);
            for (int j = i + 2; j <= maxLook; j++) {
                NavStep prev = raw.get(j - 1);
                if (isChokepoint(world, prev.x(), prev.y(), prev.z())) {
                    break;
                }
                NavStep cand = raw.get(j);
                if (!isFlatWalk(cand.moveType()) || cand.y() != anchor.y()) {
                    break;
                }
                boolean allowVoidEdge = hasVoidEdgeInSegment(raw, i, j, world);
                if (hasSweptFlatCorridor(world, anchor.x(), anchor.y(), anchor.z(), cand.x(), cand.z(), allowVoidEdge)) {
                    furthest = j;
                } else {
                    break;
                }
            }
            out.add(raw.get(furthest));
            i = furthest;
        }
        return List.copyOf(out);
    }

    private static boolean isChokepoint(VoxelNavWorld world, int x, int y, int z) {
        boolean blockedX = !world.isPassable(x - 1, y, z) && !world.isPassable(x + 1, y, z);
        boolean blockedZ = !world.isPassable(x, y, z - 1) && !world.isPassable(x, y, z + 1);
        return blockedX || blockedZ;
    }

    private static boolean hasVoidEdgeInSegment(List<NavStep> raw, int fromIdx, int toIdx, VoxelNavWorld world) {
        for (int k = fromIdx + 1; k < toIdx; k++) {
            NavStep s = raw.get(k);
            if (voidEdgePenalty(world, s.x(), s.y(), s.z()) > 0.0) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSweptFlatCorridor(
            VoxelNavWorld world, int x0, int y, int z0, int x1, int z1, boolean allowVoidEdge) {
        double sx = x0 + 0.5;
        double sz = z0 + 0.5;
        double dx = (x1 + 0.5) - sx;
        double dz = (z1 + 0.5) - sz;
        double dist = Math.hypot(dx, dz);
        if (dist < 0.01) {
            return true;
        }
        double r = 0.45;
        int samples = Math.max(2, (int) Math.ceil(dist / 0.20));
        for (int s = 1; s < samples; s++) {
            double t = (double) s / samples;
            double cx = sx + dx * t;
            double cz = sz + dz * t;
            int minBx = (int) Math.floor(cx - r);
            int maxBx = (int) Math.floor(cx + r);
            int minBz = (int) Math.floor(cz - r);
            int maxBz = (int) Math.floor(cz + r);
            for (int bx = minBx; bx <= maxBx; bx++) {
                for (int bz = minBz; bz <= maxBz; bz++) {
                    if (!canStandAt(world, bx, y, bz)) {
                        return false;
                    }
                    if (!allowVoidEdge && voidEdgePenalty(world, bx, y, bz) > 0.0) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean isFlatWalk(NavMoveType m) {
        return m == NavMoveType.WALK || m == NavMoveType.DIAGONAL;
    }

    private static List<NavStep> reconstruct(Node end, VoxelNavWorld world) {
        List<NavStep> steps = new ArrayList<>();
        Node cur = end;
        while (cur != null) {
            steps.add(new NavStep(cur.x, cur.y, cur.z, cur.moveType, world.floorTopY(cur.x, cur.y - 1, cur.z)));
            cur = cur.parent;
        }
        Collections.reverse(steps);
        return steps;
    }

    private static double heuristic(int x, int y, int z, int gx, int gy, int gz) {
        int dx = Math.abs(x - gx);
        int dz = Math.abs(z - gz);
        int diag = Math.min(dx, dz);
        int straight = Math.max(dx, dz) - diag;
        double horiz = diag * DIAG_COST + straight * WALK_COST;
        int dy = gy - y;
        double vert = dy > 0 ? dy * STEP_UP_COST : Math.abs(dy) * 3.0;
        return (horiz + vert) * 0.99;
    }

    private static double distSq3D(int x1, int y1, int z1, int x2, int y2, int z2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        double dz = z1 - z2;
        return dx * dx + dy * dy + dz * dz;
    }

    private static long pack(int x, int y, int z) {
        return (((long) x & 0x3FFFFFFL) << 38) | (((long) z & 0x3FFFFFFL) << 12) | ((long) y & 0xFFFL);
    }

    private static final class Node implements Comparable<Node> {
        final int x, y, z;
        Node parent;
        NavMoveType moveType;
        double g;
        final double h;
        double f;
        boolean closed = false;

        Node(int x, int y, int z, Node parent, NavMoveType moveType, double g, double h) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.parent = parent;
            this.moveType = moveType;
            this.g = g;
            this.h = h;
            this.f = g + h;
        }

        @Override
        public int compareTo(Node o) {
            return Double.compare(this.f, o.f);
        }
    }
}
