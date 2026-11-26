package ibuprofen;

import ibuprofen.command.IbuCommand;
import ibuprofen.event.MatchEventBus;
import ibuprofen.memory.MatchMemory;
import ibuprofen.motor.InputArbiter;
import ibuprofen.telemetry.DebugRecorder;
import ibuprofen.world.TickSnapshot;
import ibuprofen.world.WorldState;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Ibuprobridging (internal id: ibuprofen) Fabric client entrypoint.
 * Connects WorldState -> MatchEventBus -> (MatchMemory, DebugRecorder) and 5-channel InputArbiter.
 */
public final class IbuprofenMod implements ClientModInitializer {

    public static final String MOD_ID = "ibuprofen";
    public static final Logger LOGGER = LoggerFactory.getLogger("Ibuprobridging");

    private static IbuprofenMod instance;

    private final MatchEventBus eventBus = new MatchEventBus();
    private final MatchMemory matchMemory = new MatchMemory();
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

        eventBus.subscribe(matchMemory::onEvent);
        eventBus.subscribe(debugRecorder::onEvent);

        LOGGER.info("Ibuprobridging Phase 0.5 initialized (EventBus + MatchMemory + standalone Fabric 26.3).");
    }

    public MatchEventBus eventBus() {
        return eventBus;
    }

    public MatchMemory matchMemory() {
        return matchMemory;
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
        worldState.tick(mc, eventBus, debugRecorder, matchMemory);
        long tick = worldState.tickCount();
        TickSnapshot snap = worldState.snapshot();

        matchMemory.tickUpdate(mc.level, worldState.mapCore(), worldState.matchState(), snap, tick, eventBus);

        // Layer 2 skills will submit channel requests here starting in Phase 1.
        inputArbiter.commitTick(mc, tick, eventBus);

        if (debugRecorder != null && debugRecorder.isRecording()) {
            LocalPlayer p = mc.player;
            String nearName = snap.nearestEnemy != null ? snap.nearestEnemy.name : null;
            double nearDist = snap.nearestEnemy != null ? Math.sqrt(snap.nearestEnemy.distSq) : 0.0;
            debugRecorder.maybeLogHeartbeat(
                    tick,
                    inputArbiter.isAnyActive(),
                    p.getX(), p.getY(), p.getZ(),
                    p.getYRot(), p.getXRot(), p.getHealth(),
                    worldState.matchState().getNextPhase().name(),
                    worldState.matchState().getHomeTeam().name(),
                    snap.enemies.size(),
                    inputArbiter.formatActiveSummary(),
                    snap.ironCount, snap.goldCount, snap.diamondCount, snap.emeraldCount, snap.blockCount,
                    nearName, nearDist);
        }
    }

    public void onWorldChange(boolean disconnected) {
        long tick = worldState.tickCount();
        inputArbiter.releaseAll(tick, eventBus, disconnected ? "disconnect" : "level_change");
        if (disconnected) {
            worldState.onFullReset(eventBus, "disconnect");
        } else {
            worldState.onLevelSwap(eventBus);
        }
    }

    public boolean handleLocalChatCommand(String rawMessage) {
        return IbuCommand.tryHandle(this, rawMessage);
    }
}
