# ROADMAP.md — Ibuprofen Phase-by-Phase Build Plan

We build **one phase at a time**. We don't start the next phase until the current one passes real in-game tests on live servers (Hypixel / Pika / Jartex).

---

## Phase 0 — Clean Skeleton & Foundation Scavenge (Done)
* Standalone Fabric 26.3 mod (zero Baritone bloat), 5-channel `InputArbiter`, `WorldState`, `ArenaScanner`, `TickSnapshot`, and lightweight `.jsonl.gz` `DebugRecorder`.

---

## Phase 0.5 — Foundation Hardening, EventBus & Persistent MatchMemory (Done)
**Goal:** Eliminate duplicate packet/respawn resets at the root and give the bot a unified `MatchEventBus` + persistent `MatchMemory` (bed X-ray dossiers, enemy gear dossiers, generator clocks, and Shopping Wish List).
* **Pass Gate:** Play 1 live match and run `#ibu mem`. Zero duplicate events, memory survives deaths/respawns, bot remembers enemy bed layers after leaving an island and suggests counter-items (TNT/tools) on its wish list.

---

## Phase 1 — Island Navigation (`LocalPathfinder` + `WalkSkill`)
**Goal:** A BedWars-native ~350-line A* walker inspired by Baritone's tick-cost moves (walk, diagonal, step-up, 1–3 block jump, safe drop, void-edge avoidance, stuck replanning) on solid islands and existing bridges.
* **Test command:** `#ibu test walk <x y z>` and `#ibu test walk shop`.
* **Pass Gate:** Walks cleanly around base obstacles, stairs, slabs, and across existing bridges without snagging or falling into the void.

---

## Phase 2 — Standalone Shop & Inventory Automator (`ShopSkill` + `StashSkill`)
**Goal:** Uses `WalkSkill` to reach Item Shop / Upgrades NPC (raycast-verified—never clicks through walls). Salvaged cross-server lore parser + `ShopCatalogCache` + `BotInventoryUi` curved virtual cursor & Top-Right PiP HUD. Buys clean loadout **plus** `MatchMemory` wish-list counters (e.g., TNT for Green's defense!). Stashes extra diamonds/emeralds/gold to ender/team chest and sorts hotbar.
* **Test command:** `#ibu test shop` and `#ibu test stash`.
* **Pass Gate:** 20+ clean shop trips across different servers/maps with zero wall-clicks, zero stuck menus, zero wrong purchases, and clean hotbar sorting.

---

## Phase 3 — Bridging, Gap Crossing & Void Clutch (`BridgeSkill` + `ClutchSkill`)
**Goal:** Smooth, human-looking Ninja/Safe/Diagonal/Pillar bridging using `BridgePhysicsModel` + `BedwarsBridgeConnectivity` + zero-alloc `PathSmoother`, plus reactive wall-clutches and ender-pearl void saves.
* **Test command:** `#ibu test bridge <target>` and live knockoff clutch tests.
* **Pass Gate:** Bridges cleanly across 40+ block gaps without lagbacks, clutches knockoffs reliably, and reports `DONE / BLOCKED / FALLEN`.

---

## Phase 4 — Dedicated 1.8 BedWars PvP & Utility (`CombatSkill`, `FireballSkill`, `HealSkill`)
**Goal:** A PvP controller that feels human and wins real 1.8 BedWars trades and bridge fights:
* True position-delta enemy velocity in every target, TTC, and strafe calculation.
* Continuous `W + A/D` tap-strafing, single-source W-tapping (never freezes on bridges), mid-air KB counter-strafing + safe jump-reset, bridge-tip holding.
* Fireball aimer (+ self-splash raycast), anti-fireball deflect, and composable `HealSkill` (eats gapples while strafing/retreating).
* **Test command:** `#ibu test pvp` in 1v1 duels / bridge fights.
* **Pass Gate:** Wins real 1v1 trades against humans and disengages cleanly on heavy knockback.

---

## Phase 5 — Isolated BedWars Executors
**Goal:** Replace Pathadol's 4,000-line `BedwarsProcess` with small, interruptible skills (<350 lines each):
* `GeneratorSkill` (leaves immediately after pickup if next spawn >4s away or bed is threatened)
* `DefendSkill` (fast 1–2 layer shell around home bed)
* `BreakBedSkill` (`BedwarsDefensePlanner` X-ray weakest-ray break + smart block-in)
* `RetreatSkill` (routes to reachable safe cover even if home bed is gone)
* **Test command:** `#ibu test gen`, `#ibu test defend`, `#ibu test break`, `#ibu test retreat`.

---

## Phase 6 — Full-Match Brain, Clip Polish & GitHub V1 Release
**Goal:** Wire `MatchPlanner` (using `MatchMemory` + `MatchEventBus` + live return deadlines) on top of Phases 1–5.
* Bring over `FreecamController` + `BotInventoryUi` PiP mode for clean **TikTok edits & YouTube Shorts**.
* Compress the 64 MB audio file into a tiny ~4 MB `.ogg` easter egg inside the JAR.
* Publish V1 on **GitHub Releases** once `#ibu auto` consistently wins full matches cleanly.

---

## Future V2 — Opt-In Community Dataset & Real Neural PvP
* Only after V1 is fun, popular, and winning matches: add a **strictly opt-in, anonymized** replay collector (stripping chat, player names, and server IPs, uploading via a safe presigned endpoint—never an embedded GitHub token) to train a real 20 Hz sequence PvP/micro model on real human & community gameplay.
