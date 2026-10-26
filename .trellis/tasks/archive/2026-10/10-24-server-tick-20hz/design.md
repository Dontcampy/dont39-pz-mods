# Design: server tick rate option

Evidence for every game detail below: [research/findings.md](./research/findings.md). The prototype this builds on:
[research/prototype/](./research/prototype/).

## 1. Shape

A new feature module `server-tick-rate` (package `com.dont39.pz.servertickrate`), bundled in Dont39 All-in-One after
`network-status`. Facade `ServerTickRate : Feature("server-tick-rate", "Dont39AllInOne")`; hooks in `TickHooks.kt`.
No target mods.

Option: `Dont39AllInOne_ServerTickRate.TicksPerSecond`, integer 10-20, default 10, on the All-in-One sandbox page
(`media/sandbox-options.txt` and EN/CN `Sandbox.json`, as `mod-sync`). Read once at the first `setLockFPS` from
`GameServer.main`, which runs after `startServer` loaded the save's sandbox options (`main@2650` < `@2709`). A
change takes a server restart.

`problem()`: idle on a client (`GameServer.server` false) and on a hosted game (`GameServer.coop`), as `ModSync`.

## 2. Applying the rate

State: `pending` (the option's rate until the limiter is found), `rate` (10 until applied), `mainThread`, the found
limiter.

1. `PerformanceSettings.setLockFPS` `@ModifyVariable` HEAD. Once, when the argument is 10 and the caller is
   `GameServer.main` (one `StackWalker` walk, only on this path): read the option. At 10, log "off" and return 10.
   Otherwise remember `mainThread`, set `pending = N`, return N. The fps estimate at `main@2721` then starts at N.
2. `UpdateLimit.Check` `@Inject` HEAD with `@Self`. Returns at once unless a rate is pending, the call is on
   `mainThread` and the limiter's `getDelay()` is 100. The first such call whose caller is `GameServer.main` is the
   loop's limiter (`main@3495`, the first `Check` in `main`): `setUpdatePeriod(1000 / N)`, `rate = N`.
   `UpdateLimit.<init>(J)V` cannot be hooked alone (ZombieBuddy's matcher also takes `<init>(JJ)V`), hence `Check`.
3. `ServerMap.preupdate` `@Inject` HEAD, `always = true` (runs while the feature is off), on `mainThread` only:
   - the feature is off (disabled by any error): restore (step 4) once;
   - a rate is still pending at the first tick: no 100 ms limiter was checked from `main`, a mismatch;
   - else count ticks for 10 s and log the measured rate. Above the limiter's rate + 5%: mismatch (something else
     drives the ticks). Below − 5%: an error line naming the load as the cause (ticks overran the period), the rate
     stays; the counter stops after the line.
4. Mismatch: `disable(...)` with what differed (`Feature.disable`;
   [error-handling.md](../../spec/mod/error-handling.md)), then restore: `setUpdatePeriod(100)` on the found limiter,
   `setLockFPS(10)`, `rate = 10`.

Compensations read `rate`, so they are vanilla until step 2 succeeded and after a restore; their hooks are gated, so
they are vanilla as soon as the feature is off.

## 3. Compensations

- `Rand.AdjustForFramerate(I)I` `@ModifyReturnValue` with the argument: with `rate > 10` return
  `(int)(n * rate / 30f)`, the game's client formula with `rate` as the fps (vanilla server: `n * 0.33333334f`). All
  48 game call sites use this static method; none in vanilla Lua.
- World sound lifetime: only `WorldSoundManager$WorldSound.init(Object, IIIII, F, F, S)` stores `life = 16`; the
  other overloads end in it, and `init(WorldSound)` copies an already scaled life. `@Inject` RETURN with `@Self` on that
  overload scales `life` to `16 * rate / 10`, rounded.
- Not compensated, stated in the README: `IsoPhysicsObject` flight time, the trailer drop roll, mods counting
  `OnTick` calls; `OnTick` handlers cost twice the CPU per second.

The scaling helpers are pure functions in their own file with unit tests (`pz-junit`, as `mod-sync`).

## 4. Logging

Lifecycle lines through `ServerTickRate.log`: off/on with N and the period, the measured-rate line, errors. The
prototype's `TickProbe` is not shipped; a debug-level line logs the `AdjustForFramerate` scaling once at apply
(AC3). Nothing per tick ([logging-guidelines.md](../../spec/mod/logging-guidelines.md)).

## 5. Compatibility and rollback

- Only the dedicated server changes; clients need nothing and assume no server cadence.
- Another mod hooking `setLockFPS`, `UpdateLimit.Check` or `Rand.AdjustForFramerate`: arguments and return values
  are rewritten, never cancelled ([zabric-hooks.md](../../spec/mod/zabric-hooks.md)).
- Rollback: option back to 10 and restart; or remove the module from the feature list.

## 6. Cost (measured, research/findings.md)

8 / 16 fake players (~700 / ~1450 zombies): tick work per second +80-90% / +63%; process CPU 0.24 → 0.29 /
0.49 → 0.58 cores; outgoing bytes flat. Per-tick work grows with players (6 → 15 ms here), and the 50 ms budget is
reached at about half the load of 10 Hz. The relay of zombie updates and server-side action results waits
0-50 ms instead of 0-100 ms; map chunks stream twice as fast per player.
