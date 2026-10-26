# Zombie network sync vs server tick rate (B42 dedicated server)

Source: `javap -c -p` of `projectzomboid.jar`. Evidence is given as `Class.method@bytecodeOffset`.
"Tick" means one run of the 100 ms world-tick block in `GameServer.main` (`ServerMap.postupdate` is called
from that block at `GameServer.main@4470`).

## 1. Who simulates zombies: the owning client, not the server

- On the server, zombies are not added to the update buckets at all. `MovingObjectUpdateScheduler.startFrame@69-99`
  does `if (GameServer.server && obj instanceof IsoZombie) { if (guiCommandline) z.updateForServerGui(); continue; }`.
  So `IsoZombie.preupdate/update/postupdate` (AI, pathfinding, state machine) never run on a dedicated server.
  `updateForServerGui` only animates for the `-gui` window. The server holds zombie objects as state containers.
- Every zombie gets an owner client ("auth owner"). `NetworkComponent.isLocal` is `!isRemote`, and
  `NetworkZombieComponent.isRemote` is `authOwner == null`.
- Ownership is decided on the server in `NetworkZombieManager.updateAuth(IsoZombie)`, which runs once per tick for
  every zombie in `IsoCell.getZombieList()` (`NetworkZombiePacker.updateAuth@0-42`, called from `postupdate@1`):
  - It returns early when the owner changed less than 2000 ms ago (real time) and an owner exists (`@7-29`,
    `System.currentTimeMillis() - lastChangeOwner < 2000`).
  - `switchZombiesOwnershipEachUpdate` (with more than 1 player) rotates the owner to the next connection (`@30-200`).
    Despite the name, this is limited by the same 2000 ms gate.
  - Priority: the player grappling the zombie (`@202-251`), then the target player if it is in relevant range
    (`@253-328`), then the closest relevant player. A new candidate must be closer by a factor of 1.618
    (hysteresis, `@497-501`). Ownership is cleared when the owner is out of `RelevantTo((range-2)*10)` (`@658-692`).
  - `moveZombie@302-311` stamps `lastChangeOwner` and calls `NetworkZombiePacker.setExtraUpdate()`, which forces
    a sync send to every connection on the next tick.
- Zombies that nobody owns are frozen on the server (nothing simulates them). The population manager mirrors real
  zombies to native code at most once per 1000 ms of real time (`ZombiePopulationManager.updateMain@464-493`).
- On the client, `ZombieListPacket.parse` hands the owned-ID list to `NetworkZombieSimulator.add/added`, which then
  calls `becomeLocal` or `becomeRemote` (`NetworkZombieSimulator.added@0-193`). Owned zombies run full client AI.

## 2. Data flow and cadence

### Owner client to server (client cadence, independent of the server tick)
- `IsoCell.updateInternal@356-416` (client) calls `NetworkZombieSimulator.send()` when `GameClient.sendZombieTimer`
  fires, or when there are unknown zombies and `sendZombieRequestsTimer` fires. The timers are
  `UpdateLimit(4000)` and `UpdateLimit(200)` (`GameClient.<init>@62-86`).
- `ZombieSynchronizationPacket.parse@0-42` sets `sendZombieTimer` to **200 ms if `hasNeighborPlayer`, else 4000 ms**.
  That flag comes from the server in every sync packet.
- `NetworkZombieSimulator.send@44-269`: when `sendQueue` is empty it refills with all owned zombies, then sends
  **at most 300 per call** (`sipush 300 @257`). The rest go out in round-robin over later calls. Extra updates
  (changes to `bMoving`/`bPathfind` or facing direction, `NetworkZombieAI.preupdate@43-128`) go in a second
  reliable packet (`@345-488`). The first packet is reliable once per 1000 ms when unknown zombies exist
  (`zombieSimulationReliableLimit = UpdateLimit(1000)`); otherwise it is unreliable.
- The owner sends a target, not only a position. `NetworkZombieAI.set` picks a mode by AI state. Extrapolation
  predicts **500 ms ahead** (`setUsingExtrapolation@43`, `sipush 500` passed to `NetworkCharacter.predict`). Other
  modes send path, lunge, thump or climb targets. Each entry also carries `realX/realY` (the true position).
- On the server, `ZombieSimulationPacket.parse` calls `NetworkZombiePacker.parseZombie` while incoming packets are
  drained, not in the tick. A packet from someone other than the owner triggers `recheck` and an extra update
  (`@64-88`). Otherwise `applyZombie` snaps x/y/lastX/nextX to `realX/realY` (`applyZombie@41-80`), the packet is
  copied into `zombie.zombiePacket`, and the zombie is added to the `zombiesReceived` HashSet (`@174-206`).

### Server to clients: `NetworkZombiePacker.postupdate()`, once per tick
For each fully connected `UdpConnection` (`postupdate@90-403`):
1. It rebuilds the connection's owned-ID list and FNV hash (`NetworkZombieManager.getZombieAuth`). It sends
   **ZombieList** (2 B count plus 2 B per owned zombie) when the hash changed, or when the list is non-empty and
   `zombieListRefresh` (UpdateLimit **3000 ms**, `UdpConnection.<init>@221-231`) fired (`@153-289`).
2. `send(conn)` (`@0-151`):
   - It sends ZombieDeleteOnClient when there are deletes this tick.
   - It fills a ZombieSynchronization packet through `getZombieData`:
     - Zombies the client asked for through ZombieRequest (`zombiesRequest`), **capped at 300 per tick per
       connection** (`getZombieData@84-91`). For these the server rebuilds the packet with
       `ZombiePacket.set(zombie)` (`@48`).
     - All zombies received from owners since the last tick (`zombiesProcessing`) that are in this client's
       relevant range and not owned by it. This part has **no cap** (`@97-231`). It relays the owner's packet as
       received.
   - **If there are 0 entries, nothing is sent** unless `timerSendZombie` has expired or the connection is in
     `extraUpdate` (`@58-83`). `timerSendZombie` is reset to 3800 ms after each send (`@93-100`), so each client
     gets an empty keepalive at most every 3.8 s.
   - Reliability: a reliable packet once per 5000 ms from one **shared** `UpdateLimit(5000)`
     (`<init>@92-103`, `send@109-130`). All other packets are unreliable.
- Wire format (`ZombieSynchronizationPacket.write`): bool + short count + N × ZombiePacket. `ZombiePacket.write`
  always writes 26 B (id, update flags, x, y, outfit, health, walkType, dirAngle, skin). Optional blocks are
  controlled by the `update` bitmask (STATE, TARGET, MOVING, VARIABLES, PATHFINDING, REANIMATED, ZNOTZERO,
  GRAPPLED). The class declares `PACKET_SIZE_BYTES = 53`. A typical moving zombie is about 40–55 B.

What this means: each owner update is relayed exactly once to each relevant client, in the next tick. The tick only
decides how received updates are grouped into packets and how long they wait (0–100 ms at 10 Hz). The number of
zombie entries per second follows the owners' 200 ms cadence, not the server tick.

## 3. Client side: applying updates

- `ZombieSynchronizationPacket.parse` → `NetworkZombieSimulator.receivePacket/parseZombie`. For an existing remote
  zombie this calls `NetworkZombieAI.parse` and `NetworkZombieMind.parse` and resets `lastRemoteUpdate`
  (`parseZombie@849-891`).
- `NetworkZombieAI.parse`:
  - It sets `targetX/Y/Z` and `predictionType`, and copies `realx/realy`.
  - It **teleports** when the local position is more than 3 tiles from the real position (`dist² > 9`,
    `@363-467`), or on a Z mismatch (`checkZTeleport`).
  - With `usePathFind` it calls `pfb2.pathToLocationF(real)` on every update (`@0-51`).
- Remote zombies then move with `WalkTowardNetworkState.execute`: `moveToPoint(targetX, targetY)`, or pathfinding
  to the real position when the line is blocked. They walk at their own animation speed, frame by frame on the
  client.
- **No snapshot interpolation buffer for zombies.** `NetworkCharacter` has interpolate/reconstruct helpers, but
  zombies only use `predict` and `updateExtrapolationPoint`, and only on the owner (`NetworkZombieAI.set`,
  `setUsingExtrapolation`). Its constants (2000 ms reset, `deltaTime 10`) are timestamp-based.
- Client timeouts use the client's own frames: `lastRemoteUpdate += 1000 / lockFPS` per frame. A remote zombie is
  removed after more than 800 ms in a death/stagger/getup animation, or after 5000 ms without an update
  (`IsoZombie.updateInternal@332-601`).
- No client zombie code found assumes a 100 ms or 10 Hz server interval. The timers in `NetworkCharacterAI` and
  `NetworkZombieAI` are real-time (`ulBeatenVehicle` 200/400 ms, SpeedChecker 15000 ms, hit and corpse timeouts).
  Updates every 50 ms would be handled the same way, and they would not arrive more often per zombie anyway,
  because the owner cadence (200 ms) sets that rate.

## 4. Server zombie code that counts ticks instead of time

| Item | Evidence | At 20 Hz |
|---|---|---|
| `NetworkZombieManager.updateAuth` for every zombie, every tick | `NetworkZombiePacker.updateAuth@0-42` | 2× CPU. Owner changes stay limited by the 2000 ms real-time gate |
| `getZombieAuth` (walk the owned list + FNV hash) per connection per tick | `postupdate@143-151` | 2× CPU |
| ZombieList resend on any hash change, at most once per tick per connection | `postupdate@153-289` | up to 2× ZombieList packets under heavy ownership churn |
| `setExtraUpdate` forces a sync send to every connection on the next tick | `moveZombie@308-311`, `send@72-84` | up to 2× small packets while ownership churns |
| Request-response cap: 300 zombies per tick per connection | `getZombieData@84-91` | drain limit rises from 3000/s to 6000/s per client |
| `ZombieCountOptimiser.prepareZombiesForDeletion`: chance `Rand.Next(Rand.AdjustForFramerate(10)) == 0` per candidate per tick | `@163-171`; `RandInterface.AdjustForFramerate@0-13` (default method) on the server is `(int)(n * 0.33333334f)` (**a fixed 10 fps assumption**, ignores lockFPS) | chance is 1/3 per tick, so excess zombies (over `zombiesCountBeforeDeletion`) are culled about twice as fast per second. Still capped per tick by the excess count |
| `MovingObjectUpdateScheduler.frameCounter` / bucket `frameMod` | `getUpdateSchedulerSimulationLevelForObject@1-16` returns FULL on the server, and zombies are skipped anyway | none for zombies |
| `ZombiePopulationManager.updateMain` | `n_updateMain(GameTime.getMultiplier(), worldAge)` is time-scaled; real-zombie mirroring uses a 1000 ms real-time limit | spawns from `n_getAddZombieCount` each call happen in native code and could not be checked |

`Rand.AdjustForFramerate` with its server constant of 1/3 is used well beyond zombies (fire spread, BodyPart,
Food, IsoGameCharacter.updateDirt, ...). Any server-side per-tick random chance becomes roughly 2× per real second
at 20 Hz. This is outside zombie sync but is the main tick-count hazard found.

## Impact at 20 Hz

- **CPU:** zombie AI cost does not move, because the server does not simulate zombies. Ownership upkeep
  (`updateAuth` over all loaded zombies × connections × players) and the per-connection `getZombieAuth` and
  packing cost double per second. Relaying received updates costs about the same in total, split over more ticks.
- **Bandwidth:** the number of zombie entries per second stays flat, because it is driven by owners at 5 Hz each
  with a 300-zombie round-robin cap. The number of sync packets per client goes from at most 10/s to at most 20/s,
  and only on ticks with data. Ticks without data send nothing. Extra overhead is about 20–40 B per added packet.
  Example: 100 zombies in view × 5 Hz × about 45 B ≈ 22 KB/s of payload, plus at most about 0.4 KB/s of extra
  headers, so roughly +1–2%. ZombieList and extra-update packets can double in crowded multiplayer areas with
  heavy ownership churn; they are small (2 B per owned ID).
- **Client behaviour:** relay delay drops from 0–100 ms (mean about 50 ms) to 0–50 ms (mean about 25 ms). Owners
  already send targets predicted 500 ms ahead, so the visible gain is small. Nothing in client zombie code depends
  on a 100 ms interval. Per-zombie update frequency does not change, so client pathfinding requests
  (`pathToLocationF` per update) do not rise.
- **Risks:**
  - Server-side `Rand.AdjustForFramerate` (fixed 1/3) doubles per-second chances. For zombies, only count-cap
    culling is affected, and it gets faster.
  - Bursts of ZombieList and empty sync packets during ownership churn.
  - Unverified: native population spawning per `updateMain` call.
  - No breakage found in the sync protocol itself.
