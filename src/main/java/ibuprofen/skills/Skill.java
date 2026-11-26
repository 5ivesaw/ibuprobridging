package ibuprofen.skills;

import ibuprofen.event.MatchEventBus;
import ibuprofen.memory.MatchMemory;
import ibuprofen.motor.InputArbiter;
import ibuprofen.world.WorldState;
import net.minecraft.client.Minecraft;

/**
 * Common Layer 2 contract for all small, focused skills (WalkSkill, ShopSkill, BridgeSkill, CombatSkill, etc.).
 * Skills request only the {@link ibuprofen.motor.BodyChannel}s they need via {@link InputArbiter}.
 */
public interface Skill {

    String name();

    SkillResult tick(
            Minecraft mc,
            WorldState world,
            MatchMemory memory,
            InputArbiter arbiter,
            MatchEventBus bus);

    void cancel(long tick, MatchEventBus bus, String reason);
}
