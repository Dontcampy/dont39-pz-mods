# Research: MainThread frame gaps over 1 s outside ZombieBuddy's Java-mod pass (after D11 A + B)

- **Query**: For the remaining >1 s gaps of the 246-mod launch to the menu (HEAD e2bc6e4): translations, tiles and
  furniture, scripts tail, game boot, discovery: what MainThread does there, whether a per-item loop has a callee whose
  glue would be JDK-only (D9), how often it fires, whether it is woven in time (D10), whether an own frame is safe
  there, or whether the gap is one native / uninterruptible call.
- **Scope**: internal (javap of `E:/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid.jar`, 42.21; saved
  logs; workshop mod files; the mod's built glue). The game was not launched.
- **Date**: 2026-10-19

Evidence (session scratchpad `.../f252526d-.../scratchpad/`):

- "G" = `debuglog-menu-d11ab-gaps.txt` (instrumented: `[LPGAP] <ms> at <step> ...` for every gap > 300 ms, printed
  when the gap *ends*; the step named is `Progress.current()` at that moment, `Progress.gap`, Progress.java:511-516).
  Same lines in `console-menu-d11ab-gaps.txt`.
- "CL" = `classload-menu1.txt` (`-Xlog:class+load`, JVM uptime, earlier 17:38 run, same game and mod list order).
  First-load times of handler/lambda classes mark when a call site first ran.
- javap dumps written to `scratchpad/gaps/*.txt` (`Lnnn` = LineNumberTable line).

## Summary table

| Gap (G) | What MainThread does | Hook can split it? | Candidate trigger (HEAD) | Fire rate | Woven in time | Expected gap after |
|---|---|---|---|---|---|---|
| Translations 2198 ms (07.323 → 09.521) | `GameWindow.initFonts` wait loop for the CN fonts (67 MB of pages) after `Languages.init` | yes | `zombie.ui.TextManager.isAnyFontLoading()Z` | ~1 per 10-11 ms loop pass (~200 calls) | yes | ≤ ~0.1 s in the loop; `TextManager.Init`'s synchronous part (not measured) stays |
| Scripts 1692 ms (12.399 → 14.091) | `ScriptManager.loadScripts` (per-bucket `LoadScripts` → per script `Load` → `ScriptParser.parse`), then post-load | yes | `zombie.scripting.ScriptBucket.getCurrentScriptObject()Ljava/lang/String;` (or `BaseScriptObject.calculateScriptVersion()V`) | ≥ 1 per script object (thousands in ~1.5 s) | yes | ~0.1-0.2 s (tail after `LoadScripts`) |
| Tiles 2025 ms (14.522 → 16.547) | `TileGeometryManager.initGameData` → vanilla `tileGeometry.txt` → `ScriptParser.stripComments` | **no** (JDK-only inner loop) | none splits it | n/a | n/a | unchanged, ~2.0 s |
| Tiles 2103 ms (18.816 → 20.919) | same, Nekomata Ridge's `tileGeometry.txt` | **no** | none | n/a | n/a | unchanged, ~2.1 s |
| (Tiles 845 / 650 ms) | CommunityTilePack / LiuDing files, same cause | no | none | n/a | n/a | unchanged (< 1 s) |
| Discovery 1863 ms (52.577 → 54.440) | `ZomboidFileSystem.getInstalledItemModsFolders` → native `SteamWorkshop.n_GetInstalledItemFolders` (214 items) + 214 `File.exists` | **no** (native + JDK-only loop) | none splits it; bracketing only | n/a | n/a | unchanged, ~1.8 s |
| Game boot 1475 ms (34.350 → 35.825) | one Lua `OnGameBoot` callback of Map Mod Manager (`getModInfo` over all ~285 mod folders, 4 loops, + map-folder scans) | yes, mod-specific or via a hot Kahlua hook | `ChooseGameInfo.readModInfo(Ljava/lang/String;)…` (mod-specific pattern); generic: `se.krka.kahlua.vm.Coroutine.popCallFrame()V` | ~1,140 calls / 1.47 s; generic: every Lua call | yes | ≤ ~0.1-0.2 s for this mod |
| (Game boot 684 ms) | aparosa_vpmo_RaccoonCityB42's `OnGameBoot` (~104k `TileGeometryManager.copyGeometry` calls) | yes | `TileGeometryManager.copyGeometry(…)V` or the generic one | ~160/ms | yes | < 0.1 s (already < 1 s) |

D9 note (verified on the built glue): a HEAD handler's glue names only the target's *arguments*:
`Dont39LoadingProgressGlue$ChooseGameInfo_getAvailableModDetails.enter(Ljava/lang/String;Z)V` for a target returning
`ChooseGameInfo$Mod`. So HEAD triggers on methods with a game-typed return pass `checkJdkGlue`; RETURN/FINALLY on them
would not. Game-typed *parameters* are excluded in all cases. Private targets are accepted (the existing
`getAllModFoldersAux` hook); abstract/native targets are rejected by the processor (HandlerBinder.java:166-168). The
prefix-overload rule is `PatchBuilder.checkMatcher` / `LoaderMatcher.selects`.

## Findings

### 1. Translations, 2198 ms ending 09.521 "at TRANSLATIONS"

**Call chain** (`GameWindow.init`, javap): `DoLoadingText("Loading Translations")` (L1129; `DoLoadingText` only logs,
L1315, it draws no frame) → `Languages.init` (L1131) → `initFonts` (L1135) → `Translator.loadFiles` (L1137, boundary
TRANSLATIONS) → `doEpilepsyWarningText` (L1139) → `LuaManager.init` (L1141) → `initShared`.

`initFonts` (L1154-1164): `TextManager.instance.Init()`, then
`while (TextManager.instance.isAnyFontLoading()) { GameWindow.fileSystem.updateAsyncTransactions(); Thread.sleep(10); }`.

**What the gap is**: the gap is labelled TRANSLATIONS, so the frame that ended it came after `loadFiles` HEAD (the
reach-tick there). `Languages.init` loops per mod with `getAvailableModDetails` (woven, ticks), so the tick-less
stretch is `initFonts`:

- CL (17:38 run): `Languages$$Lambda` / `LanguageFile` at 18.300-18.301 s, `ZomboidFileSystem$$Lambda` 18.336, then
  **no class load until** `LoadHooks$Translations` (first run of the `loadFiles` HEAD handler) at 20.466 s and
  `Translator$$Lambda`s at 20.468-20.470 (loadFiles's own lambdas) → 2.13 s between `Languages.init` and `loadFiles`
  HEAD; `loadFiles` itself 20.466 → ~21.19 s (0.72 s, per-mod ticked), then Kahlua classes at 21.201 (`LuaManager.init`).
- Game language is CN (`~/Zomboid/options.ini` `language=CN`). The first `initFonts` call (before `Languages.init`)
  took ~0.22 s (G 51.751 → 51.994); `media/fonts/CN` is 67 MB vs `EN` 4.2 MB. `TextManager.Init` destroys the fonts
  and creates `AngelCodeFont`s whose page textures load asynchronously; the loop polls them.

So `Translator.loadFiles` is not the gap (it ticks per mod: `tryFillMapFromMods` calls `getAvailableModDetails` per
mod, per category, per language, Translator L379-390). The 976 ms gap ending 11.279 also labelled TRANSLATIONS is
`LuaManager.init` with ZombieBuddy's `Exposer.exposeAll` new-load weaving (G 10.367-11.237 `patching ...` lines,
11.367 `after Exposer.exposeAll`), under 1 s.

**Candidate**

| Item | Value |
|---|---|
| Target | `zombie.ui.TextManager.isAnyFontLoading()Z`, public instance, no overloads |
| Glue | `()` no arguments, boolean return not bound at HEAD → JDK-only |
| Fire rate | once per loop pass (10 ms sleep + `updateAsyncTransactions`) ≈ 90-100/s, ~200 in 2.2 s. Other callers (javap/grep of the jar): `GameWindow.initFonts`, `TextManager` itself, `ServerGUI` (server); plus the mod's own `Overlay` inside frames |
| Woven in time | `TextManager` first loads at CL 2.094 s in ordinary code (Core init, `Core$$Lambda` after it), not inside a transformation → woven at its new load; at the latest Zabric's first-pass retransform (G 04.1) is before this step (07.3) |
| Safety | MainThread between loop passes; `Core.frameStage == 0` (EndFrameUI sets 0 at its end, offset 318; StartFrameUI sets 3, offset 144); no monitor held (`initFonts` and `init` are not synchronized); not in a transformer. `warningShown` is still false (doEpilepsyWarningText comes at L1139), so FramePump draws its plain frame; `Overlay` draws bar only while `isAnyFontLoading` (fonts loading), as designed. Font page uploads run on the render thread in parallel, as in `ResetLua`'s non-waiting `TextManager.Init` (Core.ResetLua offset 318) with frames continuing. The Overlay's own `isAnyFontLoading` call re-enters the hook inside a frame: `FramePump.tick` returns on `pushing` (own frames) or `frameStage != 0` (vanilla frames, stage 3 at EndFrameUI HEAD) |
| Alternative | `zombie.fileSystem.FileSystemImpl.updateAsyncTransactions()V` (the abstract `FileSystem` one has no body); also called per game frame and by `ModelManager`, `GameServer`, `TilesetImageCreator`, `ImagePyramid` |
| Expected after | ≤ ~0.1 s inside the loop. Not covered: the synchronous part of `TextManager.Init` (`FontsFile.read`, `AngelCodeFont` constructors / `parseFnt` of the CN `.fnt` files), not measured |

### 2. Scripts, 1692 ms ending 14.091 "at SCRIPTS item SCRIPT 3137/3137"

**Call chain** (`ScriptManager.Load`): per-file loop `LoadFile(...)` + `Checksummer.addFile` (L1523, ticked by
`DoFrameReady` and `addFile`) ends at ~12.40 (G 11.500 `载入脚本中` → 3137 files). Then `loadScripts(mode, allOf)`
(L1544): per `ScriptBucketCollection` `PreReloadScripts`, `LoadScripts(mode)`, `PostLoadScripts`, `OnScriptsLoaded`,
`OnLoadedAfterLua`, `OnPostTileDefinitions`, `OnPostWorldDictionaryInit`; then `resolveItemTypes`,
`WorldDictionary.ScriptsLoaded`, `RecipeManager.ScriptsLoaded`, `GameSounds.ScriptsLoaded`, `ModelScript.ScriptsLoaded`,
`createClothingItemMap`, `createZedDmgMap`. Next boundary: `SpriteModelManager.init` HEAD (tick), then G 14.196 `载入服装`.

`ScriptBucket.LoadScripts` (per script): `BaseScriptObject.reset`, `Load(String,String)` (L259), …,
`calculateScriptVersion()` (L288), `onScriptLoad`. `Load` parses the body with `ScriptParser.parse` → `readBlock`, which
calls `ScriptBucket.getCurrentScriptObject()` at every block opening (ScriptParser L215, offsets 98/106).

Log inside the gap: G 12.760 `FluidContainerScript.load`, 13.633-13.841 vehicle `template ... not found` (emitted by
`VehicleScript.LoadTemplate(String)`, called from `VehicleScript.Load`, i.e. the `LoadScripts` phase), 13.956
`XuiScript`, 13.957-13.964 `XuiSkin`, 14.025 `TaggedObjectManager`. So at least 12.40 → 13.96 is `LoadScripts`-phase
work, which runs per script object.

**Candidates**

| Target | Descriptor / notes | Fire rate | Woven in time |
|---|---|---|---|
| `zombie.scripting.ScriptBucket.getCurrentScriptObject()` | `public static final`, `()Ljava/lang/String;`, one method of that name; JDK-only. Callers in the jar: `ScriptBucket`, `ScriptParser` only | every `{` in any `ScriptParser.parse`: ≥ 1 per script object here; also per block in tile/seam/seating/font files | `ScriptBucket` first loads at CL 4.778 s via `FontsFile` parsing in the first `initFonts`, ordinary code after Translator's new-load transform → woven at load |
| `zombie.scripting.objects.BaseScriptObject.calculateScriptVersion()V` | public instance, not overridden (only `BaseScriptObject`/`ScriptBucket` name it) | once per loaded script in `LoadScripts` | `BaseScriptObject` first loads at CL 8.999 s **inside** ZombieBuddy's retransformation of `UITextBox2` (between 8.983 glue redefine and 9.003 `UITextBox2` redefine) → per D10 not woven at load; Zabric's first-pass retransform (before scripts) would weave it. Riskier than `ScriptBucket` |

**Safety**: MainThread in `initShared` (launch) or `Core.ResetLua` (start/reload, same as today's `DoFrameReady`
ticks in `ScriptManager.Load`); `frameStage == 0`; no frame code touches scripts; not in a transformer. The own-frame
path (`StartFrame`/`EndFrame`/`StartFrameUI`/`EndFrameUI`) calls `UIManager.resize`, `CreateFBO`,
`renderFadeOverlay`, no Lua (javap of those call sites).

**Expected after**: the `LoadScripts` phase split at ≤ ~50-100 ms; remaining tail (post-load phases) ≈ 13.96 → 14.09
in this run (~0.13 s) plus whatever post phases precede 13.96 (not separable from logs).

### 3. Tiles and furniture: 2025 ms (→16.547), 845 ms (→17.393), 650 ms (→18.043), 2103 ms (→20.919)

**Call chain** (`initShared` after clothing): `RandStandard.init`, `RandLua.init`, `Mouse.initCustomCursor`,
`TileGeometryManager.init` (boundary TILES), `TileDepthTextureAssignmentManager.init`, `SeamManager.init`,
`SeatingManager.init`, states. CL: `LaunchHooks$Tiles` 25.177 s, `TileGeometryFile` 25.178, next
`TileDepthTextureAssignments` 31.689, `SeamFile` 31.784, `SeatingFile` 31.790, `TISLogoState` 31.803 → ~6.5 s is
`TileGeometryManager.init`.

`TileGeometryManager.init` (L44-57): `initGameData()` (vanilla `media/tileGeometry.txt`) then per mod id
`getAvailableModDetails(id)` (tick), and if `<mod>/common/media/tileGeometry.txt` exists `initModData(mod)`.
Both → `TileGeometry.init` → `TileGeometryFile.read(String)` → `parseFile(String)` (L608):
`ScriptParser.stripComments(text)` → `ScriptParser.parse(text)` (`readBlock` recursion) → walk (`parseTileset`,
`parseTile`, `parseBox` → `parseVector3` → `parseCoord(String)` → `PZMath.tryParseFloat/Int`).

`stripComments` (ScriptParser L250-270): copies the file into a static `StringBuilder`, then repeatedly
`lastIndexOf("*/")`, `lastIndexOf("/*")` and `replace(start, end + 2, "")` from the end. Each `replace` shifts the tail
(`System.arraycopy`), so the cost is ≈ comments × size / 2 bytes moved. Its only callees are `StringBuilder` methods.

**Model check** (files of the active mods, load order from G `loading <id>` lines; k fitted on vanilla = 34 GB/s):

| File (order) | Size | `/*` comments | Predicted stripComments | Observed |
|---|---|---|---|---|
| vanilla (initGameData) | 6.38 MB | 21,523 | 2.0 s (fit) | gap 1: 2025 ms |
| LGExtendedElectricity (59) … UnofficialMappersCommunityTilePack (173) | 0.67 MB … 5.29 MB | 3,478 … 8,034 | 0.02 + 0.62 s | gap 2: 845 ms (starts at gap 1's end) |
| LiuDing_Tiles (177) | 4.57 MB | 8,714 | 0.58 s | gap 3: 650 ms (starts at gap 2's end) |
| Tryhonesty (180), melos (181), Greenleaf (186), Nettle (193) | 1.4-3.3 MB | 3,226-5,585 | 0.07 + 0.17 + 0.24 + 0.27 = 0.75 s, each < 0.3 s | 18.043 → 18.816 = 0.77 s, no gap > 300 ms |
| Nekomata Ridge (202) | 6.81 MB | 21,711 | 2.17 s | gap 4: 2103 ms |
| aparosa_vpmo_RaccoonCityB42 (221) | **16.5 MB** | **0** | ~0 | 20.919 → LUA (~21.26) incl. TDTAM/Seams/Seating: no gap > 300 ms |

The largest file (16.5 MB, no comments) parses and walks in < 0.3 s, so `parse` + walk are minor; the > 1 s gaps
are each **one `ScriptParser.stripComments(String)` call** whose inner loop runs only in JDK code
(`StringBuilder.lastIndexOf/replace` → `System.arraycopy`). No game method runs inside it, so no Zabric/ZombieBuddy
hook can tick there. The vanilla file alone gives ~2.0 s on this machine for every mod list, and `Core.ResetLua`
re-runs `TileGeometryManager.init` (offset 288), so the start and reload flows' "clothing and tiles" step has the
same gap.

**Hookable points (do not split the > 1 s part)**

| Target | Descriptor | Splits |
|---|---|---|
| `ScriptParser.stripComments(Ljava/lang/String;)Ljava/lang/String;` | public static, JDK-only, single | brackets the uninterruptible call (HEAD/RETURN), e.g. as a "busy in a known call" flag |
| `ScriptBucket.getCurrentScriptObject()` (§2) | per block in `readBlock` | the parse part only |
| `TileGeometryFile.parseCoord(Ljava/lang/String;)F` | package-private; the other overload `(LScriptParser$Block;Ljava/lang/String;F)F` is not selected (param 0 not a String) | the walk only |

**Safety** (for the parse/walk ticks): MainThread in `initShared` / `ResetLua`, `frameStage == 0`, no lock, no
transformer; FramePump checks the game thread (ScriptParser can run elsewhere).

**Expected after**: vanilla ≈ 2.0 s and Nekomata ≈ 2.1 s unchanged with any hook; would need render-thread interim
frames (D11 C mechanism) gated by a flag set at `stripComments` HEAD and cleared at RETURN/FINALLY, or acceptance as
a known gap.

### 4. Discovery, 1863 ms ending 54.440 "at MODS item NONE"

**Call chain**: `GameWindow.init` `DoLoadingText("Loading Mods")` (G 52.207) → `resetDefaultModsForNewRelease("42_00")`
→ `loadMods("default")` (L1111; HEAD tick) → `readDefaultModsTxt` → `ActiveMods.checkMissingMods` →
`ChooseGameInfo.getAvailableModDetails` → `getModDetails` → `ZomboidFileSystem.getModDir` → `getAllModFolders(List)`
(ChooseGameInfo L130). `getAllModFolders` (synchronized; L638-667): `modFolders` null after
`resetModFolders` (init offset 36) → `setModFoldersOrder("workshop,steam,mods")` → `getStagedItemModsFolders`
(`SteamWorkshop.getStageFolders`, 6 dirs) → `getInstalledItemModsFolders` (L651) → cache `mods` dir → per root
`DebugFileWatcher.addDirectory` + `getAllModFoldersAux` (L667, ticked since D11 A).

`getInstalledItemModsFolders` (L548-559): `SteamUtils.isSteamModeEnabled()`,
`SteamWorkshop.instance.GetInstalledItemFolders()` (L171-174 → `private native String[] n_GetInstalledItemFolders()`),
then per folder `new File(folder, "mods").exists()` / `getAbsolutePath` / `List.add`. 214 subscribed items
(`steamapps/workshop/content/108600`).

**Evidence that the gap is this one call**: CL: `LoadHooks$Mods` (first `loadMods` HEAD handler) 5.217 s,
`SteamWorkshop$$Lambda` (the `getStageFolders` directory filter) 5.280 s → the prelude through
`getStagedItemModsFolders` takes ~63 ms. After it, until the first `getAllModFoldersAux` HEAD (G 54.440), MainThread
runs only `getInstalledItemModsFolders` (native call, then a loop of JDK `File` calls), `File.exists`/`mkdirs` of the
cache `mods` dir and one `DebugFileWatcher.addDirectory` → `registerDir`. `n_GetInstalledItemFolders` is native, so the
processor would reject it (no body); the loop after it calls no game method. The split between the native call and
the 214 `File.exists` is not measured (warm-cache `exists` ≈ tens of µs each, so the native call is the likely ~1.8 s;
on a cold HDD the `exists` loop could also take seconds, equally uninterruptible).

**Hookable brackets** (no splitting): `ZomboidFileSystem.getInstalledItemModsFolders(Ljava/util/List;)V` (ZFS woven at
premain), `SteamWorkshop.GetInstalledItemFolders()[Ljava/lang/String;` (public, has a body, JDK-only incl. the
return; `SteamWorkshop` first loads at CL 5.172 s in `SteamWorkshop.init`, ordinary code → woven at new load). Useful
to confirm the split with a probe, or to gate interim frames.

**Expected after**: unchanged ~1.8 s with hooks.

### 5. Game boot: 684 ms ending 34.350, 1475 ms ending 35.825 "at GAME_BOOT item EVENT OnGameBoot"

G 33.666 `游戏启动中` (`UI_Loading_OnGameBoot`), then `triggerEvent("OnGameBoot")`: each callback is one
`KahluaThread.pcall(int)` (ticked at HEAD). Two callbacks run long without any pcall/RunLua/checkEvent inside:

- **684 ms**: `aparosa_vpmo_RaccoonCityB42.lua` L3678-3697 (workshop 3816419379): for 27 maps, `copy(TG, …)` calls
  `TileGeometryManager:getGeometry(String,String,int,int)` and `copyGeometry("game", …)` per tile (G: "27 maps,
  654 ms"; ~90k + ~13k = ~104k copies, melos 239 ms, tikitown 159 ms).
- **1475 ms**: `Map Mod Manager` (workshop 3360392628, `42.15/media/lua/client/MapModManager_UI.lua`,
  `Events.OnGameBoot.Add(MapModManagerLabel.initializeMergedData)` L3389): `buildDynamicMapEntries` and
  `buildDynamicDependencyEntries` each loop twice over `getModDirectoryTable()` (L82-98, L177-195) calling
  `getModInfo(directory)`; plus `scanMapFoldersForMod` (`getMapFoldersForMod`, `listFilesInModDirectory`, Lua loop
  per lotpack file). Prints "Map mods found locally (not in the database)" at G 35.816 (L268).
  `GlobalObject.getModInfo(String)` → `ChooseGameInfo.readModInfo(String)` (L157-169) → `readModInfoAux` opens and
  parses the folder's `mod.info` on every call (`IndieFileLoader.getStreamReader`, L199). `getModDirectoryTable` →
  `GlobalObject.getMods()` → `getAllModFolders`: ~285 mod folders here (277 workshop + 6 staged + 2 local) → ~1,140
  `readModInfo` calls.

Lua → Java calls go through Kahlua (`KahluaThread.callJava(JavaFunction,int,int,int)` → `LuaJavaInvoker.call(LuaCallFrame,int)`
→ reflection): game-typed parameters, so not usable under D9. Per call, `callJava` also runs
`LuaCallFrame.getTop()I`, `stackCopy(III)V`, `setTop(I)V` and `Coroutine.popCallFrame()V`, all JDK-only, but those
also run on every Lua→Lua call.

**Candidates**

| Target | Descriptor | Fire rate | Woven in time | Scope |
|---|---|---|---|---|
| `zombie.gameStates.ChooseGameInfo.readModInfo(Ljava/lang/String;)` | public static, single; returns `ChooseGameInfo$Mod` (not bound at HEAD) | ~1,140 in 1.47 s for Map Mod Manager; also per folder in discovery's `getModDetails` loop | `ChooseGameInfo` retransformed by Zabric's first pass (G 04.082), before game boot | the "getModInfo over every mod folder" pattern (any mod) |
| `zombie.Lua.LuaManager$GlobalObject.getModInfo(Ljava/lang/String;)`, `listFilesInModDirectory(Ljava/lang/String;Ljava/lang/String;)`, `getMapFoldersForMod(Ljava/lang/String;)` | public static, JDK-only args | per Lua call | `GlobalObject` retransformed G 04.036 | Lua API calls only |
| `zombie.tileDepth.TileGeometryManager.copyGeometry(Ljava/lang/String;Ljava/lang/String;IILjava/util/ArrayList;)V` | public, single | ~104k in 654 ms (aparosa) | `TileGeometryManager` woven at new load (G 10.731) | callers: Lua, `TileGeometry`, `UI3DScene` (editor); not per frame |
| `se.krka.kahlua.vm.Coroutine.popCallFrame()V` | public, single, JDK-only | every Lua function return (Lua and Java functions), ~10^5-10^6/s under Lua load, also in game | `Coroutine` first loads at CL 1.032 s during ZB's premain retransform of `KahluaThread` (D10: not woven at load); Zabric's first-pass retransform would weave it | generic: any long Lua callback that calls functions |

**Safety**: MainThread inside a `pcall` from `LuaEventManager.triggerEvent`, outside any frame
(`GameWindow.init`/`enter` call path, `frameStage == 0`); the own frame runs no Lua (see §2), so ticking from a Java
function Lua calls is as safe as today's `pcall` HEAD tick. For `popCallFrame` the coroutine's stacks are mid-update
during the frame; still no Lua runs in it. Cost: each woven call runs the glue (`ModHooks.active(defaultGate())`, a
`CallbackInfo` allocation, the handler's volatile read) also in game; on a per-Lua-call target this is a measurable
share of Lua time (not measured; AC7's ≤ 5 % applies).

**Expected after**: with `readModInfo` (+ the GlobalObject map-folder calls): Map Mod Manager's 1.47 s split into
≤ ~0.1-0.2 s pieces (the Lua lotpack loops between calls remain). Other mods' heavy `OnGameBoot` callbacks are only
covered by a generic Kahlua hook.

## Recommendation (for the main agent to weigh)

1. Add HEAD triggers (Loading.tick) on `TextManager.isAnyFontLoading()Z` (translations) and
   `ScriptBucket.getCurrentScriptObject()Ljava/lang/String;` (scripts tail; also splits the parse part of tile and
   other script-format files). Both JDK-only, single methods, woven at new load, safe points. Expected: the 2.2 s and
   1.7 s gaps fall to ~0.1-0.2 s.
2. Tiles (~2.0 s vanilla, ~2.1 s Nekomata; also in `ResetLua`) and discovery (~1.8 s) cannot be split by any hook:
   each is one call with only JDK or native code inside (`ScriptParser.stripComments`,
   `SteamWorkshop.n_GetInstalledItemFolders` + the `File.exists` loop). Choices: name them as known gaps (like D8), or
   extend D11 C's render-thread interim frames beyond the Java-mod step, gated by a flag set/cleared by HEAD and
   FINALLY hooks on `ScriptParser.stripComments` and `SteamWorkshop.GetInstalledItemFolders` (or
   `getInstalledItemModsFolders`); there is no heartbeat inside these calls, so such frames would move on time, not
   work (against R8's "a real hang still shows a still bar" unless the flag's scope is accepted as the guard). User
   decision.
3. Game boot gaps come from other mods' Lua. A `ChooseGameInfo.readModInfo` HEAD trigger covers this list's 1.47 s
   case cheaply; a generic `Coroutine.popCallFrame()V` trigger covers any Lua-heavy callback but sits on the hottest Lua
   path, so measure its cost first (AC7). Otherwise classify long single Lua callbacks of other mods with D8.

## Files Found

| File | Relevance |
|---|---|
| `loading-progress/src/main/java/com/dont39/pz/loadingprogress/hooks/LoadHooks.java`, `TriggerHooks.java`, `CoreHooks.java`, `LaunchHooks.java` | current boundaries and triggers |
| `loading-progress/src/main/java/com/dont39/pz/loadingprogress/draw/FramePump.java` | own-frame guards (thread, `frameStage`, `pushing`, 50 ms) |
| `loading-progress/src/main/java/com/dont39/pz/loadingprogress/progress/Progress.java:511-516` | gap label = step at gap end |
| `loading-progress/build/classes/java/main/com/dont39/pz/loadingprogress/Dont39LoadingProgressGlue$ChooseGameInfo_getAvailableModDetails.class` | HEAD glue names arguments only |
| `zabric-processor/src/main/java/com/pzlauncher/zabric/processor/glue/HandlerBinder.java:131-190`, `PatchBuilder.java:164-195`, `LoaderMatcher.java` | overload / body / matcher rules |
| `buildSrc/src/main/kotlin/check/CheckJdkGlue.kt` | glue type check |
| `E:/SteamLibrary/steamapps/common/ProjectZomboid/media/tileGeometry.txt` (6.38 MB, 21,523 comments), `media/fonts/CN` (67 MB) | sizes behind §1 and §3 |
| workshop `3816419379/.../aparosa_vpmo_RaccoonCityB42.lua`, `3360392628/.../MapModManager_UI.lua` | game-boot callbacks |
| javap (game jar): `GameWindow`, `Translator`, `Languages`, `TextManager`, `ScriptManager`, `ScriptBucket`, `ScriptParser`, `BaseScriptObject`, `VehicleScript`, `TileGeometryManager`, `TileGeometry`, `TileGeometryFile`, `ZomboidFileSystem`, `SteamWorkshop`, `ChooseGameInfo`, `ActiveMods`, `LuaManager$GlobalObject`, `KahluaThread`, `Coroutine`, `Core`, `UIManager`, `IndieFileLoader`, `DebugFileWatcher` | chains above; dumps in `scratchpad/gaps/` |

## Related Specs

- `.trellis/spec/mod/zabric-hooks.md` — hook runtime and glue contract.
- Task `prd.md` D8-D11, R8, AC3; `design.md` "Drawing", "Discovery and the Java-mod pass"; `research/zb-pass-gap.md`.

## Caveats / Not Found

- No profiler run: the causes are inferred from javap call chains, gap labels, CL first-load times of an earlier run
  (same game, same mod order) and, for tiles, a size × comments model that matches all four gaps and the gap-free
  stretches. The CL run predates D11, so its absolute times differ from G.
- §1: the share of `TextManager.Init`'s synchronous font setup in the 2.2 s is unknown; only the wait loop is covered.
- §2: the post-`LoadScripts` phases (`OnScriptsLoaded`, `*.ScriptsLoaded`, `createClothingItemMap`, ...) were not
  inspected for per-item JDK-only callees; the log puts them in the last ~0.13 s here.
- §3: `stripComments`'s cost scales with the machine's memmove speed; other mod lists with large commented
  `tileGeometry.txt` files add more such gaps.
- §4: native vs `File.exists` split not measured; a HEAD/RETURN probe on `GetInstalledItemFolders` would settle it.
- §5: the split of Map Mod Manager's 1.47 s between `getModInfo` loops and Lua lotpack loops is not measured; the
  glue overhead of a `popCallFrame` trigger is not measured.
- "Woven in time" for `ScriptBucket`/`TextManager` is from CL load context, not from a `Transformed:` line (they have
  no hook yet); check `[ZB] Transformed: zombie.scripting.ScriptBucket (new load)` after adding one.
