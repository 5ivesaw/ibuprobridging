# ROADMAP.md — Ibuprofen Phase-by-Phase Build Plan

We build **one phase at a time**. We don't start the next phase until the current one passes real in-game tests on live servers (Hypixel / Pika / Jartex).

---

## Phase 0 — Clean Skeleton & Foundation Scavenge
**Goal:** A clean Fabric 26.3 project that boots, reads live BedWars matches accurately, and routes all bot inputs through the 5-channel arbiter—with zero gameplay automation turned on yet.

* **What to scavenge from Pathadol:**
  * `world/`: `BedwarsMapCore` (double-buffer `voidExposureGrid`), `BedwarsMatchState` (fix line 807 ternary), `BedwarsTickContext` (prune enemy velocity maps per tick), `BedwarsBridgeConnectivity`, `BedwarsDefensePlanner`, `BedwarsMapCacheIO`, `GeneratorProgress`.
  * `motor/`: `MouseModel`, `KeyboardModel`, `ClickModel`, `PerceptionGate`, `BridgePhysicsModel`, `HumanTelemetryHooks`, `BotInventoryUi`.
  * `telemetry/` & `tools/`: Lean async `DiagRecorder`, `compare_human_bot.py`, `live_watch.py`, `VerifyStandalone.java`.
* **What to build new (small & clean):**
  * `WorldState`: Runs once at the start of every tick (no other class may sweep `ctx.entities()`).
  * `InputArbiter`: 5 independent channels (`Movement`, `Look`, `LMB`, `RMB`, `Hotbar/GUI`) with clear priority ownership and logging of who owned each channel.
* **Pass Gate (how I'll test it):**
  * Launch Minecraft, join a BedWars match, run `#ibu status`.
  * Verify it detects map, team color, beds, generators, shop NPCs, and enemy velocities cleanly with zero lag spikes or crashes.

---

## Phase 1 — Standalone Shop & Inventory Automator
**Goal:** A rock-solid shop & inventory module that works on any BedWars server before we write a single line of bridging or PvP.

* **What to scavenge & clean:**
  * Lore cost parser + zero-spend `ShopCatalogCache` from `BedwarsShopPlanner` (replace the magic-number scoring soup with a clean, readable buy-priority list).
  * Curved virtual cursor + Top-Right PiP HUD from `BotInventoryUi`.
  * `advanceAndReadyGui`, `safeCloseContainer`, `stepMoveToHotbar`, and `stepContainerQuickDeposit` from `CraftingHelper` (leave all survival crafting behind).
  * `findShopInteractionStance` (never click shopkeepers through walls).
* **What to build:**
  * `ShopSkill`: Walks to the right NPC (Item Shop vs. Team Upgrades), opens it cleanly, scans unknown tabs safely without accidental buys, buys the requested items, closes cleanly, and returns `DONE / BLOCKED / TIMEOUT`.
  * `StashSkill`: Deposits extra diamonds/emeralds/gold into the ender/team chest and keeps hotbar slots tidy.
  * Test command: `#ibu test shop` (buys wool + sword/armor/tools based on current resources) and `#ibu test stash`.
* **Pass Gate (how I'll test it):**
  * 20+ clean shop trips across different servers/maps with zero wall-clicks, zero stuck menus, zero wrong purchases, and clean hotbar sorting.

---

## Phase 2 — Bridging, Movement & Void Clutch
**Goal:** Smooth, human-looking Ninja/Safe bridging, gap crossing, and reliable knockoff clutching.

* **Scavenge & clean:** Ninja/Safe/Diagonal/Pillar core of `BridgeController` (drop Moonwalk/Breezily/Telly gimmicks), `PathSmoother` (zero-alloc), `GazeDirector`, and void-fall/clutch math from `BedwarsTacticalHelper`.
* **Test command:** `#ibu test bridge <target>` and live knockoff clutch tests.
* **Pass Gate:** Bridges cleanly across 40+ block gaps without lagbacks, clutches knockoffs reliably, and reports `DONE / BLOCKED / FALLEN`.

---

## Phase 3 — Dedicated 1.8 BedWars PvP Controller
**Goal:** A PvP controller that actually feels human and wins real 1.8 BedWars trades and bridge fights.

* **Scavenge from `CombatProcess`:** `tryCombatBridgeFireball` (+ self-splash raycast check), `tryAntiFireballDeflect`, `computeReachOptimalAimPoint`, `sampleTrueEntityVelocity`, legit `EntityHitResult` click/LOS helpers, `WarriorLoadout`, `tryVoidSaveEnderPearl`.
* **Build clean (fixing Pathadol's flaws):**
  * True position-delta enemy velocity in **every** target, TTC, and strafe calculation.
  * Continuous `W + A/D` tap-strafing (no 16-ray discrete crab-walking).
  * Single-source sprint-reset W-tapping (never dead-stops on bridges).
  * Instant mid-air KB counter-strafing toward safe ground + safe jump-reset.
  * Bridge-tip & high-ground holding without 60-tick stationary freezes.
  * Composable healing (`HealSkill` eats gapples while `Movement` keeps strafing/retreating).
* **Test command:** `#ibu test pvp` in 1v1 duels / bridge fights.
* **Pass Gate:** Wins real 1v1 trades against humans and passes `compare_human_bot.py` human-likeness checks.

---

## Phase 4 — Isolated BedWars Executors
**Goal:** Replace Pathadol's 4,000-line `BedwarsProcess` with small, independent skills (<400 lines each):
* `GeneratorSkill` (leaves immediately after pickup if next spawn isn't imminent or bed is threatened)
* `DefendSkill` (fast 1–2 layer shell around home bed)
* `BreakBedSkill` (`VoxelShape`-verified breaking + smart block-in)
* `RetreatSkill` (routes to safe connected cover even if home bed is gone)

---

## Phase 5 — Full-Match Brain, Clip Polish & GitHub Release
**Goal:** Wire the salvaged `BrainContextExtractor` + `DeterministicStrategyModel` (with live return deadlines and abort conditions) on top of Phases 1–4.
* Bring over `FreecamController` + `BotInventoryUi` PiP mode for clean **TikTok edits & YouTube Shorts**.
* Publish V1 on **GitHub Releases** once it consistently wins full matches cleanly.

---

## Future V2 — Opt-In Community Dataset & Real Neural PvP
* Only after V1 is fun, popular, and winning matches: add a **strictly opt-in, anonymized** replay collector (stripping chat, player names, and server IPs, uploading via a safe presigned endpoint—never an embedded GitHub token) to train a real 20 Hz sequence PvP/micro model on real human & community gameplay.
