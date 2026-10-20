# Research: the frame gap through mod discovery and ZombieBuddy's Java-mod pass

- **Query**: How to keep the bar moving (ideally ≤1 s gaps) through mod discovery and ZombieBuddy's Java-mod pass on
  `MainThread`: render-thread drawing, a progress source inside the pass, ZombieBuddy-side or other alternatives;
  feasibility, hook targets, risks, effort, recommendation.
- **Scope**: mixed (javap of 42.21 `projectzomboid.jar`; ZombieBuddy v2.3.4 at `external-repos/ZombieBuddy` of the
  main checkout, the worktree's copy is empty; upstream master via `gh api`; Zabric source; logs)
- **Date**: 2026-10-19

Evidence: `~/Zomboid/Logs/logs_2026-10-19_17-39/2026-10-19_17-38_DebugLog.txt` (below "DL"), and from the same run (same
`Loader.loadMods() took 6834 ms`, file times 17:39:39-43) the session scratchpad's `console-menu1.txt` ("CON") and
`classload-menu1.txt` ("CL", `-Xlog:class+load`, JVM uptime). Javap dumps: scratchpad `zbgap/`. `Class Lnnn` = source
line from the class's LineNumberTable.

## Findings

### 1. What the 9.8 s gap is made of

DL L8300: `launch: done after 40.6 s ...; 206 own frames, largest frame gap 9809 ms in Loading mods`.

| Phase (all `MainThread`) | From → to (DL) | Time |
|---|---|---|
| Discovery: `GameWindow.init` "Loading Mods" → `ZomboidFileSystem.loadMods(String)` "default": `readDefaultModsTxt`, `ActiveMods.checkMissingMods`, `checkMissingMaps` (ZFS L871-874), then `loadMods(List)` | 17:38:58.105 (L139) → 17:39:01.350 (L141) | 3.25 s, one log line between (00.364 "refusing to list examplemod") |
| ZB `Loader.loadMods(List)`: mod infos, approvals, sha256 + ZBS | 01.350 → 01.636 | 0.29 s |
| per jar `loadJar` (classpath, `Main`, `PatchEngine.applyPatches`) | Zabric 0.08, ZBK 0.08, AIO 0.23, **Viewpoint 1.47** (02.036 → 03.503), vptw 0.35, vtwg 0.44, c1pv 0.09, bloodfx 0.41, PV shelves 0.43, PV vegetation 0.21, **ISeeThem 2.0** (05.452 → 07.447) | 5.8 s |
| Zabric's first-pass work at `loadMods(List)` exit (weave-check retransforms, `hooks ready`, main entrypoints) | 07.447 → 08.184 ("took 6834 ms") | 0.74 s (one step 07.547 → 07.902) |

Inside a jar the time is spread evenly: Viewpoint logs `patching X` / `Transformed: X (retransformed)` every ~15-20 ms
from 02.172 to 03.485 (no log gap ≥ 0.3 s anywhere in 01.350-08.184). Those lines are printed inside the ByteBuddy
transformation that `PatchEngine.applyPatches` starts with one `builder.installOn(Loader.g_instrumentation)`
(PatchEngine.java:625, RETRANSFORMATION strategy, default batching; listener logs at :240, `patching` at :289). So a
jar's cost is one `installOn` call that retransforms class after class; there is no point inside it outside a
`ClassFileTransformer` callback.

**Why the existing trigger did nothing here.** `ChooseGameInfo.getAvailableModDetails` (TriggerHooks.ModDetails, called
per mod by `checkMissingMods` and by ZB's loop at Loader.java:625) was not woven during this phase:
`ChooseGameInfo` first loads at CL 1.000 s, while ZB retransforms the already-loaded `ZomboidFileSystem` at premain for
this mod's glue (CL 4749, `ZomboidFileSystem` redefined at 1.029 s, CL 4773), i.e. inside a transformation, unseen by
the agent's transformers (the effect design.md "Mechanism" describes for `LuaManager$GlobalObject`). Its hook is woven
only by Zabric's first-pass retransform: CON L1561-1563 `patching zombie.gameStates.ChooseGameInfo.getAvailableModDetails`
/ `Transformed: ... (retransformed)` at DL 07.857 (CL 11820, 14.986 s `__VM_RedefineClasses__`). The 9809 ms gap ends
~07.91, right after. Design.md's trigger list ("per mod ... in ZombieBuddy's pass") does not hold on the launch.
`ZomboidFileSystem` itself is woven at premain (its `loadMods` hooks fire), so ZFS methods are usable as launch triggers.

### 2. Render-thread drawing

**Loop** (`RenderThread.renderLoop` L112-180, under `m_contextLock`): `renderStep()` → `lockStepRenderStep()`
(L265-297): no ready state and `waitForRenderState == false` → `Display.processMessages()` (L272); ready state →
`SpriteRenderer.postRender()` (L285), `Display.update(true)` (L289 = `swapBuffers` + `processMessages`, Display
L393-400). Then `flushInvokeQueue()` (L144), input poll, `Thread.yield()` (L168). `waitForRenderState` is false through
the whole launch until `GameWindow.mainThreadStart` L539, and `Core.ResetLua` sets it false at L4096 before its own
`loadMods`, so the loop spins (thousands of iterations/s) during every ZB pass.

**Entry points (all JDK-only; none needs `@Self`)**

| Target | Descriptor | Runs | Notes |
|---|---|---|---|
| `org.lwjglx.opengl.Display.processMessages` | `()V` public static | each idle iteration (L272) and after each real swap (Display L400) | already hooked by AIO's ime-keyboard-fix (`ImeWindow.kt:21`, FINALLY) and patched by Viewpoint (DL L211/485); `Display` loads at CL 1.177 s, after premain, so the glue weaves at first load |
| `zombie.core.opengl.RenderThread.flushInvokeQueue` | `()V` private static | each iteration (L144) and each wake in `waitForRenderStateCallback` (L304) | private target; not checked whether Zabric's processor accepts it |
| no hook: `RenderThread.queueInvokeOnRenderContext` | `(Ljava/lang/Runnable;)V` public static | from any thread: appends to the queue, no wait, no notify (L568-594); run by the next `flushInvokeQueue` | needs a mod-owned timer thread; `RenderContextQueueItem.invoke` catches and logs a throwable as "%s thrown during invoke()", so the runnable must route its own errors to the mod's stop |
| capture point: `Display.update` | `(Z)V` public static | before each real swap (L289) | back buffer then holds the complete frame; Viewpoint also patches `Display.update` (`viewpoint.Patch_FrameSwap`, DL L492) and `SpriteRenderer.postRender` (DL L410) |

Gate every entry on `Thread.currentThread() == RenderThread.renderThread` (public static field),
`!RenderThread.isWaitForRenderState()`, a showing flow in an own-frames step, and the last handed-over frame being old
(`Progress.lastFrame`, volatile).

**What it can draw with.** Only raw GL. Game text cannot run there: `AngelCodeFont.drawString` → `CharDef.draw(FF)`
writes into `SpriteRenderer.instance.states.getPopulatingActiveState()` (the state `MainThread` is filling) and uses
static scratch fields (`xoff`, `curR`, `scale`, `data`); calling it on the render thread corrupts `MainThread`'s
frame. ImGui exists (`Display.imGuiNewFrame`, `drawImGuiDrawData`) but only when `Core.isImGui()`; a private ImGui
context plus a CJK font atlas is possible in principle, not evaluated. Practical scope: bar rectangles (and digits only
with an embedded bitmap font).

**Context and state.** No profile/version hints in `Display.create` (only `CLIENT_API=OPENGL_API`, debug, resizable),
so a compatibility context (log: `OpenGL version: 4.6.0 NVIDIA`). The game caches render-thread GL state:
`GLStateRenderThread` (Blend, ScissorTest, ColorMask, DepthTest, Stencil…, `startFrame`/`restore`) and
`Texture.lastTextureID`; anything the overlay changes must be restored to the exact prior value. Minimal-state recipe:
GL 4.5 DSA (`glCreateTextures`/`glCreateFramebuffers`/`glBlitNamedFramebuffer`/`glClearNamedFramebufferfv`) touches no
texture or framebuffer binding; save/restore `GL_SCISSOR_TEST`, `GL_SCISSOR_BOX`, `GL_COLOR_WRITEMASK` (blits and
clears honour scissor; clears honour the color mask), and the default framebuffer's read/draw buffer. Without 4.5 the
recipe also saves/restores `GL_READ/DRAW_FRAMEBUFFER_BINDING` and `GL_TEXTURE_BINDING_2D`.

**Buffer semantics.** GLFW sets no swap behaviour; after `glfwSwapBuffers` the back buffer is undefined (exchange or
copy is the driver's choice). Drawing only the bar and swapping would show the bar over garbage or a two-frames-old
image. Workable ways:

- Capture: at `Display.update(Z)V` HEAD, while a flow shows in an own-frames step, blit the back buffer into a
  screen-sized mod texture/FBO (one GPU copy per real frame, ~31 MB at 3840x2054; re-create on resize). An interim
  frame = blit capture → default back buffer, scissor-clear the bar rectangles (the bar is opaque in `Overlay.draw`:
  0.3 grey track, 0.85 fill; the 0.6-alpha panel and the text come with the capture), `Display.swapBuffers()` (`()V`,
  L472, `glfwSwapBuffers` only, so no `Display.update` advice of Viewpoint or of the capture hook runs). Text stays as
  in the last `MainThread` frame. Coordinates: GL is bottom-up; `Overlay` uses `Core.getScreenWidth()`, the blit uses
  `Display.getFramebufferWidth/Height()`; equal in the logged windowed mode, to be checked for other modes.
- Front-buffer drawing (`glDrawBuffer(GL_FRONT)`, no swap) would keep the last image, but under DWM/flip-model
  presentation front-buffer writes are not reliably shown; not recommended.
- Full redraw without capture (black + bar) loses the step line and, in later steps, the epilepsy warning; during the
  launch's mods step the own frames are black + overlay (warning comes later, `GameWindow.init` L1139), so only the
  text would be lost there.

**Thread safety.** All GL stays on the context thread; `MainThread` never issues GL (it queues through
`invokeOnRenderContext`). The interim frame runs between `renderStep` calls, never inside `postRender`. It must not
wait on anything `MainThread` holds long: `Progress.view()` is `synchronized` but its holders are short; the drawer's
classes should be loaded before the pass (first-use class loading on the render thread while ZB appends jars is
legal but avoidable). ZB's retransformations of `Display` while the render thread is in it already happen today
(CL 9.061, 10.332, 14.740 s). VSync on: `glfwSwapBuffers` blocks ≤ one refresh, fine at ≤ 20 Hz.

**What would move.** D2 fills the bar by step only, so through the mods step an interim frame without a new input
redraws the same bar: render-thread frames help only together with a progress source (§3). PRD R8 says "Frames come
only from MainThread"; render-thread frames change that requirement.

### 3. Progress source inside the pass

- **Zabric seam, per jar (safe point on `MainThread`).** `ZombieBuddy.Loader.loadMods(List)` calls
  `loadJar(jarPath, pkg, hash, Phase.MAIN)` in a plain loop after deciding on every jar (Loader.java:936-941; decisions
  recorded in `g_jarLoadStatus` at :891 before the loop). `static boolean loadJar(Path, String, String, Phase)`
  (Loader.java:977; still present on upstream master L501) can get ByteBuddy Enter/Exit advice exactly like
  `ModsLoadedHook` (`zabric/.../loader/ModsLoadedHook.java:34-77`, installed from `Lifecycle.install` at Zabric's
  premain, `entrypoint/Lifecycle.java:73`). Advice binds `@Argument(0) Path`, `@Argument(1) String` and
  `@Argument(3) Object` (compare the enum's `name()` with `MAIN`: `loadJar` also runs for PREMAIN, Loader.java:450).
  Total and mod ids: `LoadStatus.loaded(LoadStatus.currentPass())` (`Loader.loadedThisPass`/`currentPass`, already
  valid inside the pass). Public API would be new (the `loader` package is internal, `loader/package-info.java`), e.g.
  in `com.pzlauncher.zabric.entrypoint`: a listener with JDK-only parameters `(int index, int total, String modId)`
  called on `MainThread` before and after each jar, outside any transformer, so the mod can update `Progress` and call
  `FramePump.tick` there (same situation as today's tick at ZB's :625). Zabric's own first-pass steps (each weave-check
  retransform, each main entrypoint) can call it too. Gaps left with this mod list: ISeeThem ~2.0 s, Viewpoint ~1.5 s.
- **Zabric heartbeat (finer, for a render-thread drawer only).** Every class ZB retransforms passes through every
  retransform-capable transformer; Zabric already registers one (`WeaveCheck`, `inst.addTransformer(this, true)`,
  `hook/impl/WeaveCheck.java:123`, `transform` :164). A counting transformer (or counter) exposed as a volatile
  "loader work units" value ticks every ~15-20 ms through `installOn`. It runs inside `ClassFileTransformer.transform`,
  so it may only count, never draw.
- **ZombieBuddy log lines.** ZB's `Logger` prints to `System.out` (Logger.java:22-27), which the game routes to
  `zombie.debug.DebugLogStream`; hooking game logging as a heartbeat is possible in principle, not verified, fragile
  (depends on ZB's verbosity and every log call pays the check).
- **Time-based indeterminate stripe.** Not a fill, so not literally against D2, but it moves even if `MainThread` is
  stuck (the render thread runs on its own), so it no longer tells the player the load progresses; the window is
  already responsive here (idle loop pumps at L272). A heartbeat-driven marker (moves one notch per N work units)
  keeps the meaning. User decision.

### 4. Alternatives

- **ZombieBuddy offers nothing usable.** `Callbacks` (v2.3.4: `onGameInitComplete`, `onDisplayCreate`; master adds
  `afterExposeAll`, `onEndFrameUI`, `beforeLuaInit`, `afterLuaInit`) has no loader progress; `EventsAPI` is Lua-facing;
  `LogOverlay`/`Watermark` draw in frames `MainThread` hands over. Upstream: `v3.0.0-beta2` tag; master 2026-10-18.
- **Upstream PR to zed-0xff/ZombieBuddy.** Could add a per-jar callback and batch `installOn` with ByteBuddy's
  `RedefinitionStrategy.BatchAllocator` plus `Listener.onBatch`, which runs on the calling thread between batches,
  outside any transformer. Drawing a game frame there would still run render code while another mod's patches are half
  installed (Viewpoint patches `SpriteRenderer.postRender`, `Display.update`, `Core`); as a progress source only it is
  clean. Timeline outside our control.
- **ZB pass off `MainThread`.** Not feasible: mods' `Main.main` runs inside it (`try_call_main`, Loader.java:1027) on
  the game thread they expect, the game's `loadMods(List)` needs the patches before its `loadMod` loop, and the
  change would live in ZB's `Patch_loadMods2.enter` (Patch_ZomboidFileSystem.java).
- **Discovery (3.25 s) is a separate, mod-only fix.** `getModDetails` (ChooseGameInfo L121-145) scans every mod
  folder on first use: `getAllModFolders` → `getAllModFoldersAux(Ljava/lang/String;Ljava/util/List;)V` (private, ZFS
  per root folder) then per folder `readModInfo` (L132) and `ZomboidFileSystem.setModIdToDir(Ljava/lang/String;Ljava/lang/String;)V`
  (public, L134). `ZomboidFileSystem` is woven at premain, so HEAD triggers on `setModIdToDir` and `getAllModFoldersAux`
  fire on the launch (unlike anything in `ChooseGameInfo`). The split of the 3.25 s between Steam folder queries,
  the scan and `checkMissingMaps` → `MapGroups.createGroups` (game-typed, no JDK-only hook) is not profiled.

### 5. Options compared

| Option | Gap reached (this mod list) | Zabric change | Mod change | Risks | Effort |
|---|---|---|---|---|---|
| A. Discovery triggers on ZFS `setModIdToDir` / `getAllModFoldersAux` | splits the 3.25 s if the folder scan dominates (unverified) | none | 2 trigger hooks; fix design.md's `getAvailableModDetails` claim | none new | S |
| B. Zabric per-jar listener + `MainThread` own frames | ≤ ~2.0 s (ISeeThem's `installOn`), text "Java mod i / n: id" updates | seam advice on ZB `Loader.loadJar`, public JDK-only listener API, calls from its first-pass steps, tests, version bump | subscribe at preLaunch, new item kind, tick | ZB internal method dependency (throw naming the version, as the seam does) | M |
| C. B + render-thread interim frames (capture + bar) + Zabric heartbeat | ≤ ~0.25 s visible change through `installOn` | B + heartbeat counter API | `Display.update(Z)V` capture hook, `Display.processMessages()V` (or queue thread) drawer with GL save/restore, gap report counting render-thread frames; PRD R8 change | GL state corruption if anything is not restored; Viewpoint's `Display.update`/`postRender` patches; screen vs framebuffer size; exceptions on the render thread | L |
| D. Time-based stripe on the render thread | any | none | as C without capture-free text | moves while hung; D2/R8 intent | M |
| E. ZB upstream callbacks/batching | as B or finer | adapt seam when released | as B | external timeline | n/a |

**Recommendation (for the main agent to weigh):** A plus B. Both keep R8's "frames only from `MainThread`" and the
existing drawing path, and B's API stays JDK-only. Treat the remaining within-jar time (one ZB `installOn` per jar:
~1.5-2.0 s here, unbounded for other mod lists) as a known gap like D8, named in the frame-gap line. Go to C only if
≤1 s through the pass is a hard requirement and the user accepts render-thread frames (R8 change) with a bar-only,
heartbeat-driven change; avoid D.

### Files found

| File | Relevance |
|---|---|
| `external-repos/ZombieBuddy/java/src/main/java/me/zed_0xff/zombie_buddy/Loader.java` (main checkout) | `loadMods(List)` :596-942, `loadJar` :977, `ApplyPatchesFromPackage` :996, `try_call_main` :1027, `Phase` :34 |
| `.../zombie_buddy/PatchEngine.java` | `applyPatches` :134, listener :226-260, `installOn` :625, explicit retransform :627-650 |
| `.../zombie_buddy/patches/Patch_ZomboidFileSystem.java` | ZB pass runs at `loadMods(List)` enter |
| `.../zombie_buddy/Callbacks.java`, `Logger.java` | no progress events; log to `System.out` |
| `zabric/src/main/java/com/pzlauncher/zabric/loader/ModsLoadedHook.java`, `Loader.java`, `LoadStatus.java` | seam pattern and pass records to reuse |
| `zabric/src/main/java/com/pzlauncher/zabric/entrypoint/Lifecycle.java:65-79` | where seam hooks are installed (premain) |
| `zabric/src/main/java/com/pzlauncher/zabric/hook/impl/WeaveCheck.java:123,164` | retransform-capable transformer |
| `loading-progress/src/main/java/com/dont39/pz/loadingprogress/draw/FramePump.java`, `Overlay.java`, `hooks/TriggerHooks.java`, `hooks/LoadHooks.java` | current own frames, layout, triggers |
| javap: `RenderThread`, `SpriteRenderer`, `Display`, `ChooseGameInfo`, `ZomboidFileSystem`, `ActiveMods`, `AngelCodeFont(+$CharDef)`, `GLStateRenderThread`, `RenderContextQueueItem` | anchors above |

### Related specs

- `.trellis/spec/mod/zabric-hooks.md` — hook runtime, glue contract (no rule found on private targets).
- `.trellis/spec/mod/access-widening.md` — Zabric must not load game classes early.
- Task `prd.md` D2, D8, D9, R8; `design.md` "Drawing", "Mechanism".

## Caveats / Not Found

- The discovery split (Steam folder queries vs folder scan vs `checkMissingMaps`) is not profiled; option A's gain is
  an estimate until the frame-gap line is re-measured.
- Per-jar times are one run with this mod list; `installOn` cost scales with a mod's patch targets, so B's worst gap
  is not bounded in general.
- Not verified: Zabric/ZB weaving of private targets (`flushInvokeQueue`); Viewpoint's behaviour with extra
  `glfwSwapBuffers` calls; `Core.getScreenWidth()` vs framebuffer size in fullscreen/borderless; GL 4.5 DSA
  availability on the minimum-spec GPUs.
- The worktree's `external-repos/ZombieBuddy` is empty; ZB line numbers are from the main checkout (`v2.3.4`, matches
  the running 2.3.4).
