# Verification: Loading Progress 0.2.0, asset-wait breakdown (R1)

Date: 2026-10-21/22. Same runs as `../10-21-faster-loading-strip-comments/verification.md` (local nosteam server,
the live server's full mod list, 7 joins). Reports: `../10-21-asset-wait-speedup/research/join-*.txt`.

## Offline

- `./gradlew :loading-progress:check :loading-progress:jar`: pass (unit tests, checkJdkGlue, checkLoaderRefs).

## In game

- `:loading-progress:smokeTest`: pass; `access widener: ok (23 directives)`, so every widened class loaded after
  Zabric's widener.
- **AC span within 50 ms: met.** Warm-up join: report 23:59:09.328 → 23:59:57.042, DebugLog
  `bWaitForAssetLoadingToFinish2 start` 23:59:09.328, `end` 23:59:57.043 (1 ms).
- The summary lists per-type counts and worker time and the slowest tasks; the CSV has a `file_task` row per task.
- No Loading Progress errors or self-disables in any run.

| Join | Wait | Tasks at first frame | Worker busy | Decoders waiting on the upload budget |
|---|---|---|---|---|
| warm | 47,716 ms | 4,669 | 189,632 ms (99 %) | 100,458 ms |
| off1 | 65,509 | 5,072 | 260,619 (99 %) | 157,940 |
| on1 | 58,326 | 5,057 | 231,840 (99 %) | 139,736 |
| off2 | 59,619 | 5,063 | 237,037 (99 %) | 146,688 |
| on2 | 50,181 | 4,993 | 199,584 (99 %) | 115,544 |
| off3 | 49,488 | 4,980 | 196,725 (99 %) | 119,795 |
| on3 | 74,076 | 5,283 | 294,556 (99 %) | 206,042 |

By type (warm-up): `FileTask_LoadPackImage` 137,349 ms / 1,361 tasks, `FileTask_LoadAnimation` 27,599 ms / 2,262,
`TileDepthTextures$LoadTask` 23,874 ms / 900, the rest < 0.5 s each. Physics shapes, the research's suspect, are
81 ms / 30 tasks.

**Finding for R2:** the 4 workers are saturated, and about 60 % of their time is texture decoders sleeping because
more than 50 MB of decoded textures await upload. The wait is bound by the upload path (main/render thread, once per
frame), not by decoding or animations.

## Not met / open

- **Overhead (±1.5 s) not isolated:** no runs with the report off; the join spread itself is ±12 s. Accepted as not
  measured by the user (2026-10-23) when the task was archived.
- ~~Per-mod attribution gap~~ fixed: the first runs put 169 s of 190 s worker time in "in no mod's folder" because
  pack images were named `pack/image`. Pack images are now named by the `.pack` file the game opens, and the catch-all
  is split. Re-run (`join-attr1`, same setup): `access widener: ok (28 directives)`; of 294,586 ms worker time,
  melos_tiles_for_miles_pack 118,557 ms (964 tasks), tikitown_tiles 30,529, ThermoNewburbs 16,403, …; the game's own
  66,419 ms (2,756 tasks); named without a path 0.
