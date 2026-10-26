# 10 Hz vs 20 Hz server tick: findings and recommendation

Game 42.21.0. Evidence is `Class.method@bytecodeOffset` from `javap -c -p` of `projectzomboid.jar`. Topic docs with
the full evidence: [zombie-sync.md](./zombie-sync.md), [player-vehicle-sync.md](./player-vehicle-sync.md),
[tick-counted-logic.md](./tick-counted-logic.md). Prototype and test harness: [prototype/](./prototype/).

## The loop

- `GameServer.main@2695-2709` builds `UpdateLimit(100)` (local 7) and calls `PerformanceSettings.setLockFPS(10)`;
  `@2721` seeds the fps estimate with `getLockFPS()`. Nothing reads a server option for either.
- Each loop iteration drains the network queues (packets are handled every ~5 ms, between ticks). `@3493` asks the
  limiter; when not due it sleeps until 5 ms after the iteration began (`@3501-3533`). When due the tick runs from
  `ServerMap.preupdate@3599` to `WorldMapVisitedServer.update@4835`.
- `UpdateLimit.Check` is fixed-rate with catch-up: due when `now - last > delay`, then `last += delay`, or
  `last = now` when more than 3 periods behind. A slow tick is followed by back-to-back ticks.
- After the tick, `@4498-4557`: `fps += min((1000/dtMillis - fps) * 0.05, 1)`, `GameTime.fpsMultiplier = 60/fps`.
  `dtMillis` is whole milliseconds; a catch-up tick with dt 0 gives fps = ∞ and raises the estimate by 1.

## What depends on the tick

**Not tick-bound (same per real second at 20 Hz)**

- Zombie simulation: the dedicated server runs no zombie AI. `MovingObjectUpdateScheduler.startFrame@69-99` skips
  every `IsoZombie` on the server; owner clients simulate and send every 200 ms (4000 ms with no other player
  near), the server relays them on its next tick.
- Player movement: relayed on receipt (`PlayerPacket.processServer@126-274`), clients send on a 200/1000 ms cadence.
- Vehicles: the driving client is authoritative and sends physics every 150-300 ms; the server builds no Bullet
  world and relays on receipt. `VehicleManager.serverUpdate` is behind a 100 ms limit.
- Real-time `UpdateLimit`s (stats 500-2000 ms, world-map positions 1000 ms, zombie list refresh 3000 ms, ...),
  game time and everything scaled by `GameTime.getMultiplier`/`getTimeDelta` (fire, generators, timed actions,
  weather, Lua game-time events), fixed-step systems (crafting/fluids 100 ms steps, client Bullet 0.01 s).
- Clients assume no server cadence: no zombie or player snapshot buffer tied to 100 ms; vehicle interpolation is keyed
  by the sender's clock. The server never sends its rate to players (only an admin statistics packet).

**Tick-bound (twice per real second at 20 Hz)**

| What | Evidence | Effect |
|------|----------|--------|
| `Rand.AdjustForFramerate(n)` returns `n * 1/3` on the server, a fixed 10 fps assumption | `RandInterface.AdjustForFramerate@0-13` | every per-tick roll doubles: wound infection and bleeding (`BodyPart.DamageUpdate`), clothing dirt, burning players spreading fire, microwave sparks, helicopter noise pulses, ambient events (`GameTime.update@62`), animal spooking, zombie over-cap culling (`ZombieCountOptimiser@163`) |
| `IsoPhysicsObject.update` uses fps 10 on the server | `@92-106` | thrown objects (molotovs, balls, giblets) fly the same path in half the time |
| World sound lives 16 ticks | `WorldSoundManager$WorldSound.init@6` | server keeps a sound 0.8 s instead of 1.6 s |
| Trailer item-drop roll per tick, not time-scaled | `BaseVehicle.update@394-890` | drops twice as often |
| Lua `OnTick`/`OnTickEvenPaused` per tick | `LuaEventManager` from `IngameState.update` | mod handlers cost 2× CPU; mods counting ticks run 2× fast. No vanilla server/shared handler counts ticks |
| Per-tick bookkeeping: zombie ownership (`NetworkZombieManager.updateAuth` per zombie), `getZombieAuth` per connection, IsoCell object updates, player body simulation, `ServerMap` pre/post, packet validators | topic docs | 2× CPU for these parts |
| Chunk streaming: one chunk request per connection per tick | `PlayerDownloadServer.update@78-237` | 2× map download throughput per player (faster world loading on join and fast travel) |
| Tick-counted cadences (safehouse check /200, id save check /300, connection sweep /150) | tick-counted-logic.md | benign, just more often |
| fps estimate seeded from `getLockFPS()` | `GameServer.main@2721` | without `setLockFPS(20)` game time runs fast for ~3 s after start |

Not checked: native population code per `ZombiePopulationManager.updateMain` call (`n_updateMain` is passed the
time-scaled multiplier).

## Prototype

[prototype/](./prototype/): a feature module `server-tick-rate` in All-in-One, rate from `-Ddont39.serverTickHz`.

- `setLockFPS` `@ModifyVariable` at HEAD: when called from `GameServer.main` with 10, returns the new rate.
- The limiter is a local, and `UpdateLimit.<init>(J)V` cannot be hooked alone (ZombieBuddy's matcher would also hit
  `<init>(JJ)V`; the processor refuses). So `UpdateLimit.Check` is hooked at HEAD: the first `Check` of a 100 ms
  limiter on the main thread whose caller is `GameServer.main` gets `setUpdatePeriod(1000/hz)`. After that, one
  volatile read per `Check` call.
- `TickProbe` (measurement only): tick work time from `ServerMap.preupdate` HEAD to `WorldMapVisitedServer.update`
  RETURN, tick gaps, process and main-thread CPU (JMX), bytes sent (sum of `totalActualBytesSent` over connections),
  players, zombies, vehicles, `GameTime.getMultiplier`; one line per 10 s.
- The prototype does not compensate any of the tick-bound items above.

## Measurements

Linux test server (16 cores, `myserver2_Data`, `-nosteam`, ZGC, 4 GB heap), save restored from one snapshot before
each run. Load: 8 or 16 `zombie.network.FakeClientManager` processes on the Windows PC over LAN (one per `-id`, local port
17500+id), walking 15-tile circles 40 tiles apart in West Point ([scenario.json](./prototype/scenario.json)). Fake
clients log in as real players and own and simulate zombies (`ZombieSimulator`), so the server sees the same
zombie sync, ownership and chunk traffic as with game clients. Each run: 2 min warm-up, then 30 windows of 10 s.
Order 10, 20, 20, 10 Hz with 8 clients, then 10, 20 Hz with 16 clients (`-c16`) ([run.sh](./prototype/run.sh), [summarize.py](./prototype/summarize.py)).

| run | win | tps | work_mean | work_p50 | work_p95(avg) | work_max | gap_max | cpu_proc(jmx) | cpu_proc(/proc) | cpu_main | sent_KiBps | players | zombies | vehicles | mult | mult*tps |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| hz10-a | 30 | 10.00 | 6.00 | 5.50 | 8.08 | 36.12 | 104.81 | 0.24 | 0.24 | 0.08 | 67.31 | 8.00 | 736.53 | 50.50 | 4.79 | 47.92 |
| hz10-b | 30 | 10.00 | 6.41 | 6.07 | 7.85 | 51.91 | 104.41 | 0.23 | 0.23 | 0.08 | 55.72 | 8.00 | 711.90 | 72.67 | 4.80 | 47.96 |
| hz10-c16 | 30 | 10.00 | 15.39 | 14.75 | 24.06 | 69.05 | 104.72 | 0.49 | 0.49 | 0.18 | 135.88 | 16.00 | 1504.33 | 83.87 | 4.78 | 47.81 |
| hz20-a | 30 | 20.00 | 5.43 | 5.21 | 6.39 | 58.31 | 58.60 | 0.29 | 0.29 | 0.12 | 53.01 | 8.00 | 680.60 | 78.33 | 2.39 | 47.85 |
| hz20-b | 30 | 20.00 | 6.07 | 5.82 | 7.28 | 97.19 | 97.57 | 0.29 | 0.29 | 0.14 | 62.60 | 8.00 | 730.10 | 88.60 | 2.40 | 47.92 |
| hz20-c16 | 30 | 20.00 | 12.52 | 12.14 | 16.98 | 116.34 | 117.29 | 0.58 | 0.58 | 0.27 | 124.20 | 16.00 | 1412.97 | 89.07 | 2.38 | 47.54 |

- `work` = one tick's body; `cpu_proc` = process CPU in cores (JMX and `/proc/<pid>/stat` agree); `cpu_main` = main
  thread; `sent` = RakNet actual bytes sent to all connections; `mult*tps` = game-time rate (equal = no drift).

Reading:

- The tick rate is exact: 10.00 and 20.00 ticks/s in every window; tick gaps stay at the period except after a
  slow tick (max gap = the slowest tick, then catch-up ticks).
- Per-tick work does not halve: 8 clients 6.0-6.4 ms at 10 Hz vs 5.4-6.1 ms at 20 Hz; 16 clients 15.4 vs 12.5 ms.
  Tick work per real second rises by 80-90% (8 clients) and 63% (16 clients); main-thread CPU by 50-75%.
- Process CPU rises much less, +20%: 0.24 → 0.29 cores (8 clients), 0.49 → 0.58 cores (16 clients). Packet handling,
  RakNet and other threads are not tick-bound.
- Outgoing bytes are flat; they follow zombies and vehicles in view (53-67 KiB/s with 8 clients, 124-136 KiB/s with
  16), not the rate.
- Game-time rate (`mult*tps`) matches within 0.6%. Both rates run 0.1-1% below the nominal 48 (fps estimate biased
  up by ms jitter and catch-up ticks); the bias is slightly larger at 20 Hz under load (47.54 vs 47.81 with 16).
- Headroom: per-tick work grows with players (6 → 15 ms from 8 to 16 here). A 20 Hz tick has a 50 ms budget, so a
  server reaches back-to-back ticks at about half the load that saturates 10 Hz; past that the rate degrades
  instead of the server, and packet draining between ticks shrinks.

Limits: fake clients do not use inventories, timed actions, crafting or Lua, and none are admins; the load is 8-16
players around one town. Real clients were not joined at 20 Hz.

## Recommendation

Ship as an **opt-in server option, default 10 Hz**; not default-on, not "don't".

- Worth offering: server-side results wait 0-50 ms for the tick instead of 0-100 ms (item transfers, timed-action
  completion, zombie update relay), map chunks stream twice as fast per player, at about +20% process CPU and no
  extra bandwidth at the loads measured.
- Not default-on: the 50 ms budget halves the headroom for big servers; every mod's `OnTick` handler costs twice
  per second and tick-counting mods change speed; the gain is invisible for player and vehicle movement, which are
  relayed on receipt and simulated by clients.
- The option must also undo the game's 10 Hz assumptions that change gameplay: `Rand.AdjustForFramerate` (scale by
  rate/10) and `setLockFPS` (the fps estimate's seed), and should scale world sound life. Thrown-object flight time
  and the trailer drop roll can stay as documented differences.
- Before calling it done: one real client session at 20 Hz (fake clients do not use inventories, timed actions or
  Lua).
