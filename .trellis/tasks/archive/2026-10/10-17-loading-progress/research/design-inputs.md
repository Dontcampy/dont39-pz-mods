# Research: design inputs for preLaunch-open hooks (Zabric) and the loading-progress mod

- **Query**: A (Zabric): current flow of a preloaded mod, what the weave check needs at preLaunch, manifest
  attribute pattern, docs and release steps, gate behaviour before open. B (progress mod): hook targets with
  descriptors and threads for every flow, Java-only frame drawing, translations, frame cost and throttling, other
  mods' render advice, module build layout. Ends with risks and PRD contradictions.
- **Scope**: internal (repo code, ZombieBuddy submodule, javap of `projectzomboid.jar` 42.21, Workshop jars of
  Viewpoint and ISeeThem, the 16:00 DebugLog). Builds on `loading-flow.md`; its findings are not repeated.
- **Date**: 2026-10-17

Conventions: `File.java:N` = line in this repo or the ZB submodule. `Class.method LN` = source line from the game
class's LineNumberTable (javap `-c -p -l`, GraalVM 25.0.2). Descriptors are JVM descriptors as `@Inject(method =
"name(descriptor)")` needs them for overloads (`zabric/.../hook/Inject.java:14`).

---

## Part A: Zabric

### A1. Current flow for a preloaded mod (premain → preLaunch → first pass)

**Premain (ZB `Agent.premain`, JVM main thread, before `MainScreenState.main`):**

1. `Agent.java:89` ZB's own `patches` package (PREMAIN) → `Patch_Core` (`Core.EndFrameUI` Watermark),
   `Patch_GameWindow` (`init` exit, `DoLoadingText` exit) are installed first.
2. `Agent.java:144` `Loader.preloadMods()` (`Loader.java:401`) iterates `config.json` `preload_mods` in order
   (Zabric first: `PreloadConfig.java` `withZabricFirst`). For each entry:
   - `loadJar(jar, pkg, hash, Phase.PREMAIN)` (`Loader.java:450`) → `addJarToClasspath` (append to the system class
     loader, add to `g_known_jars`) → `ApplyPatchesFromPackage(pkg, null, PREMAIN)` (`Loader.java:996`): calls
     `<pkg>.PreMain.premain` if present, and because this is the package's first phase, `PatchEngine.applyPatches(pkg,
     null)` (`Loader.java:1021-1023`). For Zabric this runs `PreMain.premain` → `AccessWidener.install()` +
     `Lifecycle.install()` (`zabric/.../PreMain.java`), which installs `PreLaunchHook` and `ModsLoadedHook`.
   - `g_known_jars.remove(jar)` (`Loader.java:451`, "to allow Main.main run") and record
     `JavaModLoadState(reason="preloaded", flags=MF_PRELOAD|MF_ACTIVE)` (`Loader.java:452-459`).
3. `PatchEngine.applyPatches` (`PatchEngine.java:134`) for a preloaded mod's package registers its patch transformer
   (`builder.installOn`, L625) and explicitly retransforms already-loaded targets (L627-645). So the generated glue of
   a preloaded mod is woven into every target class that loads from then on.
   - Side effect at premain: `collectPatches` loads every class of the package (`classInfo.loadClass()`, L704, L724)
     and runs `Exposer.hasGlobalLuaMethod(cls)` → `Accessor.allMethods(cls)` (`Exposer.java:47-48`), i.e. reflection
     over the glue's patch classes. Their advice methods have the handler's parameter types (`@Patch.This <Target>`,
     argument types, see `WeaveGlue.java:33`), so game classes named in handler signatures are loaded (not
     initialized) at premain. No preloaded mod in this repo has Zabric hooks today (zb-skip-online-checks uses
     Instrumentation directly; ZabricKotlin is not preloaded), so this is new with the progress mod.

**preLaunch (`PreLaunchHook` advice on entry to `MainScreenState.main` / `GameServer.main`,
`PreLaunchHook.java:80,113-115`):** `Lifecycle.preLaunch()` (`Lifecycle.java:82-109`) reads
`Loader.preloadedJars()` (records with reason `"preloaded"`, `LoadStatus.java:33-35`), reads each jar's manifest
(`declared(Path)`, L293), calls every `Zabric-PreLaunch` entrypoint, keeps `preLaunched` ids and `preLaunchCalls`
(results reported later in `start()`, L185-213). Nothing touches hooks: every `ModState` is closed
(`ModState.update`, `ModState.java:116-118`: `open = inPass && !held && !stopped`, `inPass` false).

**First loader pass (`GameWindow.init` L1111 → ZB `Loader.loadMods(List)`, MainThread):**

- `Loader.java:615-617`: `mergedIds = PreloadMods.getIds() + mods`: **preloaded mod ids are processed in every
  pass, whether or not the game's mod list has them.** Each gets a fresh `g_jarLoadStatus` record with reason
  `"loaded"` (or a skip reason), sticky flags merged (`Loader.java:886-898`), overwriting `"preloaded"`.
- `loadJar(..., Phase.MAIN)` (`Loader.java:939`): `addJarToClasspath` succeeds again (the jar was removed from
  `g_known_jars`) → `ApplyPatchesFromPackage(MAIN)`: `<pkg>.Main.main` runs if present; `phases.size() == 2`, so
  **patches are not re-applied** (`Loader.java:1021`). On later passes `g_known_jars` has the jar
  (`Loader.java:483`) → `loadJar` returns before `ApplyPatchesFromPackage`.
- At the exit of `loadMods(List)`, `ModsLoadedHook.Exit` passes **the game's list `mods` (argument 0)**
  (`ModsLoadedHook.java:96`) → `Lifecycle.modsLoaded(pass)` (L140) → `Loader.loadedThisPass(pass)` →
  `LoadStatus.jars("loaded", ids-of-pass)` (`LoadStatus.java:57-77`, filter `ids.contains(id)` L69). So Zabric's
  `jarsByMod` contains a preloaded mod **only if the game's mod list for that pass names it**, although ZB loaded it.
- `HookRuntime.passProcessed(loaded, systemClassLoader, instrumentation)` (`Lifecycle.java:163`,
  `HookRuntime.java:46-85`): mods absent from the pass → `leavePass()` (L48-52); present ones: first time →
  `check()` (target check, L100-138), `enterPass()` holds them closed (`ModState.java:76-84`), `weave.afterPass`
  (WeaveCheck re-registered last, entering targets that are loaded are retransformed and checked), `release()` opens,
  `"[Zabric] <mod>: hooks ready (N hook classes)"` for mods checked in this pass (L78-84).
- Before that, `modsLoaded` closes everything when the lifecycle stopped (`Lifecycle.java:150-153`) or Zabric was
  not loaded in the pass (`!zabricLoaded`, L154-161: "its preloaded hooks must not act for it").
- Later passes (server join `ResetLua` L4143 with the server's list; SP `currentGame`; `default` after
  `modsChanged`) repeat the same filtering: a mod missing from that list is closed (`leavePass`) and reopened when a
  later pass lists it (`HookRuntimeTest.openOnlyWhileInTheLatestPass`, `HookRuntimeTest.java:41-54`).
- ZB removes a mod from preload only on a `default`→`default` pass that drops it (`Loader.java:605-612`), with a
  Watermark message "Restart the game to deactivate it".

Answer to the question: **yes**, ZB's passes always include the preloaded jar (as `"loaded"`), and **no**, patches are
not re-applied; but `Loader.loadedThisPass(pass)` hides it from Zabric unless the game's list names it.

### A2. What the weave check needs, and whether it can run at preLaunch

Inputs of the pass-time machinery and their availability at preLaunch (entry of `MainScreenState.main`, before
`Display.init` L147 and before `DebugLog.init` L192):

| Need | Where used | At preLaunch |
|---|---|---|
| `Instrumentation` | `HookRuntime.passProcessed(..., inst)` → `WeaveCheck.afterPass` (`WeaveCheck.java:57-87`) | yes: `Lifecycle.instrumentation`, set in `install()` (`Lifecycle.java:65-79`) |
| Class loader | `HookListing.problem(loader)` (`HookListing.java:216-242`), `WeaveCheck.gate()` | yes: preloaded jars are on the system class loader (ZB `addJarToClasspath`) |
| The mod's jar paths | `HookListing.readAll(jar)` (`HookRuntime.java:105-112`) | yes: `Loader.preloadedJars()` (`Loader.java:70`, "valid until the mod list first loads"), already read by `preLaunch()` |
| Loader transformers registered before WeaveCheck | ordering assumption (`Loader.preloadedJars`/`onModsLoaded` Javadoc; spec Switch Checklist "weave check relies on the loader registering its patch transformers before Zabric's mod-list callback") | yes for preloaded mods: ZB registered them at premain. Non-preloaded mods register theirs in the first pass, after WeaveCheck; `afterPass` re-adds WeaveCheck last on every pass (`WeaveCheck.java:77-78`) |
| Game classes | target check: `Class.forName(owner, false, loader)` + `getDeclaredMethods()` / `getDeclaredConstructors()` (`HookListing.java:227-264`) | they are not loaded yet; the check would load them (and, through `getDeclaredMethods`, the types in their signatures) at main entry. `WeaveCheck.afterPass` itself only retransforms targets already loaded (L80-86); targets that load later are checked in `transform` (L120-143), which needs no early load |
| Game log | `HookRuntime.Log` default `GameLog` → `DebugType.Mod.println/error/printException` (`HookRuntime.java:140-155`) | not up: `DebugLog.init` is at `MainScreenState.main` L192, `System.out` is redirected to `console.txt` at L181-184. Lifecycle already defers preLaunch outcomes to `start()` at the first pass (`Lifecycle.java:46-52` comment "premain and preLaunch cannot log", L185-213) |

Consequences read from the code:

- The target check and `afterPass` can run at preLaunch technically; every log line they produce
  (`hooks stopped: ...` from `check()`, `hooks ready`, WeaveCheck findings reported through
  `ModState.failed` → `DefaultGate.failed` → `HookRuntime.log().exception`, `ModState.java:127-130`) must be held
  and replayed once the log is up, like `preLaunchCalls`. This also covers WeaveCheck findings for targets that load
  between preLaunch and `DebugLog.init` (`MainScreenState.main` L139-L192 loads e.g. `Core` at L154).
- Spec conflict: `.trellis/spec/mod/access-widening.md` ("Zabric" rules): "Premain and preLaunch code touches no game
  class and logs nothing". A preLaunch target check loads game classes.
- Early class loading matters for other mods: a game class first loaded at preLaunch is patched by later
  (non-preloaded) mods by retransformation, which ZB does with `disableClassFormatChanges()` for advice
  (`PatchEngine.java:221-229`) and which cannot apply MethodDelegation patches. In the 16:00 log
  `GameLoadingState`/`IngameState` are new loads during the launch (16:00:37.468-38.161, log lines 7593-7630), after
  the first pass. The Viewpoint and ISeeThem patches on `GameLoadingState.enter` / `IngameState.enter` are advice
  (javap of their `Patch_*` classes shows no `isAdvice=false`).

**Touch points for "open at preLaunch" and "keep launch-scoped mods open" (what exists today):**

| Code | Today | Relevant to the change |
|---|---|---|
| `Lifecycle.preLaunch` (`Lifecycle.java:82-109`) | calls preLaunch entrypoints only | has `preloadedJars()`, `declared()`, `preLaunched`; no `HookRuntime` call, no log |
| `Lifecycle.start` (L185-213) | reports `failure` and `preLaunchCalls` at the first pass | the natural place to replay held hook log lines |
| `Lifecycle.modsLoaded` (L140-168) | `closeAll()` when stopped or `!zabricLoaded`; then `passProcessed` + `load` | a stop found at the first pass now comes after preloaded hooks already ran for the launch's first ~15 s |
| `HookRuntime.passProcessed` (`HookRuntime.java:46-85`) | `leavePass` for every mod missing from the pass (L48-52); `firstPass()` + `check` once per mod (L57); `enterPass`/`release` around `afterPass` | the leavePass loop is what closes a preloaded mod on a pass without it (PRD Q7); a mod checked at preLaunch has `firstPass()` false at the pass, so no second `hooks ready` line there |
| `ModState` (`ModState.java`) | `inPass`, `held`, `stopped`, `checked`; `open = inPass && !held && !stopped` (L116-118); `live() = inPass && !stopped` (L97-99) | `live()` drives WeaveCheck (`watched` table, `check`, `transform`, `retransform`: `WeaveCheck.java:58-66,101-116,147-150,140`): a mod kept open while not "in the pass" must still count as live or its later-loading targets go unchecked |
| `HookRuntime.closeAll` (L87-90) | `leavePass` for all + `weave.clear()` | lifecycle stop must still close launch-scoped mods |
| `ModHooks` Javadoc (`hook/ModHooks.java:4-6`) | "Open while the mod is in the last mod list Zabric processed and has not stopped" | contract text |
| `Lifecycle` class Javadoc (L24-31) | "A pass's hooks open before its main entrypoints run" | contract text |

**Existing tests and where new ones fit:**

| Test | Covers |
|---|---|
| `zabric/src/test/.../hook/impl/HookRuntimeTest.java` | open only in latest pass (L41-54), default/named gate failures (L56-115), target check: ready line, unrunnable weave check, mismatch, foreign listing, unreadable listing (L117-180). Uses `HookRuntime.passProcessed` directly with `Weaving.instrumentation()` and `CapturedLog` (`CapturedLog.java`, implements `HookRuntime.Log`) |
| `.../hook/impl/WeaveCheckTest.java` | unverifiable patch on a loaded target (L67), ByteBuddy rejection (L85), named gate's weave failure ordering (L101), targets loaded after the pass checked on load + re-entry retransform reported once (L118-160, `OpenProbe` sees the mod closed while re-checked). Fixtures in `hook/check/` (`LateGlue`, `LoadedGlue`, `RejectedGlue`, `CheckGate`) |
| `.../hook/weave/WeaveTest.java` | glue semantics through ZB's real `PatchEngine` (`Weaving.weave`): closed mod leaves target untouched (L92-103), closed gate vs always (L105-116), exit pairs with its call's enter (L167-175), shapes |
| `.../hook/impl/HookListingTest.java` | listing parse/read |
| none | `Lifecycle` (manifest reading, phases) and `loader/` have no tests |

New tests that fit the existing shape (HookRuntimeTest/WeaveCheckTest, same fixtures): opening at preLaunch through
a new runtime entry with jars + loader + instrumentation; a launch-scoped mod staying open across a pass that lacks
it, a plain mod closing and reopening; `closeAll` closing a launch-scoped mod; target-check problems and the ready
line held until the log is up and then emitted once, in order (`CapturedLog`); a launch-scoped mod's targets that load
after a pass lacking it still being checked (WeaveCheckTest late-target pattern). Manifest parsing of a new attribute
would be the first `Lifecycle` test (its `declared(Path)` is private static).

### A3. Manifest attributes today, docs, version and release

- `Zabric-PreLaunch`, `Zabric-Main`: constants `Lifecycle.PRE_LAUNCH`/`MAIN` (`Lifecycle.java:34-35`); read by
  `declared(Path jar)` (L293-302) from `JarFile.getManifest().getMainAttributes()` into `record Declared(preLaunch,
  main)` (L38-40); `className()` (L304-310) strips, returns null when absent, throws on blank. Multi-jar mods:
  `declared(modId, jars)` (L271-291) logs an error and returns NONE for more than one jar.
- `ZB-Preload`: checked by Zabric in `PreloadConfig.checkPreloadable` (`PreloadConfig.java:81-98`, value
  `"true"`.equalsIgnoreCase, plus `javaPreload=true` and `javaPkgName` in mod.info) and by ZB itself
  (`Loader.PreloadMods.hasManifestPreload`, `Loader.java:383-397`, trims, `"true"` equalsIgnoreCase).
- Set in builds via `tasks.jar { manifest { attributes(...) } }`: `zabric/build.gradle.kts:59-63` (ZB-Preload),
  `zb-skip-online-checks/build.gradle.kts:31-42` (ZB-Preload, Zabric-PreLaunch, Zabric-Main);
  `pz-mod.gradle.kts` adds `Implementation-Title/Version`.
- Author docs: `zabric/mod/workshop/workshop.txt` "For mod authors" bullets (the author contract per
  `README.md:25-29` and spec): "Entrypoints", "Running at game start", and "Hooks, timing and order: hooks run from
  the first mod list Zabric processes (when main entrypoints run) and only while your mod is in the mod list" — this
  sentence changes with D6. Also the "What it does" bullet "Runs the hooks ... only while that mod is in the mod list".
  Other places that state the timing: `ModHooks` Javadoc, `Lifecycle` Javadoc, `.trellis/spec/mod/access-widening.md`
  (Zabric rules, Entrypoints and Preload Registration), `.trellis/spec/mod/zabric-hooks.md` (Publishing and
  Compatibility: "the runtime API the glue calls and the listing header only grow"), `samples/zabric-java-hooks/
  README.md` (no timing text), `zb-kt-utils` facade docs (Kotlin, not affected).
- Version and release: `gradle/libs.versions.toml` `zabric = "0.4.0"` (also the Maven version of `zabric` and
  `zabric-processor`); `samples/zabric-java-hooks/build.gradle.kts:10` hardcodes `zabricVersion = "0.4.0"`;
  `README.md:31-35`: bump the catalog, merge, push tag `zabric-v<version>`; `.github/workflows/release-zabric.yml`
  checks tag vs catalog (L24-29) and waits for the `maven-central` Environment approval. Tag `zabric-v0.4.0`
  exists. Workshop: `deployWorkshop` (`pz-mod.gradle.kts`), Zabric item id 3820726385 (`workshop.txt` `id=`). Memory:
  release order Zabric → Kotlin layer → dependents; `require` only, no version range checks.

### A4. What the generated glue does when a hook fires before the mod is open

- Glue field `MOD = ZabricHooks.mod("<id>")` (`WeaveGlue.java:15`) → `HookRuntime.state()` creates a closed
  `ModState` on first use (`HookRuntime.java:41-43`). No error, no log.
- Every advice reads `h0$active = MOD.active(gate)` at HEAD (`WeaveGlue.java:31` etc.); `ModState.active` returns
  false when `!open` before calling the gate (`ModState.java:35-45`), so a named gate's `active()` is not even
  called. `always = true` handlers read `MOD.open()` (`WeaveGlue.java:294`) and are skipped too. The target runs
  unchanged (`WeaveTest.closedModLeavesTheTargetUntouched`).
- RETURN/FINALLY handlers use the HEAD-captured local, so a call that began before the mod opened never runs its
  exit handlers even if the mod opens meanwhile (`WeaveTest.exitPairsWithItsCallsEnter`, L167-175).
- Targets that load while the mod is not live are not checked by WeaveCheck at load (`WeaveCheck.java:147-150`);
  when the mod enters a pass, `afterPass` retransforms its loaded targets with the mod held closed
  (`WeaveCheckTest` L150-157).

---

## Part B: the progress mod

### B5. Hook targets per flow (descriptors, thread)

All game-side work below runs on **MainThread** (`GameWindow.gameThread`, set by `InitGameThread` L508) unless a row
says otherwise. "Pushes frames" = vanilla calls `Core.EndFrameUI` there.

**Common / any flow**

| Target | Descriptor | Thread | Use |
|---|---|---|---|
| `zombie.GameWindow.DoLoadingText` (public static) | `(Ljava/lang/String;)V` | MainThread | named step text (body only `DebugLog.log`, L1315-1316); ZB has OnExit advice (`Patch_GameWindow.java:16-21`) and calls it itself with "Waiting for Java mods approval…"/"Loading Mods" around its approval dialog (`Loader.java:803-811`) |
| `zombie.core.Core.EndFrameUI` (instance) | `()V` | MainThread | HEAD = last point inside any frame (L904-971: … `Display.imguiEndFrame` L959, `IndieGL.glDoEndFrame` L966, `RenderThread.Ready` L968, `frameStage = 0` L970). ZB Watermark advises HEAD (`Patch_Core.java:7-12`) |
| `zombie.core.Core.frameStage` (public int field) | — | MainThread | 0 after `EndFrameUI`; set by `StartFrame`/`EndFrame`/`StartFrameUI` (writers: those methods only). Tells whether MainThread is between `StartFrame` and `EndFrameUI` |
| `zombie.Lua.Event.trigger` (instance) | `(Lse/krka/kahlua/vm/KahluaTable;Lse/krka/kahlua/integration/LuaCaller;[Ljava/lang/Object;)Z` | the Kahlua owner thread (MainThread, or `GameLoadingThread` during world load) | one call per event for all `LuaEventManager.triggerEvent(String[, Object…])` overloads (`triggerEvent(String)` L234-250 → `Event.trigger` L248; off-owner threads queue: `IsMainThread()` = `currentThread == LuaManager.thread.debugOwnerThread`, L49). `Event.name` and `Event.callbacks` are public; per-callback loop L51-57 calls `LuaCaller.protectedCallVoid` (L55; L36 in the slowLuaEvents debug loop) |
| `se.krka.kahlua.integration.LuaCaller.protectedCallVoid` (instance) | `(Lse/krka/kahlua/vm/KahluaThread;Ljava/lang/Object;[Ljava/lang/Object;)V` | caller's thread | per Lua callback inside `Event.trigger` (only per-item call inside `OnGameBoot`/`OnGameStart`/`OnLoad`…); also used for every other Java→Lua call (hot) |
| `zombie.gameStates.ChooseGameInfo.getAvailableModDetails` (static) | `(Ljava/lang/String;)Lzombie/gameStates/ChooseGameInfo$Mod;` | caller's thread | per-mod call inside every per-mod loop: `LuaManager.LoadDirBase` L1168, `ScriptManager.Load` L1444, `Translator.tryFillMapFromMods` L381, `TileGeometryManager.init` L48, `TileDepthTextureAssignmentManager.init` L45, `SeamManager.init` L45, `SeatingManager.init` L69, `SpriteModelManager.init` L48, `TileDepthTextureManager.init` L66, `ModelManager.loadModAnimations` L1985, ZB `Loader.loadMods`, Zabric `Preload` (hot elsewhere too) |
| `zombie.network.CoopMaster.update` (instance, `CoopMaster.instance`) | `()V` | MainThread | per mod (`ZomboidFileSystem.loadMod` L752), per Lua file (`LoadDirBase` L1232), per frame (`GameWindow.logic`) |

**Launch → main menu** (`GameWindow.mainThreadStart` L531-539, `waitForRenderState` false until L539)

| Step | Target | Descriptor | Per-item / notes |
|---|---|---|---|
| whole init | `zombie.GameWindow.init` (private static) | `()V` | L1077-1150; ZB OnExit fires `onGameInitComplete` |
| fonts ready | `zombie.GameWindow.initFonts` (public static) | `()V` | L1085 and L1135; waits for fonts (`isAnyFontLoading` loop L1157-1160) |
| "Loading Mods" | `zombie.ZomboidFileSystem.loadMods` (instance) | `(Ljava/lang/String;)V` | L855-877 → `loadMods(Ljava/util/List;)V` (L968-978: `loadModAndRequired` per id, then `loadMod` per id) |
| per mod | `zombie.ZomboidFileSystem.loadMod` (instance) | `(Ljava/lang/String;)V` | arg = mod id; total = `ZomboidFileSystem.instance.getModIDs().size()` (`()Ljava/util/List;`), final once the `loadModAndRequired` loop ends |
| ZB pass | `me.zed_0xff.zombie_buddy.Loader.loadMods` | `(Ljava/util/List;)V` | ~6.6 s; per Java mod `loadJar` (package-private static) — loader internals, not a Zabric target class by design (spec Loader Seam) |
| translations | `zombie.core.Translator.loadFiles` (public static) | `()V` | L1137 at launch, `ResetLua` L4146 |
| epilepsy frame | `zombie.GameWindow.doEpilepsyWarningText` (public static) | `()V` | L1139; only vanilla frame before L539 besides ModelManager's |
| Lua env | `zombie.Lua.LuaManager.init` (public static) | `()V` | L1141, `ResetLua` L4130 |
| shared init | `zombie.GameWindow.initShared` (private static) | `()V` | L179-253 |
| texture packs | `zombie.GameWindow.LoadTexturePack` (public static) | `(Ljava/lang/String;I)V` → `(Ljava/lang/String;ILjava/lang/String;)V` | the 2-arg overload calls the 3-arg one (L1015); the 3-arg one calls `DoLoadingText(UI_Loading_Texturepack)` (L1021). Fixed lists: initShared L192-198 (7 packs), enter L730-744 (1x: 4, 2x: 8) |
| scripts | `zombie.scripting.ScriptManager.Load` (instance, `ScriptManager.instance`) | `()V` | L1431-1565; file loop L1510-1523; then `loadScripts` (L1544) + `ScriptsLoaded` callbacks (L1552-1555) without per-file calls |
| per script file | `zombie.scripting.ScriptManager.LoadFile` (instance) | `(Lzombie/scripting/ScriptLoadMode;Ljava/lang/String;Z)V` | arg 2 = file name; calls `Core.DoFrameReady` (L1110); private statics `currentLoadFileMod/Name/AbsPath` set before it (L1520-1522); total ≈ size of private field `tempFileToModMap` (`HashMap`, built L1433-1481) |
| clothing | `ClothingDecals/BeardStyles/HairStyles/OutfitManager/VoiceStyles.init` (static) | `()V` | initShared L222-226, ResetLua L4156-4160 |
| tail of initShared (row 7d, 6.8 s unlogged) | `TileGeometryManager.init`, `TileDepthTextureAssignmentManager.init`, `SeamManager.init`, `SeatingManager.init` (instance, `getInstance()`) | `()V` each | L238-241. Each: `initGameData()V` (vanilla data) and per mod with the file `initModData(Lzombie/gameStates/ChooseGameInfo$Mod;)V` (public); `SeatingManager` also `initMergedTilesets()V` (private). Then `Input.initControllers()V` (L253). Per-mod loops call `getAvailableModDetails` |
| Lua | `zombie.Lua.LuaManager.LoadDirBase` (public static) | `()V` → `(Ljava/lang/String;)V` → `(Ljava/lang/String;Z)V` | `LoadDirBase()` = `"shared"` then `"client"` (L1260-1262); the per-file loop is in the 2-arg one: `loadList` (public static) is replaced with the full ordered list at L1209, the loop runs L1212-1233, `loadList.clear()` at L1234. Totals are per call (shared, client, server) |
| per Lua file | `zombie.Lua.LuaManager.RunLua` (public static) | `(Ljava/lang/String;)Ljava/lang/Object;` | called only by the loop (L1225) and `GlobalObject.reloadLuaFile`; `require` uses `RunLua(Ljava/lang/String;Z)` (`GlobalObject` require/reloadServerLuaFile). Already-loaded files return early in `RunLuaInternal` (L1087-1093) |
| models | `zombie.core.skinnedmodel.ModelManager.create` (instance) | `()V` | L764; → `initAnimationMeshes(Z)V` with `false` (create L188): waits `while (isLoadingAnimationMeshes())` with `FileSystem.updateAsyncTransactions`, `sleep(10)`, **and pushes an empty frame per iteration** (`StartFrame/EndFrame/StartFrameUI/EndFrameUI`, L2093-2103, skipped on the server). Then `loadModAnimations()V` (per mod L1982-2022 → private `loadAnimsFromDir(...)`, `loadHumanAnimations(Mod, ModAnimations)`) |
| tile depth etc. | `TileDepthTextureManager.init`, `TileDepthMapManager.init`, `TileSeamManager.init` | `()V` | enter L766-768 |
| OnGameBoot | `LuaEventManager.triggerEvent("OnGameBoot")` → `Event.trigger` | see above | enter L772-773, ~2.8 s |
| menu reached | `zombie.gameStates.TermsOfServiceState` / `MainScreenState.enter` | — | normal frame loop from L539 |

**Server join** (all MainThread except world loading)

| Step | Target | Descriptor | Notes |
|---|---|---|---|
| click "join" | `zombie.Lua.LuaManager$GlobalObject.serverConnect` (public static, Lua global) | `(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ZZILjava/lang/String;)V` | also `serverConnectCoop(Ljava/lang/String;)V`. J1 (RakNet, GettingServerInfo, auth) is pumped by the menu's own frames |
| connect state | `zombie.gameStates.ConnectToServerState.enter` / `update` / `exit` | `()V` / `()Lzombie/gameStates/GameStateMachine$StateAction;` / `()V` | driven from `MainScreenState.update` L783-788. Sub-states are private `()V` methods: `Start`, `TestTCP`, `WorkshopInit`, `WorkshopConfirm`, `WorkshopQuery`, `ServerWorkshopItemScreen`, `WorkshopUpdate`, `CheckMods`, `Finish`. `Finish` (L689-752) → private `receiveServerOptions(Lzombie/core/network/ByteBufferReader;)V` → `ResetLua("client","ConnectedToServer")` (L135); its catch triggers `"OnConnectFailed"` (L752) |
| reset | `zombie.core.Core.ResetLua` (instance) | `(Ljava/lang/String;Ljava/lang/String;)V` | `ResetLua(ZLjava/lang/String;)V` and `CheckDelayResetLua()V` both call it (L4086, L4201). Full call list with lines: below |
| queue | `zombie.gameStates.LoadingQueueState` | — | pushes its own frames (J4) |
| world load | `zombie.gameStates.GameLoadingState.enter` / `render` / `update` / `exit` | `()V` / `()V` / `()Lzombie/gameStates/GameStateMachine$StateAction;` / `()V` | enter (J5, no frames): `LoadDirBase("server")` L157, `finishChecksum` L158, `ScriptManager.LoadedAfterLua` L163, `initFBOs` L165, `initShaders` L167, MP `GameClient.init` L178 + wait loop `Thread.sleep` L184/`GameClient.update` L188, `OnPreMapLoad` L255, thread start. exit: fade to black (`ScreenFader.preRender/postRender` + `sleep(33)` loop, L462-468, pushes frames), `UIManager.init` L481, `OnCreatePlayer` L483 |
| loader thread | `zombie.gameStates.GameLoadingState$1.run` / `runInner` | `()V` (private `runInner() throws Exception`) | **GameLoadingThread**; owns Kahlua (`debugOwnerThread`), so Lua events there run on it; writes `gameLoadingString` (public static String), `convertingFileCount/Max` (public static int); `SendDone()V` (public static) at the end |
| in world | `zombie.gameStates.IngameState.enter` | `()V` | J9, no frames: `useUiFbo` set L738, chunk map `processAllLoadGridSquare`/`update` L755-756, `VehiclesDB2.loadVehiclesInMeta` L758, `LightingThread.GameLoadingUpdate` L759/L800, `OnGameStart` L780, `OnLoad` L782, MP `sendPlayerConnect` L787 + wait loop `sleep(10)` L792 / `GameClient.update()V` L796 (`GameClient.instance`), `ClimateManager.update` L775/L799, `FBORenderOcclusion.init` L807, `UIManager.FadeIn` L810. First in-world frame = first `IngameState.render` after `enter` returns (`f:1`) |

`Core.ResetLua(String,String)` (L4090-4185), in order: `drawReloadingLua` frame L4091-4093; `setWaitForRenderState(false)`
L4096; resets L4101-4139 (incl. `LuaEventManager.Reset` L4110, `UIManager.init` L4112, `LuaManager.init` L4130);
`ZomboidFileSystem.Reset/init/loadMods(String)/loadModPackFiles` L4141-4144 (per mod `loadMod`); `Languages.init`
L4145; `Translator.loadFiles` L4146; perks/sandbox/registries L4147-4151; `ScriptManager.Load` L4152 (per file
`LoadFile`); `SpriteModelManager.init` L4153; `ModelManager.initAnimationMeshes(true)` L4154 (**`true`: returns
after queueing, no wait loop and no frames**, L2086-2087); `loadModAnimations` L4155; clothing L4156-4160; tile/seam/
seating `init` L4161-4165; `TextManager.Init` L4167; `AdvancedAnimator.load()V` L4168 (checksum of anim files:
`collectBaseGameFiles`, `collectModFiles`, `buildChecksum`, L925-931); `LoadDirBase()` L4169 (per file `RunLua`);
`ZomboidGlobals.Load` L4178; `setWaitForRenderState(true)` L4180; `OnGameBoot` L4182, `OnMainMenuEnter` L4183,
`OnResetLua` L4184 (`triggerEvent(String,Object)`).

**Single player**: Lua `MainScreen.lua` calls `getCore():ResetLua("currentGame","startTutorial"|"continueSave")` or
`("default","modsChanged")` (lines 1009-1010, 1164-1169, 1233-1237) only when `ActiveMods.requiresResetLua`; these run
inside a Lua UI callback during `logic()` (MainThread, `frameStage` 0). Then character creation / map select UI may
follow (frames pumped), then `GameLoadingState.enter` → … → `IngameState.enter` as above without network steps.
`MainScreenState.update` always returns `Remain` (L783-816), so the exit to `GameLoadingState` is driven elsewhere
(Lua/state machine; not traced). `GameLoadingState.enter` is the first Java point common to all SP starts.

**The two unlogged ~7 s stretches — per-item calls usable as redraw triggers:**

- Launch row 7d (`initShared` L230-253): no per-item vanilla call with a total. Available: the four managers'
  `init()V` boundaries, their `initGameData()V`, per-mod `initModData(Mod)V` (only mods with the file),
  `SeatingManager.initMergedTilesets()V`, `Input.initControllers()V`, and inside every per-mod loop
  `ChooseGameInfo.getAvailableModDetails(String)` (one call per mod id per manager). Which call dominates the 6.8 s is
  not measurable from the log.
- Join J3.6 (`ResetLua` L4153-L4167): `SpriteModelManager.init` (`initGameData` + per-mod `initModData`),
  `initAnimationMeshes(true)` (loop over `ScriptManager.getAllAnimationsMeshes()` queueing `MeshAssetManager.load`,
  L2070-2082), `loadModAnimations()` (per mod L1982-2022, private `loadAnimsFromDir` per anim dir), the five clothing
  `init()V`, the six tile/seam/seating `init()V` (each with per-mod `getAvailableModDetails` and `initModData`),
  `TextManager.Init()V`. Again `getAvailableModDetails` is the one call that recurs through all of them.

### B6. Drawing a Java-only frame from MainThread mid-load

`GameWindow.doEpilepsyWarningText()` (L1319-1334), exact sequence:

```
if (TextManager.instance.font == null) return;                       // L1319-1320
AngelCodeFont f = TextManager.instance.getFontFromEnum(UIFont.NewLarge); int lh = f.getLineHeight();
int y = (int)(Core.getInstance().getScreenHeight() / 2f - 3.5f * lh);
Core.getInstance().StartFrame();                                     // L1325
Core.getInstance().EndFrame();                                       // L1326
Core.getInstance().StartFrameUI();                                   // L1327 (result ignored)
SpriteRenderer.instance.renderi(null, 0, 0, sw, sh, 0f, 0f, 0f, 1f, null);   // L1328: null texture = solid colour rect
for (i = 1..8) TextManager.instance.DrawStringCentre(f, sw/2.0, y, Translator.getText("UI_EpilepsyWarning"+i), 1,1,1,1); y += lh;
Core.getInstance().EndFrameUI();                                     // L1333 → RenderThread.Ready → pushFrameDown
```

Signatures: `SpriteRenderer.renderi(Lzombie/core/textures/Texture;IIIIFFFFLjava/util/function/Consumer;)V`;
`TextManager.DrawStringCentre(Lzombie/core/fonts/AngelCodeFont;DDLjava/lang/String;DDDD)V`, also
`(Lzombie/ui/UIFont;DDLjava/lang/String;DDDD)V`, `DrawString(Lzombie/ui/UIFont;DDLjava/lang/String;DDDD)V`;
`MeasureStringX(Lzombie/ui/UIFont;Ljava/lang/String;)I`, `getFontHeight(Lzombie/ui/UIFont;)I`. A null texture draws a
filled rectangle (the epilepsy background); `Texture.getWhite()` (public static) also exists. Screen size:
`Core.getInstance().getScreenWidth()/getScreenHeight()` (int); `GameWindow.frameStep` refreshes them per frame from
`RenderThread.getDisplayWidth/Height()` (L799), so during a blocking load they keep the last frame's value.

What the frame calls touch (from javap): `Core.StartFrame` (L2355-2391: `prePopulating`, `IndieGL` state, `UIManager.resize`,
debug options, `SceneShaderStore.defaultShaderId`); `StartFrameUI` (L2905-2937: returns false and draws nothing
special when `LuaManager.thread.step` (debugger); with `UIManager.useUiFbo` it renders UI into the UI FBO only on
frames where `uiRenderThisFrame` (UI FPS limiter), otherwise returns false); `EndFrameUI` (L904-971: blink alpha via
`GameTime`, `UIManager.CreateFBO` if needed, `ExceptionLogger.render`, UI FBO composite, `UIManager.renderFadeOverlay`,
cursor, `Display.imguiEndFrame`, `RenderThread.Ready`). `UIManager.resize` is Java only (FBO, clock, moodles; no Lua).
None of them calls Lua.

Prerequisites per phase:

- `Core`/`SpriteRenderer` exist from `GameWindow.InitDisplay` (RenderThread.init, `MainScreenState.main` L324).
- Fonts: `TextManager.instance.font` first set by `initFonts` at `GameWindow.init` L1085 (waits until loaded); re-done
  at L1135 after `Languages.init`. `TextManager.Init` destroys the old `AngelCodeFont`s (L260-270) and creates new ones
  whose pages load asynchronously; `initFonts` waits (`isAnyFontLoading()`, public), but `ResetLua` L4167 calls
  `TextManager.Init` without waiting. Between L4167 and the fonts finishing, the font objects exist but may be loading
  (`AngelCodeFont.isLoading()`, `TextManager.isAnyFontLoading()`); whether `drawString` copes was not verified.
  Fetch fonts per frame (`getFontFromEnum`), since old objects are destroyed.
- Vanilla frames mid-load (precedent): epilepsy (L1139, after fonts/translations, with `waitForRenderState` false),
  `ModelManager.initAnimationMeshes(false)` empty frames during `GameWindow.enter` L764 (launch only),
  `ResetLua`'s `GameWindow.render()` at L4092 (before any reset), `GameLoadingState.exit` fade frames. No vanilla
  frame is pushed between `mainThreadInit` start and L1085, or inside `ResetLua` after L4093.
- Mid-reset: `GameWindow.render()` must not be used (it reaches `MainScreenState.render` → `UIManager.render`, Lua
  UI, L527). Lua is torn down and rebuilt (`LuaEventManager.Reset` L4110, `UIManager.init` L4112, `LuaManager.init`
  L4130 replaces `LuaManager.thread`/`env`), so no Lua calls, `UIManager.render`, `triggerEvent`, or Lua-exposed UI.

Frames pushed with `waitForRenderState` false or true (`SpriteRenderer.waitForReadyState`, L691-712): if a ready
frame exists the render thread takes and renders it either way (L691-692); otherwise with the flag false it returns at
once and pumps (`RenderThread.lockStepRenderStep`), with the flag true it waits. So a pushed frame is shown in both
modes, and with the flag true each pushed frame is also the only pump (`Display.update(true)`). The two
`m_contextLock`s are different objects (`MainThread.m_contextLock`, held by `MainThread.mainLoop` around each
`mainThreadStep` but not around `mainThreadStart`; `RenderThread.m_contextLock`, held by `renderLoop`), so pushing from
MainThread cannot deadlock on them.

Overlay on `GameLoadingState.render` (L533-779): the frame is `StartFrame` L624, `EndFrame` L625, save `useUiFbo` to
a local and set it false L627-628, `StartFrameUI` L629, background `renderi` L631, `renderProgressIndicator` L635,
`ScreenFader` L636-637, then one of four tails each ending in `EndFrameUI`: unexpected error (L654, return L655),
disconnected (L670/L671), world-version error (L699/L700), normal (L777, restores `useUiFbo` L778, return L779; the
three early returns do not restore it). A RETURN/FINALLY handler on `render` runs after `EndFrameUI`, i.e. after the
frame was handed over. Zabric has no `@At("INVOKE")` (`At.java`; probe `probeUnsupportedAt`), so drawing inside the
frame means drawing at `Core.EndFrameUI` HEAD while `GameLoadingState.render` is on the stack (a flag set at its HEAD,
cleared at its FINALLY). Vanilla layout to avoid: background full screen; animated figure centred at
`x = sw/2 - 98`, width 196, bottom at `sh - 30` (L202-255 of `renderProgressIndicator`); MP `gameLoadingString` at
`y = sh - 40 - fontSmallH - 5` centred (L1320-1364); `UI_Loading` + dots at the same y (L1517-1649); quick tips
`UI_Intro1..3` centre (L724-736); `UI_ClickToSkip`/`UI_PressAToStart` (L760-773). ZB Watermark draws top-left (icon
64/128 px + text lines, `Watermark.java:88-120`).

`UIManager.useUiFbo` in frames pushed during `IngameState.enter`: `enter` sets it from the options at L738 before any
work. With it true, `StartFrameUI` may return false (UI FPS limiter) and `EndFrameUI` composites the (stale) UI FBO over
the frame (L947-950). `GameLoadingState.render` forces it false for its frame and restores it (L627-628/778): the
vanilla precedent for a Java-only frame while the flag is set.

### B7. Translation

- B42 format: `media/lua/shared/Translate/<LANG>/<File>.json`, a flat JSON object `"KEY": "text"` (game:
  `media/lua/shared/Translate/EN/UI.json`, `CN/UI.json`; Translator format string
  `"%s/media/lua/shared/Translate/%s/%s.json"`). Mods: `Translator.tryFillMapFromMods(file, map, lang)` (L379-391)
  reads the same path under each mod's **common dir and version dir**, for mods in `ZomboidFileSystem.getModIDs()`.
- Key prefix selects the map (`getTextInternal` L429-457: `UI_` → `ui`, `IGUI_`, `Sandbox_`, `Tooltip_`, …), so a
  mod's `UI_<Mod>_...` keys belong in `UI.json`.
- Timing: maps are static `HashMap`s created in `<clinit>` (`ui` at clinit L372-376), so `getText` before
  `loadFiles` does not trigger a load (the `ui == null` branch L423-424 never fires); it returns the key itself and
  adds it to `missing` (logs only in debug), `getTextOrNull` returns null (L684-693). First fill: launch L1137
  (after mod discovery L1111 and `Languages.init` L1131). `ResetLua` clears and refills at L4146 from **that pass's**
  mod list: on a server whose `Mods=` lacks the progress mod, its keys are gone for the rest of the session.
- Language and glyphs: `TextManager.Init` reads `media/fonts/<Translator.getLanguage()>/fonts.txt` (L227-239,
  fallback EN), so CJK glyphs exist only when the game language uses them. Chinese strings drawn while the game is in
  English would lack glyphs.
- Vanilla keys usable as step names (EN / CN): `UI_Loading` "Loading"/"载入中", `UI_Loading_Lua` "Loading Lua"/
  "载入 Lua 脚本中", `UI_Loading_Scripts` "Loading Scripts"/"载入脚本中", `UI_Loading_Clothing`
  "Loading Clothing"/"载入服装", `UI_Loading_Texturepack` "Loading %1 texture pack"/"载入 %1 纹理包",
  `UI_Loading_ModelsAnimations` "Loading models and animations"/"加载模型和动画", `UI_Loading_OnGameBoot`
  "Game booting"/"游戏启动中", `UI_Loading_InitPublicServers`; join: `UI_servers_Connecting`, `UI_servers_UDPConnecting`,
  `UI_servers_ConnectionPending`, `UI_servers_Connected` "Joining game...", `UI_GameLoad_LoadingQueue`; world
  (`IG_UI.json`): `IGUI_MP_Checksum`, `IGUI_MP_LoadTileDef`, `IGUI_MP_InitMap`, `IGUI_MP_LoadWorld`,
  `IGUI_MP_RequestMapData`, `IGUI_MP_DownloadedMapData` "Received %1 / %2 map chunks.". "Loading Mods",
  "Loading Translations" and "Reloading Lua" are hard-coded English in vanilla (no keys).

### B8. Cost of a pushed frame and throttling

- `EndFrameUI` → `RenderThread.Ready` (L400) → `SpriteRenderer.pushFrameDown` (L656-668) →
  `waitForReadySlotToOpen` (L719-734): waits only while the previous ready frame has not been taken by the render
  thread. The render thread takes it at the start of its next `renderStep` (`acquireStateForRendering` →
  `moveReadyToRendering`, L674-684), i.e. after it finished rendering + swapping the previous frame
  (`Display.update(true)`, vsync). Steady state: each push costs the frame build plus up to one render frame (≈16.7
  ms at 60 Hz with vsync; longer when the render thread runs long `invokeOnRenderContext` items in `flushInvokeQueue`
  or heavy post-processing). The first push after a pause does not wait.
- The bar's own draw work is a few `renderi` + `DrawString` calls; other advice on the render path also runs per
  frame (B9).
- Throttle evidence: vanilla's `initAnimationMeshes(false)` loop pushes a frame every ~10 ms + render time; Windows
  needs a pump within 5 s. A time-based throttle on per-item triggers (not per call: `RunLua` fires thousands of
  times, `getAvailableModDetails` far more) bounds the cost to roughly (render frame time) / (throttle interval).
- Thread rule: vanilla frames come only from MainThread. Per-item hooks can fire on other threads (`Event.trigger` and
  `RunLua` from `GameLoadingThread` during world load; `getAvailableModDetails` from anywhere), so only
  `Thread.currentThread() == GameWindow.gameThread` may push. During `GameLoadingState` the frame loop already pushes
  every frame and pumps; the overlay belongs in those frames (B6), not in extra frames.
- Nesting rule: a frame must not be pushed while MainThread is inside another frame (`Core.frameStage != 0`), e.g.
  Lua events fired from `UIManager.render` during a state's render.

### B9. Other mods' advice on the frame path

- 16:00 log "patching" lines on the render path: `Core.EndFrameUI` (log lines 399, 1347), `SpriteRenderer.postRender`/
  `renderClamped`/`drawGeneric`/`renderPoly` (Viewpoint family, lines 324-328, 616-634, 1229-1239),
  `Display.create`/`imguiEndFrame`/`updateMouseCursor`/`update`/`processMessages` (lines 409-416, 1383-1416),
  `GameWindow.init`/`DoLoadingText` (ZB, 70-72; again 692-697 with `GameWindow.logic`).
- Correction to `loading-flow.md` §4: Viewpoint.jar and ISeeThem.jar contain no reference to `EndFrameUI`
  (byte scan of every class; `viewpoint.Patch_ZoomScroll` targets `MultiTextureFBO2.doZoomScroll`). The repeated
  "patching zombie.core.Core.EndFrameUI" lines appear when other mods retransform `Core` (their `saveOptions`,
  `loadOptions`, `RenderOffScreenBuffer`, `isOption3DGroundItem` patches follow each one) and ZB's own
  `Patch_Core_EndFrameUI` transformer re-applies. The only `EndFrameUI` advice in this mod set is ZB's Watermark.
- Watermark (`Watermark.java:61-120`) draws on every `EndFrameUI` while `_in_init` (until ZB's `onGameInitComplete`,
  i.e. `GameWindow.init` exit) or while not in `IngameState`: icon + version text top-left, using `TextManager`
  fonts (`MeasureStringY`). It already runs on the epilepsy frame and the `initAnimationMeshes` frames. Frames pushed
  between `TextManager.Init` (ResetLua L4167) and font readiness would be the first Watermark draws with loading fonts.
- Evidence from the 16:00 log: no error or exception from Viewpoint, ISeeThem, BloodFX or ZB around the epilepsy
  frame (16:00:35.9-38.3), the `ModelManager` frames (16:00:55.6-59.5) or the `ResetLua` frame (16:01:20.2); the only
  errors there are vanilla `AngelCodeFont failed to load page 0 texture` lines (16:00:36.117) and
  `ImportedSkeleton.collectBoneFrames`. These frames have no world; render-path advice (SpriteRenderer, Display) ran on
  them without logged failures. Frames pushed before the first ZB pass (16:00:26-32) would run before these mods'
  patches exist.
- Order of HEAD advice on `EndFrameUI` between ZB's Watermark (registered at premain before `preloadMods`) and a
  preloaded mod's advice (registered in `preloadMods`) was not verified.

### B10. Module build

Models: `zb-skip-online-checks/` (in-repo plain-Java shipped mod, preloaded, Zabric entrypoints, no Kotlin) and
`samples/zabric-java-hooks/` (javac front end of `zabric-processor`, out-of-tree, Maven artifacts).

| File | From the models |
|---|---|
| `settings.gradle.kts` | `include(":<module>")` (module list L20-31) |
| `<module>/build.gradle.kts` | plugins `buildsrc.convention.pz-java` + `pz-mod` (`zb-skip-online-checks/build.gradle.kts:5-8`); `version = "..."`; `compileOnly(project(":zabric"))`; `annotationProcessor(project(":zabric-processor"))` (no in-repo module uses the javac front end yet; the sample uses Maven coordinates, L26-27); `options.compilerArgs` `-Azabric.modId=<id>`, `-Azabric.javaPkgName=<pkg>` (`Names.java:31-33`, required by `ZabricProcessor.generate`, glue class default `<Id>Glue`); `CheckBootstrapClasses` task (L15-20); `checkLoaderRefs` excludes for the generated glue in `<pkg>/*` (as `pz-feature.gradle.kts:35-38` does; zb-skip-online-checks excludes its own top-level package for a different reason); `tasks.jar { manifest { attributes("ZB-Preload" to "true", "Zabric-PreLaunch" to ..., "Zabric-Main" to ...) } }` plus the new Zabric attribute; `assembleMod` adds the jar under `42/media/java[/client]` and the Lua/translation files; tests as in zb-skip-online-checks (JUnit + `-Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading`) if it uses an Instrumentation |
| `<module>/mod/42/mod.info` | `name`, `id`, `author`, `modversion=@VERSION@`, `poster`, `description`, `require=\ZombieBuddy,\Zabric`, `loadModAfter=\Zabric`, `javaJarFile`, `javaPkgName`, `javaPreload=true`, `ZBVersionMin=2.3.4`, `versionMin=42.21.0` (`zb-skip-online-checks/mod/42/mod.info`) |
| `<module>/mod/42/poster.png`, `<module>/mod/workshop/preview.png`, `<module>/mod/workshop/workshop.txt` | Workshop layout (`version=1`, `title`, `description=` lines in BBCode, `tags=Build 42;...`, `visibility`; `id=` after the first upload, guarded by `deployWorkshop`, `pz-mod.gradle.kts`) |
| `<module>/README.md` | as zb-skip-online-checks |
| `src/main/java/<pkg>/` | only the generated glue lives in `<pkg>` itself: ZB loads every class of `javaPkgName` at premain and applies patches only from exactly that package (spec Patch Classes; `zb-skip-online-checks/.../package-info.java`); hook classes, gates, entrypoints in subpackages (sample: `<pkg>.hooks`) |
| `src/main/java/<pkg>/<sub>/...Entry.java` | `PreLaunchEntrypoint` (keeps the mod preloaded; plain Java, no game classes, no log) and `MainEntrypoint` (reports) (`zb-skip-online-checks/.../entrypoint/*.java`) |
| `src/main/resources/META-INF/zabric/<modid>.classtweaker` | only if non-public members are used (e.g. `ScriptManager.tempFileToModMap`, `GameLoadingState.done`); methods cannot be widened. The widened compile jars are built by `pz-feature` (Kotlin) today; plain-Java modules have no wiring for it |
| `src/main/lua/shared/Translate/EN/UI.json`, `.../CN/UI.json` (into `42/media/lua/...` or `common/`) | B7 |
| `.trellis/spec/mod/directory-structure.md` | the Layout lists every shipped module |

Client only: `javaJarFile=media/java/client/...` makes ZB skip the mod on a dedicated server during mod-list loading
(`JavaModInfo.java:135-139`, ModdingGuide "Client/server JAR paths"), but `preloadMods` does not check the path
(`Loader.java:401-466`), and a client and a dedicated server on one machine share `~/.zombie_buddy/config.json`
(`PreloadConfig.java` comment on `current`). Zabric's `PreLaunchHook` also hooks `GameServer.main`.

---

## Open technical risks

1. **Early class loading.** A preloaded mod's glue is reflected over at premain (`PatchEngine.collectPatches` →
   `Exposer.hasGlobalLuaMethod`), and a preLaunch target check calls `Class.forName` + `getDeclaredMethods` on every
   hook owner. Game classes then load before other mods register their patches; those apply by retransformation
   (advice only, no class-format changes, `PatchEngine.java:221-229`). Not a problem for the advice-only patches seen
   in Viewpoint/ISeeThem; unknown for other mods.
2. **Log before the log exists.** Everything Zabric's runtime and the default gate log between preLaunch and
   `DebugLog.init` (`MainScreenState.main` L192) is lost unless held; findings may occur at class loads in
   `main` L139-L192.
3. **Lifecycle stop after hooks ran.** `!zabricLoaded`, `PreLaunchHook.problem()` and other `start()` failures are
   only known at the first pass (`Lifecycle.java:150-161,185-213`); with D6, preloaded hooks already ran for the launch
   until then.
4. **Fonts after `ResetLua` L4167** are recreated asynchronously without a wait; drawing text (ours and Watermark's)
   in that window is not precedent-backed. `TextManager.isAnyFontLoading()` is the readiness signal.
5. **Frames inside frames / off-thread.** Per-item hooks fire on `GameLoadingThread` and inside other frames; pushing
   from there would break the single-producer rule. Guards: `GameWindow.gameThread` and `Core.frameStage == 0`.
6. **`useUiFbo` during `IngameState.enter`** (set at L738): Java-only frames there go through the UI FBO path unless
   the flag is handled as `GameLoadingState.render` does.
7. **Unknown cost per stretch.** No per-item call with a total exists in launch row 7d and join J3.6; only boundaries
   and the frequent `getAvailableModDetails`. Whether that keeps gaps under 5 s depends on which manager is slow
   (unmeasured). `IngameState.enter`'s ~4 s in `OnGameStart`/`OnLoad` needs per-callback triggers
   (`LuaCaller.protectedCallVoid`, hot for all Lua calls).
8. **Join abort paths.** A failed connect (`OnConnectFailed`, `ConnectToServerState.exit`, return to the menu) ends the
   flow early; abort signals beyond `Finish` L752 were not traced.
9. **Advice order on `EndFrameUI`** relative to ZB's Watermark is unverified (overlap is only top-left).
10. **Dedicated server on the same machine** may preload the client-only jar and call its preLaunch entrypoint
    (shared `config.json`, no client/server check at preload).

## Contradictions with the PRD / specs

- D6 vs spec `access-widening.md` (Zabric rules): "Premain and preLaunch code touches no game class and logs nothing".
  A preLaunch weave/target check loads game classes and produces log lines (held or not).
- D6 vs the published author contract: `zabric/mod/workshop/workshop.txt` ("hooks run from the first mod list Zabric
  processes … and only while your mod is in the mod list"), `ModHooks` Javadoc, `Lifecycle` Javadoc. These change
  with the release.
- D5 (English and Chinese via translations): game translation files load only for mods in the current pass's list and
  only from `Translator.loadFiles` (launch L1137) on; on a server without the mod they vanish at `ResetLua` L4146. The
  bar runs before L1137 and on such servers, so it needs text that does not come from the game's Translator there.
  Chinese glyphs need the game language's fonts.
- D5 "client only" vs preload: ZB preloads by absolute path without the client/server check.
- D1 says the mod "installs its own advice"; D6 replaced that with Zabric `@Hooks` (consistent with D6, but D1's text
  still says own advice).
- `loading-flow.md` corrections: the `Core.EndFrameUI` attribution to Viewpoint/ISeeThem is wrong (B9); launch row 9b
  is not static: `ModelManager.initAnimationMeshes(false)` pushes empty frames (black, with Watermark) while models
  load (`ModelManager` L2093-2103); in `ResetLua` the same call uses `true` and pushes none.

## Caveats / Not found

- `MainScreenState` → `GameLoadingState` transition for single player was not traced (Lua/state machine).
- ClassGraph's `loadClass()` initialization flag was taken as "no init" from its defaults; not verified here.
- `AngelCodeFont.drawString` behaviour with pages still loading was not verified.
- ByteBuddy advice nesting order across separate ZB transformers was not verified.
- No timing exists for the individual managers in row 7d / J3.6.
