# Asset-wait breakdown in Loading Progress (R1)

Parent: `.trellis/tasks/10-21-load-speed-optimization` (Goal, evidence and decisions live there).

## Goal

Make Loading Progress's start report say what the game waits for in `bWaitForAssetLoadingToFinish2` (39.8 s on the
2026-10-21 join), so R2 can be aimed and checked.

## Requirements

- The wait is its own report entry with its exact span (start/end of the game's wait, not the last item line).
- File work during the wait is broken down by task type (count, summed worker time) and by file and owning mod (top N),
  plus the frames the main thread pumped during the wait and the time texture decoders slept on the upload budget.
- The "Animation N / N" line ends when animations finish; it no longer absorbs the wait.
- Loading Progress stays measurement-only: no change to what the game loads or in what order.
- Hooks follow Loading Progress's constraints (JDK-only glue, preloaded, `loading-progress/README.md` "Constraints").
- Candidate hook points and the fields needing widening: `../10-21-load-speed-optimization/research/asset-wait.md`
  §3 and "Recommended R1/R2 approach".

## Acceptance Criteria

- [x] The entry's span matches the DebugLog's `bWaitForAssetLoadingToFinish2` start/end within 50 ms on a join to
  the Linux test server. (1 ms, on the local nosteam server with the live server's mod list rather than the Linux one;
  `verification.md`.)
- [x] The summary lists per-task-type count and worker time and the top files/mods; the CSV has a row per task.
- [ ] Report overhead within run-to-run noise (±1.5 s on the join, as in the 10-20 verification). **Not measured**
  (no runs with the report off; the join's own spread was ±12 s); accepted by the user on 2026-10-23.
- [x] `Progress` unit tests cover the new entry; the build gate passes.
- [x] Loading Progress README and Workshop text updated; version bumped (0.2.0).

## Out of Scope

- Any speed-up (R2, `10-21-asset-wait-speedup`).
