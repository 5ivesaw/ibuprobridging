/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package ibuprofen.motor;




import java.util.EnumMap;
import java.util.Map;

/**
 * Stateful human keyboard controller:
 * <ul>
 *   <li>Decouples feet travel direction ({@code desiredMoveYaw}) from current camera yaw</li>
 *   <li>Uses an 8-sector W/A/S/D solver with angular deadband hysteresis to prevent 1-tick key chatter</li>
 *   <li>Enforces minimum key hold duration during active movement transitions</li>
 *   <li>Enforces the vanilla sprint forward-cone constraint</li>
 * </ul>
 */
public final class KeyboardModel {

    private static final float SECTOR_HALF_WIDTH_DEG = 22.5f;
    private static final float HYSTERESIS_DEG = 5.0f;

    private static final BodyInput[] MOVEMENT_KEYS = {
            BodyInput.MOVE_FORWARD,
            BodyInput.MOVE_BACK,
            BodyInput.MOVE_LEFT,
            BodyInput.MOVE_RIGHT
    };

    private final EnumMap<BodyInput, Boolean> activeState = new EnumMap<>(BodyInput.class);
    private final EnumMap<BodyInput, Integer> holdTicks = new EnumMap<>(BodyInput.class);
    private int currentOctant = 0;
    private boolean hasActiveOctant = false;
    private Float desiredMoveYaw = null;

    public KeyboardModel() {
        for (BodyInput input : BodyInput.values()) {
            activeState.put(input, false);
            holdTicks.put(input, 100);
        }
    }

    public void setDesiredMoveYaw(Float yaw) {
        this.desiredMoveYaw = yaw;
    }

    public Float getDesiredMoveYaw() {
        return this.desiredMoveYaw;
    }

    public void clearDesiredMoveYaw() {
        this.desiredMoveYaw = null;
        this.hasActiveOctant = false;
    }

    /**
     * Resets all held key states, timers, and directional hysteresis cleanly when leaving control.
     */
    public void reset() {
        for (BodyInput input : BodyInput.values()) {
            activeState.put(input, false);
            holdTicks.put(input, 100);
        }
        currentOctant = 0;
        clearDesiredMoveYaw();
    }

    /**
     * Updates the persistent keyboard state for one tick.
     *
     * @param requestedRaw     raw inputs requested by Baritone movements/processes
     * @param currentCameraYaw actual camera yaw after {@link MouseModel} step
     * @param profile          active human profile
     * @return resolved human keyboard state for this tick
     */
    public Map<BodyInput, Boolean> step(
            Map<BodyInput, Boolean> requestedRaw,
            float currentCameraYaw,
            HumanProfile profile
    ) {
        EnumMap<BodyInput, Boolean> target = new EnumMap<>(BodyInput.class);
        for (BodyInput input : BodyInput.values()) {
            target.put(input, Boolean.TRUE.equals(requestedRaw.get(input)));
        }

        boolean wantsAnyMove = target.get(BodyInput.MOVE_FORWARD)
                || target.get(BodyInput.MOVE_BACK)
                || target.get(BodyInput.MOVE_LEFT)
                || target.get(BodyInput.MOVE_RIGHT);

        // Omnidirectional strafe decoupling when desiredMoveYaw is provided and forward movement is requested
        if (wantsAnyMove && desiredMoveYaw != null && !target.get(BodyInput.MOVE_BACK)) {
            float relYaw = Rotation.normalizeYaw(desiredMoveYaw - currentCameraYaw);
            int octant = resolveOctantWithHysteresis(relYaw);
            applyOctantKeys(octant, target);

            // Vanilla sprint requires forward movement and camera within the forward sprint cone (small +5 deg buffer avoids 45.0 deg flicker)
            if (Math.abs(relYaw) > Math.max(40.0f, profile.sprintConeDeg() + 5.0f) || !target.get(BodyInput.MOVE_FORWARD)) {
                target.put(BodyInput.SPRINT, false);
            }
        } else {
            hasActiveOctant = false;
        }

        int minHold = profile.minKeyHoldTicks();
        boolean sneakingNow = Boolean.TRUE.equals(target.get(BodyInput.SNEAK));

        // If movement stopped completely or sneaking started, update movement keys immediately for safety at ledges
        if (!wantsAnyMove) {
            for (BodyInput k : MOVEMENT_KEYS) {
                activeState.put(k, false);
                holdTicks.put(k, minHold);
            }
        } else if (sneakingNow) {
            for (BodyInput k : MOVEMENT_KEYS) {
                boolean des = target.get(k);
                if (activeState.get(k) != des) {
                    activeState.put(k, des);
                    holdTicks.put(k, 1);
                } else {
                    holdTicks.put(k, holdTicks.get(k) + 1);
                }
            }
        } else {
            // Apply minimum hold duration hysteresis across active directional transitions
            for (BodyInput k : MOVEMENT_KEYS) {
                boolean cur = activeState.get(k);
                boolean des = target.get(k);
                int held = holdTicks.get(k);
                if (cur == des) {
                    holdTicks.put(k, held + 1);
                } else if (held >= minHold) {
                    activeState.put(k, des);
                    holdTicks.put(k, 1);
                } else {
                    holdTicks.put(k, held + 1);
                }
            }
        }

        // Non-directional keys (JUMP, SNEAK, SPRINT, CLICK_LEFT, CLICK_RIGHT)
        for (BodyInput k : new BodyInput[]{BodyInput.JUMP, BodyInput.SNEAK, BodyInput.SPRINT, BodyInput.CLICK_LEFT, BodyInput.CLICK_RIGHT}) {
            boolean cur = activeState.get(k);
            boolean des = target.get(k);
            int held = holdTicks.get(k);
            if (cur == des) {
                holdTicks.put(k, held + 1);
            } else if (k == BodyInput.SNEAK && cur && !des && held < 2 && wantsAnyMove) {
                // Smooth crouch release: hold at least 2 ticks while moving so edges/taps never 1-tick flicker
                holdTicks.put(k, held + 1);
            } else {
                activeState.put(k, des);
                holdTicks.put(k, 1);
            }
        }

        // Prevent impossible simultaneous opposite keys (W+S or A+D)
        if (activeState.get(BodyInput.MOVE_FORWARD) && activeState.get(BodyInput.MOVE_BACK)) {
            if (target.get(BodyInput.MOVE_BACK)) {
                activeState.put(BodyInput.MOVE_FORWARD, false);
            } else {
                activeState.put(BodyInput.MOVE_BACK, false);
            }
        }
        if (activeState.get(BodyInput.MOVE_LEFT) && activeState.get(BodyInput.MOVE_RIGHT)) {
            if (target.get(BodyInput.MOVE_RIGHT)) {
                activeState.put(BodyInput.MOVE_LEFT, false);
            } else {
                activeState.put(BodyInput.MOVE_RIGHT, false);
            }
        }

        return new EnumMap<>(activeState);
    }

    /**
     * Maps a relative yaw angle in [-180, 180] to one of 8 octants (-3..4) with deadband hysteresis.
     */
    public int resolveOctantWithHysteresis(float relYawDeg) {
        if (hasActiveOctant) {
            float center = currentOctant * 45.0f;
            float diff = Math.abs(Rotation.normalizeYaw(relYawDeg - center));
            if (diff <= SECTOR_HALF_WIDTH_DEG + HYSTERESIS_DEG) {
                return currentOctant;
            }
        }
        int oct = Math.round(relYawDeg / 45.0f);
        if (oct <= -4) {
            oct = 4;
        }
        this.currentOctant = oct;
        this.hasActiveOctant = true;
        return oct;
    }

    private static void applyOctantKeys(int octant, EnumMap<BodyInput, Boolean> out) {
        out.put(BodyInput.MOVE_FORWARD, false);
        out.put(BodyInput.MOVE_BACK, false);
        out.put(BodyInput.MOVE_LEFT, false);
        out.put(BodyInput.MOVE_RIGHT, false);

        switch (octant) {
            case 0 -> out.put(BodyInput.MOVE_FORWARD, true);
            case 1 -> {
                out.put(BodyInput.MOVE_FORWARD, true);
                out.put(BodyInput.MOVE_RIGHT, true);
            }
            case 2 -> out.put(BodyInput.MOVE_RIGHT, true);
            case 3 -> {
                out.put(BodyInput.MOVE_BACK, true);
                out.put(BodyInput.MOVE_RIGHT, true);
            }
            case -1 -> {
                out.put(BodyInput.MOVE_FORWARD, true);
                out.put(BodyInput.MOVE_LEFT, true);
            }
            case -2 -> out.put(BodyInput.MOVE_LEFT, true);
            case -3 -> {
                out.put(BodyInput.MOVE_BACK, true);
                out.put(BodyInput.MOVE_LEFT, true);
            }
            default -> out.put(BodyInput.MOVE_BACK, true);
        }
    }

    public boolean isDown(BodyInput input) {
        return Boolean.TRUE.equals(activeState.get(input));
    }
}
