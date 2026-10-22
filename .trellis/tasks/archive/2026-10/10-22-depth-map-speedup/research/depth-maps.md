# Research: depth maps (`zombie.tileDepth`) on the join's asset wait

- **Query**: PRD questions 1-5: what the depth-map tasks compute, the lock, the render-thread upload, the levers a
  preloaded mod can reach, and launch vs join.
- **Scope**: internal. `javap -c -p -l` of `projectzomboid.jar` 42.21 `4a0e9546ec`, plus a CPU bench of the real
  depth PNGs. Citations: `Class.method @offset` (bytecode) or `Lnnn` (LineNumberTable).
- **Date**: 2026-10-22

The package is `zombie.tileDepth`, not `zombie.iso.fboRenderChunk`. The classes are `TileDepthTexture`,
`TilesetDepthTexture`, `TileDepthTextures` (+`$LoadTask`), `TileDepthTextureManager` (+`$ModData`), and
`zombie.core.utils.NativeImage`.

## Files / sources

| Source | What |
|---|---|
| `projectzomboid.jar` (`zombie/tileDepth/*`, `NativeImage`, `Texture`, `TextureID`, `ImageData`, `MipMapLevel`, `WrappedBuffer`, `DirectBufferAllocator`, `FileSystemImpl`, `GameWindow`, `Core`, `IngameState`, `GameServer`) | All behaviour below |
| `E:/SteamLibrary/steamapps/common/ProjectZomboid/media/depthmaps` | 220 files, 218 `DEPTH_*.png`. 188 are 1024 px wide RGB and 28 RGBA; 763 Mpx in total. Largest: `signs_one-off_02..05` at 1024×16384 |
| `E:/SteamLibrary/steamapps/workshop/content/108600/*/mods/*/common/media/depthmaps` | 27 mod folders, 696 maps. Largest: Melos 144, Drazion 116, CommunityTilePack 102, Tryhonesty 55, HavenFall 32. Vanilla + mods = 914, against the 900 tasks seen on the join |
| Scratchpad `.../0b9eac93-410b-45b2-a500-0b7ba355cf90/scratchpad/bench/DepthBench.java` | Single-thread bench run on the game JRE (Zulu 25.0.1) with the game jar's LWJGL. It copies `TileDepthTexture.load` and the render lambda's pixel conversion from the bytecode. No GL |
| `faster-loading/.../hooks/TextureHooks.java`, `textures/Upload.java`, `textures/Dxt5Encoder.java` | The shipped `GL11.glTexImage2D` HEAD hook. `Upload.requested` passes only `internalFormat == GL_COMPRESSED_RGBA`, so both depth uploads (RGBA 6408, `GL_RED` 6403) go through it untouched. The encoder has its own lazily started pool, split by block rows |

## 1. `TileDepthTexture.load([FLjava/nio/ByteBuffer;III)V` and the per-tileset path

**Caller** (`TilesetDepthTexture.load()V` L141-159):

1. `NativeImage.read(getAbsoluteFileName(), false)`. The path is `mediaAbsPath + sep + "depthmaps" + sep + "DEPTH_" +
   name + ".png"` (concat recipe `\u0001\u0001depthmaps\u0001DEPTH_\u0001.png`).
2. `tempPixels = new float[tileW*tileH]`: one array per tileset (@31), reused for every tile of that tileset in turn.
   It is not shared between tasks.
3. For each `row < rows`, `col < columns` (columns = 8, rows from `tilesetRows`), skipping tiles outside the image:
   `getOrCreateTile(col,row).load(tempPixels, image.pixels(), image.width*4, col*tileW, row*tileH)` (@124).
4. `NativeImage.close()` → `stbi_image_free`.

- **Tile size**: `getTileWidth` = 64×(is2x?2:1), `getTileHeight` = 128×(is2x?2:1). `createTileset` always passes
  `is2x = true` (`TileDepthTextures.createTileset @28`), so a task's tiles are **128×256 and the float[] holds 32,768
  floats**.
- **`NativeImage.read(String,Z)` @0-89**:
  - `ZomboidFileSystem.validatePrefix`.
  - `STBImage.stbi_set_flip_vertically_on_load(false)`. This flag is **global**. `Mouse` calls `read(..., true)`.
    LWJGL also has `stbi_set_flip_vertically_on_load_thread`.
  - `STBImage.stbi_load(CharSequence,int[],int[],int[],4)`: decodes the whole sheet to RGBA in native memory. One
    1024×16384 map is 64 MB.
  - On null it logs `failed to read image` and returns null.

**`load(pixels, bb, stride, left, top)` per-pixel work** (@0-135, L138-150):

- It sets `this.empty = true`, then loops over `y < this.height`, `x < this.width`:
  - `i = (top+y)*stride + (left+x)*4`
  - `a = bb.get(i+3) & 255`, `b = bb.get(i+2) & 255`. These are absolute gets, so they do not touch the position.
  - `pixels[x + y*this.width] = a == 0 ? -1f : b/255f`
  - `empty = false` on any `a != 0`
- **Reads**: the args plus the fields `width` and `height`.
- **Writes**: the float[] argument and `this.empty`.
- **After the loop** (@138-214):
  - **If empty**: `this.pixels = null`, then return.
  - **Otherwise**:
    - `allocPixelsIfNeeded()` allocates a **new `float[w*h]` (128 KB) per non-empty tile** and runs
      `Arrays.fill(-1)`. Then `System.arraycopy` copies `tempPixels` into it.
    - If `TileDepthTextureManager.isLoadingFinished()`, it links `IsoSpriteManager.instance.namedMap.get(name)
      .depthTexture = this`. Normally this is false while loading.
    - It calls `updateGPUTexture()` (section 3).
- **Allocations per non-empty tile on the worker, inside the lock**:
  - the 128 KB float[];
  - on the first call, `new Texture(w, h, name, 0)`. That builds `TextureID(int,int,int)` →
    `ImageData(int,int)` → `MipMapLevel(int,int)` → `DirectBufferAllocator.allocate(w*h*4)`:
    - a **128 KB `WrappedBuffer`**: `memAlloc` + `memSet(0)`, taken under the global
      `DirectBufferAllocator.LOCK`, after an O(n) `destroyDisposed` scan;
    - the buffer counts toward the 50 MB texture-decode budget;
    - it queues `TextureID::createTexture` to the render thread (`TextureID.<init> @190-195`).
- **Can it be split from its arguments alone? The loop can, the method cannot.**
  - The loop's result depends only on (bb, stride, left, top) and the tile size. The size is implied by
    `pixels.length` (32,768 → 128×256 on this path). Its writes are disjoint per row, so rows could be split.
  - The method's tail needs `this`: `empty`, `pixels`, `name`, `tileset`, `updateGPUTexture`.
  - A HEAD hook that does the work and cancels would therefore need `@Self`.
  - Without cancelling, the original loop runs again anyway.
  - The loop is also small: ~60 µs per tile (bench).

**JDK-only descriptors on this path**:

| Method | Note |
|---|---|
| `STBImage.stbi_load(Ljava/lang/CharSequence;[I[I[II)Ljava/nio/ByteBuffer;` | static |
| `STBImage.stbi_set_flip_vertically_on_load(Z)V`, `stbi_image_free(Ljava/nio/ByteBuffer;)V` | static |
| `MemoryUtil.memAlloc(I)Ljava/nio/ByteBuffer;`, `memSet` | static |
| `RenderThread.queueInvokeOnRenderContext(Ljava/lang/Runnable;)V` | static |
| `TilesetDepthTexture.load()V`, `TileDepthTexture.load([F...)V`, `updateGPUTexture()V`, `allocPixelsIfNeeded()V`, `TilesetDepthTexture.getAbsoluteFileName()Ljava/lang/String;` | instance: hookable, but useless without `this` |
| `TileDepthTextureManager.isLoadingFinished()Z` | instance |

Not on the list, because their descriptors name game types: `NativeImage.read(String,Z)NativeImage`,
`createTileset(String,Z)TilesetDepthTexture`, `DirectBufferAllocator.allocate(I)WrappedBuffer`.

**Bench**: single thread, warm JIT, 3 reps, game JRE, all 914 maps, rows = image height / 256.

| Part | Vanilla 218 maps | All 914 maps | Share |
|---|---|---|---|
| `stbi_load` decode | 0.83-0.86 s | 2.89-2.95 s | ~69 % |
| `load` loop (incl. float[] alloc/fill/copy for non-empty tiles) | 0.36-0.45 s | 1.25-1.40 s | ~31 % |
| Tiles / non-empty | 23,282 / 5,260 | 80,362 / 21,537 | |
| Render pixel conversion, no GL, no clamp | 0.07 s | 0.26-0.30 s | |

The bench leaves out the per-tile `Texture`/`WrappedBuffer` creation (21.5k × 128 KB zeroed is about 2.8 GB of
memset), GC, and contention from other threads.

## 2. The lock

- **What it covers**: `LoadTask.call` (L213-220) does `monitorenter` on `this.textures` (@35). Inside:
  `textures.tilesets.get(name)`; if null, `createTileset(name, true)` (@56). `createTileset` (L73-87) does:
  - `getTilesetRows(name, true)`, which reads the cached `tilesetRows` HashMap;
  - `new TilesetDepthTexture`;
  - `fileExists()` → `load()`, which is the whole decode and every tile;
  - `tilesets.put`.
- **What actually needs the lock**: only the check-then-put on the per-instance `tilesets` HashMap (plain
  HashMap). `tilesetRows` is written on the main thread before `runAsync`. Everything inside `load()` (NativeImage,
  the tiles, `Texture` creation, the render queue) already runs **concurrently across instances**, since every mod's
  instance has its own monitor. So the lock does not protect the decode from anything.
- **Who else takes the monitor**: nobody.
  - A scan of every `zombie/tileDepth/*` class finds one `monitorenter` (`LoadTask`) and no `synchronized` methods.
  - Outside the package, only `LuaManager$Exposer` names `TileDepthTextures`.
  - Main-thread readers (`getTexture`, `getExistingTileset`, `initMergedTilesets`, `initSprites`) take no lock. They
    rely on ordering: `remainingLoadTasks` drops in `LoadTask.done()` on the pumping main thread, and the merge runs
    at 0 (`finishedLoadTask` L321-326).
- **How many instances there are**: one `TileDepthTextures` per `ModData` (`ModData.<init> @15`).
  - `TileDepthTextureManager.init` (L64-80) makes one for every mod in the mod list that has `tileGeometry.txt` in
    its common media folder, plus one for `game`. A mod without a `depthmaps` folder queues no task.
  - It also makes one `mergedTilesets` instance, which has no tasks.
  - With the live list that is ~27 instances with tasks.
  - The default, billboard and preset tilesets load synchronously through `TilesetDepthTexture.load` on the caller,
    outside any task (`initDefaultDepthTexture` etc.).
- **Queue order makes it serial in practice**:
  - `loadDepthTextureImages` (L114-146) submits one instance's tasks back to back.
  - Their priority is **4** (`iconst_4` at @195 → `setPriority`), not 6 as parent `asset-wait.md` §1 says. That is
    the same as animations.
  - `FileSystemImpl` inserts by priority (higher first) and keeps FIFO within equal priority
    (`updateAsyncTransactions` insertion loop @189-255: insert before the first lower priority).
  - So the 16 in-flight slots and 4 workers mostly hold tasks of **one** instance: 1 decodes and 3 block.
  - Estimated split of the 22.6 s worker time: **~70-75 % lock waits** and ~5.6 s of work (22.6 / 4). That fits
    the bench's 4.2 s of CPU plus the unmeasured `Texture` allocations and contention. PZ_Opt measured the same split
    for vanilla: 2 s of work, 6.1 s of lock waits.
  - Within the work, **decode is ~2/3 and the per-tile `load` is ~1/3**.
  - Earlier JFR put 39 % of worker samples in `TileDepthTexture.load`. That fits if native `nstbi_load` time was not
    counted (a JFR execution sample vs a native-method sample): unverified.

## 3. Render thread: `TileDepthTexture.lambda$updateGPUTexture$0()V`

`updateGPUTexture()V` (L237-290) creates the `Texture` if it is missing, then
`RenderThread.queueInvokeOnRenderContext(this::lambda)`. It does this **once per non-empty tile**, about 21.5k times
for 914 maps (bench).

**Per non-empty tile there are two render runnables, in FIFO order:**

1. `TextureID::createTexture` → `generateHwId`. This is a `GL11.glTexImage2D` of **RGBA 6408, 128×256, the zeroed
   128 KB buffer**, then `ImageData.dispose`. The lambda re-specifies the same texture right after.
2. The lambda (@0-349):
   - `glBindTexture` and 2× `glTexParameteri` (`NEAREST`).
   - `MemoryUtil.memAlloc(w*h)`: **32,768 bytes, 1 byte per pixel**.
   - For each pixel: `getPixel(x,y)`.
     - When the depth is ≥ 0, y < 64, and the tileset is not `roofs_*`: `clampPixelToUpperFloor` (a static 128×64
       table, initialised once).
     - Then `bb.put(int, byte)` (absolute; this is the JFR `DirectByteBuffer.put` / `Buffer.checkIndex`). Pixels
       below 0 write 0; others write `max(1, floor(min(p,1)*255))`.
   - `flip()`.
   - **`GL11.glTexImage2D(GL_TEXTURE_2D, 0, GL_RED 6403, w, h, 0, GL_RED, GL_UNSIGNED_BYTE, buf)`**.
   - `memFree`.
   - Unless `tileset.isKeepPixels()`, it drops `this.pixels`.

Total render data for 21.5k tiles is ~2.8 GB of RGBA zeros plus ~0.7 GB of `GL_RED`. The bench's pixel conversion
is ~0.27 s for all tiles, so the JFR's 39 % render-thread share (compression off) is more than the conversion alone
explains. Open question: driver time, or the extra RGBA upload.

**What hooks can reach here:**

- `GL11.glTexImage2D(IIIIIIIILjava/nio/ByteBuffer;)V` (already hooked) sees both uploads. It cannot shrink the pixel
  loop, which runs before the call. It also cannot tell a depth tile's RGBA zero upload from another 128×256 RGBA
  texture by its arguments, short of scanning for an all-zero buffer.
- `RenderThread.queueInvokeOnRenderContext(Runnable)V` is JDK-only. It sees the lambda, but its captured
  `TileDepthTexture` is reachable only by reflection on the hidden lambda class.
- `updateGPUTexture()V` needs `this`.

## 4. Levers for a preloaded mod, ranked by likely gain on the join's wait

Baseline with Faster Loading 0.2.0: wait 12.5 s median. Depth maps take 22.6 s of worker time over 900 tasks; 4
workers are 98 % busy, partly blocked on monitors. Gains are estimates and need a timeline of the wait to confirm
that depth maps are its tail.

| # | Lever | Hook points (all JDK-only, no `@Self`) | Expected gain | Needs / risks |
|---|---|---|---|---|
| 1 | **Decode depth PNGs ahead, off the lock**, on the mod's own pool. The lock holder's `stbi_load` returns the ready buffer | HEAD `cancellable` on static `STBImage.stbi_load(Ljava/lang/CharSequence;[I[I[II)Ljava/nio/ByteBuffer;`: when the path is a pending prefetch, fill `w[0]`, `h[0]`, `c[0]` and `setReturnValue(buf)`. Prefetch list from a RETURN hook on `TileDepthTextureManager.init()V`: the handler reads `getInstance()`, `modData`, `ModData.textures`, `mediaAbsPath`, `tilesetRows` (fields, widened). Or list the `depthmaps` folder on the first depth path | Takes **~2/3 of the per-tileset work** (2.9 s single-thread bench, more in game) out of the serial chain, plus all of the waiting it causes. Plausibly **several seconds of worker time and ~1.5-3 s of wall time** if depth maps are the tail | **Identical output**: the same `stbi_load(path, ..., 4)` on another thread gives the same bytes, and NativeImage's `stbi_image_free` matches stb's allocator. **Flip flag** is global (Mouse sets true): prefetch threads must use `stbi_set_flip_vertically_on_load_thread(0)`. **Reentrancy**: the hook must pass its own threads' calls through. **Memory**: one 1024×16384 map is 64 MB, all maps 10.5 GB RGBA, so a bounded look-ahead window is needed. Skip `DEPTH_whole_tile` / `DEPTH_preset_depthmaps_01` and rows-0 tilesets (no `stbi_load` call), and free any buffer never claimed. **Ordering**: decode in queue order. CPU competes with the DXT5 encoder pool |
| 2 | **Interleave instances in the queue**, so the 4 workers hit different monitors | `@ModifyVariable(HEAD)` on `FileTask.setPriority(I)V` while a per-thread flag is set. The flag is set and cleared by HEAD/FINALLY hooks on `TileDepthTextures.loadDepthTextureImages()V` (main thread). Give the k-th task of each instance priority `4 - k` (or another round-robin mapping) | Removes most of the **~70-75 % lock-wait share** of the 22.6 s, so blocked workers do animation/pack work. The serial tail becomes the largest instance (vanilla 218, ~1-2 s) | Changes order against animations (also 4). With `4 - k`, later rounds go after all animations. Output does not change: tilesets are independent and the merge runs at `remainingLoadTasks == 0`. The hook must pick out depth calls: flag plus value 4. Check that `Texture.getSharedTexture` inside `getTilesetRows` cannot queue a priority-4 task. `setPriority` runs for every FileTask (a cheap flag check) |
| 3 | **More file workers during the wait** (not depth-specific) | Any JDK-only hook; resize widened `FileSystemImpl.executor` (`ThreadPoolExecutor`), see parent `asset-wait.md` §4 | Blocked depth tasks then cost less of the pool | PZ_Opt rejected 14 threads (render pile-up) and adopted 8. In-flight is still capped at 16 |
| 4 | Render side: skip or shrink the redundant RGBA zero upload per tile | Only `GL11.glTexImage2D` (args only) | Unknown; ~2.8 GB of RGBA zeros is a candidate cost for the JFR's 39 % | Cannot identify depth textures from args reliably. A wrong match drops texture data. Low |

**Out of reach (and why):**

| Method / lever | Why |
|---|---|
| Move decode out of `synchronized (textures)` in `TileDepthTextures$LoadTask.call()Ljava/lang/Object;` | Descriptor is fine, but the work needs `this.textures` / `this.path`, so `@Self`. `@Self` always names the target class in the glue, and `checkJdkGlue` rejects it |
| Cancel + parallelise `TileDepthTexture.load([FLjava/nio/ByteBuffer;III)V` | Tail needs `this` (`empty`, `pixels`, `updateGPUTexture`). Without cancel the loop runs anyway |
| Replace `TilesetDepthTexture.load()V` | Needs `this` (`name`, `owner`, tiles) |
| Hook `NativeImage.read(Ljava/lang/String;Z)Lzombie/core/utils/NativeImage;` | Game return type |
| Hook `TileDepthTextures.createTileset(Ljava/lang/String;Z)Lzombie/tileDepth/TilesetDepthTexture;` | Game return type, `@Self` |
| Hook `FileSystem.runAsync(Lzombie/fileSystem/FileTask;)I` | Game parameter type |
| Hook `DirectBufferAllocator.allocate(I)Lzombie/core/utils/WrappedBuffer;` | Game return type |
| Hook `TextureID.<init>(III)` | Constructors only take RETURN, and need `@Self` |
| Rewrite the render lambda / `updateGPUTexture()V` | Private instance lambda, `this` |
| Rewrite the render lambda via `queueInvokeOnRenderContext(Runnable)` | Needs reflection on the lambda's captured field (project rule: last resort) |

## 5. Launch vs join

- **Launch**:
  - `GameWindow.mainThreadStart` → `enter()` @237 calls `TileDepthTextureManager.init()` right after
    `ModelManager.create()` (@231).
  - So the launch queues the depth maps of `game` + every launch-list mod with `tileGeometry.txt`, behind the
    launch's animations: same priority 4, FIFO.
  - Per tileset, the main thread first runs `getTilesetRows(name, false)` (up to 512 `Texture.getSharedTexture`
    lookups) before `runAsync`.
- **Join / mod-list change**: `Core.ResetLua` calls `TileDepthTextureManager.Reset()` (@166) and later `init()`
  (@300).
  - `Reset` (L304-314) moves every `ModData` to `previouslyLoadedModData`.
  - `initModData` / `initGameData` (L87-105) **reuse** an existing `ModData` by id and queue nothing for it.
  - **So a join queues depth maps only for mods that the launch list lacked. `game`'s maps are never reloaded.**
- **Other call sites**:
  - `IngameState.exit` (@1078 Reset, @1284 init): back to the menu.
  - `GameServer.doMinimumInit` @198: server.
  - `IsoWorld.loadedTileDefinitions` @282 → `loadedTileDefinitions()`, which merges when no tasks remain.
- **Consequence for the measured joins**: vanilla maps (`DEPTH_signs_one-off_0x.png`) appear among the join wait's
  slowest tasks (`join-r2-on5.txt:37-48`). With `game` reused, those can only be the **launch's** tasks still
  queued.
  - Those runs had 492 ms in the menu, and the main thread pumps the queue only in its frame and loading loops.
  - So the join's 900-task depth load is mostly launch backlog plus the server-only mods' maps. Inference, not
    measured.
  - A user who waits in the menu would have the launch's share done before joining.

## 6. Timeline of the wait (measured)

Loading Progress's CSV has a `file_task` row per wait task: when it was sent to the workers (`start_ms`), sent → done
(`duration_ms`) and `worker_ms`. `research/timeline.py <start.csv>` prints worker time finished per second by type
and each type's first-sent / last-done. Run on the five joins in `~/Zomboid/Lua/Dont39LoadingProgress/` of
2026-10-22 00:08-00:22 (live list, auto-connect, ~0.5 s in the menu, texture compression fix **off**, waits 49-74 s):

| Join | Pack images | Animations (+ meshes etc.) | Depth maps: first sent → last done | Depth worker time |
|---|---|---|---|---|
| 00-08-57 | 0 → 47.3 s | 47.5 → 53.0 s | 53.0 → 59.1 s (6.1 s) | 23.6 s |
| 00-12-37 | 0 → 48.7 s | 48.9 → 54.2 s | 54.1 → 60.4 s (6.3 s) | 24.2 s |
| 00-15-42 | 0 → 40.2 s | 40.4 → 45.4 s | 45.3 → 50.7 s (5.4 s) | 20.6 s |
| 00-19-00 | 0 → 39.6 s | 39.8 → 45.4 s | 44.8 → 50.0 s (5.2 s) | 19.9 s |
| 00-22-38 | 1.5 → 64.1 s | 64.4 → 69.5 s | 69.4 → 74.9 s (5.5 s) | 21.1 s |

- The queue runs in strict phases by priority, then FIFO: pack images (7), meshes/physics (6), animations (4), then
  the depth maps (4, queued after the animations). **Depth maps are the last ~5.5 s of every wait, with no other task
  running.** About 4 s of worker time finishes per second, so all 4 workers sit in depth tasks, ~1 decoding.
- 900 depth tasks per join: `game` 216 (launch backlog, see §5), melos 144, tikitown 116, CommunityTilePack 102,
  Tryhonesty 55, HavenFall 32, PertsParty 28, Newburbs 27, then smaller.
- No CSV of a join with the compression fix on is left (LP keeps 10 loads). The order is set by priority, not by the
  fix, so the depth phase is expected at the end of those waits too (R2 on5: 22.6 s depth worker time).
- Lower bound for the depth phase once the instances run in parallel: max(work / 4, the longest instance's chain).
  Work ~5.6 s → ~1.4 s; the longest chain is `game` (216 maps, bench 1.25 s single-threaded, its largest
  `signs_one-off_*` maps 100-140 ms each in game). So ~2 s, if the schedule starts the long chains first.

## Caveats / open questions

1. ~~The wait's timeline by task type is not measured.~~ Measured in §6: depth maps are the tail.
2. Whether the launch's tasks really carry into the join (client `default.txt` vs the server list in those runs;
   auto-connect). The gain on a join after a normal menu stay may be smaller.
3. The JFR attribution (39 % in `load`, 39 % render in the lambda) vs the bench (decode 69 %, render conversion
   0.27 s total): re-profile with native samples counted, and with the RGBA zero uploads separated.
4. Not checked: whether `Texture.getSharedTexture` inside `getTilesetRows` can queue a priority-4 FileTask (matters
   for lever 2), and whether `STBImage` is already loaded when the hook is woven (GL11 was retransformed fine).
5. The bench takes rows from the image height. The game's `getTilesetRows` (the highest row with an existing
   texture) can be lower, which means less work.
6. Correction to parent `asset-wait.md` §1: depth-map tasks are priority **4** (`loadDepthTextureImages @195`), not 6.

## Related specs

- `.trellis/spec/mod/zabric-hooks.md`: hook kinds and the JDK-only glue rule.
- `.trellis/spec/mod/access-widening.md`: fields, classes and constructors are widened; methods are not. Classes
  loaded before the widener keep their access.
