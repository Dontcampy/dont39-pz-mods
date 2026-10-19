# Design: Loading progress without a frozen window

Two deliverables: a Zabric change (Z, R1–R4) and a new mod (P, R5–R12) that needs it. Anchors: `research/
loading-flow.md` (LF) and `research/design-inputs.md` (DI). DI's Zabric and build line numbers predate the 0.4.1
runtime and build refactors (PRD Background); this file names the current symbols, and game (javap) anchors are
unchanged.

## Z — Zabric: preloaded hooks open at preLaunch, launch-wide opt-in

### Contract

- Hooks of a mod Zabric keeps preloaded (it has a preLaunch entrypoint and ZombieBuddy preloaded its jar this launch)
  open at preLaunch. Until the first pass they act for the whole launch; from the first pass on they follow the mod
  list as today, unless the mod declares launch-wide hooks.
- New manifest attribute `Zabric-Hooks: launch` (constant next to `Lifecycle.PRE_LAUNCH`/`MAIN`, read by
  `Entrypoints.declared` into its `Declared` record): the mod's hooks stay open on every pass, also on passes whose
  list lacks it, until a stop.
  On a mod that is not preloaded it is an error logged with the mod id at its pass and ignored. Any other value is an
  error, like a blank entrypoint class.
- No change to the hook listing format or the glue's API (`zabric-hooks.md` "only grow"); old glue works unchanged.
- Author docs say: launch-wide hooks are for client-side UI only; such a mod runs on servers that did not ask for it.

### Mechanism

- `Lifecycle.preLaunch`: its loop over `Loader.preloadedJars()` already reads each jar's `Entrypoints.declared`; after
  the preLaunch entrypoints, it passes the mods with a preLaunch entrypoint, their jars and the launch-wide flag to a
  new `HookRuntime.preLaunch(jarsByMod, launchWide, loader, inst)`. No game class, no game log.
- `HookRuntime.preLaunch`: reads each jar's listings (`HookListing.readAll`, bytes only), marks the mod checked
  (`firstPass()` consumed), opens it, and registers `WeaveCheck` after ZombieBuddy's premain transformers (they were
  registered at premain, DI A2) with a watched table built as `afterPass` builds it. The reflective target check
  (`TargetCheck.problem(listing, loader)`: `Class.forName` + `getDeclaredMethods`) is not used for these mods: it
  would load game classes before the game does (`access-widening.md` Zabric rules).
- Targets already loaded at preLaunch (ZombieBuddy loads some at premain, e.g. `LuaManager`, `ZomboidFileSystem`) are
  checked from their class files there, not retransformed: ZombieBuddy describes a retransformed class by reflection
  and so loads the types in its signatures, inside its transformation, where no transformer of the agent sees them
  (measured 2026-10-19: retransforming `LuaManager$GlobalObject` at preLaunch loaded 123 game classes, `ScriptManager`
  among them, which the access widener never saw). Their weaving is checked at the first pass, where `WeaveCheck`
  first loads a target's member types, then retransforms it; it also retransforms every target it has not seen load.
- Byte-based target check: a bytes overload in `TargetCheck` that produces the reflective check's messages. For a mod
  opened at preLaunch, `WeaveCheck.transform` calls it on the class's first load (ASM `ClassReader`, ByteBuddy's
  shaded copy as `WeaveCheck` already uses), next to its woven-advice check. A finding stops the mod through its gate
  in the same `transform` call, before the class is defined, so no hook in that class runs unchecked.
- `ModState`: add `preLaunchOpen` (set at preLaunch, cleared by the first pass unless launch-wide) and `launchWide`.
  `open = (inPass || preLaunchOpen || launchWide) && !held && !stopped`; `live()` uses the same membership so
  `WeaveCheck` keeps checking a launch-wide mod's late targets (DI A2 table). `passProcessed` does not `leavePass` a
  launch-wide mod and does not hold a mod that is already open and checked.
- Held log: from `HookRuntime.preLaunch` until `Lifecycle.start`, `HookRuntime.log` is a buffering `HookRuntime.Log`
  (set through `logTo`, as the tests do); `start()` replays it in order to the game log, then switches to `GameLog`.
  `GameLog` touches `DebugType` only when called, so nothing may call it before the switch. Gate failures in that window (default gate's
  `hooks stopped after an error while …`) are buffered the same way. `Lifecycle.start` keeps its existing order:
  Zabric's own failure first, then preLaunch outcomes, then the held hook lines.
- Stops found at the first pass (`!zabricLoaded`, `PreLaunchHook.problem()`, an earlier main failure) call
  `closeAll()`, which now also clears `preLaunchOpen`/`launchWide`. The report says the hooks ran until then.
- Version: 0.4.1 → 0.5.0 (`gradle/libs.versions.toml`, `zabricVersion` in `samples/zabric-java-hooks/
  build.gradle.kts`). Release by tag after merge (user).

### Trade-offs

- Before the first pass a preloaded mod's hooks act regardless of the list (its classes are loaded anyway). This
  window already exists for preLaunch code; the author contract says so.
- A mod opened at preLaunch gets the byte check instead of the reflective one: equivalent for "member exists", and
  the weave check still catches dropped advice.
- ZombieBuddy reflects over a preloaded mod's glue at premain (`PatchEngine.collectPatches` → `Exposer`), loading the
  types in the glue's advice signatures (DI A1). Those are the target's types, not the handler's: the glue binds
  `@Self` as the owner class and arguments as the target's erased types (`GlueWriter`, `PatchBuilder`). Zabric cannot
  prevent this; the author docs say what loads early (P avoids it, D9).

## P — `loading-progress` mod

### Boundaries

- Module `loading-progress`, package `com.dont39.pz.loadingprogress`, mod id `Dont39LoadingProgress`, Workshop
  title "Loading Progress" / "加载进度条". Plain Java, modeled on `zb-skip-online-checks/build.gradle.kts`:
  `pz-java` + `pz-mod` + `pz-bootstrap`; id and package from `mod/42/mod.info` (`pzMod`). Hooks through the javac
  front end of `zabric-processor`: `annotationProcessor(project(":zabric-processor"))` with
  `-Azabric.modId=${pzMod.modId}`, `-Azabric.javaPkgName=${pzMod.javaPkgName}`, as `samples/zabric-java-hooks/`
  passes them (only `pz-feature` wires the processor today, through KSP).
- Manifest: `ZB-Preload: true`, `Zabric-PreLaunch`, `Zabric-Main`, `Zabric-Hooks: launch`. `mod.info`:
  `require=\ZombieBuddy,\Zabric`, `loadModAfter=\Zabric`, `javaPreload=true`, `javaPkgName`,
  `javaJarFile=media/java/client/...`. `pz-mod`'s `assembleMod` puts every jar in `42/media/java`; it gets a jar
  folder property (default `media/java`) that this module sets to `media/java/client`.
- Only the generated glue lives in the root package (ZombieBuddy loads every class there at premain); code in
  subpackages: `entrypoint` (preLaunch: keeps the preload, does nothing else; main: one report line), `hooks`,
  `progress` (flow state), `draw` (overlay, own frames), `text` (strings).
- JDK-only glue (D9, AC2): the glue binds every argument of a hooked method (and its return value for RETURN and
  FINALLY handlers), whatever the handler binds, so only methods whose whole descriptor names JDK types are hooked,
  never with `@Self`. State comes from statics in method bodies (`Core.getInstance()`, `GameWindow` fields, the
  managers' `instance`), which run after the targets loaded. Where the per-item method takes a game type, a JDK-only
  callee or caller is hooked instead: `Core.DoFrameReady` with `ScriptManager.getCurrentLoadFileAbsPath`/`Name` for
  script files, `LuaEventManager.checkEvent(String)` for event names, `RunLua(String, boolean)` for Lua files,
  `KahluaThread.pcall(int)` for Lua calls. Overloads ZombieBuddy's matcher cannot tell apart (the `triggerEvent`
  family) cannot be hooked; the processor rejects them. `checkJdkGlue` (`CheckJdkGlue.kt`) reads the generated glue
  classes and fails on a method or field type outside the JDK and Zabric.
- Private members it reads (`ScriptManager.tempFileToModMap` for the script total, DI B5) go through a
  `.classtweaker`. The `widenCompileJars` wiring moves from `pz-feature.gradle.kts` into a convention plugin that
  `pz-feature` and this module both apply (`widening/WidenJars` already holds the task); the mod jar ships the list
  where Zabric reads it at game start (`META-INF/zabric`, as `dont39-all-in-one` puts its merged list).
- `checkLoaderRefs` excludes only the glue in the root package, as `zb-skip-online-checks` excludes its root.
- Smoke test: `GameSmokeTest` (`buildSrc/.../smoke/`) is registered by `dont39-all-in-one` with its own inputs. This
  module registers its own `smokeTest` (reaches the menu, the mod's `hooks ready` and main lines, no error); inputs
  only the All-in-One needs (Kotlin versions, features) become optional rather than copied. No `serverSmokeTest`: a
  client-only mod.

### Flows and steps

State in `progress.Progress`: flow (none / launch / start / reload), step index and key, item text, counters. Written
by hooks on any thread, read by the drawing code on `MainThread`; fields are volatile or updated under one lock.

| Flow | Starts | Ends |
|---|---|---|
| launch | fonts ready (`GameWindow.initFonts` exit, first call; on the launch that registers the preload, the second call, after the first loader pass opened the hooks) | `GameWindow.mainThreadStart` exit |
| start | `serverConnect` / `serverConnectCoop` (join); `Core.ResetLua("currentGame", …)` or `GameLoadingState.enter` (single player) | first `IngameState.render`. A join that stops while connecting ends it: `OnConnectFailed` / `OnDisconnect`, `RakNetPeerInterface.connectionStateChangedCallback` with `Failed` / `Disconnected`, `backToSinglePlayer`, `forceDisconnect` (Dont39 All-in-One's tunnel stops a join through `OnConnectFailed`, from inside `UdpEngine.Connect` on `MainThread`). At any step `MainScreenState.enter`, `IngameState.exit` (before its reset to the menu's mods), a new join or another Lua reset end it |
| reload | `Core.ResetLua("default", …)` (mod list changed in the menu) | `ResetLua` exit |

After its Lua reset a start pauses: no bar over the menu's interactive screens (character creation, spawn selection);
it resumes at the login queue or the world load, and the time in between is not a frame gap. Boundaries the menu
passes while paused do not move the flow.

Steps (keys of the mod's strings; boundaries are the DI B5 targets):

- launch (10): mods (ZombieBuddy's Java-mod pass as item "Java mod i / n: id" from Zabric's loader progress, D11,
  then `loadMod` i / `getModIDs().size()`), translations,
  UI texture packs (`LoadTexturePack` name), scripts (files i / `ScriptManager.tempFileToModMap.size()`, counted inside
  `ScriptManager.Load`), clothing, tiles and furniture (the four managers' `init`), Lua (files i / distinct entries of
  `LuaManager.loadList`, per `LoadDirBase` folder, files run by `require` not counted), texture packs, models and
  animations, game boot (event name).
- start (13): connecting, reloading mods, translations, scripts, models, clothing and tiles (`ResetLua` L4153-4167),
  Lua, main-menu events (`OnGameBoot`/`OnMainMenuEnter`/`OnResetLua`: event name), login queue, preparing world
  (`GameLoadingState.enter`: server Lua i / n), loading world (item = `GameLoadingState.gameLoadingString`), ready
  (vanilla's click prompt), entering world (`IngameState.enter`: event name, MP connect wait).
  Single player skips the steps it does not run; the bar jumps.
- Steps and their order: `progress/Flow.java`, `Step.java`. A count past its total, or a step that begins out of order,
  is a mismatch: it throws and stops the hooks.
- reload: the `ResetLua` steps of start.

### Drawing

- One routine draws the overlay at `Core.EndFrameUI` HEAD on `MainThread` while a flow runs: every frame vanilla hands
  over (epilepsy, model-loading, connect UI, login queue, `GameLoadingState.render`) and every own frame.
  `EndFrameUI` HEAD is the last point inside a frame; a RETURN hook on `GameLoadingState.render` would run after the
  frame was handed over and Zabric has no `@At("INVOKE")` (DI B6).
- Layout: top center, below the top edge, clear of ZombieBuddy's top-left watermark and of the world-loading screen's
  centered tips, bottom text and figure (DI B6): a bar `min(640, 40 % of width)` wide, line 1 "n / N: step", line 2
  the item. Text needs `TextManager.instance.font` and no font still loading (`isAnyFontLoading`, DI risk 4); while
  fonts load, only the bar draws. Fonts are fetched per frame (`ResetLua` replaces them).
- Own frames (`draw.FramePump.tick`, called from every per-item, boundary and trigger hook): only in steps where the
  game draws no frames of its own (`Step.ownFrames`; not connecting, login queue, loading world, ready), only when the
  current thread is `GameWindow.gameThread`, `Core.frameStage == 0`, and the last handed-over frame is ≥ 50 ms old.
  Sequence as `GameWindow.doEpilepsyWarningText` (`StartFrame`, `EndFrame`, `StartFrameUI`, full-screen `renderi`
  background, `EndFrameUI`); during the launch, from the epilepsy warning until `ModelManager.create`, the own frame
  is `doEpilepsyWarningText` itself, so the warning stays. `UIManager.useUiFbo` is forced off for the frame and
  restored, as `GameLoadingState.render` does (DI risk 6). No Lua, no `UIManager.render`, no `GameWindow.render`
  (mid-reset).
- Triggers that keep gaps under 1 s: `loadMod`, `Core.DoFrameReady`, `RunLua(String, boolean)`, `LoadTexturePack`,
  `checkEvent`, `KahluaThread.pcall(int)` (per Lua call from Java), `ChooseGameInfo.getAvailableModDetails` (per mod
  in every manager loop; on the launch woven only at the end of the first pass, D10, so it does not act in discovery
  or ZombieBuddy's pass there), `ZomboidFileSystem.getAllModFoldersAux` / `setModIdToDir` (mod discovery), Zabric's
  loader progress (per Java mod jar, D11), `NetChecksum.Checksummer.addFile`, `CoopMaster.update`,
  `GameClient.update` (MP connect wait loops). Several run per frame in game: their first check is a volatile flag,
  before any clock read. `waitForRenderState` is not touched: each own frame pumps (`Display.update(true)`).
- Known gap (D8): Dont39 All-in-One's tunnel opens its connection inside `UdpEngine.Connect` with blocking socket
  calls (TCP ≤1 s + ≤1 s, KCP ≤2 s), where no trigger above runs. The progress mod adds no hook for it and does not
  know the tunnel; the gap falls in the connecting step.
- Frame-gap report: per flow end, one log line with the flow's duration, own-frame count and the largest gap between
  handed-over frames with its step (AC4, AC4b, AC7 evidence).
- Dont39 All-in-One's network status icon is created at `OnGameStart` (bottom left) and drawn by `UIManager` in
  in-world frames, after the start flow ends; no layout rule is needed for it.

### Discovery and the Java-mod pass (D11; evidence and anchors: `research/zb-pass-gap.md`)

- A, discovery: JDK-only HEAD triggers on `ZomboidFileSystem.setModIdToDir(Ljava/lang/String;Ljava/lang/String;)V`
  (per mod folder) and `getAllModFoldersAux(Ljava/lang/String;Ljava/util/List;)V` (per root folder), in
  `LoadHooks.Mods`; `ZomboidFileSystem` is woven at premain. `getAvailableModDetails` does not act there on the launch
  (woven only at the end of the first pass, D10).
- B, Zabric loader progress: `loader/JarLoadHook` advises ZombieBuddy's `Loader.loadMods(List)` entry and
  `static boolean loadJar(Path, String, String, Phase)` (MAIN phase only), installed at Zabric's premain like
  `ModsLoadedHook`. Index and total: at `loadMods` entry it keeps the identities of ZombieBuddy's jar records; at the
  pass's first jar the records written since with reason `loaded` are the pass's jars (preloaded ones included, stale
  records of earlier passes excluded), and each `loadJar` is matched to one by its canonical path. A jar without such a
  record, a second jar before the first returned, or more jars than recorded is a mismatch naming the loader version:
  kept, never thrown into the loader, and the rest of that pass is not followed. Public API
  `entrypoint.LoaderProgress.listen(modId, Listener)`, `Listener.jarLoading(index, total, modId)` and
  `jarLoaded(...)` (default no-op): called on the loader's thread outside any transformer, while the listener's mod's
  hooks are open; a throw removes the listener and stops that mod through its default gate. A hook Zabric cannot
  install, or a pass it could not follow, makes loader progress unavailable for the session: `Lifecycle` stops each
  listener's mod with the cause (or logs one Zabric line when none listens); not a lifecycle stop. Tests:
  `JarLoadHookTest` (the real `loadJar` with the hook installed), `JarListenersTest`.
- The progress mod registers `hooks.JavaMods` at preLaunch. The Java-mod pass is an item of the launch's (and a
  reset's) "Loading mods" step, not a step of its own (D4, D11 wording): line 2 "Java mod i / n: id"
  (`Item.JAVA_MOD`), a tick of `FramePump` before and after each jar. A call off the game thread while a flow runs, or
  out of order (`Progress.javaMod`), throws, which stops the mod. The flow-end line names the item shown when its
  largest gap ended.
- C, interim frames from the render thread, only while the Java-mod step shows and only for a gap between own frames:
  - heartbeat: a Zabric counter bumped per transformation during the pass (a retransform-capable transformer that
    only counts, never transforms or loads), readable through the same API;
  - capture: at `Display.update(Z)V` HEAD of an own frame of that step, the back buffer is copied into a mod-owned
    texture (GL 4.5 DSA, no binding changes);
  - draw: from the render thread's idle path (`Display.processMessages()V`, or `RenderThread.queueInvokeOnRenderContext`
    from a mod timer if that target cannot be woven), at most every 250 ms and only when the heartbeat moved: blit the
    capture to the back buffer, scissor-clear the bar rectangles with the fill for the heartbeat-advanced position
    within the current jar's share of the bar, `Display.swapBuffers()`; text stays as captured. Every GL state touched
    (scissor, color mask, read/draw framebuffer, clear color) is saved and restored; the game's cached state
    (`GLStateRenderThread`, `Texture.lastTextureID`) is never changed. Size: the framebuffer's, not `Core`'s screen.
  - errors on the render thread go to the mod's stop (default gate's report), never to the queue's log-and-continue.
  - frame-gap report counts interim frames separately (AC3b); Viewpoint's patches of `Display.update` and
    `SpriteRenderer.postRender` are tested with and without Viewpoint.

### Text, client only, errors

- `text.Strings`: the EN and CN text as Java switch tables. CN when `Translator.getLanguage().name()` is `CN`, else EN;
  the game's fonts follow its language, so CN glyphs exist exactly then (DI B7).
- Client only: no named gate (its `active()` would need `gateProblem`, which loads the targets reflectively, against
  AC2). `progress.Progress` starts a flow only where `GameServer.server` is false (`hooks.Loading`), and every handler
  and the drawing act only while a flow runs; the main entrypoint logs "dedicated server: not active" there. Drawing
  additionally requires `GameWindow.gameThread`.
- Access widening not in effect (`AccessWidener.report` returns a reason): the main entrypoint ends any running flow
  and starts none for the session (`Progress.stop`), and logs the mod idle; the hooks stay open but do nothing.
- Errors: hooks use the default gate; any exception in a handler or the drawing code stops all the mod's hooks with
  Zabric's report, and loading goes on as vanilla. An unexpected state (e.g. a step boundary out of order) throws
  with the flow and step named, which stops the hooks the same way.

## Compatibility and rollback

- Z alone changes nothing for existing mods: none has hooks and a preLaunch entrypoint today (DI A1; still true on
  2026-10-19: `zb-skip-online-checks` has a preLaunch entrypoint and no hooks, the All-in-One's hooks have no
  preLaunch entrypoint). Its tests run in `:zabric:test`.
- P requires Zabric ≥ 0.5.0 at runtime; per the project rule, `require=\Zabric` only (Workshop auto-updates). With an
  older Zabric its hooks open at the first pass and the launch shows progress only from there.
- Rollback: Z and P are separate commits; disable the mod (restart) or revert P alone. Reverting Z needs a Zabric
  release.
