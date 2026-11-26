# ARCHITECTURE.md — How Ibuprofen Works (Plain-English Map)

**Ibuprofen** is a 3-layer BedWars bot designed so small, reliable skills can **work together at the same time** (eat while strafing, swing while backing up, leave a generator the second a rusher threatens your bed) without fighting over the keyboard.

---

## The Event-Driven 3-Layer Design

```text
┌──────────────────────────────────────────────────────────┐
│  Minecraft 26.3 Client (3 Tiny Mixins)                   │
└──────────────────────────┬───────────────────────────────┘
                           │ 1x/tick, main thread only
                           ▼
┌──────────────────────────────────────────────────────────┐
│  LAYER 1A: WorldState + ArenaScanner + TickSnapshot      │
│  Single-pass entity/velocity/gear sweep + chunk scanner. │
└──────────────────────────┬───────────────────────────────┘
                           │ publishes typed MatchEvents
                           ▼
             ┌─────────────────────────────┐
             │    Central MatchEventBus    │
             └──┬───────────┬───────────┬──┘
                │           │           │
                ▼           ▼           ▼
          MatchMemory  DebugRecorder  Layer 3: MatchPlanner
          (Bed X-ray,  (.jsonl.gz     (Deadlines + instant
           Enemy gear,  async log)     event interrupts)
           Gen clocks,                  │
           Wish List)                   │ starts / stops goals
                └───────────┬───────────┘
                            ▼
┌──────────────────────────────────────────────────────────┐
│  LAYER 2: ATOMIC SKILLS (<450 lines each)                │
│  Report DONE / BLOCKED / INTERRUPTED / TIMEOUT:          │
│   • WalkSkill (Phase 1)       • ShopSkill & Stash (Ph 2) │
│   • BridgeSkill & Clutch (3)  • Combat, Fireball, Heal(4)│
│   • Generator, Defend, BreakBed, Retreat (Phase 5)       │
└──────────────────────────┬───────────────────────────────┘
                           │ requests channels by priority
                           ▼
┌──────────────────────────────────────────────────────────┐
│  LAYER 1B: 5-Channel InputArbiter                        │
│  [Movement]  [Look]  [LMB]  [RMB]  [Hotbar / GUI]        │
└──────────────────────────────────────────────────────────┘
```

### Why This Beats Pathadol
* **One Event Bus, zero duplicate hacks:** `DebugRecorder` logs the exact typed events (`MatchEvent`) that `MatchMemory` and `MatchPlanner` act on—no separate keyword filters.
* **Persistent Match Memory:** Remembers enemy bed defenses (X-ray scanned), enemy gear, and generator clocks even across chunk unloads and respawns—feeding a live Shopping Wish List (e.g., *"saw Green has wood+endstone → buy TNT & Axe at next shop"*).
* **No more keyboard fighting:** *Heal* can hold `Hotbar + RMB` while *Movement* keeps strafing, and *Combat* can hold `Look + LMB` while *Retreat* steers `Movement` to safe ground.

---

## Clean Package Layout

```text
src/main/java/ibuprofen/
├── event/          # Central MatchEventBus + typed MatchEvent sealed hierarchy
├── memory/         # Persistent MatchMemory, TeamDossier, EnemyDossier, ShoppingWishList
├── world/          # Layer 1A: WorldState, ArenaScanner, TickSnapshot, MapCore, MatchState,
│                   #           BridgeConnectivity, DefensePlanner, MapCacheIO
├── motor/          # Layer 1B: InputArbiter (5 channels), MouseModel, KeyboardModel,
│                   #           ClickModel, BridgePhysicsModel, HumanProfile, Rotation
├── skills/         # Layer 2: Small, testable, single-purpose executors
│   ├── nav/        #   Phase 1: LocalPathfinder, WalkSkill
│   ├── shop/       #   Phase 2: ShopSkill, StashSkill, ShopParser, ShopCatalogCache, BotInventoryUi
│   ├── move/       #   Phase 3: BridgeSkill, ClutchSkill, PathSmoother, GazeDirector
│   ├── combat/     #   Phase 4: CombatSkill (1.8 PvP), FireballSkill, PearlSkill, HealSkill
│   └── bedwars/    #   Phase 5: GeneratorSkill, DefendSkill, BreakBedSkill, RetreatSkill
├── planner/        # Layer 3 (Phase 6): MatchPlanner, GoalDeadlines
├── telemetry/      # Event-driven async DebugRecorder (.jsonl.gz), HumanTelemetryHooks
├── camera/         # Optional QoL (Phase 6): FreecamController (for TikTok/Shorts clips)
└── command/        # Local client commands (#ibu status, #ibu mem, #ibu rec, #ibu stop)

tools/              # Offline Python diagnostics: ibu_inspect.py
```

---

## Module Status Tracker (Keep Updated Each Phase)

| Phase | Module | Status | Plain-English Role |
| :---: | :--- | :---: | :--- |
| 0 | `world/` + `motor/` + `telemetry/` + `command/` + `mixin/` | Done | Shared map/match awareness, 5-channel arbiter, human aim/keys, `.jsonl.gz` recorder, `#ibu` |
| 0.5 | `event/` + `memory/` + Layer 1 hardening | Done | `MatchEventBus`, persistent `MatchMemory` (X-ray bed dossiers, enemy gear, wish list), respawn persistence, `#ibu mem` |
| 1 | `skills/nav/` | Next | BedWars-native A* island walker (`WalkSkill`) around stairs/slabs/void edges |
| 2 | `skills/shop/` | Pending | Opens shop NPCs cleanly, reads server prices, buys loadouts + wish-list counters, stashes valuables |
| 3 | `skills/move/` | Pending | Smooth Ninja/Safe bridging, gap crossing, and reactive void clutching |
| 4 | `skills/combat/` | Pending | 1.8 BedWars melee, W-tap/strafe, KB recovery, fireball aim & deflection, composable heal |
| 5 | `skills/bedwars/` | Pending | Timed generator runs, bed defense shells, X-ray bed breaking, safe retreats |
| 6 | `planner/` + `camera/` | Pending | Full-match deadline brain + Freecam/PiP polish + audio easter egg & GitHub V1 release |
