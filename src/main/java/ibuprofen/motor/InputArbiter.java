package ibuprofen.motor;

import ibuprofen.event.MatchEvent;
import ibuprofen.event.MatchEventBus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.EnumMap;
import java.util.Map;

/**
 * Layer 1 central input arbiter.
 * Skills submit requests per BodyChannel; each tick InputArbiter picks the highest-priority
 * owner per channel independently, applies the resolved controls, and logs ownership changes.
 */
public final class InputArbiter {

    private final EnumMap<BodyChannel, ChannelRequest> pending = new EnumMap<>(BodyChannel.class);
    private final EnumMap<BodyChannel, ChannelRequest> active = new EnumMap<>(BodyChannel.class);
    private final EnumMap<BodyChannel, String> lastOwners = new EnumMap<>(BodyChannel.class);
    private final EnumMap<BodyInput, Boolean> effectiveKeys = new EnumMap<>(BodyInput.class);

    private final MouseModel mouseModel = new MouseModel(new ForkableRandom());
    private HumanProfile profile = HumanProfile.defaultProfile();
    private boolean anyActive = false;

    public InputArbiter() {
        for (BodyInput key : BodyInput.values()) {
            effectiveKeys.put(key, false);
        }
    }

    public void setProfile(HumanProfile profile) {
        if (profile != null) {
            this.profile = profile;
        }
    }

    public HumanProfile profile() {
        return profile;
    }

    /**
     * Submits a request for the current tick. Higher priority wins; equal priority keeps previous owner.
     */
    public void request(ChannelRequest req) {
        if (req == null) {
            return;
        }
        ChannelRequest existing = pending.get(req.channel);
        if (existing == null || req.priority > existing.priority) {
            pending.put(req.channel, req);
        } else if (req.priority == existing.priority) {
            String prevOwner = lastOwners.get(req.channel);
            if (req.owner.equals(prevOwner)) {
                pending.put(req.channel, req);
            }
        }
    }

    /**
     * Resolves all 5 channels for this tick and applies look/hotbar to the local player.
     * Key overrides are read directly by KeyboardInputMixin via {@link #getKeyOverride(BodyInput)}.
     */
    public void commitTick(Minecraft mc, long tick, MatchEventBus bus) {
        active.clear();
        active.putAll(pending);
        pending.clear();

        anyActive = !active.isEmpty();

        // Reset effective key state
        for (BodyInput key : BodyInput.values()) {
            effectiveKeys.put(key, false);
        }

        // Detect and publish channel ownership transitions on MatchEventBus
        for (BodyChannel ch : BodyChannel.values()) {
            ChannelRequest req = active.get(ch);
            String newOwner = req != null ? req.owner : null;
            String oldOwner = lastOwners.get(ch);
            if ((oldOwner == null && newOwner != null)
                    || (oldOwner != null && !oldOwner.equals(newOwner))) {
                lastOwners.put(ch, newOwner);
                if (bus != null) {
                    bus.publish(new MatchEvent.ChannelChangedEvent(
                            tick, ch.name(), oldOwner, newOwner, req != null ? req.reason : "released"));
                }
            }
        }

        // Apply MOVEMENT keys
        ChannelRequest moveReq = active.get(BodyChannel.MOVEMENT);
        if (moveReq != null && moveReq.moveKeys != null) {
            for (Map.Entry<BodyInput, Boolean> e : moveReq.moveKeys.entrySet()) {
                effectiveKeys.put(e.getKey(), Boolean.TRUE.equals(e.getValue()));
            }
        }

        // Apply LMB / RMB
        ChannelRequest lmbReq = active.get(BodyChannel.LMB);
        if (lmbReq != null) {
            effectiveKeys.put(BodyInput.CLICK_LEFT, lmbReq.press);
        }
        ChannelRequest rmbReq = active.get(BodyChannel.RMB);
        if (rmbReq != null) {
            effectiveKeys.put(BodyInput.CLICK_RIGHT, rmbReq.press);
        }

        LocalPlayer player = mc != null ? mc.player : null;
        if (player == null) {
            return;
        }

        // Apply LOOK via MouseModel (GCD-quantized human smoothing)
        ChannelRequest lookReq = active.get(BodyChannel.LOOK);
        if (lookReq != null && lookReq.targetLook != null) {
            if (lookReq.instantLook) {
                Rotation clamped = lookReq.targetLook.clamp();
                player.setYRot(clamped.getYaw());
                player.setXRot(clamped.getPitch());
            } else {
                Rotation current = new Rotation(player.getYRot(), player.getXRot());
                double sens = mc.options != null ? mc.options.sensitivity().get() : 0.5;
                Rotation stepped = mouseModel.step(current, lookReq.targetLook, false, false, profile, sens);
                player.setYRot(stepped.getYaw());
                player.setXRot(stepped.getPitch());
            }
        }

        // Apply HOTBAR slot selection
        ChannelRequest hotbarReq = active.get(BodyChannel.HOTBAR_GUI);
        if (hotbarReq != null && hotbarReq.hotbarSlot >= 0 && hotbarReq.hotbarSlot <= 8) {
            player.getInventory().setSelectedSlot(hotbarReq.hotbarSlot);
        }
    }

    /**
     * Returns whether the bot currently owns the channel governing this key.
     * When null is returned, the real player's physical keyboard/mouse passes through untouched.
     */
    public Boolean getKeyOverride(BodyInput input) {
        if (!anyActive || input == null) {
            return null;
        }
        BodyChannel governing = switch (input) {
            case CLICK_LEFT -> BodyChannel.LMB;
            case CLICK_RIGHT -> BodyChannel.RMB;
            default -> BodyChannel.MOVEMENT;
        };
        if (!active.containsKey(governing)) {
            return null;
        }
        return effectiveKeys.getOrDefault(input, false);
    }

    /**
     * Emergency stop: immediately releases all 5 channels back to the human player.
     */
    public void releaseAll(long tick, MatchEventBus bus, String reason) {
        pending.clear();
        active.clear();
        anyActive = false;
        for (BodyInput key : BodyInput.values()) {
            effectiveKeys.put(key, false);
        }
        for (BodyChannel ch : BodyChannel.values()) {
            String prev = lastOwners.put(ch, null);
            if (prev != null && bus != null) {
                bus.publish(new MatchEvent.ChannelChangedEvent(tick, ch.name(), prev, null, reason));
            }
        }
    }

    public String getOwner(BodyChannel channel) {
        ChannelRequest req = active.get(channel);
        return req != null ? req.owner : null;
    }

    public boolean isAnyActive() {
        return anyActive;
    }

    public String formatActiveSummary() {
        StringBuilder sb = new StringBuilder();
        for (BodyChannel ch : BodyChannel.values()) {
            String owner = getOwner(ch);
            if (owner != null) {
                if (!sb.isEmpty()) {
                    sb.append(", ");
                }
                sb.append(ch.name()).append('=').append(owner);
            }
        }
        return sb.isEmpty() ? "idle (human control)" : sb.toString();
    }
}
