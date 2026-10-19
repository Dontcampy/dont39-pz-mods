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
- JDK-only glue (D9, AC2): no handler uses `@Self`, and a handler binds a target's arguments only when all of them are
  JDK types (`String`, primitives, `Object`); otherwise it binds none. State comes from statics in method bodies
  (`Core.getInstance()`, `GameWindow` fields, the managers' `instance`), which run after the targets loaded. Where a
  per-item name needs a game-typed argument, line 2 shows the count without the name, or a JDK-only overload or
  callee is hooked instead (e.g. `LuaEventManager.triggerEvent(String, …)` for event names). A build check reads the
  generated glue classes and fails on any non-JDK type in an advice signature.
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
| launch | fonts ready (`GameWindow.initFonts` exit, first call) | first `TISLogoState`/`MainScreenState` frame after `GameWindow.mainThreadStart` sets `waitForRenderState` |
| start | `serverConnect` / `serverConnectCoop` (join); `Core.ResetLua("currentGame", …)` or `GameLoadingState.enter` (single player) | first `IngameState.render`; a join that returns to the menu (`ConnectToServerState.exit` without a reset, `OnConnectFailed`) ends it; Dont39 All-in-One's tunnel also stops a join by triggering `OnConnectFailed`, from inside `UdpEngine.Connect` on `MainThread` |
| reload | `Core.ResetLua("default", …)` (mod list changed in the menu) | `ResetLua` exit |

Steps (keys of the mod's strings; boundaries are the DI B5 targets):

- launch (10): mods (`loadMod` i / `getModIDs().size()`; ZombieBuddy's Java-mod pass falls in this step), translations,
  UI texture packs (`LoadTexturePack` name), scripts (`LoadFile` i / total), clothing, tiles and furniture (the four
  managers' `init`), Lua (`RunLua` i / `LuaManager.loadList.size()`, per `LoadDirBase` dir), texture packs, models and
  animations, game boot (`OnGameBoot` callbacks i / n).
- start (13): connecting, reloading mods, translations, scripts, models, clothing and tiles (`ResetLua` L4153-4167),
  Lua, main-menu events (`OnGameBoot`/`OnMainMenuEnter`/`OnResetLua` callbacks), login queue, preparing world
  (`GameLoadingState.enter`: server Lua i / n), loading world (item = `GameLoadingState.gameLoadingString`), ready
  (vanilla's click prompt), entering world (`IngameState.enter`: `OnGameStart`/`OnLoad` callbacks, MP connect wait).
  Single player skips the steps it does not run; the bar jumps.
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
- Own frames (`draw.FramePump.maybePush`, called from every per-item and boundary hook): only when the current thread
  is `GameWindow.gameThread`, `Core.frameStage == 0`, a flow runs, and the last handed-over frame is ≥ 50 ms old.
  Sequence as `GameWindow.doEpilepsyWarningText` (`StartFrame`, `EndFrame`, `StartFrameUI`, full-screen `renderi`
  background, `EndFrameUI`), with `UIManager.useUiFbo` forced off for the frame and restored, as
  `GameLoadingState.render` does (DI risk 6). No Lua, no `UIManager.render`, no `GameWindow.render` (mid-reset).
- Triggers that keep gaps under 1 s: `loadMod`, `LoadFile`, `RunLua`, `LoadTexturePack`, `Event.trigger` and
  `LuaCaller.protectedCallVoid` (per Lua callback; hot, so the handler's first check is a cheap flow/time test),
  `ChooseGameInfo.getAvailableModDetails` (per mod in every manager loop and in ZombieBuddy's pass), the managers'
  `initModData`, `CoopMaster.update`, `GameClient.update` (MP connect wait loops). `waitForRenderState` is not
  touched: each own frame pumps (`Display.update(true)`).
- Known gap (D8): Dont39 All-in-One's tunnel opens its connection inside `UdpEngine.Connect` with blocking socket
  calls (TCP ≤1 s + ≤1 s, KCP ≤2 s), where no trigger above runs. The progress mod adds no hook for it and does not
  know the tunnel; the gap falls in the connecting step.
- Frame-gap report: per flow end, one log line with the flow's duration, own-frame count and the largest gap between
  handed-over frames with its step (AC4, AC4b, AC7 evidence).
- Dont39 All-in-One's network status icon is created at `OnGameStart` (bottom left) and drawn by `UIManager` in
  in-world frames, after the start flow ends; no layout rule is needed for it.

### Text, client only, errors

- `text.Strings`: two UTF-8 resource tables (EN, CN) in the jar. CN when the game language is `CN`, else EN; the game's
  fonts follow its language, so CN glyphs exist exactly then (DI B7).
- Client only: a named gate active only when `!GameServer.server`; the main entrypoint logs "dedicated server: not
  active" there. Drawing additionally requires `GameWindow.gameThread`.
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
