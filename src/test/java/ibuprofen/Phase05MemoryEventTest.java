package ibuprofen;

import ibuprofen.event.MatchEvent;
import ibuprofen.event.MatchEventBus;
import ibuprofen.memory.EnemyDossier;
import ibuprofen.memory.MatchMemory;
import ibuprofen.memory.ShoppingWishList;
import ibuprofen.memory.TeamDossier;
import ibuprofen.telemetry.DebugRecorder;
import ibuprofen.world.BedwarsDefensePlanner;
import ibuprofen.world.BedwarsDefensePlanner.DefenseBlockKind;
import ibuprofen.world.BedwarsDefensePlanner.ToolProfile;
import ibuprofen.world.BedwarsMapCore.TeamColor;
import ibuprofen.world.BedwarsMatchState;
import ibuprofen.world.WorldState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    @Test
    void shoppingWishListSummarizesMultipleArmoredEnemiesConcisely() {
        Map<String, EnemyDossier> enemies = new LinkedHashMap<>();
        for (int i = 1; i <= 5; i++) {
            EnemyDossier ed = new EnemyDossier("Player" + i);
            ed.armorTier = 2;
            ed.swordTier = 2;
            ed.seenFireball = true;
            enemies.put(ed.name, ed);
        }

        List<ShoppingWishList.WishItem> wish = ShoppingWishList.compute(TeamColor.RED, Map.of(), enemies);
        ShoppingWishList.WishItem armor = wish.stream()
                .filter(w -> w.itemId().equals("IRON_ARMOR")).findFirst().orElseThrow();
        assertEquals("5 enemies have iron/diamond armor (e.g. Player1)", armor.reason());
        assertTrue(armor.reason().length() < 80);
    }

    @Test
    void matchMemoryIgnoresDuplicateBedScansAndResetsOnNewMatch() {
        MatchEventBus bus = new MatchEventBus();
        MatchMemory memory = new MatchMemory();
        bus.subscribe(memory::onEvent);

        bus.publish(new MatchEvent.BedDefenseObservedEvent(
                100L, TeamColor.AQUA, 2, DefenseBlockKind.WOOD,
                8, 6, 0, 0, 0, 32.0));
        assertEquals(100L, memory.getTeamDossier(TeamColor.AQUA).lastDefenseScanTick);

        // Unchanged duplicate scan at tick 120 must not overwrite scan timestamp
        bus.publish(new MatchEvent.BedDefenseObservedEvent(
                120L, TeamColor.AQUA, 2, DefenseBlockKind.WOOD,
                8, 6, 0, 0, 0, 32.0));
        assertEquals(100L, memory.getTeamDossier(TeamColor.AQUA).lastDefenseScanTick);

        // World reset from new match chat clears old dossiers
        bus.publish(new MatchEvent.MatchLifecycleEvent(200L, "world_reset", "new_match_chat"));
        assertNull(memory.getTeamDossier(TeamColor.AQUA));
    }

    @Test
    void worldStateOnLevelSwapPreservesAliveMatchAndResetsWhenEliminated() {
        MatchEventBus bus = new MatchEventBus();
        MatchMemory memory = new MatchMemory();
        bus.subscribe(memory::onEvent);

        WorldState ws = new WorldState();
        ws.matchState().onChatMessage("You are now in team Red.", 1000L);
        bus.publish(new MatchEvent.TeamAssignedEvent(10L, TeamColor.RED));
        bus.publish(new MatchEvent.BedDefenseObservedEvent(
                20L, TeamColor.BLUE, 1, DefenseBlockKind.WOOL,
                5, 0, 0, 0, 0, 12.0));

        // Alive mid-match respawn preserves memory
        ws.onLevelSwap(bus, null);
        assertNotNull(memory.getTeamDossier(TeamColor.BLUE));
        assertTrue(ws.matchState().isMatchStarted());

        // Eliminated home team triggers full reset on level swap
        ws.matchState().onChatMessage("Team Red has been eliminated from the game!", 2000L);
        ws.onLevelSwap(bus, null);
        assertFalse(ws.matchState().isMatchStarted());
        assertNull(memory.getTeamDossier(TeamColor.BLUE));
    }

    @Test
    void classifyBlockPathNeverMiscountsMapTerrainAsWoolAndDetectsEndstoneDefenseAccurately() {
        assertEquals(DefenseBlockKind.ENDSTONE, MatchMemory.classifyBlockPath("end_stone"));
        assertEquals(DefenseBlockKind.WOOL, MatchMemory.classifyBlockPath("blue_wool"));
        assertEquals(DefenseBlockKind.WOOD, MatchMemory.classifyBlockPath("oak_planks"));
        assertEquals(DefenseBlockKind.GLASS, MatchMemory.classifyBlockPath("blue_stained_glass"));
        assertEquals(DefenseBlockKind.OBSIDIAN, MatchMemory.classifyBlockPath("obsidian"));
        assertEquals(DefenseBlockKind.TERRACOTTA, MatchMemory.classifyBlockPath("red_terracotta"));

        // Map terrain & architecture must NEVER default to WOOL or WOOD
        assertEquals(DefenseBlockKind.ORIGINAL_MAP, MatchMemory.classifyBlockPath("grass_block"));
        assertEquals(DefenseBlockKind.ORIGINAL_MAP, MatchMemory.classifyBlockPath("coarse_dirt"));
        assertEquals(DefenseBlockKind.ORIGINAL_MAP, MatchMemory.classifyBlockPath("sandstone"));
        assertEquals(DefenseBlockKind.ORIGINAL_MAP, MatchMemory.classifyBlockPath("oak_log"));
        assertEquals(DefenseBlockKind.ORIGINAL_MAP, MatchMemory.classifyBlockPath("oak_stairs"));
        assertEquals(DefenseBlockKind.ORIGINAL_MAP, MatchMemory.classifyBlockPath("oak_slab"));
        assertEquals(DefenseBlockKind.ORIGINAL_MAP, MatchMemory.classifyBlockPath("glass_pane"));

        // 2-layer Endstone defense with 1 blue wool + surrounding grass/sandstone must report ENDSTONE & 2 layers
        var scan = BedwarsDefensePlanner.scanBedDefenseShell(
                0, 65, 0, 1, 65, 0, 4, ToolProfile.bareHand(),
                (x, y, z) -> {
                    int distHead = Math.abs(x) + Math.abs(y - 65) + Math.abs(z);
                    int distFoot = Math.abs(x - 1) + Math.abs(y - 65) + Math.abs(z);
                    int d = Math.min(distHead, distFoot);
                    if (d == 0) return DefenseBlockKind.AIR;
                    if (x == 0 && y == 66 && z == 1) return MatchMemory.classifyBlockPath("blue_wool");
                    if (d <= 2 && y >= 65) return MatchMemory.classifyBlockPath("end_stone");
                    if (x >= 3 && y >= 65) return MatchMemory.classifyBlockPath("oak_log"); // nearby house wall
                    return DefenseBlockKind.AIR;
                });
        assertEquals(DefenseBlockKind.ENDSTONE, scan.hardestBlock());
        assertEquals(2, scan.layersDetected());
        assertEquals(1, scan.counts().getOrDefault(DefenseBlockKind.WOOL, 0));
        assertTrue(scan.counts().getOrDefault(DefenseBlockKind.ENDSTONE, 0) > 10);
    }
}
