# Verification: PR #24 review fixes

## A. Faster Loading texture encoders (A1-A10)

Date 2026-10-23. PC as in the BC7 task (Ryzen 7 9800X3D, RTX 4090, driver 617.42). Offline only; in game in the
parent's AC5.

### Method

- `qualityCheck` now times each page padded to its power-of-two size as the game's `ImageData` decodes it (`widthHw`
  × `heightHw`, transparent padding; all 21 sample pages become 1024x1024, 11 levels each), PSNR still on the unpadded
  pages (A10). `-PqualityDump=<dir>` writes a SHA-256 of every BC7 level `qualityCheck` defines (284 levels: 21 pages'
  level 0, the 6 odd mip chains, the 2048x2048 and 1501x1099 levels, the GL-state level, and the 21 padded pages with
  all levels) to `<dir>/bc7-blocks.txt`.
- Baseline: the encoder at 02bea37 with only that `QualityCheck` change, `research/baseline/bc7-blocks.txt` (the dump).
  Three runs gave the same hashes.
- After each step: 3 runs of `qualityCheck` (each 7 interleaved runs, medians a page), hashes compared with the
  baseline's. The machine was shared with the other implementer's builds, so absolute GL-thread times move with
  load (DXT5 4.35-5.72 ms); the share of DXT5's time in the same run is the comparable figure.

### Measurements (per 1024x1024 page with all levels; three runs each)

| Step | Hashes | GPU ms | BC7 GL thread ms | DXT5 GL thread ms | BC7 / DXT5 |
|---|---|---|---|---|---|
| Baseline (02bea37 encoder) | | 6.50, 6.69, 6.53 | 1.85, 1.74, 1.63 | 4.53, 4.39, 4.35 | 41, 40, 37 % |
| A1 balanced grid | identical | 4.57, 4.62, 4.61 | 1.89, 1.89, 1.86 | 4.53, 4.56, 4.52 | 42, 41, 41 % |
| + A2 skip exact blocks in modes 1/3/7 | identical | 3.46, 3.45, 3.63 | 1.92, 1.98, 1.95 | 4.45, 4.63, 4.50 | 43, 43, 43 % |
| + A8 barrier bits | identical | 3.68, 3.68, 3.67 | 1.75, 1.87, 1.89 | 4.82, 4.58, 4.49 | 36, 41, 42 % |
| + A3 fewer queries | identical | 3.66, 3.66, 3.68 | 1.77, 1.76, 1.77 | 4.50, 4.53, 4.87 | 39, 39, 36 % |
| + A5 shared memory per entry | identical | 3.00, 3.05, 3.03 | 2.09, 1.80, 1.76 | 5.30, 4.59, 4.50 | 39, 39, 39 % |
| + A5 mode 1/3/7 pixels in their own array | identical | 3.00, 2.95, 2.94 | 1.79, 1.71, 1.61 | 4.67, 4.51, 4.38 | 38, 38, 37 % |
| + A6, A7, A9 | identical | 3.03, 3.04, 2.83 | 1.80, 1.80, 1.79 | 4.61, 4.56, 4.43 | 39, 40, 40 % |
| Final (5 runs, busier machine) | identical | 2.86, 2.87, 3.12, 3.02, 2.90 | 1.79, 2.10, 2.27, 1.84, 1.86 | 4.45, 5.48, 5.72, 4.76, 4.76 | 40, 38, 40, 39, 39 % |

Experiments, not kept in the code:

| Experiment (on the code of the row named) | GPU ms | BC7 GL thread ms | BC7 / DXT5 |
|---|---|---|---|
| A4 bound: levels of ≤ 256 blocks not sent (after A5) | 2.40, 2.56, 2.43 | 1.63, 1.67, 1.64 | 40, 39, 39 % |
| A8 undone (after A6, A7, A9) | 2.88, 2.97, 2.78 | 1.83, 1.89, 1.88 | 40, 40, 38 % |
| Unpadded pages (after A6, A7, A9) | 2.74, 2.68 | 1.45, 1.54 | 33, 35 % |

- **AC1 met.** `groups(n)`: `y = ceil(n / 65535)`, `x = ceil(n / y)`; the shader numbers groups with
  `gl_NumWorkGroups.x`. A 1024x1024 level's mode 1/3/7 passes now dispatch 65,536 groups (32,768 x 2), not 131,070.
  `Bc7EncoderTest` checks `x * y >= count`, `x * y - count < y` and the fewest rows on every level size 1-4096 wide x
  13 heights and around the row boundary. GPU time: 6.5-6.7 → 4.6 ms.
- **AC2 met.** Output bit-identical to the baseline on all 284 levels after every step. GPU time a page 6.50-6.69 →
  2.86-3.12 ms. A2: 4.6 → 3.5 ms (padding and transparent blocks are exact after modes 4-6). A8: neutral (undone:
  2.78-2.97 ms); kept, one barrier call fewer a strip. **A5 kept**: 3.67 → 3.03 ms with the fields per entry, 2.94-3.00
  with the mode 1/3/7 pixels in an array of their own (2 KB → 1.3 KB of shared memory a group in that entry).
- **AC3: A3 met, A4 not implemented.**
  - `glGet*` and `glGetError` a BC7 level, by reading the code (hook path: `Upload.packedRows` 5, error clear and
    check 2, `GlState`, storage checks):

    | | Level 0 of a new texture | Later level | A 1024x1024 page (11 levels) |
    |---|---|---|---|
    | Before | 21 (`GlState` 12, format 1, filter 1) | 22 (`GlState` 12, storage 3) | 241 |
    | After, storage buffers 0-2 unbound | 15 (`GlState` 5, texture binding 1, format 1, filter 1) | 13 (`GlState` 5, texture binding 1) | 145 |
    | After, all three bound | 21 | 19 | 211 |

    The driver-format probe adds 3 once a session.
  - GL-thread time: not higher than before (share 39 % median against the baseline's 40 %), but no lower: once one
    query remains a level, it waits for the driver's copy of the pixels the level before uploaded (5.6 MB a page),
    which is most of the 1.8 ms.
  - **A4 not implemented**: the seven levels of ≤ 256 blocks (64x64 down to 1x1) cost 0.52 ms of GPU a page in all
    (2.95 → 2.43 ms median without them), about 0.07 ms each. A batch still pays at least one level's fixed cost, so
    it can save at most ~0.45 ms, below the 0.5 ms the PRD asks for.
  - Relative gate (BC7 ≤ 40 % of DXT5's GL thread): at the game's padded sizes the share is 37-41 % before and 38-40 %
    after (median 39 %); `qualityCheck` fails on about one run in three by rounding past 40 %. At the images' own
    sizes it is 33-35 %. The padding costs BC7 its full pixel copy, while DXT5 encodes flat padding blocks almost for
    free. The limit is the user's; left at 40 %.
- **AC4 met.**
  - A6: `Encoders.session` probes the driver's format for `GL_COMPRESSED_RGBA` once on a scratch 4x4 texture without
    pixels (the hook leaves that call to the driver) and logs it; `TextureHooks` throws for unpacked rows whenever
    `encoder.glInternalFormat()` differs from it, with no encoder type named. `qualityCheck` checks the probe against
    the format a real page got: 0x83f3 both.
  - A7: `Upload.checkSize` (called by `Upload.compressed`, and by `Dxt5Encoder.encode`, which reads native memory and
    is called directly by tests), `Upload.checkErrors`, `Blocks.columns/rows/count`.
  - A9: the calling thread encodes the last part and the pool has `threads - 1` threads. DXT5 output: `Dxt5EncoderTest`
    (parallel equal to serial) passes; `qualityCheck`'s DXT5 numbers are unchanged (39.26 / 58.15 dB, 98,250 alpha
    pixels off, max 23). DXT5 GL thread unchanged within noise (the pool had a thread for every part before).
- AC9 (`faster-loading`): `./gradlew :faster-loading:check :faster-loading:assembleMod` — see the final report.

## C. Loading Progress asset-wait report (C1-C6)

Date 2026-10-23. Offline only; in game in the parent's AC5.

- C2 decision: **hooked**. `javap -c` of `zombie.tileDepth.TileDepthTextures$LoadTask.call()` (game 42.21
  `projectzomboid.jar`): the call derives the tileset name from its file (`path.toFile().getName()`,
  `replaceFirst("DEPTH_", "")`, `replace(".png", "")`), and under the textures' lock calls the private
  `TileDepthTextures.createTileset(String, boolean)` with it when `tilesets` has no such entry, then returns null.
  `AssetWaitHooks.DepthTileset` hooks it at HEAD (glue: `String`, `boolean` only; `checkJdkGlue` passes); the call
  names its tileset (`FileWork.callNamed`), the task the same name derived from its path, and null results match by
  (hook, name) first, then by hook in order. A map whose tileset another map already made calls nothing and keeps the
  in-order match.
- C1: "Items by kind" leaves out `ASSET_WAIT`; the wait's numbers come only from `AssetWait`. The line CSV is back to
  0.1.0's columns (`flow,step,kind,index,total,name,mod,start_ms,duration_ms`); file tasks go to
  `<report>-files.csv` (`type,name,mod,queued_ms,done_ms,worker_ms,failed`), written for every load with an asset
  wait and pruned with its load. The archived `10-22-depth-map-speedup/research/timeline.py` and
  `reprioritise_sim.py` read the files CSV since the check (`done_ms` replaces `start_ms + duration_ms`, `type`
  replaces `task_type`).
- C3: `FileWork.waiting` is true only while tracking (report on, world loading); turning tracking off ends it, so the
  throttle hooks idle with the report off.
- C4: each call's time carries the pump count when it ended; one still unmatched after 3 pumps (the game takes every
  done task at its next pump) is dropped, which releases a result of a task cancelled while it ran and keeps it from
  shifting the in-order matches.
- C5: an unmounted pack names its task `packName/imageName` (the game's call then fails, so the task shows failed); a
  pack's file is resolved once per world load (`FileWork.packFile`). A file system other than `FileSystemImpl` still
  throws (a game mismatch).
- C6: `Report.ranked` (total once per group, largest first, then by name) serves "Time per mod", "By type" and
  "Worker time per mod".
- Tests: `FileWorkTest` +4 (matching by call name, a call cancelled mid-run forgotten and not shifting the next match,
  pack file cache per load, no counting without tracking), `ProgressTest` (the wait's hooks idle with the report off),
  `ReportTest` +2 and updated (one source for the wait, line CSV columns, files CSV, ranking ties),
  `ReportsTest` +1 and updated (files CSV written, clashes and pruning). `./gradlew :loading-progress:check
  :loading-progress:assembleMod`: pass, 135 tests.

## B. Faster Loading structure and shared settings (B1-B5)

Date 2026-10-23. Offline only; in game in the parent's AC5.

- B2 mechanism: `mod-settings/` (no Gradle project) holds `Switches.java` (ini reader/writer generic over an enum of
  `Switches.Key`), `Switches.lua` (the options page: `Switches.page{ id, name, file, switches }`) and
  `SwitchesPage.java` (the B1 check, test only). `buildSrc/.../pz-switches.gradle.kts` copies the Java files into
  `build/generated/sources/switches/{main,test}/java` with the package line replaced by `<javaPkgName>.settings` (the
  build fails if the file's package line is not the expected one) and ships the Lua unchanged as
  `42/media/lua/client/<modId>_Switches.lua`; each mod's `_Options.lua` `require`s it by that name and passes its
  table. The Lua needs no substitution: only its file name must differ, since `require` names are global. Each mod
  keeps a thin `Settings` (Faster Loading: `Fix implements Switches.Key`; Loading Progress: a package-private
  `Settings.Switch` enum). The header line comes from the mod's name in both Java and Lua.
- Behaviour: ini text unchanged for both mods (LP's `theTextIsAsBefore` pins the 0.2.0 text). Loading Progress gains
  the missing-key upgrade (missing key → its default, file rewritten with the values read kept; Lua page shows the
  default instead of refusing the file): `aFileWithoutAKeyKeepsItsValuesAndGetsTheKey`, `aFileWithEveryKeyIsLeftAsItIs`.
  One message wording changed in Faster Loading: "cannot add the keys of the newer fixes" → "cannot add the missing
  keys".
- Jars: `Dont39FasterLoading.jar` has `com/dont39/pz/fasterloading/settings/Switches{,$Key}.class`,
  `Dont39LoadingProgress.jar` has `com/dont39/pz/loadingprogress/settings/Switches{,$Key}.class`; neither has a
  `modsettings` package or `SwitchesPage`. Assembled Lua: `Dont39FasterLoading_Switches.lua` and
  `Dont39LoadingProgress_Switches.lua` in `42/media/lua/client/`, byte-identical to `mod-settings/Switches.lua`.
- Lua run: both options files with the shared module, run in the game's own Kahlua (`projectzomboid.jar`, scratchpad
  harness with mocked `PZAPI.ModOptions`, `getFileReader`/`getFileWriter`, `DebugType`): page id and text ids as
  before; a file with one key shows the others on; apply writes text identical to `Settings.text` (same header,
  comments, order); an unchanged apply writes nothing; an unknown key and a missing file show the defaults.
- B1: `SettingsTest.theOptionsPageListsTheFixes` (FL) and `theOptionsPageListsTheSwitches` (LP) check the page's
  id/name/file, each switch's key and description in order against the Java keys and `Switches.header()`, and that
  each language's `UI.json` has exactly `UI_<id>_Options` plus each `<text>` and `<text>Tooltip`.
  `theOptionsPageCheckFindsADifference` (FL) shows it fails on swapped keys, a changed description, a renamed key and
  a tooltip missing in CN.
- B3: `com.dont39.pz.fasterloading.Mod` holds `MOD_ID`/`PREFIX` (JDK types only; `checkJdkGlue` passes with it in the
  glue package; README constraint updated). `depth/DepthOrder` moved to `hooks/DepthOrder` (package-private
  `queued`/`reorder`), with the widening state from `Session`; `depth/` keeps only `Batch` and `Schedule`, which
  import nothing of the mod. Nothing uses `Session.PREFIX` any more; it is gone.
- B4: `FolderWalk` keeps `File.list()`; a listed entry takes one `Files.readAttributes(NOFOLLOW_LINKS)` (folder,
  file), except a link/other reparse point or a name ending in a dot or space, where `File.isDirectory()` decides and
  the canonical lookup runs as before; the root keeps the game's `isDirectory()`. One `getAbsolutePath` per file.
  `FolderWalkTest` 8 tests (1 skipped, symbolic links not allowed here, as before): equal to vanilla on the game's and
  every Workshop mod's Lua folders and on the generated trees, lookups counted as before.
- B5: `Fix`'s Javadoc says `TEXTURE_BC7` is an option of `TEXTURE_COMPRESSION`.
- Build gate: `./gradlew :faster-loading:check :faster-loading:assembleMod :loading-progress:check
  :loading-progress:assembleMod`: pass (FL 74 tests, 1 skipped; LP 138 tests), configuration cache stored.

## Check

Date 2026-10-23, after A, B and C, on the whole uncommitted change.

- Fixed:
  - The archived `10-22-depth-map-speedup/research/timeline.py` and `reprioritise_sim.py` read `<report>-files.csv`
    (`type`, `queued_ms`, `done_ms`; rows without `queued_ms` left out, as the old rows without `start_ms` were);
    `timeline.py` no longer opens the summary beside its argument. `timeline.py` checked on a hand-made files CSV.
    `quality-guidelines.md` item 10 names the files CSV.
  - `error-handling.md`'s GL state row: the unpack buffer is no longer saved, only put back to the 0 the hook checked.
  - `BC7Encode.comp`'s list of changes from the HLSL lacked A5 (shared fields per entry, mode 1/3/7 pixels in their
    own array).
  - This file's notes on `Session.PREFIX` (removed) and on the archived scripts (ported).
- Reviewed without change: the chain memo (reset before each level, kept only after every strip of a level was
  filled, any other texture, level or size falls back to the queries), `GlState`'s skipped range queries, the driver
  format probe (its `glTexImage2D` without pixels is not a call the hook takes, so it does not re-enter the encoder),
  the shader's early returns (whole group, uniform, before any barrier), `FileWork`'s pump age (the game's
  `updateAsyncTransactions` checks all of at most 16 tasks at the workers each pump, so a done task's result is taken
  at the next pump; 3 pumps leave a margin for a call ending just before its future completes), the depth-map name
  match, `FolderWalk`'s attribute read (links, other reparse points and names ending in a dot or space keep the
  game's check and lookup), the generated settings sources and both jars (own `settings.Switches`, no shared
  package, `<modId>_Switches.lua`), Loading Progress's missing-key upgrade, DXT5's pool of `threads - 1` (only used
  with 2 or more parts).
- Build gate `./gradlew :faster-loading:check :faster-loading:assembleMod :loading-progress:check
  :loading-progress:assembleMod`: pass (Faster Loading 74 tests, 1 skipped; Loading Progress 138).
- `qualityCheck -PqualityDump`, twice (before and after the shader comment): pass, 284 BC7 levels, hashes identical
  to `research/baseline/bc7-blocks.txt` both times. GL thread a page BC7 1.85 / 1.82 ms, DXT5 4.66 / 4.59 ms, 40 %
  both times (limit 40 %, passed by rounding); GPU 3.00 / 2.94 ms a page.

## GL-thread gate (user decision, 2026-10-23)

At the padded game size BC7's GL-thread share of DXT5 sat at 37-41 % before this task and 38-40 % after, so the 40 %
gate failed about one run in three. The user kept the 40 % limit on the timing it was set on, the pages' own sizes;
`qualityCheck` now times both and gates only the first. Run after the change: own sizes BC7 1.33 ms vs DXT5 4.23 ms
(32 %, gate), GPU 2.92 ms; padded 1.64 vs 4.45 ms (37 %, printed), GPU 2.89 ms; BC7 hashes identical to
`research/baseline/bc7-blocks.txt`; build gate of both mods passes.
