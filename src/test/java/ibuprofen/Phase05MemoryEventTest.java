package ibuprofen;

import ibuprofen.event.MatchEvent;
import ibuprofen.event.MatchEventBus;
import ibuprofen.memory.MatchMemory;
import ibuprofen.memory.ShoppingWishList;
import ibuprofen.memory.TeamDossier;
import ibuprofen.telemetry.DebugRecorder;
import ibuprofen.world.BedwarsDefensePlanner.DefenseBlockKind;
import ibuprofen.world.BedwarsMapCore.TeamColor;
import ibuprofen.world.BedwarsMatchState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class Phase05MemoryEventTest {

    @Test
    void greenBaseTntRememberedAfterLeavingAndClearedWhenBedBreaks() {
        MatchEventBus bus = new MatchEventBus();
        MatchMemory memory = new MatchMemory();
        bus.subscribe(memory::onEvent);

        bus.publish(new MatchEvent.TeamAssignedEvent(100L, TeamColor.GRAY));
        bus.publish(new MatchEvent.BedDefenseObservedEvent(
                200L, TeamColor.GREEN, 2, DefenseBlockKind.ENDSTONE,
                8, 6, 8, 0, 0, 42.0));

        // Even long after walking away, Green's defense stays in MatchMemory and drives ShoppingWishList
        TeamDossier green = memory.getTeamDossier(TeamColor.GREEN);
        assertNotNull(green);
        assertTrue(green.bedAlive);
        assertTrue(green.hasWood());
        assertTrue(green.hasEndstone());

        List<ShoppingWishList.WishItem> wish = memory.computeWishList();
        assertTrue(wish.stream().anyMatch(w -> w.itemId().equals("TNT") && w.priority() >= 85 && w.reason().contains("GREEN")));
        assertTrue(wish.stream().anyMatch(w -> w.itemId().equals("AXE") && w.reason().contains("GREEN")));
        assertTrue(wish.stream().anyMatch(w -> w.itemId().equals("PICKAXE") && w.reason().contains("GREEN")));

        // Once Green's bed breaks, counter-items for Green automatically drop off the wish list
        bus.publish(new MatchEvent.BedBrokenEvent(500L, TeamColor.GREEN, "Green Team's Bed has been destroyed!"));
        assertFalse(memory.getTeamDossier(TeamColor.GREEN).bedAlive);
        List<ShoppingWishList.WishItem> afterBreak = memory.computeWishList();
        assertTrue(afterBreak.stream().noneMatch(w -> w.itemId().equals("TNT")));
    }

    @Test
    void blastproofGlassAndObsidianAdjustWishListPriorities() {
        MatchEventBus bus = new MatchEventBus();
        MatchMemory memory = new MatchMemory();
        bus.subscribe(memory::onEvent);

        bus.publish(new MatchEvent.TeamAssignedEvent(10L, TeamColor.GRAY));
        // Red has wood + blastproof glass -> lower TNT priority, prioritize Axe
        bus.publish(new MatchEvent.BedDefenseObservedEvent(
                20L, TeamColor.RED, 2, DefenseBlockKind.WOOD,
                0, 6, 0, 8, 0, 28.0));
        // Blue has obsidian -> Diamond Pickaxe top priority (95)
        bus.publish(new MatchEvent.BedDefenseObservedEvent(
                30L, TeamColor.BLUE, 1, DefenseBlockKind.OBSIDIAN,
                0, 0, 0, 0, 6, 120.0));

        List<ShoppingWishList.WishItem> wish = memory.computeWishList();
        assertFalse(wish.isEmpty());
        assertEquals("DIAMOND_PICKAXE", wish.get(0).itemId());
        assertEquals(95, wish.get(0).priority());

        ShoppingWishList.WishItem tnt = wish.stream().filter(w -> w.itemId().equals("TNT")).findFirst().orElseThrow();
        ShoppingWishList.WishItem axe = wish.stream().filter(w -> w.itemId().equals("AXE")).findFirst().orElseThrow();
        assertTrue(axe.priority() > tnt.priority(), "Blastproof glass should make AXE outrank TNT");
    }

    @Test
    void pikaAndJartexBedDestroyedMessagesParseTeamCorrectly() {
        BedwarsMatchState ms = new BedwarsMatchState();
        var outcome = ms.onChatMessage("Gray Team's Bed has been destroyed by PAPAYA_05", 1000L);
        assertEquals(TeamColor.GRAY, outcome.destroyedBedTeam());

        var outcome2 = ms.onChatMessage("Red Bed was destroyed by someone!", 1100L);
        assertEquals(TeamColor.RED, outcome2.destroyedBedTeam());
    }

    @Test
    void eventBusMaintainsRingBufferAndFeedsRecorder(@TempDir Path tempDir) {
        MatchEventBus bus = new MatchEventBus();
        DebugRecorder recorder = new DebugRecorder(tempDir);
        bus.subscribe(recorder::onEvent);

        Path log = recorder.startSession("bus_test");
        assertNotNull(log);

        bus.publish(new MatchEvent.BedBrokenEvent(50L, TeamColor.RED, "Red Bed destroyed"));
        bus.publish(new MatchEvent.PlayerKillEvent(55L, "EnemyA", "callback", true, true, "EnemyA killed by callback FINAL KILL"));
        recorder.stopSession("done");

        List<MatchEvent> recent = bus.recentEvents(10);
        assertEquals(2, recent.size());
        assertEquals(1, bus.recentOfType(MatchEvent.BedBrokenEvent.class, 10).size());
    }

    @Test
    void matchMemorySurvivesRespawnAndClearsOnlyOnWorldReset() {
        MatchEventBus bus = new MatchEventBus();
        MatchMemory memory = new MatchMemory();
        bus.subscribe(memory::onEvent);

        bus.publish(new MatchEvent.TeamAssignedEvent(10L, TeamColor.GRAY));
        bus.publish(new MatchEvent.BedDefenseObservedEvent(
                20L, TeamColor.GREEN, 2, DefenseBlockKind.ENDSTONE,
                6, 0, 6, 0, 0, 30.0));
        bus.publish(new MatchEvent.EnemySpottedEvent(25L, "kailash_321", 10, 64, 10, 2, 2, "FB"));

        // Mid-match respawn must preserve all remembered dossiers
        bus.publish(new MatchEvent.MatchLifecycleEvent(100L, "respawn", "mid_match_respawn"));
        assertNotNull(memory.getTeamDossier(TeamColor.GREEN));
        assertFalse(memory.computeWishList().isEmpty());

        // Real world reset clears memory for the next match
        bus.publish(new MatchEvent.MatchLifecycleEvent(200L, "world_reset", "disconnect"));
        assertNull(memory.getTeamDossier(TeamColor.GREEN));
        assertTrue(memory.computeWishList().isEmpty());
    }
}
