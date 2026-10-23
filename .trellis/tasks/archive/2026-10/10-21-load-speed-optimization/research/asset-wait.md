# Research: the join's asset wait (`bWaitForAssetLoadingToFinish2`), R1 instrumentation, R2 levers

- **Query**: what the 39.8 s wait checks and what is queued; the animation-clip path and a safe cache key; JDK-only hook
  points for Loading Progress (R1); levers for R2; unknowns.
- **Scope**: internal (B42.21 jar `4a0e9546ec`, javap `-c -p -l`; this repo; DebugLog / Loading Progress report) +
  PZ_Optimization docs for measurements and pitfalls only (used: `docs/plan-game-load.md`, `docs/plan-instant-load.md`,
  `docs/override-edits.md` sections "zombie.fileSystem.FileSystemImpl (second/third edit)",
  "zombie.core.skinnedmodel.model.AnimationAssetManager", "Continue asset wait: file tasks in flight..."). No code copied.
- **Date**: 2026-10-21

Line citations are `Class.method Lnnn` from the jar's LineNumberTable.

## 0. Headline finding: the 39.8 s was not animations

Loading Progress logs a new line only when an item's text/count changes (`Progress.item`,
`loading-progress/.../progress/Progress.java:808-825`, `lastLines.put` dedupe). The join CSV
(`C:\Users\dontc\Zomboid\Lua\Dont39LoadingProgress\2026-10-21_21-11-04_start.csv:11724`) has exactly one row:
`animation,2291,2291,...,73920.161,39755.408`. So at the **first** poll of `ModelManager.isLoadingAnimations()` (the
wait's first frame) all 2291 `AnimationAsset`s in `AnimationAssetManager`'s table were already non-EMPTY, and stayed
2291/2291 for the whole wait. The release condition is `!isLoadingAnimations() && !fileSystem.hasWork()`
(section 1), so the entire 39.8 s was `FileSystem.hasWork()` = other file tasks still queued/in flight. The report's
label "Animation 2291 / 2291" is the item that happens to be polled, not the work.

Consequences: an animation clip cache does not directly shorten this join's wait; R1 must break down the
**file-system queue**, not the animation table. (On a join whose mod set equals the menu's, `ResetLua` re-calls
`loadModAnimations`, but `AssetManager.load` returns the existing non-empty asset and starts nothing: `AssetManager.load
L59-76` only calls `doLoad` when `isEmpty()` and `desiredState == EMPTY`.)

Timeline check: world step starts at 58,023 ms (13,677 menu + steps 1-10), wait row starts 73,920 ms, ends 113,675 ms =
end of "Loading the world" (58,023 + 55,652). DebugLog lines 16727-16742 (`2026-10-21_20-55_DebugLog.txt`):
start 21:10:15.863, end 21:10:55.621. One line inside: `[Viewpoint] far world: terrain relief ...` at 21:10:16.868.

## 1. What the wait checks, what feeds it, who does the work

### The wait

| Where | What |
|---|---|
| `GameLoadingState$1.runInner L388-398` | right before the wait: `ChatManager.init`, **`Bullet.startLoadingPhysicsMeshes()` (L391)**, 5× `Texture.getSharedTexture` (L394-398) |
| `GameLoadingState$1.runInner L400-410` | prints "bWaitForAssetLoadingToFinish2 start", sets `waitForAssetLoadingToFinish2 = true`, `assetLock2.wait()` loop on the loader thread, prints "... end" |
| `GameLoadingState.update L863-872` (main thread, every frame) | `if (waitForAssetLoadingToFinish2 && !ModelManager.instance.isLoadingAnimations() && !GameWindow.fileSystem.hasWork())` → clear flag, `notifyAll`, then run every `RuntimeAnimationScript.exec()` (L868-871) |
| `ModelManager.isLoadingAnimations L1919-1924` | any value of `ModelManager.animationAssets` (HashMap) with `isEmpty()` (state EMPTY) |
| `FileSystemImpl.hasWork L309-323` | `!pending.isEmpty() || !inProgress.isEmpty()`, else (CAS on `lock`) `!added.isEmpty()` |

So the condition is evaluated once per main-thread frame; the wait cannot end faster than the frame rate.

### The file system (one instance, `GameWindow.fileSystem`)

| Fact | Evidence |
|---|---|
| Pool: `Executors.newFixedThreadPool(n)`, `n = availableProcessors() <= 4 ? 2 : 4` → **4 threads** on this 16-core PC; field `executor` is `private final ExecutorService` (a `ThreadPoolExecutor`); default thread factory (`pool-N-thread-M`) | `FileSystemImpl.<init> L40-41` |
| Submit from any thread: `runAsync(FileTask)` wraps in `FutureTask`, appends to `added` under an `AtomicBoolean` spin-lock | `FileSystemImpl.runAsync L214-231, L236-239` |
| Pump: `updateAsyncTransactions()` (main thread) (a) collects done futures among the first `min(inProgress,16)` (L244-265) and calls `task.handleResult(result)` + `task.done()` **on the pumping thread**; (b) merges `added` into `pending` sorted by priority, higher number first, FIFO within equal (L269-291); (c) submits `16 - inProgress.size()` items (L294-304) | `FileSystemImpl.updateAsyncTransactions L244-305` |
| **In-flight cap 16** (two literals `bipush 16`), and a finished task frees its slot only at the next pump | same |
| Pumped from `GameWindow.logic L415-416` (every frame), `GameWindow.initFonts L1157-1160` (10 ms loop), `ModelManager.initAnimationMeshes L2094-2098` (10 ms loop) | GameWindow, ModelManager |
| Priorities: textures 7 (`TextureIDAssetManager.startLoading L28, L36`), meshes 6 (`MeshAssetManager.startLoading L34`), physics shapes 6 (`PhysicsShapeAssetManager.startLoading L34`), depth maps 6 (`TileDepthTextures.loadDepthTextureImages L133-135`), world-map data 6/other (`WorldMapDataAssetManager.startLoading`), animations **4**, `bob_idle/walk/run` 6 (`AnimationAssetManager.startLoading L25-28`) | — |

### Everything that can be in the queue (complete list, from a full extraction of `zombie/*`)

FileTask subclasses: `FileTask_LoadAnimation`, `FileTask_LoadMesh`, `core.physics.FileTask_LoadPhysicsShape` (these three
extend `FileTask_AbstractLoadModel`, i.e. jassimp or `.txt`), `asset.FileTask_LoadPackImage`, `asset.FileTask_LoadImageData`,
`AiSceneAssetManager$FileTask_LoadAiScene`, `asset.FileTask_LoadAiScene`, `asset.FileTask_ParseXML`, `asset.FileTask_Exists`,
`FileSystemImpl$OpenTask`/`$CloseTask` (async open for `AssetTask_LoadFromFileAsync`, e.g. clothing XML),
`tileDepth.TileDepthTextures$LoadTask`, `worldMap.FileTask_LoadImagePyramidTexture`, `FileTask_LoadWorldMapBinary`,
`FileTask_LoadWorldMapXML`. Direct `runAsync` callers besides asset tasks: `TileDepthTextures`, `IngameState`,
`worldMap.ImagePyramid`. Asset managers: Texture/TextureID/AnimatedTextureID/NinePatch, Model/Mesh/AiScene/Animation,
AnimNode, ClothingItem, PhysicsShape, WorldMapData. Sounds (FMOD banks) do not go through this file system
(`SoundAssetManager` has no file task).

Candidates for this join's 39.8 s (not yet measured):
- **Physics shapes**: `Bullet.startLoadingPhysicsMeshes L115-123` loads one `PhysicsShape` asset per
  `ScriptManager.getAllPhysicsShapes()` (mods included), each a `FileTask_LoadPhysicsShape` = jassimp import
  (`FileTask_LoadPhysicsShape.loadX L64-82`: `validatePrefix` then `Jassimp.importFile`), queued **immediately before
  the wait** (runInner L391). PZ_Optimization's vanilla phase D does not list them, so vanilla's are few; with 245 mods
  the count is unknown.
- Texture pages/loose images (priority 7) still decoding, throttled by `TextureIDAssetManager.waitFileTask L73-79`
  (sleep 20 ms while `DirectBufferAllocator.getBytesAllocated() > 52,428,800`); the budget drains only as the render
  thread uploads (`TextureID.setImageData L419` → `RenderThread.queueInvokeOnRenderContext`).
- Depth maps: `TileDepthTextures$LoadTask.call L213-220` runs `createTileset(name, true)` → `TilesetDepthTexture.load()`
  **inside `synchronized (textures)`** (`createTileset L73-87`), so all depth maps of one `TileDepthTextures` instance
  decode one at a time. Queued by `TileDepthTextureManager.init` (boot and `Core.ResetLua L4163`).
- Meshes/AiScenes requested by the world load (items, vehicles, `BaseVehicle.LoadAllVehicleTextures` at runInner L318).
- Note: the DebugLog shows no "texturepack: loading" lines after the launch (0 after line 9000), so mod texture packs
  were not re-mounted on this join.

### Threads and serial stages

- Workers: 4 pool threads run `FileTask.call()` (decode, jassimp import, `ProcessedAiScene.process`).
- Main thread: every completion's callback (`handleResult` inside `updateAsyncTransactions`): for animations
  `AnimationAssetManager.loadCallback L37-49` → `AnimationAsset.onLoadedX` → `ProcessedAiScene.applyToAnimation
  L251-261` (interns every keyframe's `boneName` via `JAssImpImporter.getSharedString`, static unsynchronized maps) →
  `ModelManager.animationAssetLoaded L2058-2067` (`skinningData.animationClips.putAll`); for textures
  `TextureIDAssetManager.onFileTaskFinished L62-69` → `TextureID.setImageData`.
- Render thread: texture uploads (queued at `TextureID.setImageData L419`).
- Serial locks: depth maps (above). jassimp has no Java lock: `Jassimp.importFile L167-169` = `loadLibrary()`
  (double-checked, once) + native `aiImportFile`.
- Frame rate bounds throughput: ≤16 completions+refills per pump, one pump per frame. Viewpoint caps the frame rate in
  `GameLoadingState` with its *game* cap (`viewpoint.game.FrameCaps.playing()` treats `GameLoadingState` as playing;
  Workshop 3809306528 jar), and runs its own "Viewpoint far world" pool (`viewpoint.far.FarWorkers`) whose work logged
  1 s into the wait, competing for cores. The connecting phase logged a line per ~16.7 ms (~60 fps).

## 2. Animation-clip path

| Step | Where |
|---|---|
| Boot: `ModelManager.create L188` `initAnimationMeshes(false)` (waits for animation meshes, pumping, L2093-2098; then queues game anims via `loadAnimsFromDir`, L2107-2117); `L202` `loadModAnimations()` | ModelManager |
| Join/reload: `Core.ResetLua L4154-4155` `initAnimationMeshes(true)`, `loadModAnimations()` | Core |
| Per mod: `loadModAnimations L1975-2008+`: mod `animsXFile` common/version folders → `loadAnimsFromDir` → `loadAnim L1846-1853`: `AnimationAssetManager.load(new AssetPath(name), params{animationsMesh})`, `anim.skinningData = mesh.skinningData`, `putAnimationAsset` (per-`ModAnimations`, priority-ordered) | ModelManager |
| Task: `AnimationAssetManager.startLoading L19-34` → `FileTask_LoadAnimation` (prefixes `media/anims`, `media/anims_x`) | — |
| Run (worker): `FileTask_AbstractLoadModel.call()` → `checkExtensionType()` (calls `getRawFileName()` = asset path, resolves `fileName` through `ZomboidFileSystem.getString`, picks .txt/.fbx/.glb/.x) → `loadX/loadFBX/loadGLTF/loadTxt` | FileTask_AbstractLoadModel |
| `FileTask_LoadAnimation.loadX L46-66`: `validatePrefix(fileName)` (L51), `Jassimp.importFile(fileName, {MAKE_LEFT_HANDED, REMOVE_REDUNDANT_MATERIALS})` (L52), `ProcessedAiScene.process(params{scene, mode=AnimationOnly, skinnedTo=animationsMesh.skinningData})` (L54-63), `takeOutTheTrash(scene)`. FBX/GLTF add `animBonesScaleModifier=0.01` and a -90° X rotation | FileTask_LoadAnimation |
| Skeleton dependency: `ImportedSkeleton.processAiScene L95-97` copies `skinnedTo.boneIndices` and `skinnedTo.skeletonHierarchy`; clips are built with `new AnimationClip(duration, keyframes, name, bool)` | ImportedSkeleton |
| Result: `ProcessedAiScene` (private ctor; private `skeleton` → `ImportedSkeleton.clips: HashMap<String, AnimationClip>`) | — |

Result objects and serializability:
- `AnimationClip` (final, not Serializable): `name`, `isRagdoll`, `keepLastFrame`, `duration`, `keyframeArray`,
  derived `keyFramesByBoneIndex` (rebuilt by the ctor: `AnimationClip.<init> L30-41` → `recalculateKeyframesByBoneIndex`),
  `rootMotionKeyframes` (only ever the ctor's empty list). Public ctors `(float, List<Keyframe>, String, boolean[, boolean])`.
- `Keyframe` (not Serializable): public `rotation` (lwjgl `Quaternion`), `position`, `scale` (`Vector3f`), `bone`,
  `boneName`, `time`. lwjgl `Vector` is `Serializable`. A cache needs its own binary format, rebuilt through the public ctor.
- A second, simpler delivery path exists: `ModelTxt` (public no-arg ctor; package-private `clips`), accepted by
  `AnimationAssetManager.loadCallback L41-44` → `ModelLoader.applyToAnimation L385-387` (sets `animationClips`, putAll
  into the mesh's `SkinningData`). It skips the bone-name interning `applyToAnimation` does.
- Clips are mutated after load (`AnimationClip.setRagdollSimulationActive`; `RuntimeAnimationScript.exec` at
  `GameLoadingState.update L868-871`), so a cache must be written from the import result, not from live clips later.

Safe cache key (what the result depends on): resolved absolute `fileName` (mod overrides change it) + file size and
mtime (or a content hash) + loader variant (X vs FBX/GLTF modifiers, post-process flags) + the skinning skeleton of
`params.animationsMesh` (bone names in index order + parent indices, as `ImportedSkeleton` copies them) + game build.
PZ_Optimization pitfall (override-edits.md, AnimationAssetManager section): keying the mesh by asset path
(`Skinned/MaleBody`) served vanilla bone indices to Tomb's Player Body (extra `...Nub` bones) and mangled every
character; they key on the skeleton identity, not the mesh file's mtime. Their numbers: 2,209 imports = 14-17
thread-s per boot → 2.9 thread-s from cache; 89 MB cache for 2,161 files.

## 3. R1 instrumentation: JDK-only hook points

Glue rule: only the generated glue's method/field descriptors are checked (`buildSrc/.../check/CheckJdkGlue.kt`);
handler bodies may use game types. Available injectors: `@Inject` HEAD/RETURN/FINALLY (cancellable),
`@ModifyReturnValue`, `@ModifyVariable` (HEAD, args only); no constant/redirect/overwrite
(`zabric/.../hook/At.java`, `ModifyVariable.java`).

| Hook (descriptor is JDK-only) | Thread | Gives |
|---|---|---|
| `DebugType.println(Ljava/lang/String;)V` HEAD, filter the two "bWaitForAssetLoadingToFinish2 ..." strings | loader | exact wait start/end |
| `FileSystemImpl.hasWork()Z` HEAD (or the existing `ModelManager.isLoadingAnimations()Z` hook) | main, once per frame of the wait | snapshot `pending` + `inProgress` by task class and name (same thread as their only writer); count frames |
| `FileSystemImpl.updateAsyncTransactions()V` HEAD/RETURN | main | pumps/s, completions per pump (inProgress size delta), main-thread callback time |
| `FileTask_AbstractLoadModel.call()Ljava/lang/Object;` HEAD/FINALLY | worker | per model/anim/physics-shape task time (ThreadLocal start) |
| `FileTask_LoadAnimation/LoadMesh/LoadPhysicsShape.getRawFileName()Ljava/lang/String;` RETURN | worker, inside `call()` | asset name of the running task |
| `ZomboidFileSystem.validatePrefix(Ljava/lang/String;)V` HEAD (worker threads only; also called from `LuaManager$GlobalObject`) | worker | resolved absolute path just before jassimp → Workshop folder → mod |
| `jassimp.Jassimp.importFile(Ljava/lang/String;Ljava/util/Set;)Ljassimp/AiScene;` HEAD/FINALLY | worker | jassimp-only time per absolute path |
| `FileTask_LoadPackImage/FileTask_LoadImageData/TileDepthTextures$LoadTask/AiSceneAssetManager$FileTask_LoadAiScene/FileTask_LoadWorldMap*.call()Ljava/lang/Object;` HEAD/FINALLY | worker | time by task type |
| `FileSystemImpl.getTexturePackDevice(Ljava/lang/String;)...` HEAD | worker (in `FileTask_LoadPackImage.call L35`) | texture pack name |
| `TileDepthTextures.createTileset(Ljava/lang/String;Z)...` HEAD/FINALLY | worker, under the lock | depth-map tileset name and serial time |
| `TextureIDAssetManager.waitFileTask()V` HEAD/FINALLY | worker | time in the 50 MB throttle; read `DirectBufferAllocator.getBytesAllocated()J` |
| `Bullet.startLoadingPhysicsMeshes()V` HEAD/RETURN | loader | physics shapes queued (`ScriptManager.getAllPhysicsShapes().size()`) |

Name/mod attribution from the snapshot needs the queue's members, which are not public: `FileSystemImpl.pending`,
`inProgress` (private), `FileSystemImpl$AsyncItem` (package-private class; `task`, `future` package-private),
`FileTask_LoadAnimation.anim` (private), `FileTask_LoadPackImage.packName/imageName`, `FileTask_LoadImageData.imageName`,
`TileDepthTextures$LoadTask.path`, `AiSceneAssetManager$FileTask_LoadAiScene.filename` (package-private). Public:
`getRawFileName()`, `FileTask_AbstractLoadModel.fileName` is protected. These go in Loading Progress's `.classtweaker`.
Do not read `added` without the `lock` CAS (`hasWork L313-321`).

## 4. R2 levers and their hook points

| Lever | Hook point / mechanism | Measurements, pitfalls |
|---|---|---|
| Wider pool during the wait | widened `FileSystemImpl.executor` is a `ThreadPoolExecutor`: `setMaximumPoolSize` then `setCorePoolSize` at wait start, back after (wait markers above, or `GameLoadingState.exit`) | PZ_Opt: 14 threads + 256 MB texture budget **rejected** (one 5.05 s frame: uploads piled on the render thread); 14 threads during `IsoWorld.init` slowed the 8 meta-grid loaders 1.5 → 4.0 s; adopted `cores/2` (8) for load and the full pool only while the loader waits (`fileThreadsWait`) |
| In-flight cap 16 | literal in `updateAsyncTransactions L244/L294`: only by cancelling HEAD of `updateAsyncTransactions()V` and running an equivalent body (widened fields), or by pumping more often (extra `updateAsyncTransactions()` calls on the main thread, e.g. from the per-frame `hasWork()Z` hook) | PZ_Opt Mac: backlog drained "at exactly 16 tasks a frame"; `fileInflightLoad` 128 until world entry + full pool: Continue 8.16 → 6.25-6.64 s |
| Texture decode budget | `TextureIDAssetManager.waitFileTask()V` HEAD-cancel and own wait | PZ_Opt kept stock 50 MB after the 256 MB 5 s frame |
| Depth maps out of the lock | `TileDepthTextures$LoadTask.call()Ljava/lang/Object;` HEAD-cancel: build the tileset outside, publish under the lock | PZ_Opt: 218 vanilla maps = 2 s single-thread + 6.1 s summed lock waits; ~0.2 s after. Whether `TilesetDepthTexture.load` touches GL needs checking |
| Physics shapes earlier/cached | queued at `Bullet.startLoadingPhysicsMeshes` (runInner L391); same jassimp path as animations (`FileTask_AbstractLoadModel`) | count unknown (R1) |
| Animation clip cache | read side: HEAD-cancel `FileTask_AbstractLoadModel.call()Ljava/lang/Object;` returning a `ModelTxt` with `clips` (or a `ProcessedAiScene` via widened ctors); needs the task (`@Self`) or a ThreadLocal; write side: from the worker's import result | helps launch CPU (14-17 thread-s, overlaps menu) and joins that change the mod set; **not** this join's wait (section 0) |
| Pump during blocking main-thread phases | the pool is idle while the main thread blocks (Lua, `ResetLua`) because only the main thread pumps | PZ_Opt `bootPump` (thread pumping every 3 ms + lock around the pump): boot 4 s idle removed; font-callback NPE on the pump thread was a pitfall |

The `@Self`/game-type rule applies to preloaded mods' glue; whether the new optimization mod must be preloaded depends
on when its hooks must be open (FileSystemImpl is built in `GameWindow`'s static init; animations queue at
`ModelManager.create`, after "Loading mods" in the launch report).

## 5. Unknowns that need an in-game measurement

1. What is in `pending`/`inProgress` at wait start and how it drains (class, count, names, mods) — physics shapes vs
   textures vs depth maps vs meshes.
2. Main-thread frame rate during the wait (pumps/s) and whether Viewpoint's cap or its far-world work lowers it.
3. Per-task worker time vs idle time of the 4 workers (are they saturated, starved by the 16/frame cap, or sleeping in
   `waitFileTask`?).
4. Depth-map lock wait totals on this mod set; how many `TileDepthTextures` instances share one lock.
5. Whether `FileSystemImpl` loads after Zabric's widener (it loads in `GameWindow.<clinit>`; `ZomboidFileSystem` is a
   known "already loaded" class) — `smokeTest` shows it.
6. Earlier 10-17 research measured the same wait at 16.9 s; what changed to 39.8 s (mod set, Viewpoint version).

## Caveats / Not found

- The "Animation 2291/2291 from the first poll" conclusion assumes `ModelManager.animationAssets` ⊆ the asset table
  (true by construction in `loadAnim`); an asset removed from the table but still in `animationAssets` would not be seen.
- Physics-shape count and world-map data loads during this join were not found in the log.
- Mod attribution from absolute paths relies on Workshop folder layout (`.../108600/<id>/mods/<mod>/...`).

## Recommended R1/R2 approach

R1 (Loading Progress, measurement only): (a) wait markers via `DebugType.println(String)`; (b) per-frame snapshot of the
file queue in the existing `isLoadingAnimations()Z` hook (or `hasWork()Z`), grouped by task class and by mod from the
task's name/path, plus pump count; (c) worker timing by task type via HEAD/FINALLY on each `call()Ljava/lang/Object;` and
on `waitFileTask()V`, with names from `getRawFileName()` RETURN / `validatePrefix(String)` HEAD / `createTileset(String,Z)`
HEAD; report "asset wait: N tasks, by type (count, worker-s), top files/mods, frames, throttle sleep". Replace the
misleading "Animation 2291/2291" label with the queue breakdown.

R2 (new mod), in order once R1 names the queue: during the wait only, widen the pool to the cores and raise the
effective in-flight budget (extra pumps, or a cancel-and-replace of `updateAsyncTransactions`), keep the 50 MB texture
budget; take depth-map decode out of the lock if depth maps show up; cache jassimp results for physics shapes and
animations with the full key above (skeleton identity included) if `FileTask_AbstractLoadModel` work dominates.
