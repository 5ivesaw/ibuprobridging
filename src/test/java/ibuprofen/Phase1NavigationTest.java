package ibuprofen;

import ibuprofen.skills.nav.LocalPathfinder;
import ibuprofen.skills.nav.LocalPathfinder.NavPath;
import ibuprofen.skills.nav.NavMoveType;
import ibuprofen.skills.nav.NavStep;
import ibuprofen.skills.nav.VoxelNavWorld;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class Phase1NavigationTest {

    private static final class GridNavWorld implements VoxelNavWorld {
        private final Set<Long> solid = new HashSet<>();

        void fill(int x0, int y0, int z0, int x1, int y1, int z1) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        solid.add(key(x, y, z));
                    }
                }
            }
        }

        void clear(int x0, int y0, int z0, int x1, int y1, int z1) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        solid.remove(key(x, y, z));
                    }
                }
            }
        }

        @Override
        public boolean isPassable(int x, int y, int z) {
            return !solid.contains(key(x, y, z));
        }

        @Override
        public boolean isSolidFloor(int x, int y, int z) {
            return solid.contains(key(x, y, z));
        }

        @Override
        public boolean isHazard(int x, int y, int z) {
            return false;
        }

        @Override
        public double floorTopY(int x, int y, int z) {
            return y + 1.0;
        }

        private static long key(int x, int y, int z) {
            return (((long) x & 0x3FFFFFFL) << 38) | (((long) z & 0x3FFFFFFL) << 12) | ((long) y & 0xFFFL);
        }
    }

    @Test
    void flatIslandPathIsSmoothedIntoDirectWaypoints() {
        GridNavWorld w = new GridNavWorld();
        w.fill(0, 63, 0, 12, 63, 12);

        NavPath path = LocalPathfinder.findPath(w, 1, 64, 1, 10, 64, 7, 0.75);
        assertTrue(path.reachable());
        assertTrue(path.complete());
        // String-pulling collapses 10+ grid steps into a direct 2-waypoint line
        assertTrue(path.steps().size() <= 3, "Expected smoothed corridor <= 3 waypoints, got " + path.steps().size());
        NavStep last = path.steps().get(path.steps().size() - 1);
        assertEquals(10, last.x());
        assertEquals(7, last.z());
    }

    @Test
    void wallAvoidanceNeverClipsDiagonalCorners() {
        GridNavWorld w = new GridNavWorld();
        w.fill(0, 63, 0, 8, 63, 8);
        // Solid wall blocking direct path with a single 1-wide doorway at (4, 64, 4)
        w.fill(4, 64, 0, 4, 66, 8);
        w.clear(4, 64, 4, 4, 65, 4);

        NavPath path = LocalPathfinder.findPath(w, 1, 64, 1, 7, 64, 7, 0.75);
        assertTrue(path.reachable());
        assertTrue(path.complete());
        assertTrue(path.steps().stream().anyMatch(s -> s.x() == 4 && s.z() == 4),
                "Path must pass cleanly through the doorway without clipping corners");
    }

    @Test
    void prefersSafeInteriorOverVoidEdgeButCrossesOneWideBridge() {
        // 1. Wide platform: starts near void edge z=0, should prefer interior z>=1 over hugging cliff z=0
        GridNavWorld wide = new GridNavWorld();
        wide.fill(0, 63, 0, 10, 63, 4);
        NavPath safePath = LocalPathfinder.findPath(wide, 0, 64, 0, 10, 64, 0, 0.75);
        assertTrue(safePath.complete());
        assertTrue(safePath.steps().stream().anyMatch(s -> s.z() >= 1),
                "Should step inward away from void cliff on flat island");

        // 2. 1-wide bridge over void: must still cross it cleanly
        GridNavWorld bridge = new GridNavWorld();
        bridge.fill(0, 63, 0, 12, 63, 0);
        NavPath bridgePath = LocalPathfinder.findPath(bridge, 0, 64, 0, 12, 64, 0, 0.75);
        assertTrue(bridgePath.reachable());
        assertTrue(bridgePath.complete());
    }

    @Test
    void stepUpChecksCeilingClearance() {
        GridNavWorld w = new GridNavWorld();
        w.fill(0, 63, 0, 2, 63, 2);
        w.fill(3, 64, 0, 5, 64, 2);

        // Open ceiling -> climbs cleanly
        NavPath open = LocalPathfinder.findPath(w, 1, 64, 1, 4, 65, 1, 0.75);
        assertTrue(open.complete());
        assertTrue(open.steps().stream().anyMatch(s -> s.moveType() == NavMoveType.STEP_UP));

        // Block ceiling at y=66 across takeoff line x=2 -> cannot step up!
        w.fill(2, 66, 0, 2, 66, 2);
        NavPath blocked = LocalPathfinder.findPath(w, 1, 64, 1, 4, 65, 1, 0.75);
        assertFalse(blocked.reachable(), "Must reject step-up when y+2 ceiling is blocked");
    }

    @Test
    void safeDropAllowedAndLethalDropRejected() {
        GridNavWorld safe = new GridNavWorld();
        safe.fill(0, 65, 0, 2, 65, 2);
        safe.fill(3, 63, 0, 5, 63, 2); // 2-block drop
        NavPath ok = LocalPathfinder.findPath(safe, 1, 66, 1, 4, 64, 1, 0.75);
        assertTrue(ok.complete());
        assertTrue(ok.steps().stream().anyMatch(s -> s.moveType() == NavMoveType.DROP));

        GridNavWorld lethal = new GridNavWorld();
        lethal.fill(0, 65, 0, 2, 65, 2);
        lethal.fill(3, 59, 0, 5, 59, 2); // 6-block lethal drop
        NavPath bad = LocalPathfinder.findPath(lethal, 1, 66, 1, 4, 60, 1, 0.75);
        assertFalse(bad.reachable(), "Must reject >3 block drop");
    }

    @Test
    void parkourGapSucceedsWhenClearAndFailsWithOverhang() {
        GridNavWorld w = new GridNavWorld();
        w.fill(0, 63, 0, 2, 63, 2);
        // 2-block air gap at x=3, x=4
        w.fill(5, 63, 0, 7, 63, 2);

        NavPath ok = LocalPathfinder.findPath(w, 1, 64, 1, 6, 64, 1, 0.75);
        assertTrue(ok.complete());
        assertTrue(ok.steps().stream().anyMatch(s -> s.moveType() == NavMoveType.PARKOUR_JUMP));

        // Add low overhang at y=66 across the gap -> head-bonk rejects jump!
        w.fill(3, 66, 0, 4, 66, 2);
        NavPath blocked = LocalPathfinder.findPath(w, 1, 64, 1, 6, 64, 1, 0.75);
        assertFalse(blocked.reachable(), "Must reject parkour jump when overhang blocks arc");
    }

    @Test
    void unreachableTargetBehindWallOrVoidFailsCleanly() {
        GridNavWorld w = new GridNavWorld();
        w.fill(0, 63, 0, 3, 63, 3);
        // Target isolated across 8-block void
        w.fill(12, 63, 0, 15, 63, 3);

        NavPath res = LocalPathfinder.findPath(w, 1, 64, 1, 13, 64, 1, 0.75);
        assertFalse(res.reachable());
        assertFalse(res.complete());
        assertTrue(res.steps().isEmpty());
    }
}
