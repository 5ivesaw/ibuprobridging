# ARCHITECTURE.md — How Ibuprofen Works (Plain-English Map)

**Ibuprofen** is a 3-layer BedWars bot designed so small, reliable skills can **work together at the same time** (eat while strafing, swing while backing up, leave a generator the second a rusher threatens your bed) without fighting over the keyboard.

---

## The 3-Layer Design

```text
┌──────────────────────────────────────────────────────────┐
│  LAYER 3: PLANNER (The Brain — Phase 5)                  │
│  Decides WHAT goal matters right now (Rush, Defend,      │
│  Shop, Collect Diamond, Retreat) using live deadlines    │
│  and aborts immediately when the match changes.          │
└──────────────────────────┬───────────────────────────────┘
                           │ starts / stops goals
                           ▼
┌──────────────────────────────────────────────────────────┐
│  LAYER 2: ATOMIC SKILLS (The Muscles — Phases 1–4)       │
│  Small, single-job skills (<500 lines each) that report  │
│  DONE / BLOCKED / INTERRUPTED / TIMEOUT:                 │
│   • ShopSkill & StashSkill    • BridgeSkill & ClutchSkill│
│   • CombatSkill (1.8 PvP)     • DefendSkill & BreakBed   │
│   • HealSkill & FireballSkill                            │
└──────────────────────────┬───────────────────────────────┘
                           │ requests body channels
                           ▼
┌──────────────────────────────────────────────────────────┐
│  LAYER 1: WORLDSTATE + 5 BODY CHANNELS (Phase 0)         │
│  • WorldState Blackboard: updated ONCE per tick (map,    │
│    match timers, true enemy velocities, routes, inv).    │
│  • Input Arbiter: merges requests across 5 independent   │
│    channels so skills compose instead of wiping keys:    │
│    [Movement]  [Look]  [LMB]  [RMB]  [Hotbar / GUI]      │
└──────────────────────────────────────────────────────────┘
```

### Why This Beats Pathadol
* **No more keyboard fighting:** In Pathadol, switching to combat cancelled BedWars and wiped all keys. In Ibuprofen, *Heal* can hold `Hotbar + RMB` while *Movement* keeps strafing, and *Combat* can hold `Look + LMB` while *Retreat* steers `Movement` to safe ground.
* **No more silent stalls:** Every skill reports back why it finished or failed (`DONE`, `BLOCKED`, `TIMEOUT`), so the planner switches plans instead of staring at a generator for 700 ticks.
* **No more mystery bugs:** Every skill can be tested alone (`#ibu test shop`, `#ibu test bridge`, `#ibu test pvp`) and logged with our diagnostic tools.

---

## Clean Package Layout

```text
src/main/java/ibuprofen/
├── world/          # Layer 1: WorldState blackboard, MapCore, MatchState, TickContext,
│                   #          BridgeConnectivity, DefensePlanner, MapCacheIO (async)
├── motor/          # Layer 1: InputArbiter (5 channels), MouseModel, KeyboardModel,
│                   #          ClickModel, PerceptionGate, BridgePhysicsModel, BotInventoryUi
├── skills/         # Layer 2: Small, testable, single-purpose executors
│   ├── shop/       #   Phase 1: ShopSkill, StashSkill, ShopParser, ShopCatalogCache
│   ├── move/       #   Phase 2: BridgeSkill, ClutchSkill, PathSmoother, GazeDirector
│   ├── combat/     #   Phase 3: CombatSkill (1.8 PvP), FireballSkill, PearlSkill
│   └── bedwars/    #   Phase 4: GeneratorSkill, DefendSkill, BreakBedSkill, RetreatSkill
├── planner/        # Layer 3 (Phase 5): UtilityPlanner, GoalDeadlines, MatchCoordinator
├── telemetry/      # Async DiagRecorder, HumanTelemetryHooks, anti-cheat parity checks
├── camera/         # Optional QoL: FreecamController (for clean TikTok/Shorts clips)
└── command/        # Simple in-game commands (#ibu test shop, #ibu status, #ibu stop)

tools/              # Offline Python diagnostics: compare_human_bot.py, live_watch.py
```

---

## Module Status Tracker (Keep Updated Each Phase)
*(Update this table as files are added so every piece of the mod stays visible at a glance.)*

| Phase | Module | Status | Plain-English Role |
| :---: | :--- | :---: | :--- |
| 0 | `world/` + `motor/` + `telemetry/` + `command/` + `mixin/` | Built (Phase 0) | Shared map/match awareness, 5-channel body arbiter, human aim/keys, lightweight `.jsonl.gz` debug recorder, `#ibu` commands |
| 1 | `skills/shop/` | Pending | Opens shop NPCs cleanly, reads server prices, buys loadouts, stashes valuables |
| 2 | `skills/move/` | Pending | Smooth Ninja/Safe bridging, gap crossing, and reactive void clutching |
| 3 | `skills/combat/` | Pending | 1.8 BedWars melee, W-tap/strafe, KB recovery, fireball aim & deflection |
| 4 | `skills/bedwars/` | Pending | Timed generator runs, bed defense shells, bed breaking, safe retreats |
| 5 | `planner/` + `camera/` | Pending | Full-match decision brain + Freecam/PiP polish for clips & GitHub release |
