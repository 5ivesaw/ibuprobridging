package ibuprofen.motor;

import java.util.EnumMap;
import java.util.Map;

/**
 * A single skill's request to control one body channel for the current tick.
 * Higher priority wins; ties preserve the previous tick's owner to prevent chatter.
 */
public final class ChannelRequest {

    public static final int PRIORITY_IDLE = 0;
    public static final int PRIORITY_NORMAL = 50;
    public static final int PRIORITY_HIGH = 80;
    public static final int PRIORITY_SURVIVAL = 100;

    public final BodyChannel channel;
    public final String owner;
    public final int priority;
    public final String reason;

    // MOVEMENT payload
    public final EnumMap<BodyInput, Boolean> moveKeys;
    // LOOK payload
    public final Rotation targetLook;
    public final boolean instantLook;
    // LMB / RMB payload
    public final boolean press;
    // HOTBAR_GUI payload (-1 means no hotbar change)
    public final int hotbarSlot;

    private ChannelRequest(
            BodyChannel channel,
            String owner,
            int priority,
            String reason,
            EnumMap<BodyInput, Boolean> moveKeys,
            Rotation targetLook,
            boolean instantLook,
            boolean press,
            int hotbarSlot) {
        this.channel = channel;
        this.owner = owner;
        this.priority = priority;
        this.reason = reason != null ? reason : "";
        this.moveKeys = moveKeys;
        this.targetLook = targetLook;
        this.instantLook = instantLook;
        this.press = press;
        this.hotbarSlot = hotbarSlot;
    }

    public static ChannelRequest movement(String owner, int priority, String reason, Map<BodyInput, Boolean> keys) {
        EnumMap<BodyInput, Boolean> copy = new EnumMap<>(BodyInput.class);
        if (keys != null) {
            copy.putAll(keys);
        }
        return new ChannelRequest(BodyChannel.MOVEMENT, owner, priority, reason, copy, null, false, false, -1);
    }

    public static ChannelRequest look(String owner, int priority, String reason, Rotation target, boolean instant) {
        return new ChannelRequest(BodyChannel.LOOK, owner, priority, reason, null, target, instant, false, -1);
    }

    public static ChannelRequest lmb(String owner, int priority, String reason, boolean press) {
        return new ChannelRequest(BodyChannel.LMB, owner, priority, reason, null, null, false, press, -1);
    }

    public static ChannelRequest rmb(String owner, int priority, String reason, boolean press) {
        return new ChannelRequest(BodyChannel.RMB, owner, priority, reason, null, null, false, press, -1);
    }

    public static ChannelRequest hotbar(String owner, int priority, String reason, int slot) {
        return new ChannelRequest(BodyChannel.HOTBAR_GUI, owner, priority, reason, null, null, false, false, slot);
    }
}
