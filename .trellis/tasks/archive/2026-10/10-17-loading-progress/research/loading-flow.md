# Research: loading flow, threads, drawing and message pumping (launch → menu, join → in world)

- **Query**: Where the game loads (menu and in-game), which thread runs each step, what is drawn, whether the OS message
  loop is pumped, which hooks expose progress, where ZombieBuddy/Zabric work sits, per-phase timings, and the technical
  options and constraints for a progress bar and a responsive window.
- **Scope**: mixed (javap of the 42.21 jar, ZombieBuddy and Zabric source, DebugLogs, GLFW and Win32 docs)
- **Date**: 2026-10-17

Sources and conventions

- Game: `E:/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid.jar` (Build 42.21), read with
  `javap -c -p -l` (GraalVM 25.0.2). No decompiler was needed. `Class.method Lnnn` means source line nnn from the
  class's LineNumberTable, not a line in this repo.
- Launch config: `ProjectZomboid64.json` (mainClass `zombie/gameStates/MainScreenState`; vmArgs
  `-agentlib:zbNative=policy=allow-all,patches_jar=ZBSkipOnlineChecks.jar:...`). The DebugLog prints
  `java.home=C:\Users\dontc\.jdks\graalvm-ce-25.0.2`, so the newest runs use GraalVM 25, not `jre64`.
- Logs (millisecond timestamps, UTF-8): newest full menu + server join =
  `~/Zomboid/Logs/logs_2026-10-17_16-02/2026-10-17_16-00_DebugLog.txt` (below: "16:00 log"). Cross-checks:
  `logs_2026-10-17_15-55/2026-10-17_15-53_DebugLog.txt` (join), `2026-10-17_16-45_DebugLog.txt` (newest, menu only),
  baseline `logs_2026-10-17_02-57/2026-10-17_02-53_DebugLog.txt`. The newest logs (16-16, 16-43, 16-45) have no join.
  No single-player load exists in today's logs; the newest is `logs_2026-10-13_11-09/2026-10-13_10-53_DebugLog.txt`
  (older mod set, used only for the SP sequence).
- Log prefix `f:N` is the in-game frame counter: it stays `f:0` through every loading phase and first reaches `f:1`
  once `IngameState` runs (16:00 log 16:02:32.453).

## Findings

### Files found

| File / class | Description |
|---|---|
| `zombie.gameStates.MainScreenState.main` | JVM entry; creates the window, starts the game thread, then runs the render loop on the JVM main thread |
| `zombie.core.opengl.RenderThread` | Render loop, GL-context owner, message pump, render-context invoke queue |
| `zombie.MainThread` | The game-logic thread (`"MainThread"`, thread group `ThreadGroups.Main`) |
| `zombie.GameWindow` | `mainThreadStart/Init`, `init`, `initShared`, `enter`, `frameStep`, `DoLoadingText`, `doEpilepsyWarningText` |
| `zombie.core.SpriteRenderer`, `zombie.core.Core` (`StartFrame`/`EndFrame`/`StartFrameUI`/`EndFrameUI`) | Frame hand-off from the game thread to the render thread |
| `org.lwjglx.opengl.Display` | `processMessages` = `glfwPollEvents`; `update(true)` = swap + `processMessages` |
| `zombie.core.Core.ResetLua(String,String)` | Full reload (scripts, mods, translations, models, Lua) |
| `zombie.gameStates.ConnectToServerState` | Server-join state machine; `Finish` → `receiveServerOptions` → `ResetLua` |
| `zombie.gameStates.GameLoadingState` (+ `$1`) | World loading: work on `GameLoadingThread`, loading screen drawn on the game thread |
| `zombie.gameStates.IngameState.enter` | First in-world setup; `OnGameStart`/`OnLoad`; MP player-connect wait |
| `zombie.Lua.LuaManager` | `LoadDirBase`, `RunLua`, `loadList`, `loaded`, single `KahluaThread thread` |
| `zombie.scripting.ScriptManager`, `zombie.ZomboidFileSystem` | Script and mod loading loops |
| `external-repos/ZombieBuddy/java/src/main/java/me/zed_0xff/zombie_buddy/patches/Patch_ZomboidFileSystem.java` | ZB's `loadMods` hook and its timing log lines |
| `external-repos/ZombieBuddy/.../Watermark.java`, `patches/Patch_Core.java`, `patches/Patch_GameWindow.java` | ZB draws in `Core.EndFrameUI`; ZB patches `GameWindow.DoLoadingText` |
| `external-repos/ZombieBuddy/.../frontend/SwingModApprovalFrontend.java:47` | ZB's approval UI runs as a separate process (`ProcessBuilder`) |
| `zabric/src/main/java/com/pzlauncher/zabric/entrypoint/PreLaunchHook.java`, `Lifecycle.java` | Zabric preLaunch runs on entry to `MainScreenState.main` |
| `ime-keyboard-fix/src/main/kotlin/com/dont39/pz/imekeyboardfix/ImeWindow.kt:21` | Our existing Zabric `@Inject` on `Display.processMessages` (FINALLY) |

### 1. Thread architecture, window ownership and the message pump

**Two threads, and the JVM main thread renders.**

- `MainScreenState.main` (JVM `main` thread): `Display.init()` = `glfwInit` (L147) → … → `RenderThread.init()` (L324)
  → asset managers (L325-337) → `GameWindow.InitGameThread()` (L338) → `RenderThread.renderLoop()` (L339, does not
  return until exit).
- `RenderThread.init`: `renderThread = Thread.currentThread()` (L67), `GameWindow.InitDisplay()` (L78, creates the
  GLFW window via `Display.create`; title "Project Zomboid", `Core.init`, `SpriteRenderer.create`),
  `Controllers.create` (L79). The GLFW window and GL context therefore belong to the JVM main thread, which is also the
  render thread.
- `GameWindow.InitGameThread` (L506-508) → `MainThread.init` starts the `"MainThread"` thread (MainThread L44-48).
  `MainThread.mainLoop`: `mainThreadStart.run()` once (L59), then loops `mainThreadLoop.run()` = `GameWindow.mainThreadStep`
  (L69) with `Thread.yield()`.
- GL context: `RenderThread.acquireContextReentrantInternal` (L422-445) throws if another thread holds it; other
  threads run GL work through `RenderThread.invokeOnRenderContext` (L480-498: queue + wait), executed by
  `flushInvokeQueue` (L322-375) on the render thread. `queueInvokeOnRenderContext` (L568-593) runs inline only before
  the render thread exists or when already on it.

**Render loop** (`RenderThread.renderLoop` L112-180), per iteration under `m_contextLock`:

1. `renderStep()` → `lockStepRenderStep()` (L265-297):
   - `SpriteRenderer.acquireStateForRendering(waitForRenderStateCallback)` (SpriteRenderer L674-685) →
     `waitForReadyState` (L691-712):
     - a ready frame → render it: `SpriteRenderer.postRender()` (L285), then `Display.update(true)` (L289) = swap +
       `processMessages` (Display L393-400);
     - no ready frame and `RenderThread.waitForRenderState == false` (and the game thread is not waiting for a slot)
       → returns null at once (L695-696); `lockStepRenderStep` then calls `Display.processMessages()` (L270-272);
     - no ready frame and `waitForRenderState == true` → loops on `states.wait()` (L700-709). Each wake-up runs only
       `flushInvokeQueue` + `shouldContinueWaiting` (RenderThread L304-309). **No `processMessages`** (the only
       exception: Lua debugger stepping, L270 `LuaManager.thread.step`).
2. `flushInvokeQueue()` (L144), input poll (L147-149), `Thread.yield()` (L168).

`Display.processMessages` (Display L448-468) = `glfwPollEvents` + keyboard/mouse poll + focus handling.

**Who sets `waitForRenderState`** (field default false; `<clinit>` does not set it). The only writers in the jar:

| Writer | Value | Anchor |
|---|---|---|
| `GameWindow.mainThreadStart` after `mainThreadInit()` + `enter()` | true | GameWindow L539 |
| `Core.ResetLua(String,String)` start | false (+ `notifyRenderStateQueue`) | Core L4096-4097 |
| `Core.ResetLua` after `LuaManager.LoadDirBase` + `ZomboidGlobals.Load`, before `OnGameBoot`/`OnMainMenuEnter`/`OnResetLua` | true | Core L4180-4184 |
| `IngameState.exit` | false, later true | IngameState `exit` (bytecode 269, 1396) |

Consequences, read from the code:

- While `waitForRenderState == false` (all of startup up to the end of `GameWindow.enter`, and most of `ResetLua`)
  the render thread spins, calling `glfwPollEvents` every iteration, but renders nothing new: the window keeps its last
  swapped image. Windows should not mark it hung.
- While `waitForRenderState == true`, the pump runs only once per frame the game thread pushes. Any game-thread work
  that pushes no frame for more than 5 s leaves the window hung by Windows' definition (see External references).
- Frame hand-off: `Core.EndFrameUI` → `RenderThread.Ready()` (Core L968) → `SpriteRenderer.pushFrameDown()`
  (RenderThread L400), which first waits for the ready slot to free (`waitForReadySlotToOpen`, SpriteRenderer
  L656-669, L719-734). Normal frames come from `GameWindow.frameStep` (L782-849: `logic()` → `renderInternal()` →
  `GameStateMachine.render`).
- `GameWindow.DoLoadingText(String)` (L1315-1316) only calls `DebugLog.log`. Vanilla shows no startup loading text;
  the Chinese DoLoadingText lines in the log ("载入脚本中", "载入 Lua 脚本中", …) are written to the log only.

### 2a. Launch → main menu: call chain, thread, drawing, pumping

Everything below runs on **MainThread** inside `GameWindow.mainThreadStart` (L531-539) → `mainThreadInit` (L590-663)
→ `GameWindow.init` (L1077-1150) / `initShared` (L179-253) → `GameWindow.enter` (L720-777), with
`waitForRenderState == false`. A frame is pushed only by `GameWindow.doEpilepsyWarningText` (L1319-1334:
`StartFrame/EndFrame/StartFrameUI`, draws translated warning text, `EndFrameUI`), called once at `GameWindow.init`
L1139. It returns early if `TextManager.instance.font == null` (L1319-1320).

Order of the work:

- `mainThreadInit`: FMOD/SoundManager (L609-617), `ZomboidFileSystem.init` (L633), then `init()` (L660).
- `GameWindow.init`:
  - `initGlobalShader`, `invokeOnRenderContext` (L1077-1078), `initFonts` (L1085), Steam/RakNet/JNI init
    (L1087-1099);
  - `DoLoadingText("Loading Mods")` (L1108) → `resetDefaultModsForNewRelease` (L1110),
    `ZomboidFileSystem.loadMods("default")` (L1111), `loadModPackFiles` (L1112);
  - `DoLoadingText("Loading Translations")` (L1129) → `Languages.init` (L1131), `initFonts` (L1135),
    `Translator.loadFiles` (L1137), `doEpilepsyWarningText` (L1139), `LuaManager.init` (L1141), `initShared()`
    (L1143);
  - `DoLoadingText(UI_Loading_Lua)` (L1145) → `LuaManager.LoadDirBase()` (L1146: shared + client, LuaManager
    L1260-1262), `ZomboidGlobals.Load` (L1148), `OnLoadSoundBanks` (L1150).
- `initShared`: texture packs UI, UI2, … WeatherFx (L192-198), `UI_Loading_Scripts` → `ScriptManager.Load`
  (L215-217), `SpriteModelManager.init` (L218), `UI_Loading_Clothing` → Clothing/Beard/Hair/Outfit/Voice (L220-226),
  `DoLoadingText("")` (L228), RandStandard/Lua, cursor, `TileGeometryManager`, `TileDepthTextureAssignmentManager`,
  `SeamManager`, `SeatingManager` init (L230-241), add `TISLogoState`/`TermsOfServiceState`/`MainScreenState`
  (L244-247), `Input.initControllers` (L253) plus controller info.
- `GameWindow.enter`: 1x/2x tile texture packs (L730-744), `UI_Loading_ModelsAnimations` → `ModelManager.create`
  (L763-764), TileDepth managers, voice (L766-770), `UI_Loading_OnGameBoot` → `triggerEvent("OnGameBoot")`
  (L772-773).
- Then `mainThreadStart` sets `waitForRenderState = true` (L539), and the normal frame loop starts: `TISLogoState`
  (logo frames, skippable by click/keys: `TISLogoState.update` L191-194), `TermsOfServiceState`, `MainScreenState`.

Sub-steps and their threads: texture packs, scripts, Lua, translations and mod discovery all run on MainThread. GL
uploads go through `invokeOnRenderContext` to the render thread. Asset loading (`AssetManagers`) has its own workers
(not traced). Sounds: FMOD init in `mainThreadInit`; script sounds in `ScriptManager.Load` → `GameSounds.ScriptsLoaded`
(L1554).

### 2b. Loading into a game

**Server join** (`myserver1`), all on MainThread:

- `MainScreenState.update` L784 → `ConnectToServerState.update` → states `Start`/`TestTCP`/`Workshop*`/`CheckMods`/
  `Finish`; `Finish` → `receiveServerOptions` → `Core.ResetLua("client","ConnectedToServer")`
  (ConnectToServerState L135). The whole reset runs inside one `logic()` call of one frame.
- `Core.ResetLua` (L4090-4185):
  - if `SpriteRenderer.instance != null`: `drawReloadingLua = true; GameWindow.render(); drawReloadingLua = false`
    (L4090-4093). That pushes one frame in which `MainScreenState.render` (L529-538) draws a centered box with the
    hard-coded text "Reloading Lua" over the menu.
  - `setWaitForRenderState(false)` (L4096), then resets (L4101-4139), `ZomboidFileSystem.Reset/init/loadMods/
    loadModPackFiles` (L4141-4144), `Languages.init`, `Translator.loadFiles` (L4145-4146), perks/sandbox/registries
    (L4147-4151), `ScriptManager.Load` (L4152), `SpriteModelManager.init`, `ModelManager.initAnimationMeshes`,
    `loadModAnimations` (L4153-4155), clothing (L4156-4160), tile/seam/seating managers (L4161-4165),
    `TextManager.Init` (L4167), `AdvancedAnimator.load` (L4168), `LuaManager.LoadDirBase()` (L4169; on error it logs
    "Reloading Lua - ERRORS!" via `DoLoadingText` and sleeps, L4171-4174).
  - `ZomboidGlobals.Load` (L4178), **`setWaitForRenderState(true)` (L4180)**, then `OnGameBoot`, `OnMainMenuEnter`,
    `OnResetLua` (L4182-4184), which run without the pump.
- Then `MainScreenState` exits, `LoadingQueueState` (login queue; draws a menu background + `LoadingQueueUI`,
  LoadingQueueState L59-75), then `GameLoadingState.enter` (below).

**GameLoadingState** (same for SP and MP):

- `enter()` on MainThread, which pushes no frame: `LuaManager.LoadDirBase("server")` + `finishChecksum` (L157-158),
  `ScriptManager.LoadedAfterLua` (L163), `Core.initFBOs`/`initShaders` (L165-167), MP `GameClient.init` + a
  connect-wait loop with `Thread.sleep` (L177-188), `OnPreMapLoad` (L255), then starts thread `"GameLoadingThread"`
  (group `ThreadGroups.Workers`, L286-435) and sets `UIManager.suspend = true` (L431).
- Loader thread `GameLoadingState$1.run` (L291-302): sets `LuaManager.thread.debugOwnerThread` and
  `debugthread.debugOwnerThread` to itself, runs `runInner`, then hands both back to `GameWindow.gameThread`.
  `runInner` (L307-426): waits on asset lock 1, `BaseVehicle.LoadAllVehicleTextures`, MP `GameLoadingRequestData`,
  `new IsoWorld().init()` (L329-334), `OnGameTimeLoaded` (L349), `GlobalObjects.initSystems`, waits for asset loading
  ("bWaitForAssetLoadingToFinish2", L400-410), `FBORenderChunkManager.gameLoaded`, `Bullet.initPhysicsMeshes`, then
  `gameLoadingString = ""` and `SendDone()` (L425-426).
- `GameLoadingState.render` runs every frame on MainThread while the loader works. It draws text (`DrawStringCentre`;
  `gameLoadingString` at bytecode 1247-1364), quick tips, "UI_Loading" with animated dots, `convertingFileCount/Max`
  (world conversion only), and `renderProgressIndicator` (L825-848: an animated walking/sprinting figure from
  `media/ui/Progress/*.png` with a time-based fade; not a percentage). The window is pumped and redrawn here.
- `gameLoadingString` writers: `IsoWorld.init` (`IGUI_MP_Checksum`, `IGUI_MP_LoadTileDef`, `IGUI_MP_InitMap`,
  `IGUI_MP_LoadWorld`, "", `IGUI_MP_ServerPlayerIDMismatch`), `IsoCell.LoadPlayer`,
  `RequestDataManager.receiveClientData` (translated `RequestID` descriptor), `GameLoadingState$1` ("Saving converted
  world.", "").
- After loading, the state waits for a click or key (`showedClickToSkip`). In the baseline log the player idled 79 s
  here (02:56:33 → 02:57:52).
- `IngameState.enter` on MainThread pushes no frame while it runs: `OnGameStart`, `OnLoad` (L780-782), MP
  `sendPlayerConnect` + a busy wait `Thread.sleep(10)` + `GameClient.update` until `onlineId != -1` (L786-796),
  `ClimateManager.update`, `LightingThread.GameLoadingUpdate` (L799-800).

**Single player**: `MainScreen.lua` resets only when the ordered mod list changed:
`ActiveMods.requiresResetLua(...)` → `getCore():ResetLua("currentGame","startTutorial"|"continueSave")` or
`("default","modsChanged")` (`media/lua/client/OptionScreens/MainScreen.lua:1009-1010, 1164-1169, 1233-1237`). These
calls run inside a Lua UI callback on MainThread. After that comes the same GameLoadingState → click → IngameState
sequence, without the network steps. Oct 13 SP example: reset 11:08:25 → MainScreen exit 11:09:15.9 → asset wait end
11:09:37.5 → (click) GLS exit 11:10:12.9 → `f:1` 11:10:24.1.

### 3. Vanilla progress sources and hook points (cheap to read)

| Phase | Counter / state available | Per-item call (hook candidate) | Anchor |
|---|---|---|---|
| Any | Text key passed to `GameWindow.DoLoadingText(String)` ("Loading Mods", "Loading Translations", `UI_Loading_Texturepack`, `UI_Loading_Scripts`, `UI_Loading_Clothing`, "", `UI_Loading_Lua`, `UI_Loading_ModelsAnimations`, `UI_Loading_OnGameBoot`; ZB adds "Waiting for Java mods approval…"/"Loading Mods") | `DoLoadingText` itself; already patched by ZB (`Patch_GameWindow.java:16-21`) and by `bloodfx.SlowMo$Hook` per the 16:00 log | GameWindow L1108, L1129, L1145, L215, L220, L228, L763, L772, L1021; Core L4172; ZB `Loader.java:803-811` |
| Mod loading | `ZomboidFileSystem.getModIDs()` = total; per-mod "loading <id>" log | `ZomboidFileSystem.loadMod(String)`, which calls `CoopMaster.instance.update()` per mod | ZomboidFileSystem `loadMod` L752 |
| Lua files | `LuaManager.loadList` (public static; full ordered list of the current `LoadDirBase` pass, built before the loop) and `LuaManager.loaded` (public static List, grows per file and per `require`) | `LuaManager.RunLua(String)` per file; `CoopMaster.instance.update()` after every file | LuaManager L1160-1233 (loop L1212-1233, CoopMaster L1232), RunLua L1335-1349 |
| Scripts | `ScriptManager.currentFileName` (public), private static `currentLoadFileMod/Name/AbsPath`; file list is local to `Load()` | `ScriptManager.LoadFile(...)`, which calls `Core.DoFrameReady()` (= `updateKeyboard`) per file | ScriptManager L1105-1110, `Load` L1432-1563 |
| Texture packs | pack name (DoLoadingText `UI_Loading_Texturepack`) | `GameWindow.LoadTexturePack(String,int[,String])` | GameWindow L1019-1031 |
| Lua events (OnGameBoot, OnGameStart, …) | `zombie.Lua.Event.callbacks` (public `ArrayList<LuaClosure>`) | `Event.trigger(...)` | `zombie.Lua.Event` |
| World load | `GameLoadingState.gameLoadingString` (public static), `convertingFileCount/Max` (public static) | none needed: GameLoadingState already draws them | see 2b |
| Frame loop | — | `CoopMaster.update` is also called per frame in `GameWindow.logic` | `GameWindow.logic` |

`CoopMaster.update()` callers in the whole jar: only `GameWindow.logic`, `LuaManager.LoadDirBase` and
`ZomboidFileSystem.loadMod` (bytecode scan of every class referencing `zombie/network/CoopMaster`).

Not found: no vanilla percentage or "n / total" counter for startup or `ResetLua`; no progress field for translations,
models or `AdvancedAnimator.load` (not traced further).

### 4. ZombieBuddy and Zabric work inside the loading phases

- ZB patch (`Patch_ZomboidFileSystem.java:31-47`): on entry to `ZomboidFileSystem.loadMods(List)` it logs
  "loadMods(N mods) ...", runs `Loader.loadMods(mods)` (`Loader.java:596-…`: jar list, approvals,
  `loadZBSContext`, classpath, each mod's `Main`, ByteBuddy patching), logs "Loader.loadMods() took X ms" if > 1 s,
  then times the vanilla body ("ZomboidFileSystem.loadMods(...) took X ms"). It runs on MainThread inside
  `GameWindow.init` L1111 (menu) and `Core.ResetLua` L4143 (join), with `waitForRenderState == false` in both.
- Zabric runs inside ZB's pass: "[Zabric] access widener" / "preLaunch" / "main" lines (16:00 log 16:00:26.700 →
  32.878). `PreLaunchHook` adds advice on entry to `MainScreenState.main` / `GameServer.main`
  (`PreLaunchHook.java:20, 31-58`), so a mod with a preLaunch entrypoint that Zabric has registered as a ZB preload
  mod runs before `Display.init`, from the launch after its registration (`Preload.java`, spec
  `.trellis/spec/mod/access-widening.md` "Entrypoints and Preload Registration"). Without preload, a mod's code and
  hooks start only inside the first `loadMods` pass, after window creation, mod discovery and most of ZB's pass.
- Timings, 16:00 log, menu pass (ZB `Loader.loadMods() took 6578 ms`, 16:00:26.299 → 32.879):
  - list + approvals 26.30 → 26.70; Zabric main/widener/preLaunch 26.70 → 26.82;
  - `Viewpoint.jar` 26.85 → 28.36 (1.5 s); TrueWeathers 0.37 s; TrueWeathersGameplay 0.43 s; C1PViewpoint 0.09 s;
    BloodFX 0.43 s; PV-modmapbakefix 0.03 s; PVShelves 0.44 s; PVNearVegetation 0.18 s;
  - `ISeeThem.jar` 30.34 → 32.54 (2.2 s);
  - ZabricKotlin 32.54 → 32.66; Dont39AllInOne + Zabric main entrypoints 32.66 → 32.88;
  - vanilla body after it: 2570 ms (→ 35.451).
  - Newest menu-only run (16-45 log): ZB 7072 ms, vanilla 2669 ms.
- Join pass (in `ResetLua`): ZB work 16:01:23.593 → 23.856 (≈0.26 s, no "took" line, so under 1 s); vanilla body
  2359 ms (→ 26.216).
- ZB also transforms classes lazily at first load ("[ZB] Transformed: … (new load)" lines all through loading, e.g.
  `GameLoadingState`, `IngameState`, `viewpoint.render.*`). That cost is spread out and not measured.
- Mods already patch the render/loading path (from "[ZB] patching …" lines in the 16:00 log): `Display.update`,
  `Display.processMessages` (our `ime-keyboard-fix`, `ImeWindow.kt:21`), `Display.imguiEndFrame`,
  `Core.EndFrameUI` (ZB Watermark; Viewpoint `Patch_ZoomScroll`; ISeeThem `Patch_WorldDepth` per the attribution),
  `SpriteRenderer.postRender`, `GameWindow.DoLoadingText`/`init`/`logic`, `GameLoadingState.enter`/`exit`,
  `IngameState.enter`. Attributing a patch to a mod from the preceding "Found patch class" line is approximate.

### 5. Per-phase timeline (16:00 log; `Not Responding` follows the 5 s rule and the pump analysis in §1)

Launch → menu: 50.3 s from the first log line (16:00:17.593) to `TermsOfServiceState` exit (16:01:07.917). The
newest 16-45 run took 49.9 s; the 02:53 baseline took ~65 s.

| # | Phase (thread: MainThread unless noted) | Window | From → to | Duration | On screen | Pumped |
|---|---|---|---|---|---|---|
| 0 | Process start, ZB premain/preload, `patches_jar` (JVM main, before `main`) | none | before 17.593 | not in DebugLog (28 s at baseline, prior research) | no window | n/a |
| 1 | `main`: specs, Bullet, `RenderThread.init` (window created, onDisplayCreate 18.260), `InitGameThread` (JVM main) | yes | 17.593 → 18.289 | 0.7 s | blank | render loop starts |
| 2 | `mainThreadInit`: FMOD, filesystem, `initGlobalShader`, `initFonts`, Steam/RakNet libs | yes | 18.289 → 21.479 | 3.2 s | blank | yes |
| 3 | "Loading Mods": mod discovery in `loadMods("default")` before ZB's hook | | 21.479 → 26.299 | 4.8 s | blank | yes |
| 4 | ZB `Loader.loadMods` incl. Zabric + Java mods | | 26.299 → 32.879 | 6.6 s | blank | yes |
| 5 | vanilla `loadMods(List)` + `loadModPackFiles` | | 32.879 → 35.989 | 3.1 s | blank | yes |
| 6 | Translations, `initFonts`, epilepsy frame, `LuaManager.init` | | 35.989 → 38.340 | 2.4 s | epilepsy warning text appears (only frame) | yes |
| 7a | `initShared`: UI texture packs | | 38.340 → 38.460 | 0.1 s | static | yes |
| 7b | `ScriptManager.Load` + `SpriteModelManager.init` | | 38.460 → 41.719 | 3.3 s | static | yes |
| 7c | Clothing | | 41.719 → 41.998 | 0.3 s | static | yes |
| 7d | Unlogged tail of `initShared` (tile geometry/depth, seams, seating, states, controllers) | | 41.998 → 48.772 | 6.8 s | static | yes |
| 8 | `LuaManager.LoadDirBase` shared+client, `ZomboidGlobals`, `OnLoadSoundBanks` | | 48.772 → 55.356 | 6.6 s | static | yes |
| 9a | `enter`: 2x texture packs | | 55.356 → 55.588 | 0.2 s | static | yes |
| 9b | `ModelManager.create` (models + animations) | | 55.588 → 59.558 | 4.0 s | static | yes |
| 9c | `OnGameBoot` (Lua, all mods) | | 59.558 → ~02.369 | ~2.8 s | static | yes |
| 10 | Frame loop: TIS logo (skippable), Terms, menu enter | | ~02.4 → 07.917 | ~5.5 s | logo frames | yes (per frame) |

Server join → in world: from "Initialising RakNet" (16:01:13.563) to GameLoadingState exit (16:02:24.401) is 70.8 s,
of which ~3.7 s is the click wait; to the first in-game frame `f:1` (16:02:32.453) is 78.9 s. Baseline: ~89 s.

| # | Phase | From → to | Duration | On screen | Pumped |
|---|---|---|---|---|---|
| J1 | RakNet init, GettingServerInfo (4.0 s gap), UDP connect, auth | 13.563 → 19.454 | 5.9 s | menu + Lua connect UI (frames) | yes |
| J2 | `ConnectToServerState` Start → CheckMods (Workshop item checks) | 19.607 → ~20.18 | 0.6 s | frames | yes |
| J3 | `ResetLua` total: one "Reloading Lua" frame at the start, then nothing new until the end | ~20.18 → ~46.0 | ~25.8 s | static "Reloading Lua" over the menu | yes until L4180 |
| J3.1 | resets, `LuaManager.init` (ZB re-exposes classes 20.35) | 20.18 → 21.29 | 1.1 s | static | yes |
| J3.2 | mod discovery, translation mod "Spawn Location_CN" | 21.29 → 23.59 | 2.3 s | static | yes |
| J3.3 | ZB pass + vanilla `loadMods` | 23.59 → 26.22 | 2.6 s | static | yes |
| J3.4 | Languages, Translator, perks, sandbox, registries | 26.22 → 26.89 | 0.7 s | static | yes |
| J3.5 | `ScriptManager.Load` (to the last script-loading log line) | 26.89 → 29.11 | 2.2 s | static | yes |
| J3.6 | Unlogged: SpriteModelManager, `ModelManager.initAnimationMeshes`/`loadModAnimations`, clothing, tile managers, `TextManager.Init` | 29.11 → 36.35 | 7.2 s | static | yes |
| J3.7 | `AdvancedAnimator.load` (first `searchFolders`, "missing-anim-folders: active" 36.35) and the start of `LoadDirBase` (first Lua warning 40.63) | 36.35 → 40.63 | 4.3 s | static | yes |
| J3.8 | `LoadDirBase` shared+client (Zabric's client file 43.33, near the list end) | 40.63 → ~43.4 | ~2.8 s | static | yes |
| J3.9 | after L4180: `OnGameBoot`, `OnMainMenuEnter` (main-menu UI Lua 45.49-45.99), `OnResetLua` | ~43.4 → ~46.0 | ~2.6 s | static | **no** |
| J4 | MainScreenState exit (46.177), LoadingQueueState (exit 46.471) | 46.18 → 46.47 | 0.3 s | queue UI frames | yes |
| J5 | `GameLoadingState.enter`: server Lua `LoadDirBase("server")`, shaders, FBOs (Viewpoint renderer start logged 48.645) | 46.47 → ~48.6 | ~2.2 s | static | **no** |
| J6 | Loader thread: `IsoWorld.init` (OnInitWorld 50.70, tile defs 54.14, map zones 59.35), `OnGameTimeLoaded` 03.697 | ~48.6 → 03.82 | ~15.2 s | loading screen frames (text + animated figure) | yes |
| J7 | Loader waits for asset loading ("bWaitForAssetLoadingToFinish2") | 03.821 → 20.736 | 16.9 s | loading screen frames | yes |
| J8 | FBO chunks, physics meshes, click to start | 20.736 → 24.401 | 3.7 s | loading screen / click prompt | yes |
| J9 | `IngameState.enter`: `OnGameStart`/`OnLoad` (gap 25.86 → 29.89), player-connect wait 30.223 → 30.25, rest | 24.401 → 32.453 | **8.1 s** | last loading-screen frame | **no** |

Cross-check of J9 (`GameLoadingState` exit → `f:1`): 15:53 log 15:55:12.091 → 15:55:20.181 (8.1 s); baseline
02:57:52.361 → 02:58:01.764 (9.4 s).

Per the code, the window should be marked hung (no pump for 5 s or more) only in **J9**. It stays unpumped for
2-3 s in J3.9 and J5, which the log resolution cannot narrow further. The long phases (launch rows 2-9, ~44 s; J3,
~23 s before L4180) keep pumping but redraw nothing: the window shows a blank image, then a static image (the
epilepsy-warning frame at launch; the "Reloading Lua" frame at join). With nothing redrawn, the game looks hung even
though the OS does not mark it so.

### 6. Feasibility notes (options and constraints, with evidence)

**(a) Drawing a progress bar and text during blocking phases**

- Pushing a frame mid-work from MainThread is a vanilla pattern: `GameWindow.doEpilepsyWarningText` (L1319-1334:
  `Core.StartFrame`/`EndFrame`/`StartFrameUI`, `SpriteRenderer.renderi`, `TextManager.DrawStringCentre`,
  `Core.EndFrameUI`) during `GameWindow.init`, and `Core.ResetLua` L4090-4093 (`GameWindow.render()` with
  `drawReloadingLua`). `EndFrameUI` → `RenderThread.Ready` → `pushFrameDown` hands the frame over; the render thread
  draws it and its `Display.update(true)` also pumps messages (Display L393-400).
- Prerequisites: `Core.init` + `SpriteRenderer.create` (`GameWindow.InitDisplay` L477-502) and fonts
  (`TextManager.instance.font`, first loaded by `initFonts` at `GameWindow.init` L1085, 16:00:21.1). Before that,
  there is no text. ResetLua re-inits `TextManager` (Core L4167) and `UIManager` (L4112) mid-reset.
- `pushFrameDown` blocks until the render thread has taken the previous ready frame (SpriteRenderer L656-669,
  L719-734) and the swap may wait for vsync (`Display.update(true)`), so each pushed frame costs at least one render
  frame on MainThread.
- Going through `GameWindow.render()` / `GameStateMachine.render` reaches `MainScreenState.render` → `UIManager.render`
  (Lua UI, L527) and `ActiveMods.renderUI`/`JoypadManager.renderUI`. In the middle of a reset, Lua and the UI are being
  rebuilt; `doEpilepsyWarningText` and `GameLoadingState.render` are the Java-only drawing precedents.
- Every `EndFrameUI` also runs the mods' `EndFrameUI` advice (ZB Watermark `Patch_Core.java:44-49`; Viewpoint,
  ISeeThem per the patch log).
- Frames must come from one producer: vanilla pushes frames only from MainThread (`frameStep`, `doEpilepsyWarningText`,
  `ResetLua`). `GameLoadingThread` never draws; `GameLoadingState.render` draws on MainThread.
- Possible triggers for a redraw: `DoLoadingText` (named step boundaries), `CoopMaster.update` (per mod and per Lua
  file), `LuaManager.RunLua`, `ScriptManager.LoadFile` / `Core.DoFrameReady`, `ZomboidFileSystem.loadMod`,
  `GameWindow.LoadTexturePack`, `Event.trigger` (see §3). J3.6 / launch row 7d (7 s each) have no per-item log and no
  traced per-item call.
- Hook availability: a Zabric/ZB mod's hooks are active only from its `loadMods` pass (launch row 4 onward) unless it
  is preloaded (preLaunch → registered preload → runs at entry to `main` from the next launch; §4). Launch rows 0-3 are
  out of reach without preload.

**(b) Keeping the window responsive**

- Only the JVM main (render) thread can pump. GLFW restricts event processing to the main thread (External
  references); here `glfwInit` (`MainScreenState.main` L147) and window creation (`RenderThread.init` L78) both run on
  it. MainThread cannot call `Display.processMessages`.
- Option: keep `RenderThread.waitForRenderState` false while MainThread is blocked. Vanilla does exactly this for most
  of `ResetLua` (Core L4096) and for all of startup (default false until GameWindow L539). The render thread then spins
  on `processMessages` + `Thread.yield` (one busy core). `setWaitForRenderState(boolean)` is public static
  (RenderThread L317). Today the unpumped phases are J9 (`IngameState.enter`), J5 (`GameLoadingState.enter`) and J3.9
  (post-reset Lua events).
- Option: push frames often enough (above). Each swap pumps (Display L400).
- Option: move work off MainThread. Vanilla precedent: `GameLoadingState` runs world loading and Lua on
  `GameLoadingThread`, after moving `LuaManager.thread.debugOwnerThread` to it (`GameLoadingState$1.run` L291-301).
  MainThread meanwhile draws a Java-only screen with `UIManager.suspend = true` (L431; `UIManager.update`/`render`
  check it).
  - Lua: there is one shared `KahluaThread` (`LuaManager.thread`, public static) with nothing in the code that locks
    it. Lua must not run on two threads at once, so the UI Lua must be suspended while a worker runs Lua.
    `ResetLua` swaps the whole Lua environment (`LuaManager.init`, Core L4130) and itself is called from Lua UI
    callbacks (SP, `MainScreen.lua`) and from `MainScreenState.update` (join).
  - GL: any GL work from a worker must go through `RenderThread.invokeOnRenderContext` (context ownership,
    RenderThread L422-445). Texture/model loading already does this.
  - Ordering: the reset steps run in a fixed sequence on one thread and use shared singletons (`ScriptManager`,
    `ZomboidFileSystem`, `TextManager`, `UIManager`); the code has no locks for concurrent access.
- Option: a separate process or window for progress. ZB shows its approval dialog in a separate process
  (`SwingModApprovalFrontend.java:47` `ProcessBuilder`; `frontend/ImguiApprovalMain.java` owns its own GLFW window and
  `glfwPollEvents` loop, L71-161). That leaves the game window untouched.
- Win32 detail: `IsHungAppWindow` exempts apps "in startup processing". Launch row 0 has no window.

## External References

- [GLFW Introduction, "Thread safety"](https://www.glfw.org/docs/latest/intro_guide.html#thread_safety): "Initialization,
  termination, event processing and the creation and destruction of windows … are all restricted to the main thread";
  `glfwPostEmptyEvent` may be called from any thread; `glfwMakeContextCurrent`/`glfwSwapBuffers` may be called from
  any thread. (Fetched 2026-10-17.)
- [IsHungAppWindow (winuser.h)](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-ishungappwindow):
  "An application is considered to be not responding if it is not waiting for input, is not in startup processing, and
  has not called PeekMessage within the internal timeout period of 5 seconds." (Fetched 2026-10-17.)

## Related Specs

- `.trellis/spec/mod/zabric-hooks.md`: Zabric `@Inject` hooks (the mechanism `ImeWindow.kt` uses on `Display`).
- `.trellis/spec/mod/access-widening.md`, "Entrypoints and Preload Registration": when preLaunch/main code runs.
- `.trellis/spec/mod/zombiebuddy-patching.md`: ZB loading, `PreMain`, `patches_jar`.
- `../archive/2026-10/10-17-reduce-load-time/prd.md`: baseline timings and the ResetLua-on-join analysis.

## Caveats / Not Found

- No "Not Responding" evidence exists in any log (the game does not log it). The pump analysis is static, from
  bytecode. It shows startup and most of ResetLua pumping, which conflicts with a report of OS-level "Not Responding"
  during the launch → menu load. Not checked: a long `invokeOnRenderContext` item on the render thread (it runs all
  waiting items in `flushInvokeQueue` without the 10 ms budget, L353-361), GC pauses, and mods patching
  `Display.processMessages`/`update`. A timed observation (screen recording or a render-loop gap log) would settle it.
- Phase boundaries come from the nearest log lines. J3.6, J3.7/J3.8 and launch row 7d have no inner markers; the
  split between `AdvancedAnimator.load` and `LoadDirBase` in J3.7 is approximate. ZB's
  `experimental/Patch_LuaManager.java` would time `LoadDirBase`/`RunLua`, but it is not active.
- Process start → first DebugLog line (launch row 0) is not measurable from the DebugLog. Prior research found 28 s at
  the baseline; it is not re-measured with `patches_jar`.
- What the window shows before the first swapped frame (blank/black/white) depends on GLFW/DWM; not verified.
- Single-player timings are from an older log (Oct 13, 229 mods, older mod builds) and only show the sequence.
- Asset-manager worker threads and the Translator/`ModelManager` internals were not traced.

## Summary: the 5 most important findings

1. **Rendering and the OS message pump live on the JVM main thread**, which owns the GLFW window and GL context.
   All loading runs on a separate `MainThread` (plus `GameLoadingThread` for the world). MainThread cannot pump;
   GLFW allows event processing only on the main thread.
2. **Whether the window pumps depends on `RenderThread.waitForRenderState`.** False: the render loop calls
   `glfwPollEvents` constantly but draws nothing new. True: it pumps only when MainThread pushes a frame. The flag is
   false for all of startup and for `ResetLua` up to Core L4180, and true otherwise.
3. **Where the window freezes.** By that rule, the window that Windows can mark "Not Responding" (5 s without
   `PeekMessage`) during the join is `IngameState.enter`: 8.1 s, 8-9 s in three logs (`OnGameStart`/`OnLoad` Lua plus
   the MP player-connect wait). Shorter unpumped stretches are `GameLoadingState.enter` (~2.2 s) and the post-reset
   `OnGameBoot`/`OnMainMenuEnter` (~2.6 s). The long phases (~44 s of launch; ~23 s of the join reset) pump but show a
   static image: blank, then the epilepsy-warning frame at launch; the single "Reloading Lua" frame at join. The
   reported startup "Not Responding" is not explained by the code paths read (see Caveats).
4. **Vanilla already has the building blocks.**
   - `GameWindow.DoLoadingText` names each step, but only logs it.
   - The progress sources are `LuaManager.loadList`/`loaded`, `CoopMaster.update` per mod and per Lua file, and
     `ScriptManager.LoadFile` → `Core.DoFrameReady` per script.
   - `doEpilepsyWarningText` and `ResetLua`'s `drawReloadingLua` frame show that MainThread can push a Java-only frame
     mid-load (`StartFrame` … `EndFrameUI` → `RenderThread.Ready`).
   - `GameLoadingState` shows the threaded pattern (a worker owns the Kahlua thread, `UIManager.suspend`, and
     MainThread draws text plus an animated figure; no percentage).
5. **Newest timings** (16:00 log): launch → menu 50.3 s and join click → in world 78.9 s (70.8 s to GameLoadingState
   exit).
   - Menu: ZB `Loader.loadMods` 6.6 s (Viewpoint 1.5 s, ISeeThem 2.2 s), vanilla `loadMods` 2.6 s, mod discovery
     4.8 s, Lua 6.6 s, an unlogged `initShared` tail 6.8 s, models 4.0 s.
   - Join: `ResetLua` ~25.8 s (ZB only 0.26 s of it; an unlogged models/animations block 7.2 s).
   - World: build 15 s, asset wait 16.9 s.
   - Zabric/ZB hooks start only at the first `loadMods` pass (26 s after the log starts) unless the mod is preloaded
     through a preLaunch entrypoint.
