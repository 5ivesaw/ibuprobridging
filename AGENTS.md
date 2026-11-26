# AGENTS.md — Working Together on Ibuprofen

Welcome to **Ibuprofen**, a clean, phase-built Minecraft 26.3 (Fabric, 1.8-style PvP) BedWars bot built as the smarter successor to `Pathadol`.

This file explains how we work together so the project stays fast, clean, and understandable from commit #1.

---

## 1. How We Work Together (Important!)

* **We are building this together.** Don't silently rewrite half the project or jump 3 phases ahead while I'm away. Build one clear piece at a time, then pause so I can test it in-game and give you feedback.
* **Explain things in plain English.** I'm the project director and BedWars player—I don't read raw Java for fun. After every change, give me a short, jargon-free update covering:
  1. **What changed** (in gameplay/behavior terms, not just class names)
  2. **Why you did it that way** (any tradeoffs or gotchas I should know about)
  3. **How I should test it in-game** (exact command or scenario to try)
  4. **What you recommend next** (or any choice you want my call on)
* **Give honest feedback.** If my idea has a flaw or a simpler way exists, tell me directly—don't just nod along and pile on band-aids.

---

## 2. Current Focus

* **ACTIVE PHASE:** `Phase 1 (Island Navigation: LocalPathfinder + WalkSkill) → Phase 2 (Shop & Inventory Automator)`
* Stay focused on the active phase. Don't build Combat, Bridging, Macro Strategy, or ML yet—we'll unlock each phase together once the current one works reliably in real matches.

---

## 3. Keeping the Code Clean (Guidelines, Not Red Tape)

You have full freedom to organize code well and solve root causes properly. Just keep these sensible guardrails in mind so we don't recreate Pathadol's 40K-line spaghetti:

* **Keep files small and focused** (~400–500 lines is a good soft ceiling). If a file starts juggling multiple unrelated jobs, split it cleanly instead of growing another 4,000-line `BedwarsProcess` or 3,470-line `CombatProcess`.
* **Fix root causes, not symptoms.** Avoid stacking `if` workarounds on top of broken math or stale data. If a bug comes from another layer, flag it and fix it at the source.
* **One shared world view per tick.** All entity scans, enemy velocity tracking (position-delta EMA—never raw `RemotePlayer.getDeltaMovement()`), and arena state live in `WorldState` and update once per tick. Keep disk I/O off the game tick thread.
* **Skills compose through channels.** Behaviors shouldn't wipe all keys or cancel each other out. Each skill requests only the body channels it needs (`Movement`, `Look`, `LMB`, `RMB`, `Hotbar/GUI`) and returns a clear outcome (`RUNNING`, `DONE`, `BLOCKED`, `INTERRUPTED`, `TIMEOUT`).
* **Keep `ARCHITECTURE.md` up to date.** Whenever you add or rename a major module, update the 1-line description in `ARCHITECTURE.md` so I can always understand the whole project in 30 seconds.

---

## 4. Scavenging from Pathadol (What to Bring vs. Leave Behind)

We are **scavenging the proven pure-math & motor gold** from `Pathadol`—not starting from zero, and not dragging over its bloat.

### Bring Over (as each phase needs them)
* **Phase 0 (Core & Perception):** `BedwarsMapCore`, `BedwarsMatchState`, `BedwarsDefensePlanner`, `BedwarsBridgeConnectivity`, `BedwarsTickContext` (prune velocity maps), `BedwarsMapCacheIO`, `BedwarsQueueCommands`, `GeneratorProgress`, plus `MouseModel`, `KeyboardModel`, `ClickModel`, `PerceptionGate`, `BridgePhysicsModel`, `HumanTelemetryHooks`, and the diagnostic scripts (`compare_human_bot.py`, `live_watch.py`, `VerifyStandalone.java`).
* **Phase 1 (Shop & Inventory):** `BedwarsShopPlanner` (keep the cross-server lore parser & `ShopCatalogCache`; replace the magic-number score ladder), `BotInventoryUi` (curved virtual cursor + Top-Right PiP HUD), and the ~200-line GUI/chest quick-deposit helper from `CraftingHelper`.
* **Phase 2 (Bridging & Void Clutch):** Ninja/Safe/Diagonal/Pillar core of `BridgeController`, `PathSmoother` (made zero-alloc), `GazeDirector`, and void-fall/clutch math from `BedwarsTacticalHelper`.
* **Phase 3 (1.8 PvP):** Only the proven primitives from `CombatProcess` (`tryCombatBridgeFireball` + self-splash check, `tryAntiFireballDeflect`, `computeReachOptimalAimPoint`, `sampleTrueEntityVelocity`, legit `EntityHitResult` click/LOS helpers, `WarriorLoadout`, `tryVoidSaveEnderPearl`). Rebuild melee movement cleanly from scratch.
* **Phase 5 (Macro & Clips):** `BrainContextExtractor`, `DeterministicStrategyModel` (clean utility baseline), and optionally `FreecamController` in a separate `client/camera/` package for TikTok/Shorts footage.

### Leave Behind Permanently
* All survival/speedrun code (`SpeedrunProcess`, `SpeedrunTaskTree`, `BoatNavigationCore`, `ElytraOverworldHelper`).
* All 60-stage proving ground / Mirror Mode / Diorama camera bloat (`AutoTestRunner`, `MirrorArenaCatalog`, `ExtendedArenaCatalog`, `StageNarrativeAnalyzer`, `TesterWorldLauncher`, `PathadolTesterScreen`, `HumanStyleLearner`, `DioramaCameraController`).
* All broken/orphaned Pathadol ML plumbing (`BrainGymBridge`, `BrainTrajectoryRecorder`, `PureNnRecorder`, `PureNnSchema`, `RuleTeacherPolicy`, `colab/` NumPy sim). We'll design a clean, opt-in community dataset collector only after V1 plays great on its own.
* The 4 monolithic God-classes (`BedwarsProcess`, `BedwarsArenaMemory`, `CombatProcess`'s 17-branch waterfall, `BrainProcess`).
