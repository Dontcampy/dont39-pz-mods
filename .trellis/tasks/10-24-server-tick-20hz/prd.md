# Server tick rate option (20 Hz)

## Goal

A server owner can run the B42 dedicated server's world tick at 20 Hz instead of the hardcoded 10 Hz, as an opt-in
Dont39 All-in-One feature. Server-side results (item transfers, timed actions, relayed zombie updates, chunk
streaming) arrive sooner, and game behaviour per real second stays as at 10 Hz wherever the game assumed 10 Hz.

## Background

- `GameServer.main` hardcodes the tick (`UpdateLimit(100)`, `setLockFPS(10)`); no server option controls it.
- Research, prototype and measurements: [research/findings.md](./research/findings.md). In short: zombies, player
  movement and vehicles are simulated by clients and relayed on receipt or on real-time limits, so bandwidth stays
  flat; game time is scaled by measured frame time and stays correct; per-tick work doubles. A few places assume
  10 Hz and would change behaviour (per-tick random rolls via `Rand.AdjustForFramerate`, world sound lifetime,
  thrown-object flight time, a trailer drop roll, mods that count `OnTick` calls).
- Measured with 8 and 16 fake players (~700 and ~1450 zombies): process CPU +20% (0.24 → 0.29, 0.49 → 0.58 cores),
  tick work per second +63-90%, outgoing bytes unchanged, game-time rate unchanged. A 50 ms tick budget halves the
  headroom before ticks run back to back.
- Asked by the user while planning `10-24-mp-sync-loss-recovery` (independent of the tick rate).

## Decisions

- D1 (proposed) Opt-in, default 10 Hz: mods' `OnTick` work doubles and tick-counting mods change speed, which a server
  owner must choose knowingly.
- D2 (proposed) A sandbox option on the All-in-One page, integer ticks per second 10-20, default 10 (off).
- D3 (proposed) Compensate the game's own 10 Hz assumptions that change gameplay: `Rand.AdjustForFramerate`, world
  sound lifetime, `setLockFPS`. Leave thrown-object flight time and the trailer drop roll as documented differences.
- D4 (proposed) Dedicated servers only; a hosted (co-op) game keeps 10 Hz.

## Requirements

- R1 (D2) With the option at N > 10 the dedicated server ticks N times per second from the first tick, and lock fps is
  N; at 10 nothing is hooked into the loop and the game behaves as vanilla.
- R2 (D3) At N, per-tick random chances per real second equal vanilla's at 10 Hz (`Rand.AdjustForFramerate` scaled by
  N/10 on the server), and a world sound lives as long in real time as at 10 Hz.
- R3 The feature checks it found the game's tick limiter; when the loop does not match, it logs an error naming what
  differed, leaves or restores 10 Hz and the vanilla constants, and turns itself off. Ten seconds after start it logs
  the measured tick rate.
- R4 (D4) On a client or a hosted game the feature is idle with a reason line.
- R5 The Workshop text and README say what the option does, what it costs (CPU per tick, mods' `OnTick` twice per
  second) and the known differences.

## Acceptance criteria

- AC1 (R1) Linux test server at option 20: log line with the rate; after 10 s the measured rate line shows 20 ± 0.5
  ticks/s; with 8 fake players, game-time rate (`GameTime.getMultiplier` × ticks/s) within 1% of a 10 Hz run.
- AC2 (R1) Option 10: no rate change, the feature logs it is off, no hook state changes.
- AC3 (R2) Unit tests for the scaling (N = 10 returns the vanilla value; N = 20 doubles the argument before the 1/3);
  on the server, a debug line (debug level) shows `AdjustForFramerate(30)` returning 20 at 20 Hz.
- AC4 (R3) A forced mismatch (test hook with a different expected period) logs the error, the server ticks at 10 Hz,
  and the feature is disabled.
- AC5 (R4) Client `smokeTest` passes with the feature idle.
- AC6 A real game client plays on the test server at 20 Hz for 10 minutes (walk, fight zombies, move items, drive)
  without errors or visible stutter; reported by the user or stated as not done.
- AC7 `./gradlew build` passes.

## Out of scope

- Rates above 20, or a default-on rate.
- Compensating `IsoPhysicsObject`'s fixed fps, the trailer drop roll, or mods' tick counting.
- The client's frame rate; the co-op host.
- Workshop upload (the user uploads).

## Open questions

- Q1 D1/D2: opt-in integer 10-20 (proposed), an on/off 20 Hz switch, or default-on?
