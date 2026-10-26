# Tick-counted vs time-scaled server world logic (B42 dedicated server, 10 Hz -> 20 Hz)

Source: `javap -c -p` of the game jar (scratchpad `javap/<pkg>.txt`), vanilla Lua under
`ProjectZomboid/media/lua`. `Class.method @off` = bytecode offset in that method.

## Clock basics (how "time-scaled" works on the server)

- Tick gate: `GameServer.main` builds `UpdateLimit(100)` @2695, `setLockFPS(10)` @2709, `fps = getLockFPS()` @2721;
  world tick runs when `UpdateLimit.Check()` @3493 (fires when `now-last > delay`, then `last += delay`; resets if
  behind by `> 3*delay`, `UpdateLimit.Check @0-58`). The outer loop runs every ~5 ms for packets regardless (@3501-3533).
- fps estimate @4498-4557: `fps += min((1000/dt_ms - fps)*0.05, 1.0)`; `GameTime.fpsMultiplier = 60/fps`.
  At a steady 20 Hz: fps -> 20, `fpsMultiplier` 6 -> 3. If `lockFPS` stays 10, fps starts at 10 and converges with
  factor 0.95/tick (~16.4 after 1 s, ~19.5 after 3 s), so game time runs up to 2x fast for the first ~3 s.
- `GameTime.getMultiplier @36-144` (server path) = `multiplier * fpsMultiplier * multiplierBias * perObjectMultiplier
  * slomo * 0.8` (fast-forward factor when sleeping). No clamp. 4.8*bias per tick at 10 Hz, 2.4*bias at 20 Hz.
  `getTimeDelta` = `getMultiplier/0.8/bias/60` = real seconds of the tick (0.1 -> 0.05).
  `getThirtyFPSMultiplier` = `getMultiplier/1.6`; `getRealworldSecondsSinceLastUpdate` = `fpsMultiplier/60`;
  `getMultipliedSecondsSinceLastUpdate` = `getUnmoddedMultiplier/60`. All of these scale with real time.
- `getTrueMultiplier` = `multiplier * perObjectMultiplier` (no fpsMultiplier) -> tick-counted. Server callers:
  `Helicopter.update @55` (value unused for movement), `ThumpState`/`IsoZombie` (not run on server).
- `GameTime.getServerMultiplier` (hardcodes `10/averageFPS`): no Java callers, no vanilla Lua callers (grep: 0 hits).
- `GameWindow.averageFPS` is set once in `GameWindow.<clinit> @47-51` from `getLockFPS()` and only updated by
  `FPSTracking.frameStep` (client loop). Frozen on the server.
- **`Rand.AdjustForFramerate(n)` hardcodes 10 Hz on the server**: `RandInterface.AdjustForFramerate @0-13` returns
  `(int)(n * 0.33333334f)` when `GameServer.server`, else `n * lockFPS/30`. Not affected by `setLockFPS`.
  Every per-tick roll `Rand.Next(AdjustForFramerate(N)) == 0` fires 2x as often in real time at 20 Hz.
- Other `getLockFPS()` readers that run on the server: fps EMA start, `IsoGameCharacter.doSleepSpeech @2-27`,
  `IsoCell.updateInternal @683-701` (rain scroll, cosmetic), `IsoChunk.addBloodSplat @207-212` (fade = lockFPS*5 ticks).
  `IsoPhysicsObject.update @92-111` hardcodes `10` on the server instead of reading lockFPS.

## 1. MovingObjectUpdateScheduler

- Levels: `UpdateSchedulerSimulationLevel` enum SIXTEENTH..FULL; `getFrameMod = 1 << (FULL.ordinal - ordinal)` ->
  FULL 1, HALF 2, QUARTER 4, EIGHTH 8, SIXTEENTH 16. Bucket = `obj.getID() % frameMod` (`UpdateBucket.add @0-9`);
  `update(int)` runs bucket `frameCounter % frameMod` (@11-21) with `GameTime.perObjectMultiplier = frameMod` (@0-8),
  reset to 1 after (@152-156). `frameCounter++` per `startFrame` (@0-7).
- Skipped ticks are compensated for everything derived from `getMultiplier`/`getUnmoddedMultiplier`/`getTrueMultiplier`
  (they include `perObjectMultiplier`), but NOT for `getRealworldSecondsSinceLastUpdate`/`fpsMultiplier`-only code.
- **Server: irrelevant.** `getUpdateSchedulerSimulationLevelForObject @0-16` returns FULL whenever `GameServer.server`,
  so every object is updated every tick (10 -> 20 Hz). In `startFrame @69-99` server `IsoZombie`s are never added to a
  bucket (only `updateForServerGui` when `-gui`), so zombie AI (`IsoZombie.update`) does not run on a dedicated server.
  Objects that do run per tick: players, animals, vehicles, physics objects.
- Client-only reference (level choice uses distance/alpha and `averageFPS` 10/20/25/35/45/55 thresholds, @163-372):
  real rate = tick rate / frameMod.

## 2. Logic table

| Logic | Kind | Evidence | Effect at 20 Hz |
|---|---|---|---|
| Game clock (`timeOfDay`, hours survived, EveryOneMinute/TenMinutes/Hours, erosion, climate 10-min, radio) | time-scaled | `GameTime.update @469-536` `+= 1/minutesPerDay/60*getMultiplier/2`; `ErosionMain.mainTimer` only from `EveryTenMinutes` | none (except warm-up if lockFPS stays 10) |
| Helicopter random spawn roll | time-scaled | `GameTime.update @255-264` `Rand.Next(800*getInvMultiplier)` | none |
| Random ambient event (`addRandomAmbient`) | **tick-counted** | `GameTime.update @62-82` `Rand.Next(AdjustForFramerate(9000))` | 2x frequency |
| Helicopter zombie-attracting world sound | **tick-counted** | `Helicopter.update @839-876` `AdjustForFramerate(300)` -> `addSound(500,500)`; movement @880-908 uses `getThirtyFPSMultiplier` | sound pulses 2x as often (avg 10 s -> 5 s) |
| WorldSound lifetime (server list; animals, popman via `addWorldSound`) | **tick-counted** | `WorldSound.init @6-8` `life = 16`; `WorldSoundManager.update @164-169` `life--` per tick | sounds live 0.8 s instead of 1.6 s on server; clients keep their own copy |
| Player body sim on server: wound infection, bleeding blood | **tick-counted** | `IsoGameCharacter.updateInternal @1521-1538` runs `BodyDamage.Update` (doCharacterStats); `BodyPart.DamageUpdate` rolls `AdjustForFramerate` 1000 @268, 5000 @999 (dirty clothes), 80000 @2056 (bandage), 20000 @2118 (stitches) | infection/blood chances 2x per real second; healing/bandage decay use `getMultiplier` (unchanged) |
| Clothing dirt/blood accumulation | **tick-counted** | `updateInternal @2171-2190` calls `updateDirt` on server; `updateDirt @58-64..` `AdjustForFramerate(3500/2500/...)` (9 sites) | clothes get dirty 2x faster |
| Burning character: stop burning, spread fire | **tick-counted** | `ReduceHealthWhenBurning @297-316` `AdjustForFramerate(150/400)`; `SpreadFire @55-68` `AdjustForFramerate(3000)` | stops burning sooner, spreads fire 2x as often |
| Microwave with metal sparks fire | **tick-counted** | `IsoStove.update @105-119` `Rand.Next(AdjustForFramerate(200)) == AdjustForFramerate(100)` | 2x faster (avg 6.6 s -> 3.3 s) |
| Over-cap zombie culling | **tick-counted** | `ZombieCountOptimiser.prepareZombiesForDeletion @163-171` `AdjustForFramerate(10)` per excess zombie | excess zombies removed 2x faster |
| Animal fear/stress from spotting players | **tick-counted** | `BaseAnimalBehavior.spotted @956-1199` `AdjustForFramerate` 25000/200/385 (11 sites) | wild animals spook ~2x faster |
| Animal miscarriage under stress | **tick-counted** | `AnimalData.checkPregnancy @21-33` `NextBool(50)` per update | near-certain either way; just faster |
| Other animal stats, hutch | time-scaled | `IsoHutch.update` UpdateLimit + `getMultiplier`; `AnimalData.update` hour-gated | none |
| Physics objects (molotov, ball, blood drop, falling clothing, giblets) if simulated on server | **tick-counted** | `IsoPhysicsObject.update @92-111` server uses fixed `fps=10` for drag/gravity/step | fly/fall 2x fast on server |
| Safehouse trespass / last-visited check | **tick-counted** | `IsoPlayer.updateInternal2 @474-507` `checkSafehouse--`, reset 200 | every 10 s instead of 20 s (benign) |
| id_manager_data.bin save check | **tick-counted** | `ObjectIDManager.checkForSaveDataFile @7-25` every 300 ticks | every 15 s instead of 30 s (I/O only if dirty) |
| Connection-attempt timeout sweep | tick-counted cadence | `GameServer.main @4322-4330` every 150 ticks; timeout itself time-based | sweep every 7.5 s (CPU only) |
| Sleep "ZzzZZZzzzz" | tick-counted vs lockFPS | `doSleepSpeech @2-27` `cnt > 250*lockFPS/30` | 2x rate unless lockFPS raised to 20 |
| Startup grid-stack dirtying | tick-counted | `IngameState.updateInternal @2-47` first 60 ticks | 3 s instead of 6 s (negligible) |
| Fire (IsoFire life, spread, burn corpses) | time-scaled fixed step | `IsoFire.update @444-450` `updateFromTimer(getThirtyFPSMultiplier)`; loop `@19-34`, `@536 ifgt 18`; `IsoDeadBody.Burn` uses `getMultipliedSecondsSinceLastUpdate` | none |
| Traps | time-scaled | `IsoTrap.update` gated by `GameTimer.check` (`getWorldAgeHours`) | none |
| Generators, compost, chargers, washers/dryers, barbecues, fireplaces | time-scaled | `IsoGenerator/IsoCompost/...update` use `getWorldAgeHours` (hour-gated rolls) | none (generator re-adds a repeating WorldSound each tick: CPU) |
| World item processing (food rot/cook) on server | time-gated | `IsoCell.updateInternal @172-210` `ProcessItems` only every 5000 ms on server | none |
| Entity systems (crafting, drying, furnace, fluids, resources, logistics) | time-scaled fixed 100 ms step | `EntitySimulation.update @0-58` accumulates `(long)(getTimeDelta*1000)`; `GameEntityManager.Update @18-55`; mashing uses world age | none, but `f2l` truncation loses up to 1 ms/tick: <=1% slow at 10 Hz, <=2% at 20 Hz |
| Vehicles: Bullet physics | time-scaled fixed step | `WorldSimulation.updatePhysic @0-93`: accumulate `getPhysicsSecondsSinceLastUpdate`, `n = localTime/0.01`, `n x Bullet.stepSimulation(0.01)` | same 100 steps/s (5 per tick instead of 10) |
| Vehicles: server update/sync, position history | time-gated | `VehicleManager.serverUpdate` UpdateLimit (called twice per tick: `GameServer.main @3882`, `IsoWorld.updateInternal @19`); `BaseVehicle.postupdate @463-545` UpdateLimit | none |
| Zombies: AI | not run on server | `MovingObjectUpdateScheduler.startFrame @69-99` | none |
| Zombie sync/ownership, popman | time-gated / time-scaled | `NetworkZombiePacker.postupdate/send` UpdateLimit; `NetworkZombieManager.updateAuth` ms; `ZombiePopulationManager.updateMain @11-23` passes `getMultiplier`, world age to native | none visible (native internals not inspected) |
| Pathfinding, LOS | worker threads | `PolygonalMap2.updateMain` per tick hand-off; `ServerLOS` own thread, suspended/resumed around `ServerCell` unload in `ServerMap.postupdate` | results picked up sooner; CPU only |
| ServerMap/ServerCell, chunk loading, PlayerDownloadServer | time-gated / per-tick queues | `ServerCell.update`, `ServerMap.preupdate` UpdateLimit/ms; `PlayerDownloadServer.update` drains queue per tick | faster queue draining, more CPU |
| SafeHouse, WarManager, Trading, LoginQueue, NetworkPlayerManager, StatisticManager, ImportantAreaManager, MapCollisionData, VirtualZombieManager | time-gated | UpdateLimit / `currentTimeMillis` / `getServerTime` in each `update` | none |
| Timed actions / transactions | time-scaled | `BaseAction.update` (`getMultiplier`), `ActionManager/TransactionManager.update` (`getServerTime`) | none |
| Thunder, weather periods, climate | time-scaled | `ThunderStorm.update` via `GameTime$AnimTimer.update` (`getMultipliedSecondsSinceLastUpdate`); `ClimateManager.update` UpdateLimit + world age | none |
| SGlobalObjects (farming, rain barrels, ... Lua) | game-time events | `SGlobalObjectSystem.update` is empty; Lua systems use EveryTenMinutes/EveryHours | none |
| XP from moving (OnPlayerMove) | ms-gated | `xpUpdate.onPlayerMove` -> `IsoGameCharacter.isRandXp @6-38` (`currentTimeMillis`) | none |

## 3. Lua per tick on the server

- `IngameState.updateInternal @56-67` fires `OnTickEvenPaused(numberTicks)`; `onTick @0-11` fires `OnTick(numberTicks)`
  once per tick; `numberTicks++` @1452-1461. Both run 2x as often; the argument is a tick count.
- `OnPlayerUpdate` (`IsoPlayer.updateInternal2`) and `OnZombieUpdate` (`IsoZombie.updateInternal`) are not fired for
  server-side remote players / zombies on a dedicated server (zombie update does not run; see section 1).
- Vanilla server/shared handlers: `forageServer.relevanceTick` (`server/Foraging/forageServer.lua:455-460`) gated by
  `getTimestampMs`; `ISRadioInteractions.OnTick` (`shared/RadioCom/ISRadioInteractions.lua:281-305`) decrements cooldowns
  by `getMultiplier`; `ISCampingMenu` one-shot OnTick is a client action; two other server OnTick lines are commented out.
  `Fish.lua:54-55` counts `splashTimer % 30` but is client fishing UI. No vanilla server Lua counts OnTick calls.
- Mods: anything doing work in `OnTick`/`OnTickEvenPaused` costs 2x CPU; mods counting ticks (`tick % N`) or using the
  OnTick `numberTicks` argument as time will run 2x fast.

## 4. CPU

Measured sections available: `GameServer$s_performance.frameStep` (whole world tick), `.mainLoopDealWithNetData`,
`.RCONServerUpdate`; `IngameState$s_performance.update`; `IsoWorld$s_performance.isoWorldUpdate/isoCellUpdate`;
`IsoPlayer$s_performance.update`; `WorldSimulation$s_performance.worldSimulationUpdate`; `GameProfiler` areas inside
`IngameState.UpdateStuff` (ZombiePopulationManager.updateMain, PolygonalMap2.updateMain, MapCollisionData.updateMain,
IsoFireManager.Update, WorldSoundManager.update, ...). Expectation without measurement:
- Doubles: per-tick fixed work: IsoCell.update (scheduler over players/animals/vehicles, `ProcessIsoObject`,
  static updaters, dead bodies), player character/BodyDamage sim, animal AI, Lua OnTick (vanilla + mods),
  ServerMap pre/postupdate, chunk bookkeeping, PlayerDownloadServer.
- Unchanged per second: Bullet (100 substeps/s), EntitySimulation (10 sim ticks/s), all UpdateLimit/ms-gated managers
  (vehicle/zombie sync, popman native ticks get smaller dt), packet handling (runs every loop iteration, ~5 ms).
- No zombie AI on the server, so the biggest client cost is absent. Likely dominant per-tick cost: IsoCell object
  update + Lua OnTick with many players/animals/mods. Net world CPU roughly 1.5-2x; measure with the probes above.

## Risk list

1. `Rand.AdjustForFramerate` hardcodes 10 Hz on the server (`RandInterface @0-13`): all per-tick random rolls double
   (wound infection, clothing dirt, fire spread from burning players, microwave fires, helicopter noise, ambient events,
   animal spooking, zombie culling). A 20 Hz patch must also patch this (e.g. scale by `tickHz/30`).
2. `IsoPhysicsObject.update` hardcodes `10` fps on the server: thrown/physics objects simulated on the server move 2x fast.
3. WorldSound `life = 16` ticks: server-side sound memory halves (animals, anything polling the server list).
4. fps EMA starts at `getLockFPS()` (10): without also setting lockFPS 20, game time runs fast for ~3 s after start.
   `setLockFPS(20)` is otherwise low-risk on the server (only sleep speech, blood fade, rain scroll read it there).
5. Small slowdowns: `EntitySimulation` ms truncation (<=2%), and EMA of `1000/dt` with ms timer jitter biases fps up
   (slightly slow clock); both relatively larger at 50 ms ticks.
6. Tick-counted cadences (safehouse check 200, id-manager 300, connection sweep 150, startup 60): benign, just faster.
7. Mods using OnTick tick counts or the `numberTicks` argument as a clock run 2x fast; all OnTick mod work costs 2x CPU.
