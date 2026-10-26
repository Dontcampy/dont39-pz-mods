# Server tick rate

A Dont39 All-in-One feature: a dedicated server updates its world up to 20 times a second instead of the game's fixed
10. Made for game 42.21. Research and measurements: `.trellis/tasks/10-24-server-tick-20hz/research/` (archived with
the task).

## The option

`Dont39AllInOne_ServerTickRate.TicksPerSecond` on the All-in-One sandbox page ("Server ticks per second"), 10 to 20,
default 10 (off). Read once when the server starts; a change takes a restart. Clients and hosted (co-op) games are
not changed: the feature is idle there.

## What changes

- Results the server works out wait for its next tick: 0-50 ms instead of 0-100 ms at 20 (item transfers, finished
  timed actions, zombie updates relayed from their owners). Each player's map chunks stream twice as fast.
- Player and vehicle movement and zombie AI do not change: clients simulate them, and the server relays them as they
  arrive.
- Game time is unchanged: the game scales it by the measured tick time.

## Cost

Measured with 8 and 16 test players (about 700 and 1450 zombies) at 20 against 10:

- About 20% more server process CPU (0.24 to 0.29 cores, 0.49 to 0.58 cores); the tick's own work per second rises by
  63-90%, as each tick costs about as much as before.
- No more outgoing traffic.
- A tick has 50 ms instead of 100 ms, so a server runs out of time at about half the load. Past that the ticks run
  back to back and the rate drops. Ten seconds after start the log shows the measured rate, and an error line when it
  stays below the option.
- Every mod's `OnTick` / `OnTickEvenPaused` code runs twice as often and costs twice the CPU; mods that
  count ticks as time run twice as fast.

## Compensated

The game assumes 10 ticks a second on the server in places that would change gameplay. The feature scales them by the
rate, so per real second they stay as at 10:

- `Rand.AdjustForFramerate`, the per-tick random rolls (wound infection and bleeding, clothing dirt, fire from burning
  players, microwave sparks, helicopter noise, ambient events, animal spooking, removal of zombies over the cap).
- The life of a world sound (16 ticks).
- The game's lock fps, which seeds its tick time estimate, is set to the rate.

## Known differences

- Thrown and falling objects the server simulates (molotovs, balls, giblets) fly their path in half the time at 20.
- A trailer's item-drop roll runs per tick, so items drop twice as often.
- Mods that count `OnTick` calls (see Cost).

## How it works

- `PerformanceSettings.setLockFPS` (`@ModifyVariable` at HEAD): when `GameServer.main` sets 10, the option's rate.
- `UpdateLimit.Check` (`@Inject` at HEAD): the tick loop's limiter is a local of `GameServer.main`, so the feature
  finds it at its first check from there and sets its period. The constructor `UpdateLimit(long)` cannot be hooked
  alone (ZombieBuddy's matcher also takes `UpdateLimit(long, long)`).
- `ServerMap.preupdate` (`@Inject` at HEAD, also while the feature is off): counts the first 10 s of ticks, and puts
  the 10 Hz limiter and lock fps back once the feature is off.
- `Rand.AdjustForFramerate` (`@ModifyReturnValue`) and `WorldSoundManager$WorldSound.init(Object, int x5, float,
  float, short)` (`@Inject` at RETURN), the overload every other one but the copy ends in.
- When the loop is not as expected (no 100 ms limiter checked from `GameServer.main` before the first tick, or more
  ticks than the limiter allows), the feature logs what differed, puts back 10 Hz and the game's values, and turns
  itself off. Setting the option back to 10 and restarting undoes everything.
