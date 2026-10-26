# Player and vehicle sync vs server tick rate (B42 dedicated server)

Source: `javap -c -p -constants` of the B42 jar. Evidence is `Class.method @bytecode-offset`.
Scope: players, vehicles, time sync, server options. Zombies are in `zombie-sync.md`.

## 0. Main loop facts that the rest depends on

- `UpdateLimit.Check` fires when `now - last > delay`, then sets `last += delay`. If more than `3*delay` overdue, it sets `last = now` instead. It is real time (`currentTimeMillis`) with catch-up.
- `GameServer.main @2695-2709`: `tick = new UpdateLimit(100)`, `PerformanceSettings.setLockFPS(10)`; `@2721` the smoothed fps starts at `getLockFPS()`.
- Each loop iteration (`@2822`) drains `MainLoopNetDataHighPriorityQ`, then `MainLoopPlayerUpdateQ`, then `MainLoopNetDataQ`. The last drain stops after 70 ms per iteration (`@3286-3362`, "Server is too busy..."). Then `tick.Check()` (`@3495`). On a non-tick iteration it sleeps `clamp(5 ms - elapsed, 0, 100)` (`@3501-3533`). So when idle the loop drains about every 5 ms, and the tick does not gate packet handling.
- `GameServer.addIncoming @88-150`: `PlayerUpdateReliable/Unreliable` go to `MainLoopPlayerUpdateQ`, and `VehiclePhysicsReliable/Unreliable` go to the budgeted `MainLoopNetDataQ`. Everything else goes to the high-priority queue.
- fps smoothing `@4494-4557`: `fps += min((1000/dt - fps)*0.05, 1.0)`, `GameTime.fpsMultiplier = 60/fps`. fps can rise by at most 1 per tick but can fall without limit.

## 1. Server to client: player sync

**Movement is relayed when a packet arrives, not on the tick.**
- Client send rate is set by the client. `GameClient.sendPlayer2 @14-49` calls `PlayerPacket.set` → `NetworkPlayerAI.set(PlayerPacket)`. That method sends only if `timerMin` (200 ms) passed, `needUpdate` is set, or the square changed (`@97-116`). It forces `PlayerUpdateReliable` when `timerMax` (1000 ms; reset to 600 ms after a reliable send) passes, or when variables, turning or direction change (`@355-427`). Constructor `NetworkPlayerAI.<init> @6-44`: reliable 2000, timerMax 1000, timerMin 200.
- Server side, `PlayerPacket.processServer @33-280` applies the packet (`NetworkPlayerAI.parse`), stores `prediction.position` into `UdpConnection.releventPos`, and forwards the same packet type straight away to every relevant and visible connection (`@126-274`: `isRelevantTo`, `checkCanSeeClient`, `doPacket`/`write`/`send`). Nothing is batched per tick.

**Server-authored player data runs on real-time limits.** `GameServer.main @4820` calls `NetworkPlayerManager.update` once per tick. It runs:
  - `damageUpdateLimit` 2000 → `syncDamage`
  - `statsUpdateLimit` 1000 → `syncStats` and `syncXp`
  - `healthUpdateLimit` 500 → `syncHealth`
  - `UdpConnection.playerSave` → DB save
  - Evidence: `NetworkPlayerManager.update @6-151`, `<clinit> @4-36`.
- `sendWorldMapPlayerPosition` uses a 1000 ms limiter (`GameServer.main @4746-4755`, `<clinit> @257-267`). `calcCountPlayersInRelevantPosition` uses 2000 ms (`@4058`, `<clinit> @244-254`).
- `StatisticManager.update` sends `Statistics` only to admins with `GetStatistic` and `statisticTransmissionEnabled`, every `multiplayerStatisticsPeriod` (`StatisticManager.update @7-591`). It is for display only.

**Per-tick work tied to players (CPU; no player-position bytes):**
  - `ServerMap.characterIn(player)` for every player (`main @3921-4012`)
  - `ServerMap.characterIn` / `ClientServerMap.characterIn` for 4 `connectArea`s per connection (`@4113-4177`)
  - `PacketValidator.update` and the `chunkObjectStateRequests` prune per connection (`@4586-4743`)
  - `IsoPlayer.update` for each remote player inside `IngameState.update`. This is time-scaled.
- `PlayerDownloadServer.update` runs every tick for every connection (`main @4180-4193`). Each call removes and serializes one `ClientChunkRequest` (`PlayerDownloadServer.update @78-237`, `ccrWaiting.remove(0)`, `IsoChunk.SaveLoadedChunk`). **Map-chunk streaming per connection is per tick, so it doubles at 20 Hz.**

**Bytes per second at 20 Hz:**
  - Player movement relay, stats/health/damage/xp and world-map positions: **flat**.
  - Chunk streaming while players move or join: **doubles** (up to 2 requests per connection per 100 ms).
  - Overall for players: **partial**.

## 2. Vehicles

**Server send path.** `VehicleManager.serverUpdate @0-10` returns unless `updateRate` (100 ms) fires. `updatePassengers` (1000 ms) sets flag `0x4000` on every vehicle (`@21-70`). For each connection, `sendVehicles` sends `VehicleFullUpdate` or `VehicleUpdate` only for relevant vehicles whose `ServerVehicleState.shouldSend` reports flags (`sendVehicles @66-163`). `shouldSend @0-94` returns false with no controller or during `updateLockTimeout`, and otherwise ORs in authorization changes and `BaseVehicle.updateFlags`. Those flags are set by events such as lights, horn, keys, engine and skin (`transmitEngine`, `setHeadlightsOn`, `onHornStart`, ...). So `VehicleUpdate` is change-driven and at most every 100 ms.
- `serverUpdate` is called **twice per tick**: from `IsoWorld.updateInternal @10-19` (when server) and from `GameServer.main @3882`. Both calls go through the same 100 ms `updateRate`, so they fire at most about 10 times per second.
  - At 10 Hz the limit fires on most ticks.
  - At 20 Hz it fires about every other tick.
  - Either way the send rate stays near 10 per second: **flat**.

**Who is authoritative.** The driving client.
- The driver's `BaseVehicle.update @374-391` calls `updatePhysicsNetwork` only when `GameClient.client && hasAuthorization(GameClient.connection)`.
- `updatePhysicsNetwork @18-129` sends `VehiclePhysics` when `limitPhysicSend` fires:
  - every 150 ms while the vehicle moves (distance² > 0.001),
  - otherwise easing back toward 300 ms (`setSmoothUpdatePeriod(300)`),
  - as Reliable when `physicReliableLimit` (500 ms) fires.
- `VehiclePhysicsPacket.set @1-28` stamps the packet with the sender's `WorldSimulation.time`, which is synced server time.
- `VehiclePhysicsPacket.processServer @1-438` copies the driver's position, rotation, velocity, engine and wheels into the server's `BaseVehicle` when `hasAuthorization(connection)`. It then calls `sendToRelativeClients` right away, so this is a relay on receipt.

**Server physics.** The dedicated server does not create the Bullet world:
  - `BaseVehicle.createPhysics @166-175` skips `WorldSimulation.create` when `GameServer.server`.
  - `IsoChunk.AddVehicles @46-55` and `IsoWorld.init @5022` do the same.
  - `GameServer` itself never touches `Bullet` or `WorldSimulation`.
  - So `WorldSimulation.updateInternal @0-7` returns while `created` is false, and the server steps no physics. On clients, `WorldSimulation.updatePhysic @0-83` uses a fixed **0.01 s** substep (`Bullet.stepSimulation(0.01f,0,0)`) with an accumulator fed by `GameTime.getPhysicsSecondsSinceLastUpdate`, which is `fpsMultiplier/60`. That is fixed dt and not tick-dependent. One exception: `LuaManager.GlobalObject.addVehicle @101-104` calls `WorldSimulation.create` with no server check, so it is a Lua/admin edge case.
- `CarController` does exist on the server (`createPhysics @233-238`), which is what `shouldSend` checks.

**Logic that counts ticks.** `BaseVehicle.update @394-890` handles the trailer trunk on `!GameClient.client` (so the server):
  - Conditions: towing `Base.Trailer`, speed > 30 km/h, `TrailerTrunk` condition < 50.
  - Each update rolls `Rand.Next(0, max(10000 - chance, 1)) == 0` to drop an item. This is per tick and not scaled by time, so **the drop rate doubles at 20 Hz**.

## 3. Client side: applying updates

- **Remote players** are moved by path-following toward a predicted target. There is no timed interpolation buffer.
  - `PlayerPacket.processClient @40-153` → `NetworkPlayerAI.parse(PlayerPacket) @11-213` sets `targetX/Y/Z` and `predictionType`, copies `Prediction`, and calls `PathFindBehavior2.pathToLocationF`. If the remote player is more than 1 tile off and the line is blocked, or on a different floor, the client calls `teleportTo` (`processClient @110-293`).
  - The **sender** computes the target in `NetworkPlayerAI.setMoving @10-152`: `deferredMovement / GameTime.getTimeDelta()`, ×1.2, ×0.6 lead, `DISTANCE_SCALE` 8. This is client frame time and has nothing to do with the server tick.
- **Remote vehicles** use `VehicleInterpolation`:
  - Render delay is 500 ms with 800 ms history (`<init> @40-58`).
  - The target delay is ×3 when ping > 290 ms, and `delayTarget` is driven by `setDelayLength(f) = f*500` (`getPointUpdate @78-456`).
  - The buffer is keyed by the sender's `WorldSimulation.time`, and `interpolationDataGet` samples at `WorldSimulation.time - delay` (`@12-32`).
  - `VehiclePhysicsPacket.processClient @1-63` adds a point when this client has no authorization, and otherwise requests the vehicle.
- `NetworkCharacter` (interpolate/extrapolate/predict) is used only by `NetworkZombieAI` (`setUsingExtrapolation`, `set(ZombiePacket)`). No player or vehicle code calls it.
- **No server-tick constant in client time math.** The 10s, 100s and 500s found are:
  - client send intervals,
  - the interpolation delay,
  - `NetworkCharacterAI` `HIT_INTERVAL` 100 ms, which is `nanoTime` real time (`hasHitIntervalPassed @1-8`),
  - `getPointUpdatePer = 10` (client frames).
  - `GameTime.getServerMultiplier` hardcodes `10.0f / GameWindow.averageFPS` but has no Java callers. It may be reachable from Lua.
- **The server never sends its fps or tick count.** In the `network` package, fps appears only in `GameServer.main` (`getLockFPS @2721`, `fpsMultiplier @4557`). The `PerformanceStatistic.fps` counter goes only to admin statistics.

## 4. Time sync

- `TimeSyncPacket` is an NTP-style exchange of `nanoTime` values. `processServer` echoes it, and `processClient` calls `GameTime.syncServerTime(clientTime, serverTime, now)`. That computes `shift = serverTime - clientTime + rtt/2`, then smooths it by 1/100 and re-requests when the jump is > 10 ms (`syncServerTime @0-93`). `getServerTime` returns `nanoTime + shift` on clients and `nanoTime` on the server (`@14-45`). This is wall-clock and independent of the tick.
- `SyncClock` carries `fastForward`, `timeOfDay` and `nightsSurvived` (`SyncClockPacket.setData/write`). `GameTime.update @804-834, @1261-1271` sends it every `SYNC_CLOCK_MS` = 10000 ms, **or every tick while `GameServer.fastForward`** (sleep). So during fast-forward the sends double at 20 Hz. The packets are tiny (9 bytes).
- The client keeps its own clock and only snaps `timeOfDay` and `serverNewDays` on `SyncClock` (`SyncClockPacket.processClient`, `GameTime.update @885-946`). Nothing on the client is computed from the server tick count.

## 5. Server options

- Options with rate-like names: `pingLimit`, `speedLimit`, `fastForwardMultiplier`, `multiplayerStatisticsPeriod`, `maxPacketsPerSecond` (incoming-packet flood check, `isLimitExceeded`), `switchZombiesOwnershipEachUpdate` (`NetworkZombieManager.updateAuth`), `backupsPeriod`. **None sets the tick or fps.**
- The tick part of the main loop reads only `sleepAllowed` (`@4026-4055`, for fast-forward). Tick and fps are hardcoded (`@2699`, `@2707`).

## 6. Other frame-count code on the server

- `IsoPlayer.updateInternal2 @552-616`: `timeSinceLastNetData += (int) GameTime.getMultiplier()`. A remote player whose value exceeds 600 is dropped from the object list.
  - The `(int)` truncation loses the same fraction at both rates: about 4.8→4 per tick at 10 Hz and about 2.4→2 at 20 Hz.
  - So the timeout stays at about the same wall time. Only the rounding granularity changes.
- Some code assumes ticks per second = `getLockFPS()`. Examples are `IsoGameCharacter.doSleepSpeech` (counter vs `250*lockFPS/30`) and the fps smoother's start value. **If the tick is raised, `setLockFPS` should be raised to match.** Otherwise fps starts at 10 and ramps up over about 1 s, with `fpsMultiplier` too high meanwhile.

## Impact at 20 Hz

- **CPU:** these costs per second roughly double:
  - remote `IsoPlayer.update`
  - `ServerMap.characterIn` (players + 4 connect areas per connection)
  - `PacketValidator.update`
  - `PlayerDownloadServer.update` chunk serialization
  - `BaseVehicle.update` for all vehicles

  Vehicle physics costs nothing (no Bullet on the server). Packet handling does not change, since it is driven by received packets.
- **Bandwidth:**
  - Flat: player movement, vehicle physics relay, `VehicleUpdate`, player stats/health and time sync. These are all real-time limits or relays.
  - Doubles: map-chunk streaming per connection (a burst while moving or joining), and `SyncClock` during fast-forward.
- **Client behaviour:** essentially unchanged for players and vehicles. Their positions come from other clients' 150–300 ms sends, relayed on receipt, and are smoothed client-side with no assumption about the server rate. Relay latency can drop slightly, because a tick that blocks the loop is shorter if CPU allows. The server's own copy of remote players (used for hit and relevance checks) is updated twice as often.
- **Risks:**
  1. If a tick takes more than 50 ms, `UpdateLimit` catch-up runs ticks back to back. Up to 150 ms of debt is caught up, and beyond that it resets. The loop then drains queues only once per tick, which adds to relay latency for everyone.
  2. Per-tick random rolls such as the trailer item drop, plus any similar unscaled logic, double in rate.
  3. Keep `setLockFPS` consistent with the tick, or code scaled by `getLockFPS` will be off.
  4. Twice the chunk streaming per connection raises outbound bursts.
