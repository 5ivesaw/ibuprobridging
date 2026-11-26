package ibuprofen.skills.nav;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Fast, BedWars-native 3D A* island walker inspired by Baritone's tick-cost moves
 * plus Pathadol's swept flat-corridor string-pulling smoother.
 * Handles solid island terrain, stairs/slabs, 1-3 block safe drops, 1-2 block gap parkour,
 * strict corner clearance, and void-edge avoidance without Baritone's survival bloat.
 */
public final class LocalPathfinder {

    public record NavPath(List<NavStep> steps, boolean complete, boolean reachable, int nodesExpanded) {
        public static NavPath unreachable(int expanded) {
            return new NavPath(List.of(), false, false, expanded);
        }
    }

    // Baritone-calibrated tick costs
    private static final double WALK_COST = 4.633;
    private static final double DIAG_COST = WALK_COST * 1.4142;
    private static final double STEP_UP_COST = 7.5;
    private static final double DROP_BASE_COST = 4.5;
    private static final double DROP_PER_BLOCK_COST = 1.8;
    private static final double PARKOUR_1_GAP_COST = 10.5;
    private static final double PARKOUR_2_GAP_COST = 14.5;
    private static final double VOID_EDGE_PENALTY = 1.65;

    private static final int DEFAULT_MAX_EXPANSIONS = 3500;
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
        double radiusSq = Math.max(0.5, arrivalRadius) * Math.max(0.5, arrivalRadius);
        if (distSq3D(startX, startY, startZ, goalX, goalY, goalZ) <= radiusSq) {
            NavStep here = new NavStep(startX, startY, startZ, NavMoveType.WALK, world.floorTopY(startX, startY - 1, startZ));
            return new NavPath(List.of(here), true, true, 0);
        }

        PriorityQueue<Node> open = new PriorityQueue<>();
        Map<Long, Node> visited = new HashMap<>(512);

        Node start = new Node(startX, startY, startZ, null, NavMoveType.WALK, 0.0,
                heuristic(startX, startY, startZ, goalX, goalY, goalZ));
        open.add(start);
        visited.put(pack(startX, startY, startZ), start);

        Node bestPartial = start;
        double bestPartialDistSq = distSq3D(startX, startY, startZ, goalX, goalY, goalZ);
        int expanded = 0;

        while (!open.isEmpty() && expanded < maxExpansions) {
            Node cur = open.poll();
            if (cur.closed) {
                continue;
            }
            cur.closed = true;
            expanded++;

            double dSq = distSq3D(cur.x, cur.y, cur.z, goalX, goalY, goalZ);
            if (dSq <= radiusSq) {
                List<NavStep> raw = reconstruct(cur, world);
                List<NavStep> smoothed = smoothFlatCorridor(raw, world);
                return new NavPath(smoothed, true, true, expanded);
            }
            if (dSq < bestPartialDistSq) {
                bestPartialDistSq = dSq;
                bestPartial = cur;
            }

            // 1. Cardinal WALK, STEP_UP (+1), DROP (-1..-3), and PARKOUR (1-2 block gap)
            for (int[] d : CARDINALS) {
                int nx = cur.x + d[0];
                int nz = cur.z + d[1];

                if (canStandAt(world, nx, cur.y, nz)) {
                    double cost = WALK_COST + voidEdgePenalty(world, nx, cur.y, nz);
                    relax(open, visited, cur, nx, cur.y, nz, NavMoveType.WALK, cost, goalX, goalY, goalZ);
                } else {
                    // STEP_UP (+1 Y)
                    if (world.isPassable(cur.x, cur.y + 2, cur.z)
                            && canStandAt(world, nx, cur.y + 1, nz)) {
                        double cost = STEP_UP_COST + voidEdgePenalty(world, nx, cur.y + 1, nz);
                        relax(open, visited, cur, nx, cur.y + 1, nz, NavMoveType.STEP_UP, cost, goalX, goalY, goalZ);
                    }
                    // DROP (-1..-3 Y)
                    if (world.isPassable(nx, cur.y, nz) && world.isPassable(nx, cur.y + 1, nz)) {
                        for (int drop = 1; drop <= 3; drop++) {
                            int ly = cur.y - drop;
                            if (!world.isPassable(nx, ly, nz)) {
                                break;
                            }
                            if (world.isSolidFloor(nx, ly - 1, nz)) {
                                double cost = DROP_BASE_COST + DROP_PER_BLOCK_COST * drop
                                        + voidEdgePenalty(world, nx, ly, nz);
                                relax(open, visited, cur, nx, ly, nz, NavMoveType.DROP, cost, goalX, goalY, goalZ);
                                break;
                            }
                        }
                    }
                    // PARKOUR_JUMP across 1-block gap (dist=2) or 2-block gap (dist=3)
                    tryParkour(open, visited, world, cur, d[0], d[1], goalX, goalY, goalZ);
                }
            }

            // 2. Diagonal WALK with strict 2-block corner clearance
            for (int[] d : DIAGONALS) {
                int nx = cur.x + d[0];
                int nz = cur.z + d[1];
                if (!canStandAt(world, nx, cur.y, nz)) {
                    continue;
                }
                boolean sideAClear = world.isPassable(cur.x + d[0], cur.y, cur.z)
                        && world.isPassable(cur.x + d[0], cur.y + 1, cur.z);
                boolean sideBClear = world.isPassable(cur.x, cur.y, cur.z + d[1])
                        && world.isPassable(cur.x, cur.y + 1, cur.z + d[1]);
                if (!sideAClear || !sideBClear) {
                    continue;
                }
                // Avoid cutting diagonally over a pure void corner when both sides have no floor
                boolean sideAFloor = world.isSolidFloor(cur.x + d[0], cur.y - 1, cur.z);
                boolean sideBFloor = world.isSolidFloor(cur.x, cur.y - 1, cur.z + d[1]);
                if (!sideAFloor && !sideBFloor) {
                    continue;
                }
                double cost = DIAG_COST + voidEdgePenalty(world, nx, cur.y, nz);
                relax(open, visited, cur, nx, cur.y, nz, NavMoveType.DIAGONAL, cost, goalX, goalY, goalZ);
            }
        }

        // If we expanded the search budget without reaching goal, only return partial if we made meaningful progress
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

        // Must have takeoff ceiling clearance
        if (!world.isPassable(cur.x, cur.y + 2, cur.z)) {
            return;
        }
        // First gap cell must be empty floor (an actual gap) and clear for the jump arc
        int gx1 = cur.x + dx;
        int gz1 = cur.z + dz;
        if (world.isSolidFloor(gx1, cur.y - 1, gz1)) {
            return;
        }
        if (!isJumpArcClear(world, gx1, cur.y, gz1)) {
            return;
        }

        // 1-block gap (landing at dist=2, same Y or Y-1)
        int lx2 = cur.x + dx * 2;
        int lz2 = cur.z + dz * 2;
        if (canStandAt(world, lx2, cur.y, lz2) && world.isPassable(lx2, cur.y + 2, lz2)) {
            relax(open, visited, cur, lx2, cur.y, lz2, NavMoveType.PARKOUR_JUMP,
                    PARKOUR_1_GAP_COST + voidEdgePenalty(world, lx2, cur.y, lz2), goalX, goalY, goalZ);
            return;
        }
        if (canStandAt(world, lx2, cur.y - 1, lz2) && world.isPassable(lx2, cur.y + 1, lz2)) {
            relax(open, visited, cur, lx2, cur.y - 1, lz2, NavMoveType.PARKOUR_JUMP,
                    PARKOUR_1_GAP_COST + 1.0 + voidEdgePenalty(world, lx2, cur.y - 1, lz2), goalX, goalY, goalZ);
            return;
        }

        // 2-block gap (landing at dist=3, same Y or Y-1)
        if (!isJumpArcClear(world, lx2, cur.y, lz2)) {
            return;
        }
        int lx3 = cur.x + dx * 3;
        int lz3 = cur.z + dz * 3;
        if (canStandAt(world, lx3, cur.y, lz3) && world.isPassable(lx3, cur.y + 2, lz3)) {
            relax(open, visited, cur, lx3, cur.y, lz3, NavMoveType.PARKOUR_JUMP,
                    PARKOUR_2_GAP_COST + voidEdgePenalty(world, lx3, cur.y, lz3), goalX, goalY, goalZ);
        } else if (canStandAt(world, lx3, cur.y - 1, lz3) && world.isPassable(lx3, cur.y + 1, lz3)) {
            relax(open, visited, cur, lx3, cur.y - 1, lz3, NavMoveType.PARKOUR_JUMP,
                    PARKOUR_2_GAP_COST + 1.0 + voidEdgePenalty(world, lx3, cur.y - 1, lz3), goalX, goalY, goalZ);
        }
    }

    private static boolean isJumpArcClear(VoxelNavWorld world, int x, int y, int z) {
        return world.isPassable(x, y, z) && world.isPassable(x, y + 1, z) && world.isPassable(x, y + 2, z);
    }

    private static boolean canStandAt(VoxelNavWorld world, int x, int y, int z) {
        return world.isSolidFloor(x, y - 1, z)
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

    /**
     * Swept flat-corridor string pulling: collapses consecutive same-Y WALK/DIAGONAL steps
     * into direct straight waypoints whenever the full 0.35m swept corridor has solid floor and 2-block headroom.
     */
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
                NavStep cand = raw.get(j);
                if (!isFlatWalk(cand.moveType()) || cand.y() != anchor.y()) {
                    break;
                }
                if (hasSweptFlatCorridor(world, anchor.x(), anchor.y(), anchor.z(), cand.x(), cand.z())) {
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

    private static boolean hasSweptFlatCorridor(VoxelNavWorld world, int x0, int y, int z0, int x1, int z1) {
        double sx = x0 + 0.5;
        double sz = z0 + 0.5;
        double dx = (x1 + 0.5) - sx;
        double dz = (z1 + 0.5) - sz;
        double dist = Math.hypot(dx, dz);
        if (dist < 0.01) {
            return true;
        }
        double nx = -dz / dist;
        double nz = dx / dist;
        double radius = 0.35;
        int samples = Math.max(2, (int) Math.ceil(dist / 0.25));
        for (int s = 1; s <= samples; s++) {
            double t = (double) s / samples;
            double cx = sx + dx * t;
            double cz = sz + dz * t;
            for (int side = -1; side <= 1; side++) {
                int bx = (int) Math.floor(cx + nx * radius * side);
                int bz = (int) Math.floor(cz + nz * radius * side);
                if (!canStandAt(world, bx, y, bz)) {
                    return false;
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
