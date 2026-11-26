package ibuprofen;

import ibuprofen.motor.BodyChannel;
import ibuprofen.motor.BodyInput;
import ibuprofen.motor.ChannelRequest;
import ibuprofen.motor.InputArbiter;
import ibuprofen.telemetry.DebugRecorder;
import ibuprofen.world.BedwarsMapCore;
import ibuprofen.world.BedwarsMatchState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

final class Phase0FoundationTest {

    @Test
    void inputArbiterComposesIndependentChannelsWithoutWipingKeys() {
        InputArbiter arbiter = new InputArbiter();

        // Retreat owns MOVEMENT while Heal owns RMB + HOTBAR simultaneously
        arbiter.request(ChannelRequest.movement(
                "RetreatSkill", ChannelRequest.PRIORITY_HIGH, "back_to_base",
                Map.of(BodyInput.MOVE_FORWARD, true, BodyInput.SPRINT, true)));
        arbiter.request(ChannelRequest.rmb(
                "HealSkill", ChannelRequest.PRIORITY_SURVIVAL, "eat_gapple", true));
        arbiter.request(ChannelRequest.hotbar(
                "HealSkill", ChannelRequest.PRIORITY_SURVIVAL, "select_gapple", 4));

        arbiter.commitTick(null, 1L, null);

        assertEquals("RetreatSkill", arbiter.getOwner(BodyChannel.MOVEMENT));
        assertEquals("HealSkill", arbiter.getOwner(BodyChannel.RMB));
        assertEquals("HealSkill", arbiter.getOwner(BodyChannel.HOTBAR_GUI));
        assertNull(arbiter.getOwner(BodyChannel.LMB));

        assertEquals(Boolean.TRUE, arbiter.getKeyOverride(BodyInput.MOVE_FORWARD));
        assertEquals(Boolean.TRUE, arbiter.getKeyOverride(BodyInput.SPRINT));
        assertEquals(Boolean.TRUE, arbiter.getKeyOverride(BodyInput.CLICK_RIGHT));
        // Unowned LMB channel passes through to human player (null override)
        assertNull(arbiter.getKeyOverride(BodyInput.CLICK_LEFT));

        arbiter.releaseAll(2L, null, "test_stop");
        assertFalse(arbiter.isAnyActive());
        assertNull(arbiter.getKeyOverride(BodyInput.MOVE_FORWARD));
    }

    @Test
    void inputArbiterPriorityHigherWinsAndTieKeepsPreviousOwner() {
        InputArbiter arbiter = new InputArbiter();

        arbiter.request(ChannelRequest.lmb("SkillA", ChannelRequest.PRIORITY_NORMAL, "swing", true));
        arbiter.commitTick(null, 1L, null);
        assertEquals("SkillA", arbiter.getOwner(BodyChannel.LMB));

        // Equal priority tie keeps SkillA to prevent chatter
        arbiter.request(ChannelRequest.lmb("SkillB", ChannelRequest.PRIORITY_NORMAL, "swing", true));
        arbiter.request(ChannelRequest.lmb("SkillA", ChannelRequest.PRIORITY_NORMAL, "swing", true));
        arbiter.commitTick(null, 2L, null);
        assertEquals("SkillA", arbiter.getOwner(BodyChannel.LMB));

        // Higher priority takes over cleanly
        arbiter.request(ChannelRequest.lmb("SkillA", ChannelRequest.PRIORITY_NORMAL, "swing", true));
        arbiter.request(ChannelRequest.lmb("CombatSkill", ChannelRequest.PRIORITY_HIGH, "attack", true));
        arbiter.commitTick(null, 3L, null);
        assertEquals("CombatSkill", arbiter.getOwner(BodyChannel.LMB));
    }

    @Test
    void debugRecorderWritesCompactGzippedJsonl(@TempDir Path tempDir) throws Exception {
        DebugRecorder recorder = new DebugRecorder(tempDir);
        Path logFile = recorder.startSession("unit_test");
        assertNotNull(logFile);
        assertTrue(recorder.isRecording());

        recorder.logChannelChange(10L, "MOVEMENT", null, "BridgeSkill", "cross_gap");
        recorder.logSkillOutcome(25L, "BridgeSkill", "DONE", "arrived");
        recorder.maybeLogHeartbeat(30L, true, 10.5, 64.0, -5.2, 90.0f, 12.0f, 20.0f, "DIAMOND_II", "RED", 1, "MOVEMENT=BridgeSkill");
        recorder.stopSession("test_complete");

        assertFalse(recorder.isRecording());
        assertTrue(Files.exists(logFile));
        assertTrue(Files.size(logFile) > 0 && Files.size(logFile) < 4096);

        StringBuilder content = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new GZIPInputStream(Files.newInputStream(logFile)), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                content.append(line).append('\n');
            }
        }
        String text = content.toString();
        assertTrue(text.contains("session_start"));
        assertTrue(text.contains("BridgeSkill"));
        assertTrue(text.contains("session_end"));
    }

    @Test
    void matchStateParsesPhaseThreeAndScoreboardCleanly() {
        BedwarsMatchState ms = new BedwarsMatchState();
        ms.updateScoreboard("BED WARS", List.of(
                "Diamond III in 1:30",
                "R Red ✓ YOU",
                "B Blue ✓",
                "G Green ✗"
        ));
        assertEquals(BedwarsMatchState.MatchPhase.DIAMOND_III, ms.getNextPhase());
        assertEquals(BedwarsMapCore.TeamColor.RED, ms.getHomeTeam());
        assertTrue(ms.isMatchStarted());
        assertTrue(ms.isTeamBedDestroyed(BedwarsMapCore.TeamColor.GREEN));
        assertFalse(ms.isTeamBedDestroyed(BedwarsMapCore.TeamColor.BLUE));
    }
}
