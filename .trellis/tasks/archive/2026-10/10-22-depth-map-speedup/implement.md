# Implementation plan: depth-map order

Requirements `prd.md`, design `design.md`. Gradle: `JAVA_HOME=~/.jdks/graalvm-ce-25.0.2`.

## Checklist

1. **Schedule (game-free, tested first).** `depth/Schedule` as in `design.md`. Tests (AC1): two and more instances
   alternate; the instance with the most work left starts first and its large tasks are not left in a serial tail
   (a fixture shaped like the measured join: 216/144/116/102/... with a few heavy tasks late in the big chain);
   all priorities ≤ 4 and distinct per position; instance order within each chain kept; 0- and 1-instance batches
   unchanged; worker counts 2 and 4. Cross-check: the port gives the same order as `research/reprioritise_sim.py`'s
   `order_greedy` on one generated batch (expected order checked in as test data).
2. **Widening.** Faster Loading's classtweaker with the members in `design.md` (verify each name/descriptor with
   `javap`); `build.gradle.kts` lists it; `smokeTest` `widener = true`.
3. **Hook and fix.** `depth/DepthOrder` (lock copy with release in `finally`, checks, priorities, one log line),
   `hooks/DepthHooks` (HEAD/RETURN on `TileDepthTextureManager.init()V`, `ModHooks.open()` at RETURN), `Fix
   .DEPTH_MAP_ORDER` (`depthMapOrder`), options page, `Translate/EN|CN/UI.json`, `Settings` tests for the new key.
   Unit-test the game-free parts of the checks (AC5): name from `DEPTH_<name>.png`, count/priority mismatch messages.
   README: fix row, widening note, Checks. Version 0.3.0 (`build.gradle.kts`, `mod.info`).
   Confirm `Session.on` is safe at the launch's `init` (game log and cache folder up by `GameWindow.enter`).
4. **Build gate (AC6).** `./gradlew :faster-loading:check :faster-loading:assembleMod`, then
   `:faster-loading:smokeTest` (twice after a fresh preload registration); the smoke log shows the widener ok and the
   launch batch's `depth map order:` line.
5. **In-game series (AC2-AC4).** Tooling from R2 (`archive/2026-10/10-21-asset-wait-speedup/implement.md` step 6):
   `setup.ps1`, `server.ps1`, `run.ps1`, `series.ps1` switching `depthMapOrder` in `Dont39FasterLoading.ini` (keep
   `textureCompression = true` and game `textureCompression=true` throughout), `teardown.ps1`, LPTestDriver.
   - Back up first: `~/Zomboid/mods/default.txt`, `~/.zombie_buddy/config.json`, `~/Zomboid/options.ini`, the Workshop
     staging folders (`Dont39LoadingProgress`, `Dont39FasterLoading`, Zabric), `~/Zomboid/Lua/Dont39LoadingProgress/`
     (the user's reports), `~/Zomboid/Lua/Dont39FasterLoading.ini`. Check the mod list before the runs (the launcher
     rewrites it).
   - Stage Faster Loading 0.3.0 (`deployWorkshop`; `git lfs pull` first in this worktree), preload registered.
   - Warm-up join, then off/on alternating, ≥ 3 each. Record each run's LP numbers in
     `verification.md` before LP prunes the reports (raw reports are not committed).
   - Per run: `python -I research/timeline.py <start.csv>` (depth phase, phases by type), join total, asset wait,
     largest frame gap, depth tasks done / failed, `console.txt` errors, the launch report's total.
   - Screenshot the same spot on one on and one off run.
6. **Restore** everything from step 5's backups; teardown removes the mod copies.
7. **Record** `verification.md` (offline, in game, medians, timeline table on vs off, not done). If the depth phase
   is still the wait's tail above ~2 s, note it for lever 1.
8. **Check and spec.** `trellis-check`; spec update (`access-widening.md`: Faster Loading's first list;
   `zabric-hooks.md` if the queue post-pass pattern is new there).

## Validation commands

```bash
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew :faster-loading:check :faster-loading:assembleMod
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew :faster-loading:smokeTest
python -I .trellis/tasks/10-22-depth-map-speedup/research/timeline.py <LP start.csv>
```

## Risky points

- Holding the game's queue lock: copy only, release in `finally`; a bug here hangs the file queue (smokeTest would
  show the launch stall).
- Widened classes loaded before the widener keep their access: smokeTest with `widener = true` must pass, and the
  launch batch must log its line.
- Render-thread load from 4 concurrent depth decodes: watch AC3's frame gaps; if they grow, report before tuning.
- Why the join re-queues `game`'s 216 maps is unexplained (`research/reprioritise.md` §1); the fix handles either
  case, but note what the runs show.

## Rollback points

- Commit after step 4 (fix complete, unit-tested, smoke-tested) and after step 7 (verified), when the user asks. The
  switch turns the fix off without a build.
