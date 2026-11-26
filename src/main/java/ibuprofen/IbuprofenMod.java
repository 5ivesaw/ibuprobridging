package ibuprofen;

import ibuprofen.command.IbuCommand;
import ibuprofen.motor.InputArbiter;
import ibuprofen.telemetry.DebugRecorder;
import ibuprofen.world.WorldState;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Ibuprobridging (internal id: ibuprofen) Fabric client entrypoint.
 * Owns the Layer 1 WorldState blackboard, 5-channel InputArbiter, and lightweight DebugRecorder.
 */
public final class IbuprofenMod implements ClientModInitializer {

    public static final String MOD_ID = "ibuprofen";
    public static final Logger LOGGER = LoggerFactory.getLogger("Ibuprobridging");

    private static IbuprofenMod instance;

    private final WorldState worldState = new WorldState();
    private final InputArbiter inputArbiter = new InputArbiter();
    private DebugRecorder debugRecorder;

    public static IbuprofenMod get() {
        return instance;
    }

    @Override
    public void onInitializeClient() {
        instance = this;
        Path gameDir = Minecraft.getInstance().gameDirectory.toPath();
        this.debugRecorder = new DebugRecorder(gameDir);
        LOGGER.info("Ibuprobridging Phase 0 initialized (standalone Fabric 26.3).");
    }

    public WorldState worldState() {
        return worldState;
    }

    public InputArbiter inputArbiter() {
        return inputArbiter;
    }

    public DebugRecorder debugRecorder() {
        return debugRecorder;
    }

    /**
     * Called once at the start of every Minecraft client tick from MinecraftTickMixin.
     */
    public void onClientTick(Minecraft mc) {
        if (mc == null || mc.level == null || mc.player == null) {
            return;
        }
        worldState.tick(mc, debugRecorder);
        long tick = worldState.tickCount();

        // Layer 2 skills will submit channel requests here in Phases 1-4.
        inputArbiter.commitTick(mc, tick, debugRecorder);

        // Emit compact periodic heartbeat to the lightweight debug log
        if (debugRecorder != null && debugRecorder.isRecording()) {
            LocalPlayer p = mc.player;
            debugRecorder.maybeLogHeartbeat(
                    tick,
                    inputArbiter.isAnyActive(),
                    p.getX(), p.getY(), p.getZ(),
                    p.getYRot(), p.getXRot(), p.getHealth(),
                    worldState.matchState().getNextPhase().name(),
                    worldState.matchState().getHomeTeam().name(),
                    worldState.snapshot().enemies.size(),
                    inputArbiter.formatActiveSummary());
        }
    }

    public void onWorldChange(String reason) {
        inputArbiter.releaseAll(worldState.tickCount(), debugRecorder, "world_change");
        worldState.onWorldReset(debugRecorder, reason);
    }

    public boolean handleLocalChatCommand(String rawMessage) {
        return IbuCommand.tryHandle(this, rawMessage);
    }
}
