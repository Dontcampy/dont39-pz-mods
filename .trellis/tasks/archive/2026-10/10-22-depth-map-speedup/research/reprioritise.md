# Research: reprioritising the depth-map tasks (lever 2)

- **Query**: compare two hook designs that interleave the `TileDepthTextures$LoadTask`s of different instances in the
  file queue. A is a counter on `FileTask.setPriority`. B is a post-pass over the queue at `TileDepthTextureManager.init`
  RETURN. Questions 1-5 are from the task brief.
- **Scope**: internal. `javap -c -p -l` of `projectzomboid.jar` 42.21 `4a0e9546ec`, Loading Progress CSVs of the five
  joins of 2026-10-22, and the depth PNGs on disk. Citations are `Class.method @offset` (bytecode) or `Lnnn`.
- **Date**: 2026-10-22
- **Script**: `research/reprioritise_sim.py <start.csv> <workshop content/108600>` (run with `python -I`).

## 1. `FileSystemImpl` internals and threads

**Queue path**

1. `runAsync(FileTask)` (L236-239) wraps the task in `AsyncItem{id, task, future = new FutureTask(task)}`.
2. It then calls the private `runAsync(AsyncItem)` (L214-231):
   - spins on `lock.compareAndSet(false, true)` with `Thread.onSpinWait`;
   - sets `id = lastId++`;
   - appends to **`added`**;
   - calls `lock.set(false)`.
   - The thread check at @0-19 (`gameThread` / `GameLoadingState.loader`) only stores a local. It has no effect.

**`updateAsyncTransactions()V`** (L244-305):

1. **Reap** (@0-134): over the first `min(inProgress.size(), 16)` items, each `future.isDone()` item is removed from
   `inProgress`, then `handleResult(get())` and `task.done()` run. `done()` is where `LoadTask` calls
   `finishedLoadTask`.
2. **Insert** (@137-279), under `lock`. For each item of `added` in order:
   - `insertAt = pending.size()`;
   - it scans `pending` and stops at the first item with `item.task.priority > pending[j].task.priority`;
   - it inserts there, then clears `added`.
   - The result is a stable sort by priority, highest first, FIFO within a priority.
   - **`FileTask.priority` is read only here**: the new item's value, and the values of items already in `pending` as
     comparison keys. Changing an item's priority after it is in `pending` does not move it. It only changes where
     later items land.
3. **Dispatch** (@288-371): `canAdd = 16 - inProgress.size()`. Items are taken from the head of `pending`; cancelled
   ones get `done()`. The rest are appended to `inProgress` and `executor.submit(future)`.
   - The executor is `Executors.newFixedThreadPool(availableProcessors() > 4 ? 4 : 2)` (constructor @139-157).
   - Its queue is FIFO, so of the 16 in flight, 4 run and 12 wait in submit order.
   - **On ≤ 4 cores there are only 2 workers.**

**Who pumps**

- A scan of every `zombie/**` class for the string `updateAsyncTransactions` finds only these callers:
  - `GameWindow.logic` @681 (every frame, after `GameStateMachine.update` @491);
  - `GameWindow.initFonts`;
  - `ModelManager.initAnimationMeshes(Z)` @152 (a wait loop, skipped when the argument is true);
  - `GameServer.main` / `doMinimumInit`;
  - `TilesetImageCreator.loadModelsEtc`;
  - `ImagePyramid.destroy`.
- On a client they all run on the main/game thread.
- `pending` and `inProgress` are touched only by that thread (`cancelAsync` aside); `added` is shared under `lock`.

**Who calls `TileDepthTextureManager.init()V`**, all on the main/game thread:

| Context | Call site |
|---|---|
| Launch | `GameWindow.enter` @237 (from `mainThreadStart` @3), right after `ModelManager.create` @231 |
| Join | `Core.ResetLua(String,String)` @300 ← `ConnectToServerState.receiveServerOptions` @47 ← `Finish` ← `update` @117 ← `GameStateMachine.update` ← `GameWindow.logic` @491. `ResetLua` @261 calls `initAnimationMeshes(true)`, which returns at @141 without pumping |
| Back to the menu | `IngameState.exit` @1284 |
| Server | `GameServer.doMinimumInit` @198 (Faster Loading is not active there) |

`TileDepthTextureManager` is also exposed to Lua (`LuaManager$Exposer`), which only matters for debug tools.

**Order of the instances**

- `init` (L64-80) walks `ZomboidFileSystem.getModIDs()` and calls `initModData` for each mod with
  `common/tileGeometry.txt` that is not in `previouslyLoadedModData`.
- It then calls **`initGameData` last**.
- Each of those calls `loadDepthTextureImages` once. That creates every `LoadTask` (`new` @179, the only creation
  site), in directory-stream order (on NTFS, by name).
- The measured join agrees:
  - mods are sent in mod-list order and `game` last, its first task at +4167 ms of the 6133 ms phase;
  - 21 of 874 adjacent pairs within an instance are sent out of name order, which is tie noise within a pump.
- ~~Correction to `depth-maps.md` §5/§6: the game's 216 tasks were queued by the join's own `init`.~~ Wrong; the
  in-game series (`verification.md`) settled it: the join's `init` queues 0 depth tasks (`ModData` reused, as §5 of
  `depth-maps.md` says), and the join's 900 are the launch's batch, still pending because the auto-connect runs spend
  ~0.5 s in the menu. They run last in the join because the join's pack images (7) and animations (4, queued before
  them at launch) go first.

**Can a pump happen between the `runAsync` calls and `init`'s return? No.**

- None of the pump sites above can be reached from `init`'s chain: `getModIDs`, `getAvailableModDetails`,
  `initModData` / `initGameData` → `loadDepthTextureImages`.
- `getTilesetRows(name,false)` → `Texture.getSharedTexture` → `TextureAssetManager.load` only queues.
- So at `init` RETURN, every `LoadTask` of that call is still in `added`, along with anything else queued since the
  last pump (in the join, `loadModAnimations` @267 runs earlier in the same `ResetLua`).

## 2. B: what it needs, and whether it can reach it

**Members**

| Use | Member (as in the jar) | Access today | Widening needed |
|---|---|---|---|
| Reach the queue | `GameWindow.fileSystem` (`public static FileSystem`) → `instanceof FileSystemImpl` | public | no |
| Not-yet-sorted items | `FileSystemImpl.added` (`private final ArrayList<AsyncItem>`) | private | field |
| Take the game's lock while reading `added` | `FileSystemImpl.lock` (`private final AtomicBoolean`) | private | field |
| Item → task | class `FileSystemImpl$AsyncItem` (package-private final); its field `task` (`FileTask`, package-private) | | class + field (Loading Progress lists both) |
| Check the game's value | `FileTask.priority` (`protected int`) | protected | field. Writing can use the public `setPriority(I)V` |
| Pick out depth tasks | class `TileDepthTextures$LoadTask` (package-private final, extends `FileTask`) | | class (Loading Progress lists it) |
| Group by instance | `LoadTask.textures` (`final TileDepthTextures`, package-private) | | field |
| Tileset name | `LoadTask.path` (`final java.nio.file.Path`) → `DEPTH_<name>.png`. `call()` derives the name with `replaceFirst("DEPTH_","").replace(".png","")` | | field (Loading Progress lists it) |
| Cost estimate without I/O | `TileDepthTextures.tilesetRows` (`private final HashMap<String,Integer>`). `loadDepthTextureImages` @148-164 fills it before each `runAsync`. Rows × 8 tiles of 128×256 at 2x; the image width is fixed at 1024. 0 rows means `createTileset` returns null without decoding (@11-17) | private | field |
| Count check | `TileDepthTextureManager.remainingLoadTasks` (`private int`). `addedLoadTask()` runs once per task, @168-171 | private | field. Or count `addedLoadTask()V` calls with a hook |
| Logging | `TileDepthTextures.modId` (package-private) | | field (optional) |

**Load order vs the widener**

- Loading Progress, also preloaded, widens `FileSystemImpl` fields, `AsyncItem` (+`task`, `future`), `LoadTask`
  (+`path`) and `FileTask.fileSystem`.
- The live `console.txt` shows `[Dont39LoadingProgress] access widener: ok (28 directives)`. So `FileSystemImpl`,
  `AsyncItem`, `FileTask` and `LoadTask` load after the widener in a preloaded setup.
- `TileDepthTextures` and `TileDepthTextureManager` first load in `GameWindow.enter`, so they are later still.
- Faster Loading needs its own list and must not rely on Loading Progress's: today it ships none
  (`faster-loading/build.gradle.kts` "no fix reaches a non-public member yet"; `smokeTest` `widener = false`).

**Thread safety**

- The handler runs on the main thread, the only thread that pumps.
- Other threads may append to `added`, but only under `lock`. So the handler must:
  1. CAS-spin `lock` as the game does;
  2. copy the `LoadTask` references out;
  3. release the lock in `finally`. A throw while it is held would spin the game's queue forever.
- Priorities can be written after the release: only this thread reads them, at the next pump's insertion.
- `pending` could also be re-sorted safely from this thread, but B does not need to touch it.

**Placement**

- `TileDepthTextureManager.init()V` RETURN covers launch, join, back-to-menu and server. It runs once per batch, and
  pairs with a HEAD snapshot of `remainingLoadTasks` for an exact count check.
- The alternative is HEAD of `FileSystemImpl.updateAsyncTransactions()V`, the last point before insertion. It would
  catch every caller (including Lua), but it runs every frame and loses the per-batch count check.
- So `init` RETURN is the better fit.

## 3. A: confirmations

- **`LoadTask` does not override `setPriority`**. Its methods are `<init>`, `done`, `call`. The call @196 is
  `invokevirtual TileDepthTextures$LoadTask.setPriority:(I)V`, which resolves to `FileTask.setPriority(I)V`
  (`putfield priority`, L27). Advice on `FileTask` runs for it.
- **Which `setPriority(4)` calls run on the main thread inside `loadDepthTextureImages`?** Game-wide callers of
  `FileTask.setPriority` are:

  | Caller | Value |
  |---|---|
  | `TextureIDAssetManager` (2 sites) | 7 |
  | `AnimatedTextureIDAssetManager` | 7 |
  | `MeshAssetManager`, `PhysicsShapeAssetManager` | 6 |
  | `AnimationAssetManager` | 4 / 6 |
  | `WorldMapDataAssetManager` | 4 / 6 |
  | `FileTask_LoadImagePyramidTexture` | 10 |
  | `TileDepthTextures.loadDepthTextureImages` @195 | 4 |

  The constructor sets 5 directly (`putfield`, no call).
  - Inside the window, `getTilesetRows(name,false)` → `getSharedTexture` → `TextureAssetManager.load` can queue a
    lazy pack-page load. That goes through `TextureIDAssetManager`, at **7**, not 4.
  - No animation or world-map load is reachable from that chain.
  - A tighter filter than "value 4 in the window": `addedLoadTask()V` (@171) runs right before each `new LoadTask` /
    `setPriority(4)`. Between them there is only a `getstatic` and the constructor. So "the first `setPriority` on this
    thread after `addedLoadTask`" is the depth task's, and its value must be 4.
- **Order of the calls**: mods in mod-list order, `game` last (§1). Within an instance, tasks go in directory order.

**What round-robin does to `game`**

- Its largest maps sit late in its directory order: `signs_one-off_02..05` (16.8 Mpx each) at k = 160-163,
  `signs_one-off_08` at k = 166.
- Under A they land at positions 844-850 of 900.
- Melos (144), the next-longest chain, runs out at k = 144. From then on `game` runs alone on one worker for its last
  72 maps, which are **44 % of its work**, including all of the signs maps. That serial tail is A's whole gap to the
  floor.

## 4. Simulation

**Model** (`reprioritise_sim.py`):

- `pending` is sorted at insertion;
- a pump every 6.95 ms (the median gap between sent times in the CSV) reaps tasks and refills to 16;
- the 4-thread executor is FIFO;
- a waiting `LoadTask` holds its worker, and the monitor hand-off is FIFO;
- a task's work is proportional to its PNG's pixel count (IHDR). 684 mod files no longer under `~/Zomboid/mods` were
  read from the Workshop copies.

**Calibration**

- **Calibrated**: scaled so that today's FIFO reproduces the measured phase of the join.
- **5.6 s**: total work fixed at 5.6 s.
- A third model with 2 ms per task plus pixels, also calibrated, lands between the two (FIFO 6133, A 2476, B2 1822).
- **Check against measurement**: in join 00-08-57, `game` ran alone from first sent to last done in **1.97 s**. The
  calibrated model's game chain is 1.91 s.

**Join 00-08-57** (measured depth phase 6133 ms, worker time 23.6 s):

| Schedule | Calibrated (ms) | 5.6 s model (ms) |
|---|---|---|
| Floor max(work/4, longest chain) | 1911 (game chain; work/4 = 1629) | 1642 |
| FIFO (today) | 6126 | 5271 |
| **A** round-robin, priority 4-k | **2601** | **2239** |
| A2: A, plus `game` gets 2 slots per round (needs an `initGameData` bracket) | 2559 | 2204 |
| B1: round-robin, instances by estimated work, largest first | 2594 | 2225 |
| **B2**: greedy list schedule (longest remaining chain first), priority 4 - position | **1912** | **1648** |
| B2 with PNG file size as the estimate | 2239 | 1919 |

**All five joins, calibrated model** (ms):

| Join | Measured | FIFO | A | B2 | B2 (file-size estimate) | Floor |
|---|---|---|---|---|---|---|
| 00-08-57 | 6133 | 6126 | 2601 | 1912 | 2239 | 1911 |
| 00-12-37 | 6289 | 6286 | **2670** | 1961 | 2295 | 1960 |
| 00-15-42 | 5371 | 5368 | 2281 | 1676 | 1961 | 1674 |
| 00-19-00 | 5192 | 5188 | 2204 | 1620 | 1891 | 1617 |
| 00-22-38 | 5486 | 5480 | 2330 | 1711 | 2003 | 1708 |

**What the numbers say**

- A cuts the phase to ~42 % of FIFO, 2.2-2.7 s. That misses the ≤ 2.5 s target on 2 of 5 joins.
- B2 hits the floor, the `game` chain at 1.6-2.0 s, on every join. The dispatch (16 in flight, executor FIFO) follows
  the static order closely enough.
- B1 shows that sorting instances alone gains nothing over A: the tail is `game`'s serial remainder.
- Estimate quality matters: file size as the estimate costs about 0.3 s. `tilesetRows` × 8 tiles equals the pixel
  count when rows = image height / 256; it is lower for tilesets whose top rows have no texture.

**Not modelled**

- `DirectBufferAllocator.LOCK` contention between 4 depth workers;
- the render thread (2 runnables per non-empty tile, §3 of `depth-maps.md`);
- memory bandwidth;
- the 2-worker case on ≤ 4 cores (there the floor becomes work/2 ≈ 3 s).

## 5. Failure and mismatch surfaces; launch

**Both designs**

- `Session.on(fix)` already throws on any revision other than `4a0e9546ec`. Its default gate stops all the mod's
  hooks, so a game update is reported, never degraded.
- What follows are checks within that build, against wrong assumptions.

**A**

| What to check | When / how | Effect |
|---|---|---|
| Targets exist | Default-gate weave check of `FileTask.setPriority(I)V`, `TileDepthTextures.loadDepthTextureImages()V`, `TileDepthTextureManager.addedLoadTask()V` (all JDK-only descriptors, no `@Self`, no widening) | An unwoven hook stops the mod |
| Each `addedLoadTask` is followed on the same thread by exactly one `setPriority` with value 4 before the next `addedLoadTask` or FINALLY | At use; otherwise throw | Mismatch stops the mod |
| Only the window's thread is counted | Record the thread at HEAD | Other threads' `setPriority(4)` (animations) pass through |
| `setPriority` runs for every `FileTask` from the splash on | The handler checks its bracket flag before `Session.on`: the game log and cache folder are not up yet (as `TextureHooks` does) | |

- **Residual risk**: none silent within the build.
- **Coupling**: the call order inside `loadDepthTextureImages`, and the semantics of `FileSystemImpl`'s insertion.

**B**

| What to check | Effect |
|---|---|
| The weave of `init()V` HEAD and RETURN | |
| `GameWindow.fileSystem instanceof FileSystemImpl` | Else throw |
| The number of `LoadTask`s found in `added` equals the `remainingLoadTasks` delta between HEAD and RETURN | A pump inside `init` or a changed path → throw |
| Each has `priority == 4` | Else throw |
| Each tileset name is a key of its instance's `tilesetRows` | Else throw |
| Widening | An `IllegalAccessError` (widener status not ok, or a class loaded early) throws from the handler and stops the mod. The `smokeTest` with `widener = true` catches it before release |
| The lock is released in `finally` | Not catching. It is required: a throw while the lock is held would hang the queue |
| The exit handler changes state other calls read | Per `zabric-hooks.md` it checks `ModHooks.open()` |

- **Coupling**: 9 widened members across 5 classes, the `added`/`lock` protocol, and the meaning of `tilesetRows`.

**Launch**

- The same hook reorders the launch's depth tasks, which are queued after the launch's animations (priority 4, FIFO).
  A and B keep every depth task at ≤ 4, so nothing that was ahead falls behind.
- One shift: priority-4 tasks queued after `init` now go ahead of depth tasks at < 4, where today they queue behind.
  Every game value is ≥ 4 and the default is 5.
- The output is unchanged:
  - instances are independent;
  - `initMergedTilesets` runs when `remainingLoadTasks` reaches 0;
  - `isLoadingFinished()` is false during loading, so `TileDepthTexture.load` does not link sprites early.
- **Costs that also apply at launch** (and at join):
  - 4 depth decodes at once instead of ~1. The native peak goes up to 4 × the largest maps in flight (one 1024×16384
    map is 64 MB; `game`'s four signs maps stay serial behind its own lock).
  - About 3× the render-thread rate of per-tile `createTexture` + `GL_RED` uploads, each holding a 128 KB
    `WrappedBuffer` until the render thread runs it.
  - If the render thread lags, those buffers pile up and the cost moves past the asset wait as frame gaps.
  - These apply to A and B alike. They need a measurement (render-queue drain, frame gaps), not a design choice.

## Comparison

| | A: `setPriority` counter | B: post-pass at `init` RETURN |
|---|---|---|
| Simulated depth phase (5 joins, calibrated) | 2.2-2.7 s (−57 %); 2 of 5 over 2.5 s | 1.6-2.0 s (−69 %), at the floor |
| Gap to the floor | +0.6-0.7 s: `game`'s serial tail, 44 % of its work incl. the 16.8 Mpx signs maps | ~0 with a rows-based estimate; +0.3 s with a file-size estimate |
| Hooks | 3 (HEAD/FINALLY bracket, `addedLoadTask`, `@ModifyVariable` on `setPriority` for every `FileTask`) | 2 (`init` HEAD + RETURN) |
| Widening | none | `added`, `lock`, `AsyncItem` + `task`, `FileTask.priority`, `LoadTask` + `textures` + `path`, `tilesetRows`, `remainingLoadTasks`. Faster Loading's first classtweaker; smokeTest widener on |
| Game coupling | Call order in `loadDepthTextureImages`; insertion semantics | The same, plus the `FileSystemImpl` queue internals and lock protocol, the `LoadTask` fields, `tilesetRows` |
| Mismatch detection | Pairing check per task | Exact count, value and name checks per batch |
| Hang risk | none | Lock held while reading `added` (release in `finally`) |
| Coverage | Every `loadDepthTextureImages` call (incl. Lua debug) | Calls through `init` only |
| Hot-path cost | A flag check on every `FileTask.setPriority` | Once per `init` |

## Recommendation

**B (at `init` RETURN), with the greedy longest-remaining-chain-first order from `tilesetRows`, all priorities at or
below 4.**

- It is the only design whose simulated phase meets the ≤ 2.5 s target on every measured join. It reaches the floor
  that any reordering can reach: the `game` chain, measured at 1.97 s.
- A is simpler and needs no widening, but in the calibrated model it lands at 2.2-2.7 s. Its tail is structural: no
  per-task counter can know which chain is longest.
- Going below ~2 s needs lever 1: moving `game`'s decode off its lock by prefetch. That shortens the chain that sets
  the floor, and combines with B.

## Caveats / not found

- Task cost is modelled as pixel count, calibrated to the measured FIFO. The real per-task decode time is not measured,
  and contention between 4 concurrent depth workers (allocator lock, memory bandwidth, render thread) is not modelled.
  So the absolute numbers for A and B are optimistic by an unknown margin; the A-vs-B gap is the robust part.
- Monitor hand-off is modelled as FIFO. Java monitors are not fair.
- Why the join re-queued `game`'s maps (§1 correction) is unresolved.
- On machines with ≤ 4 cores the pool has 2 workers. There the floor is work/2 and both designs gain less.
- Not checked: whether another mod on other users' lists patches `FileTask.setPriority`, `loadDepthTextureImages` or
  `TileDepthTextureManager.init`. On the live list, nothing does (no `[ZB] patching` line for them).
