# Server Reborn

A Kotlin mod for multiplayer servers, made for game 42.21: network sync optimization (formerly the Sync Guard mod) and
the server tick rate (formerly an All-in-One feature). Both run on one lifecycle: they are checked, turned on and, after
an error, turned off together. Needed on the dedicated server and the clients; requires ZombieBuddy, Zabric and
ZabricKotlin. Design and decisions: the archived task `10-26-server-reborn` and, for the sync part's mechanism, the
archived task `10-24-mp-sync-loss-recovery` (`design.md`, `research/`).

## Lifecycle

- The main entrypoint checks the access widener's list, the hooked methods and the game's packet and container types.
  Any mismatch leaves the mod off for the session, with the reason in `console.txt`.
- Client: ready; it checks its containers only on a server that runs the mod too (the join's trailer says so).
- Dedicated server: active. Co-op hosts: off.
- The first error in any hook turns the whole mod off and undoes what it changed: the packet channels, and on the
  server the tick rate goes back to 10 at its next tick.

## Network sync optimization

The game sends container and item changes incrementally and never notices a lost one. After each world tick the server
sends each checked client a frame of digests for the containers and items it just sent that client; a client whose copy
differs asks for a snapshot and reconciles in place. Opening a container checks it once more. Repairs show a short note
over the character and a log line on both sides.

Launch switches (JVM system properties, off by default; a switch that is on says so once):

| Switch | Side | Effect |
|---|---|---|
| `-Dserverreborn.summarySeconds=<n>` | both | `key=value` summary line every n seconds (default 600) and per connection when it leaves |
| `-Dserverreborn.debugDropDelta=<n>` | client | drops every n-th incoming container or item update, to test repairs |
| `-Dserverreborn.debugDropModule=<module>[.<command>]` | client | drops a mod's server commands, to test repairs of a mod's own sync |
| `-Dserverreborn.debugFieldDiff=true` | both | logs which digest fields differ around each repair / each snapshot item's values |
| `-Dserverreborn.debugTouches=true` | server | logs every in-scope update and content change |

## Server tick rate

A dedicated server updates its world up to 20 times a second instead of the game's fixed 10.

### The option

`Dont39ServerReborn_ServerTickRate.TicksPerSecond` on the "Server Reborn" sandbox page ("Server ticks per second"),
10 to 20, default 10 (the game's rate). Read once when the server starts; a change takes a restart. Clients and hosted
(co-op) games are not changed.

### What changes

- Results the server works out wait for its next tick: 0-50 ms instead of 0-100 ms at 20 (item transfers, finished
  timed actions, zombie updates relayed from their owners). Each player's map chunks stream twice as fast.
- Player and vehicle movement and zombie AI do not change: clients simulate them, and the server relays them as they
  arrive.
- Game time is unchanged: the game scales it by the measured tick time.

### Cost

Measured with 8 and 16 test players (about 700 and 1450 zombies) at 20 against 10:

- About 20% more server process CPU (0.24 to 0.29 cores, 0.49 to 0.58 cores); the tick's own work per second rises by
  63-90%, as each tick costs about as much as before.
- No more outgoing traffic.
- A tick has 50 ms instead of 100 ms, so a server runs out of time at about half the load. Past that the ticks run
  back to back and the rate drops. Ten seconds after start the log shows the measured rate, and an error line when it
  stays below the option.
- Every mod's `OnTick` / `OnTickEvenPaused` code runs twice as often and costs twice the CPU; mods that count ticks as
  time run twice as fast.

### Compensated

The game assumes 10 ticks a second on the server in places that would change gameplay. The mod scales them by the
rate, so per real second they stay as at 10:

- `Rand.AdjustForFramerate`, the per-tick random rolls (wound infection and bleeding, clothing dirt, fire from burning
  players, microwave sparks, helicopter noise, ambient events, animal spooking, removal of zombies over the cap).
- The life of a world sound (16 ticks).
- The game's lock fps, which seeds its tick time estimate, is set to the rate.

### Known differences

- Thrown and falling objects the server simulates (molotovs, balls, giblets) fly their path in half the time at 20.
- A trailer's item-drop roll runs per tick, so items drop twice as often.
- Mods that count `OnTick` calls (see Cost).

### How it works

- `UpdateLimit(long)` (`@Inject` at RETURN): the tick loop's limiter is a local of `GameServer.main`, so the mod takes
  the 100 ms limiter main builds.
- `PerformanceSettings.setLockFPS` (`@ModifyVariable` at HEAD): when `GameServer.main` sets 10, right after building
  the limiter, the mod sets the limiter's period and returns the option's rate.
- `ServerMap.preupdate` (`@Inject` at HEAD, also while the mod is off): counts the first 10 s of ticks, and puts the
  10 Hz limiter and lock fps back once the mod is off.
- `Rand.AdjustForFramerate` (`@ModifyReturnValue`) and `WorldSoundManager$WorldSound.init(Object, int x5, float,
  float, short)` (`@Inject` at RETURN), the overload every other one but the copy ends in.
- When the loop is not as expected (no 100 ms limiter built by `GameServer.main` before it sets the lock fps, or more
  ticks than the limiter allows), the mod logs what differed and turns itself off, which puts back 10 Hz and the game's
  values. Setting the option back to 10 and restarting undoes everything.
