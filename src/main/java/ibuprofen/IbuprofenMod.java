package ibuprofen;

import ibuprofen.command.IbuCommand;
import ibuprofen.event.MatchEvent;
import ibuprofen.event.MatchEventBus;
import ibuprofen.memory.MatchMemory;
import ibuprofen.motor.InputArbiter;
import ibuprofen.skills.Skill;
import ibuprofen.skills.SkillResult;
import ibuprofen.telemetry.DebugRecorder;
import ibuprofen.ui.IbuSettings;
import ibuprofen.world.TickSnapshot;
import ibuprofen.world.WorldState;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Ibuprobridging (internal id: ibuprofen) Fabric client entrypoint.
 * Connects WorldState -> MatchEventBus -> (MatchMemory, DebugRecorder), Layer 2 Skills, and InputArbiter.
 */
public final class IbuprofenMod implements ClientModInitializer {

    public static final String MOD_ID = "ibuprofen";
    public static final Logger LOGGER = LoggerFactory.getLogger("Ibuprobridging");

    private static IbuprofenMod instance;

    private final MatchEventBus eventBus = new MatchEventBus();
    private final MatchMemory matchMemory = new MatchMemory();
    private final WorldState worldState = new WorldState();
    private final InputArbiter inputArbiter = new InputArbiter();
    private final IbuSettings settings = new IbuSettings();
    private DebugRecorder debugRecorder;
    private Skill activeSkill = null;

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

        LOGGER.info("Ibuprobridging Phase 1 initialized (EventBus + MatchMemory + Island Navigation).");
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

    public IbuSettings settings() {
        return settings;
    }

    public Skill activeSkill() {
        return activeSkill;
    }

    public String activeSkillName() {
        return activeSkill != null ? activeSkill.name() : "none";
    }

    public void startSkill(Skill skill) {
        long tick = worldState.tickCount();
        if (activeSkill != null) {
            activeSkill.cancel(tick, eventBus, "replaced");
        }
        activeSkill = skill;
        if (skill != null) {
            eventBus.publish(new MatchEvent.SkillOutcomeEvent(tick, skill.name(), "STARTED", "command"));
        }
    }

    public void stopActiveSkill(String reason) {
        long tick = worldState.tickCount();
        if (activeSkill != null) {
            String name = activeSkill.name();
            activeSkill.cancel(tick, eventBus, reason);
            eventBus.publish(new MatchEvent.SkillOutcomeEvent(tick, name, "CANCELLED", reason));
            activeSkill = null;
        }
        inputArbiter.releaseAll(tick, eventBus, reason);
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

        // Tick active Layer 2 skill (submits channel requests to InputArbiter)
        if (activeSkill != null) {
            SkillResult res = activeSkill.tick(mc, worldState, matchMemory, inputArbiter, eventBus);
            if (res != null && res.isTerminal()) {
                String finishedName = activeSkill.name();
                activeSkill.cancel(tick, eventBus, res.reason());
                activeSkill = null;
                eventBus.publish(new MatchEvent.SkillOutcomeEvent(tick, finishedName, res.outcome().name(), res.reason()));
                IbuCommand.say(finishedName + " -> " + res.outcome() + " (" + res.reason() + ")");
            }
        }

        inputArbiter.commitTick(mc, tick, eventBus);

        if (debugRecorder != null && debugRecorder.isRecording()) {
            LocalPlayer p = mc.player;
            String nearName = snap.nearestEnemy != null ? snap.nearestEnemy.name : null;
            double nearDist = snap.nearestEnemy != null ? Math.sqrt(snap.nearestEnemy.distSq) : 0.0;
            int pingMs = queryPingMs(mc, p);
            int fps = mc.getFps();
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
                    nearName, nearDist,
                    pingMs, fps);
        }
    }

    public static int queryPingMs(Minecraft mc, LocalPlayer player) {
        if (mc == null || player == null || mc.getConnection() == null) {
            return -1;
        }
        PlayerInfo info = mc.getConnection().getPlayerInfo(player.getUUID());
        return info != null ? info.getLatency() : -1;
    }

    public void onWorldChange(boolean disconnected) {
        stopActiveSkill(disconnected ? "disconnect" : "level_change");
        if (disconnected) {
            if (debugRecorder != null && debugRecorder.isRecording()) {
                debugRecorder.stopSession("disconnect");
            }
            worldState.onFullReset(eventBus, "disconnect");
        } else {
            worldState.onLevelSwap(eventBus, debugRecorder);
        }
    }

    public boolean handleLocalChatCommand(String rawMessage) {
        return IbuCommand.tryHandle(this, rawMessage);
    }
}
