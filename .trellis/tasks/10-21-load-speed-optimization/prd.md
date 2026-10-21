# Load speed optimization

## Goal

Cut Project Zomboid load time for this setup (B42.21 `4a0e9546ec`, ~245 mods, 15 Java mods, MP joins): first
measure the join's unexplained asset wait, then ship measured, switchable fixes in a new client-only mod. Loading
Progress's reports are the before/after evidence.

## Background

### Where the time goes (Loading Progress reports, 2026-10-21)

Reports: `C:\Users\dontc\Zomboid\Lua\Dont39LoadingProgress\` (summary `.txt` + per-line `.csv`).

- **MP join** `2026-10-21_21-11-04_start.txt`: 122.2 s (13.7 s waiting in the menu). Connecting 17.3 s, Loading mods
  6.2 s, Clothing/tiles 6.4 s, Lua 6.7 s, mods' start-up code 2.6 s, **Loading the world 55.7 s**, Entering the
  world 6.6 s. Lua events OnGameStart 4.7 s, OnGameBoot 2.0 s, OnLoadMapZones 2.7 s; mod tile definitions 2.3 s.
- **The asset wait**: `bWaitForAssetLoadingToFinish2` start 21:10:15.863 → end 21:10:55.621 = **39.8 s**
  (`Logs/2026-10-21_20-55_DebugLog.txt` L16727-16742), a third of the join. The report labels it "Animation 2291 /
  2291" (start CSV row 11724), but all animations were loaded at its first poll; the wait ends on
  `!isLoadingAnimations() && !fileSystem.hasWork()` (`GameLoadingState.update L863-872`), so it was other file tasks.
  10-17 measured it at 16.9 s; vanilla's is ~3.6 s (PZ_Optimization `docs/plan-game-load.md`).
- **Launch → menu** `2026-10-21_20-56-19_launch.txt`: 51.1 s (other runs 39.8-41.6 s). Loading mods 22.7 s (Java
  mods 8.2 s), Lua 9.1 s, Models and animations 5.2 s, Tiles and furniture 4.3 s, Scripts 4.3 s.

### Research (details and citations under `research/`: `asset-wait.md`; `launch-items.md` indexes `zabric-hook-model.md`, `strip-comments.md`, `lua-loading.md`, `deferred-items.md`)

- **File queue**: 4 worker threads, ≤ 16 tasks in flight, refilled once per main-thread frame
  (`FileSystemImpl L40-41`, `updateAsyncTransactions L244-305`); depth maps decode under one lock; texture decoders
  sleep above 50 MB awaiting upload. Prime suspect, unmeasured: physics shapes, a jassimp import per script shape
  queued by `Bullet.startLoadingPhysicsMeshes` just before the wait (`runInner L391`). Viewpoint caps the frame rate
  on the loading screen and runs its own pool.
- **Hook model**: Zabric hooks run at HEAD/RETURN/FINALLY, rewrite arguments/return values and can cancel a call. A
  client-only mod is never in a server's mod list, so to act on joins it must be preloaded (`Zabric-Hooks: launch`);
  preloaded hooks bind JDK types only, no `@Self`, Java only, default gate (`zabric/README.md:28`,
  `CheckJdkGlue.kt:74-98`).
- **`ScriptParser.stripComments`**: static String→String, quadratic; ~2 s per flow for vanilla `tileGeometry.txt` plus
  mod tile files. Its quirks (markers inside strings stripped, two-level nesting, stray `*/`, `/*/`, markers formed
  across a cut) must be reproduced; the real method is the test reference.
- **Lua**: per-file compile+run is ~2.9 s; **~4.8 s (launch) / ~3.8 s (join) is the folder walk** in
  `LoadDirBase(String,boolean)` before the first file: `searchFolders(URI, File)` L1266-1279 does a
  `getCanonicalFile()` per entry and `ZomboidFileSystem.getRelativeFile` per `.lua` file (Viewpoint patches the
  latter). Cause inferred from bytecode, not profiled. Precompile hands in Kahlua types (`LuaCompiler.loadis`), so it
  cannot be a preloaded hook; its share of the 2.9 s is unmeasured. No MP checksum impact.

### PZ_Optimization

`E:\Dev\Project\pz\PZ_Optimization` (Diego Villalobos, `github.com/xD3I/PZ_Optimization`) has no license file: its
code is not copied. We reimplement from our own reading of the 42.21 jar and use its docs for measurements and
pitfalls only (rejected 14 threads/256 MB for a 5.05 s frame; too many loader threads slow the serial path; an
unkeyed jassimp cache broke body-mesh mods).

## Decisions (user, 2026-10-21)

- Scope: "我要优化这些", then trimmed as recommended: R1, R2, R3, R4 below. Dropped: `Item.DoParam` switch (copy of
  ~365 branches, high risk). Deferred: FMOD async (≤ 1.4 s, invisible to Loading Progress, thread-safety unverified)
  and texture pack index (≤ 0.45 s, launch only).
- Delivery: the fixes ship as a **new client-only Zabric mod** in this repo, one switch per fix; the measurement
  (R1) goes into **Loading Progress**, which stays measurement-only.

## Requirements

- **R1 Asset-wait breakdown (Loading Progress).** The join/start report shows the asset wait as its own row with
  its exact span, and breaks the file work done during it down by task type (count, worker time) and by file/mod,
  plus frames during the wait. The misleading "Animation N / N" row no longer absorbs the wait.
- **R2 Asset-wait speed-up (new mod).** Cut the wait using what R1 shows. Candidate levers: wider pool and in-flight
  budget during the wait only, depth-map decode outside its lock, a keyed jassimp result cache for physics shapes.
  The lever set and target are fixed in R2's own planning after R1 data exists.
- **R3 Linear comment stripping (new mod).** Replace `ScriptParser.stripComments` with a linear version whose output is
  identical for every input, on launch, join and Lua reload.
- **R4 Lua folder walk (new mod).** Cut the time from `LoadDirBase` to the first Lua file while producing the same
  `loadList` (content and order) and keeping other mods' patches on `getRelativeFile` in effect. Lua precompile is
  added only if a measurement shows a compile share worth it (≥ 1 s) and a preloadable hook point exists; otherwise it
  is recorded as not worth doing.
- **New mod basics.** Client-only, preloaded, Java hooks. One switch per fix in an ini in the game cache's `Lua`
  folder, on by default, also on its mod options page. With a switch off the game behaves exactly as vanilla there.
  A hook that finds the game or a target not as expected reports it and disables the mod (no silent fallback),
  per `.trellis/spec/mod/error-handling.md`.

## Acceptance Criteria

- [ ] AC1 (R1): a join report on the test server shows an "asset wait" entry whose span matches the DebugLog's
  `bWaitForAssetLoadingToFinish2` start/end within 50 ms, with per-task-type counts and worker time and the top
  files/mods; the report's own overhead stays within run-to-run noise (±1.5 s).
- [ ] AC2 (R2): planned in its child task once AC1 data exists; target set there.
- [ ] AC3 (R3): a unit test compares the replacement against the real 42.21 method on the vanilla and every installed
  mod's script/tile-geometry files plus the quirk cases, byte for byte; in-game, the stripComments known-call time
  drops by ≥ 1.5 s per launch and per join (median of 3 runs on vs off).
- [ ] AC4 (R4): `loadList` equals vanilla's (dumped with the switch on vs off) on launch and join; `LoadDirBase` →
  first file time drops by ≥ 50 % (median of 3 runs).
- [ ] AC5: with every switch on, launch and an MP join to the Linux test server complete with no new errors in
  `console.txt`, no checksum mismatch, and the 15 Java mods loaded; with every switch off, timings match the baseline
  within noise.
- [ ] AC6: the build gate passes (`.trellis/spec/mod/quality-guidelines.md`).

## Out of Scope

- `Item.DoParam` switch (dropped); FMOD async and texture pack index (deferred).
- Other mods' own slow Lua events (OnGameStart, OnGameBoot, OnLoadMapZones) and Java mod patch costs.
- JVM/GC flag changes and installing PZ_Optimization.
- Dedicated-server load time.
