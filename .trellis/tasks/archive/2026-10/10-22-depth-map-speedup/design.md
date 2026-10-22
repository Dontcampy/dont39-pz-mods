# Design: depth-map order (Faster Loading switch `depthMapOrder`)

Requirements: `prd.md`. Evidence and alternatives: `research/depth-maps.md`, `research/reprioritise.md` (design B,
chosen; A rejected because its simulated phase misses 2.5 s on 2 of 5 joins), simulator `research/reprioritise_sim.py`.

## Shape

One post-pass per batch of depth-map tasks, on the main thread, after the game queued them and before the next pump
sorts them into the file queue. It only rewrites `FileTask.priority` of the batch's `TileDepthTextures$LoadTask`s;
the game's insertion (`FileSystemImpl.updateAsyncTransactions`, stable by priority) then produces the new order.

```
TileDepthTextureManager.init()V            (main thread: launch GameWindow.enter, join Core.ResetLua, IngameState.exit)
  HEAD    -> Session.on(DEPTH_MAP_ORDER)? snapshot remainingLoadTasks
  ... game: initModData* / initGameData -> loadDepthTextureImages -> runAsync(LoadTask) -> FileSystemImpl.added
  RETURN  -> DepthOrder.reorder(): read the batch from `added` under the game's lock, check, schedule, set priorities
next frame: updateAsyncTransactions inserts `added` into `pending` by priority  (nothing pumps inside init: research §1)
```

## Components

| Unit | Kind | Role |
|---|---|---|
| `settings/Fix.DEPTH_MAP_ORDER` | enum entry | key `depthMapOrder`, label "depth map order", on by default; options page, `Translate/EN|CN/UI.json`, README row |
| `depth/Schedule` | game-free | Input: the batch in queue order as (instance key, estimated cost), worker count. Output: one priority per task. Greedy list schedule on `workers` virtual workers: a free worker takes the next task (instance's own order kept) of the not-running instance with the most estimated work left; the k-th emitted task gets priority `4 - k`. A batch with < 2 instances is returned unchanged (all 4). Port of `order_greedy(..., largest_first=False)` in `reprioritise_sim.py` |
| `hooks/DepthHooks` | `@Hooks(TileDepthTextureManager.class)` | HEAD and RETURN on `init()V` (JDK-only descriptor, no `@Self`). Thin: gate, snapshot, call `DepthOrder` |
| `depth/DepthOrder` | game-facing | Reads the queue through widened members, runs the checks, calls `Schedule`, writes priorities via `FileTask.setPriority(I)V`, logs once per batch |

## Game members (widened; Faster Loading's first `classtweaker`)

From `research/reprioritise.md` §2 (verify each against the jar while writing the list):
`FileSystemImpl.added`, `FileSystemImpl.lock`, `FileSystemImpl.executor`, class `FileSystemImpl$AsyncItem` + its
`task`, `FileTask.priority`, class `TileDepthTextures$LoadTask` + `textures` + `path`, `TileDepthTextures.tilesetRows`,
`TileDepthTextures.modId` (log only), `TileDepthTextureManager.remainingLoadTasks`. The queue is reached through the
public `GameWindow.fileSystem`. `build.gradle.kts` lists the file (`pzWidening`) and `smokeTest` turns `widener = true`
(spec `access-widening.md`).

## Data flow at RETURN

1. Gate: only when HEAD recorded a batch (switch on, `Session.on` true) and `ModHooks.open()` (spec `zabric-hooks.md`:
   an exit handler that changes state others read).
2. `fs = GameWindow.fileSystem`; not a `FileSystemImpl` → throw.
3. Take the game's lock as the game does (CAS spin on `lock`, `Thread.onSpinWait`), copy every `AsyncItem.task` that is
   a `LoadTask` out of `added` in order, **release in `finally`**. Nothing else happens under the lock.
4. Checks (any failure throws `IllegalStateException` naming the batch, counts and the first offending task):
   - count == `remainingLoadTasks` now − at HEAD;
   - every task's `priority == 4`;
   - every tileset name (from `path`: `DEPTH_<name>.png`, as `LoadTask.call` derives it) is a key of its instance's
     `tilesetRows`.
5. Cost per task = `tilesetRows.get(name)` (rows × 8 tiles of fixed width; 0 rows decodes nothing). Instance key =
   the `textures` object (identity). Workers = `((ThreadPoolExecutor) executor).getCorePoolSize()` (not a
   `ThreadPoolExecutor` → throw).
6. `Schedule` → priorities; `task.setPriority(p)` for each (only this thread reads them, at the next pump).
7. Log once per batch: `depth map order: N tasks of M mods, workers W` (and the largest chain's mod id).

A throw from the handler stops the mod through Zabric's default gate (as `Session`'s revision check does): reported,
never a partial reorder. Priorities are written only after every check passed.

## Compatibility and behaviour

- Same build only: `Session` already rejects any revision but `4a0e9546ec`.
- Order only (R2): each `LoadTask` still runs once; instances are independent; `initMergedTilesets` runs when
  `remainingLoadTasks` hits 0, as before. All depth priorities ≤ 4, so nothing that ran before a depth task runs after
  it. One shift: priority-4 tasks queued after `init` (animations, world map) now go ahead of depth tasks below 4.
- Launch and back-to-menu batches are reordered the same way (output unchanged). Server: `Session` is inactive.
- Effects to watch in game (not simulated): 4 depth decodes at once (native peak up to 4 large maps), ~3× the
  render-thread rate of per-tile depth uploads; AC3's frame-gap bound and `console.txt` catch them.
- 2-worker machines (≤ 4 cores): the schedule uses the real worker count; the gain is smaller.

## Rejected / deferred

- A (`setPriority` counter): no widening, but cannot see chain lengths; game's large `signs_one-off_*` maps end up in
  a serial tail (simulated 2.2-2.7 s).
- Re-sorting `pending` or hooking `updateAsyncTransactions` HEAD: every frame, loses the per-batch count check.
- Lever 1 (prefetch decode at `STBImage.stbi_load`): the only way below the `game` chain (~2 s); later, if needed.

## Rollback

Switch `depthMapOrder = false` in `Dont39FasterLoading.ini` (no build). Code: the fix is self-contained (`depth/`,
`DepthHooks`, the classtweaker, the `Fix` entry).
