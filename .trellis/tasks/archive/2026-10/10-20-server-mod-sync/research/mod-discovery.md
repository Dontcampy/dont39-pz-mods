# Research: how the client finds a mod by id, and which copy wins

- **Query**: How Build 42 (client) finds a mod by id; precedence when the same id exists in several folders; how the
  join applies the server's mod list and when id → folder is resolved; which Workshop folders are scanned; hook
  candidates to redirect ids to a per-server cache for one session; whether the Lua checksum would pass.
- **Scope**: internal (game bytecode + vanilla Lua; nothing was run)
- **Date**: 2026-10-20

Sources: `E:/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid.jar`, disassembled with
`javap -c -p -l` (`-v` for string-concat recipes). Citations are `Class.method (File.java:line)` from the
LineNumberTables. Lua citations are relative to `ProjectZomboid/media/lua/`.

## Files Found

| File | Description |
|---|---|
| `zombie/ZomboidFileSystem` | Mod folder list, id → dir map, file overlay (`activeFileMap`), `loadMods` |
| `zombie/gameStates/ChooseGameInfo` | id → `Mod` cache (`Mods`), `getModDetails`, `readModInfo` |
| `zombie/gameStates/ChooseGameInfo$Mod` | Per-folder mod: `dir`, `commonDir`, `versionDir`, `workshopId`, `source` |
| `zombie/gameStates/ConnectToServerState` | Join states; `WorkshopUpdate`, `CheckMods`, `Finish` → `receiveServerOptions` |
| `zombie/core/Core` | `ResetLua(activeMods, reason)`: full reset and reload of mods + Lua |
| `zombie/gameStates/IngameState` | `exit()`: reload of the `default` mods after leaving a game |
| `zombie/Lua/LuaManager` | `LoadDirBase`: Lua file list + Lua checksum |
| `zombie/network/NetChecksum`, `packets/service/ChecksumPacket` | Checksum hashing and the server-side compare |
| `zombie/network/ConnectionDetails` | Server side: writes the `(id, workshopId, name)` mod list the client reads in `CheckMods` |
| `zabric/.../widener/ModFolders.java` | Zabric's own copy of the game's mod roots (premain, no game API) |
| `zabric/.../entrypoint/Preload.java` | Uses `getAvailableModDetails(id).getVersionDir()` as "the copy the game uses this session" |

## 1. Where mod folders are discovered

### The folder list: `ZomboidFileSystem.getAllModFolders(List)` (ZomboidFileSystem.java:638-674)

- Cached in the field `modFolders`; rebuilt only when it is null (line 639). `resetModFolders()` sets it to null and
  resets `allowedPrefixes` (ZomboidFileSystem.java:543-545).
- Source order comes from `modFoldersOrder`, default `"workshop,steam,mods"` (line 642). Only the command-line
  argument `-modfolders <list>` changes it (`MainScreenState.main` (MainScreenState.java:281-283) →
  `setModFoldersOrder`, which splits on `,` (ZomboidFileSystem.java:634)). Tokens other than the three below are
  ignored (the loop at 645-658 only matches these three strings).

| Token | Method | Folders added (each is a "folder holding mod folders") |
|---|---|---|
| `workshop` | `getStagedItemModsFolders` (ZomboidFileSystem.java:562-571) | `<stage>/Contents/mods` for each `SteamWorkshop.getStageFolders()` entry: the user's own items under `Zomboid/Workshop/<item>` (SteamWorkshop.java:96-115) |
| `steam` | `getInstalledItemModsFolders` (ZomboidFileSystem.java:548-559) | `<itemFolder>/mods` for every folder `SteamWorkshop.GetInstalledItemFolders()` returns, if it exists; only when `SteamUtils.isSteamModeEnabled()` |
| `mods` | inline (ZomboidFileSystem.java:653-658) | `getCacheDirSub("mods")` = `Zomboid/mods` (created if missing) |

- Then each of those folders is expanded by `getAllModFoldersAux(folder, out)` (ZomboidFileSystem.java:592-631):
  - lists the direct child directories (`Files.newDirectoryStream`, line 598);
  - skips a folder named `examplemod` (599-601);
  - keeps a child only if `<child>/common/mod.info` or `<child>/<versionDir>/mod.info` exists (604-608);
  - adds the child's absolute path to the result (626) and registers the folder and its `common/media` and
    `<versionDir>/media` with `DebugFileWatcher` (611-623).
- Not scanned: the game install's own `ProjectZomboid/mods` folder (it holds only `examplemod` and `loaded.txt` in this
  install). Zabric's `ModFolders.roots()` lists the same three kinds of roots (client Workshop content, server
  Workshop content, `<cache>/mods`, `<cache>/Workshop/*/Contents/mods`).

### The id → folder resolution

Two caches, both keyed by mod id:

| Cache | Type | Written by | Cleared by |
|---|---|---|---|
| `ChooseGameInfo.Mods` | `HashMap<String, Mod>` | `readModInfo` (ChooseGameInfo.java:157-175) | `ChooseGameInfo.Reset()` (ChooseGameInfo.java:48-51) |
| `ZomboidFileSystem.modIdToDir` | `HashMap<String,String>` | `setModIdToDir` = `putIfAbsent` (ZomboidFileSystem.java:715) | `ZomboidFileSystem.Reset()` (line 498), `ZomboidFileSystem.update()` (line 1525) |

Plus `ZomboidFileSystem.modDirToMod` (dir → `Mod`, `getModInfoForDir`, ZomboidFileSystem.java:991-996), cleared in the
same two places (lines 499, 1526), and `ChooseGameInfo.MissingMods` (negative cache, cleared by
`ChooseGameInfo.Reset`).

`ChooseGameInfo.getModDetails(id)` (ChooseGameInfo.java:121-145):

1. `MissingMods.contains(id)` → `null` (121-122).
2. `Mods.containsKey(id)` → that `Mod` (124-125). The cache wins over everything below.
3. `dir = ZomboidFileSystem.getModDir(id)` (= `modIdToDir.get(id)`, ZomboidFileSystem.java:987) (127).
4. If `dir == null`: walk `getAllModFolders` **in order** (129-136); for each folder `readModInfo(folder)`, then
   `setModIdToDir(mod.getId(), folder)` for **every** mod read (134), and return the first whose id equals `id` (135-136).
   Folders after the match are not read.
5. If `dir != null`: `readModInfo(dir)`; `null` → add to `MissingMods` (141-143).

`getAvailableModDetails(id)` = `getModDetails(id)` filtered by `Mod.isAvailable()` (ChooseGameInfo.java:149-153).

`readModInfo(dir)` (ChooseGameInfo.java:157-175) → `readModInfoAux(dir)` (parses `<versionDir>/mod.info`, falling back to
`<commonDir>/mod.info`, ChooseGameInfo.java:180-...; logs `can't find mod.info in mod dir` otherwise), then puts it into
`Mods` if the id is absent, **or replaces the existing entry when the new dir's index in `getAllModFolders` is smaller
than the existing one's** (`index1 < index2`, lines 167-171).

### `Mod` constructor: `commonDir`, `versionDir`, `workshopId` (ChooseGameInfo.java:489-546)

- `commonDir = <dir>/common` (absolute), `versionDir = <dir>/<getModVersionDirName(dir)>` (520-528).
- `baseFile`, `mediaFile`, `actionGroupsFile`, `animSetsFile`, `animsXFile` are `PZModFolder(common, version)` pairs
  (530-534).
- `source`/`workshopId` are derived from `Paths.get(dir, "..", "..").normalize().getFileName()` (536-543):
  `Contents` → `source = "Workshop"`; a valid Steam id → `source = "Steam"`, `workshopId = <that name>`; otherwise
  `source = "Mods"`, `workshopId = ""`. So a folder laid out as `<anything>/<itemId>/mods/<mod>` reports
  `workshopId = itemId`, whatever root it sits under.

### B42 version folder choice: `ZomboidFileSystem.getModVersionDirName(Path)` (ZomboidFileSystem.java:574-588)

- Starts with `versionDirName = ChooseGameInfo.getMinRequiredVersion().toString()` = `"42.0"`
  (`new GameVersion(42, 0, "")`, ChooseGameInfo.java:42; `GameVersion` formats `"%d.%d%s"`).
- For each entry name in the mod folder, `getGameVersionIntFromName` (ZomboidFileSystem.java:677-686): `"42"` →
  42000, `"42.13"` → 42013 (`major*1000 + min(minor,999)`), anything non-numeric → 0.
- Picks the entry with the **highest** version that is `>= minRequired (42000)` and `<= Core.getGameVersion().getInt()`
  (582-584). Example in this install: `2409333430/mods/91range` has `42.0`, `42.13`, `42.20`, `common`.
- `common/` is always the second layer: `loadMod` walks `commonDir` first, then `versionDir`, so version files override
  common files of the same relative path (see section 3).

## 2. Precedence when two folders hold the same mod id

- **Earliest folder in the `getAllModFolders` list wins**, both in the `getModDetails` scan (first match returned,
  `setModIdToDir` is `putIfAbsent`) and in `readModInfo`'s replacement rule (lower index replaces higher).
- With the default order that means: own staged items (`Zomboid/Workshop/*/Contents/mods`) → installed Workshop
  items (in `GetInstalledItemFolders` order) → `Zomboid/mods`. A copy in `Zomboid/mods` loses to any Workshop copy.
- Determinism caveats:
  - The order among Workshop items is whatever the native `SteamWorkshop.n_GetInstalledItemFolders()` returns
    (SteamWorkshop.java:174, no sorting on the Java side). **Not confirmed** (native code).
  - Within one root, children come from `Files.newDirectoryStream`, whose order the API does not specify.
  - Once an id is in `Mods`/`modIdToDir`, later lookups do not rescan until a reset (section 3).
  - `readModInfo(dir)` on a dir **not** in the list gets index `-1`, which is smaller than any real index, so it
    replaces the `Mods` entry (ChooseGameInfo.java:167-171). It does not touch `modIdToDir`.
- `searchForModInfo` (ZomboidFileSystem.java:719-746) does a plain `modIdToDir.put` (line 738), but it has no
  callers outside itself in this jar.

## 3. The join path and when id → folder is resolved

### Server side: what the client receives

`ConnectionDetails.writeMods` (ConnectionDetails.java:82-114) writes, for each server mod, `id`,
`Mod.getWorkshopID()`, `Mod.getName()` (105-107), resolving the server's own dir with `getModDir` + `readModInfo`
(86-89). Unknown ids are sent with an empty workshop id (110-112).

### Client side, in state order

1. `WorkshopUpdate` (ConnectToServerState.java:628-647): when every `WorkshopItem` is ready it calls
   `ZomboidFileSystem.resetModFolders()` (642), so newly downloaded items enter the folder list. It does **not**
   clear `Mods` or `modIdToDir`.
2. `CheckMods` (ConnectToServerState.java:651-681): reads `(id, workshopId, name)` triples (656-663), sets
   `GameClient.instance.serverMods` (666-667), then `ZomboidFileSystem.loadModsAux(serverMods, tmp)` (669). That only
   runs `loadModAndRequired` → `ChooseGameInfo.getAvailableModDetails` per id (ZomboidFileSystem.java:929-954), i.e. a
   presence/availability/`require=` check. Failure → `UI_OnConnectFailed_ModRequired` + disconnect (671-676).
   Ids already resolved on the main menu are answered from the stale `Mods` cache here.
3. `Finish` → `receiveServerOptions` (ConnectToServerState.java:128-137) → **`Core.ResetLua("client",
   "ConnectedToServer")` (135)**. This is where the session's mods are resolved and loaded:
   - `Core.ResetLua` (Core.java:4090-4185): ... `ChooseGameInfo.Reset()` (4114) ... `LuaManager.init()` (4130) ...
     `ZomboidFileSystem.Reset()` (4141; clears `modIdToDir`, `modDirToMod`, `mods`, `activeFileMap`, calls
     `resetModFolders`, ZomboidFileSystem.java:494-507) → `init()` (4142) → **`loadMods("client")`** (4143) →
     `loadModPackFiles()` (4144) → `Languages.init` / `Translator.loadFiles` (4145-4146) → `CustomPerks`,
     `CustomSandboxOptions` (4147-4150) → `ModRegistries.init` (4151) → `ScriptManager.Load` (4152) → models, clothing,
     tile depth, seams, seating (4153-4165) → `AdvancedAnimator.load` (4168) → `LuaManager.LoadDirBase()` (4169).
   - `loadMods(String)` (ZomboidFileSystem.java:850-879): if `GameClient.client` (854), list =
     `loadTranslationMods(...)` (the user's `default.txt` mods that are translation-only, 856; this resolves every
     default mod id via `loadModsAux`, ZomboidFileSystem.java:914-921) + `GameClient.serverMods` (857) →
     `loadMods(List)`.
   - `loadMods(List)` (ZomboidFileSystem.java:968-980): `loadModAndRequired` for each id (resolution via
     `getAvailableModDetails`, adds required mods first), then `loadMod(id)` for each.
   - `loadMod(id)` (ZomboidFileSystem.java:750-789): `dir = getModDir(id)` (**uses `modIdToDir`, not
     `ChooseGameInfo.Mods`**); returns silently if null (750). Walks `commonDir` then `versionDir` with
     `searchFolders` and puts `relative lowercase path → absolute path` into `activeFileMap` (759-785). Later mods and
     the version layer overwrite earlier entries (the log line `mod "X" overrides <path>`).
4. Lua: `MultiplayerUI.onResetLua` handles reason `"ConnectedToServer"` (client/OptionScreens/MultiplayerUI.lua:1655).

### Leaving the server

- Normal quit: `IngameState.updateInternal` calls `GameClient.doDisconnect` when `GameClient.client`
  (IngameState.java:1395-1398); `doDisconnect` clears `GameClient.client` (GameClient.java:1775). On a server
  disconnect, `IngameState.updateInternal` clears `connected`/`client` itself (IngameState.java:1439-1440).
- `IngameState.exit` then re-resolves everything: `ChooseGameInfo.Reset()` (1005), `ZomboidFileSystem.Reset()` (1039),
  `init()` (1045), **`loadMods("default")`** (1051), `loadModPackFiles` (1052), `Languages`/`Translator` (1053-1054),
  ... `ScriptManager.Load` (1062), ... `LuaManager.LoadDirBase()` (IngameState.java:1082).
- Leaving before reaching the game (after `ResetLua("client")`): Lua calls `getCore():ResetLua("default",
  "exitJoinServer")` from client/OptionScreens/CharacterCreationProfession.lua:582, MapSpawnSelect.lua:616 and
  WorldSelect.lua:102. `MainScreenState.enter` also clears `GameClient.client` (MainScreenState.java:414).
  Other failure paths after `ResetLua("client")` (e.g. a disconnect during world loading) were **not traced**.
- A join that fails at `CheckMods` or earlier never ran `ResetLua("client")`, so the main-menu mods stay loaded.
- `ZomboidFileSystem.update()` (ZomboidFileSystem.java:1515-1535), called only from `MainScreenState.update`
  (MainScreenState.java:808), clears `modIdToDir`/`modDirToMod`/folders and `ChooseGameInfo.Reset()`, then re-resolves
  `getModIDs()` and fires `OnModsModified`. It runs 2 s after a change inside a watched mod folder
  (`onModFileChanged` / `isModFile`, ZomboidFileSystem.java:1538-1560).

## 4. Which Workshop item folders are scanned

- **Every** installed item: `getInstalledItemModsFolders` iterates `SteamWorkshop.instance.GetInstalledItemFolders()`
  (ZomboidFileSystem.java:549-554), which is the native `n_GetInstalledItemFolders()` (SteamWorkshop.java:171-174).
  The server's `WorkshopItems` list does not filter it. The join subscribes to the server's items
  (`WorkshopItem.CheckItemState` → `SteamWorkshop.SubscribeItem`, ConnectToServerState.java:356), so they join that set.
- Per-item lookups exist but are not used for mod resolution: `SteamWorkshop.GetItemInstallFolder(id)` is used by
  `ZomboidFileSystem.getWorkshopItemMods(long)` (ZomboidFileSystem.java:690-711, Lua `getSteamWorkshopItemMods`) and by
  the server's `GameServerWorkshopItems.Install`.
- Whether the native list contains only subscribed items or every downloaded item is **not confirmed**.

## 5. Hook candidates and other readers of the paths

### Who reads the resolved folder

Nearly every consumer goes through `ChooseGameInfo.getAvailableModDetails/getModDetails(id)` →
`Mod.getCommonDir()/getVersionDir()` or through `activeFileMap` (built by `loadMod` from `getModDir(id)`). Call sites
found (javap of every class referencing `ZomboidFileSystem`/`ChooseGameInfo`):

- `ZomboidFileSystem`: `loadMod` (756), `loadModAndRequired` (939), `isTranslationMod` (901), `loadModPackFiles` (1092),
  `loadModTileDefs` (1120), `loadModTileDefPropertyStrings` (1150), `loadFileGuidTable` (1193), `walkGameAndModFiles`
  (1392).
- Lua: `LuaManager.LoadDirBase` (1168); `GlobalObject.getModFileReader` (5237, `getModDetails` then
  `versionDir`/`commonDir` + path, 5241-5244), `getModFileWriter` (5367), `listFilesInModDirectory` (5292),
  `getModInfoByID` (4766), `getMapFoldersForMod` (4786), `getMapDirectoryTable` (4127), `spawnpointsExistsForMod` (4837).
- Translations: `Languages.init` (37), `Translator.tryFillMapFromMods` (381).
- Sandbox / perks: `CustomSandboxOptions.init` (30), `CustomPerks.init` (31).
- Scripts / assets: `ScriptManager.init` (14), `ScriptManager.Load` (1444), `CraftRecipe.InitLoadPP` (370),
  `ModelManager.loadModAnimations` (1985), `AdvancedAnimator.collectModFiles` (883), `isAnimSetFilePath` (92),
  `isActionGroupFilePath` (126), `BeardStyles`/`HairStyles`/`ClothingDecals`/`VoiceStyles`/`OutfitManager`,
  `SpriteModelManager.init` (48), `TileGeometryManager`/`TileDepthTextureManager`/`TileDepthTextureAssignmentManager`,
  `SeamManager.init` (45), `SeatingManager.init` (69), `RadioData.fetchRadioData` (101, 163), `MapGroups.createGroups`
  (110), `WorldDictionary.getModNameFromID` (447), `ActiveMods.checkMissingMods` (182).

### Path checks tied to the folder list

- `allowedPrefixes` = all `getAllModFolders` entries + `getCacheDir()` (`Zomboid/`) + the game folder
  (`lambda$new$0`, ZomboidFileSystem.java:104-109). `validatePrefix(path)` throws `Invalid prefix found for: %s`
  otherwise (ZomboidFileSystem.java:1660-1669). Callers include `Texture.getSharedTextureInternal` (564),
  `NativeImage.read` (43), model/animation/physics loaders (`FileTask_LoadMesh`, `FileTask_LoadAnimation`,
  `FileTask_AbstractLoadModel`, `FileTask_LoadPhysicsShape`), `LuaManager.GlobalObject.reloadLuaFile` (3743). A cache
  folder under `Zomboid/` passes through the `getCacheDir()` prefix even when it is not in the folder list.
- Texture packs: `loadModPackFiles` (ZomboidFileSystem.java:1090-1114) keys `loadedPacks` by the **resolved absolute
  path** (`getString("media/texturepacks/X.pack")`, 1103-1106) and `loadedPacks` is never cleared (only assigned in the
  constructor). So a pack from a different folder loads as a new pack, and packs loaded earlier stay loaded. Which
  texture wins when two loaded packs hold the same texture name is **not confirmed**.

### Candidate interception points (narrowest first)

| Method | What a redirect there does | Notes |
|---|---|---|
| `ZomboidFileSystem.getInstalledItemModsFolders(List)` (548-559) | Swap `<installed item>/mods` for `<cache>/<item>/mods` for the mismatched item ids | The client's newer copy is then not in the list at all (no duplicate id), the cache keeps the item's position, and the cache becomes part of `allowedPrefixes` and the file watcher. The list is cached in `modFolders`: needs `resetModFolders()` and a reset of `Mods`/`modIdToDir` when switching. |
| `ZomboidFileSystem.getAllModFolders(List)` (638-674) | Prepend cache mod folders | Earliest folder wins, so a prepended copy wins over the Workshop copy; the newer copy stays in the list (duplicate ids). Same cache/reset needs. |
| `ChooseGameInfo.getModDetails(String)` (121-145) **and** `ZomboidFileSystem.getModDir(String)` (987) | Return the cache copy per id | Both are needed: `loadMod` reads `modIdToDir`, most others read `Mods`. `setModIdToDir` alone is not enough (`putIfAbsent`, and the scan's return value does not use it). |

Timing points:

- Last moment before the session's resolution: before `Core.ResetLua("client", "ConnectedToServer")` in
  `ConnectToServerState.receiveServerOptions` (ConnectToServerState.java:135). `ResetLua` itself clears `Mods`
  (Core.java:4114) and `modIdToDir` + folder list (4141) before `loadMods` (4143).
- `CheckMods` (ConnectToServerState.java:669) also resolves ids, mostly from the main-menu cache; a redirect active
  only from `Finish` on means `CheckMods` checks the client's copy (its `require=` list included).
- Return to normal: before `IngameState.exit` reaches `ChooseGameInfo.Reset` / `ZomboidFileSystem.Reset` /
  `loadMods("default")` (IngameState.java:1005, 1039, 1051), and before `ResetLua("default", "exitJoinServer")` on the
  pre-game exits.
- Between `ChooseGameInfo.Reset` (Core.java:4114) and `ZomboidFileSystem.Reset` (4141) `modIdToDir` still holds the old
  dirs. If anything in that window calls `getModDetails`, `Mods` is refilled from the old dir and `loadMod` later finds
  no `modIdToDir` entry. Whether any call in 4115-4140 does that is **not confirmed**.

## 6. Lua checksum

- Built during `ResetLua`: `LuaManager.LoadDirBase()` (LuaManager.java:1260-1262) = `initChecksum()`,
  `LoadDirBase("shared")`, `LoadDirBase("client")`.
- `LoadDirBase(sub, onlyChecksum)` (LuaManager.java:1144-1235): game files, then for each id in
  `ZomboidFileSystem.getModIDs()` the mod's `commonDir/media/lua/<sub>` and `versionDir/media/lua/<sub>` (1164-1198),
  each mod's list sorted case-insensitively (1201) and appended. Each relative path is resolved once through
  `ZomboidFileSystem.getAbsolutePath(rel)` (the `activeFileMap` winner, 1219) and, when `GameClient.client` or
  `GameServer.server`, added with `NetChecksum.checksummer.addFile(rel, abs)` (1228-1229), except `SandboxVars.lua`.
- `Checksummer.addFile` (NetChecksum.java:39-56) MD5s the file's bytes with `\r` (13) dropped, and records
  `relPath`/per-file MD5 in `GroupOfFiles`. The client result goes to `GameClient.checksum`, the server's to
  `GameServer.checksum` (LuaManager.finishChecksum, 1247-1256).
- Compare: at world load, if the server option `DoLuaChecksum` is on, `IsoWorld.init` runs `NetChecksum.comparer`
  (IsoWorld.java:2205-2227). The server compares three totals: Lua vs `GameServer.checksum`, scripts vs
  `ScriptManager.getChecksum()`, anims vs `AdvancedAnimator.getChecksum()` (ChecksumPacket.parseServer,
  ChecksumPacket.java:227-232). A role with the right capability skips it (234-235). On a mismatch it narrows down by
  groups and files and reports `File doesn't match the one on the server` / `File doesn't exist on the client` /
  `... on the server`.
- So the compare is over the **contents of the resolved files, in load order**, not per mod folder. A cached copy that
  is byte-identical to the server's (CR differences ignored) resolves to the same relative paths and the same bytes, so
  it passes, provided every other mod on both sides also matches. `ScriptManager.Load` (scripts, ScriptManager.java:
  1506-1531) and `AdvancedAnimator` (anims) checksums are built from the same resolved files.

## Related Specs

- `.trellis/spec/mod/access-widening.md`: Zabric's widening scans the fixed roots in `ModFolders.roots()`; a cache
  folder outside those roots is not scanned for widener lists or jars.
- `.trellis/spec/mod/error-handling.md`: fail-fast rules that apply to any hook here.

## Caveats / Not Found

- `n_GetInstalledItemFolders` order and contents (subscribed only, or every downloaded item) are native; not
  confirmed.
- Whether `DebugFileWatcher` is active outside debug mode was not checked; it decides whether writing into a watched
  mod folder on the main menu triggers `ZomboidFileSystem.update()`.
- Texture lookup order between two loaded packs with the same names: not confirmed.
- Failure paths after `ResetLua("client")` other than the three Lua `exitJoinServer` calls: not traced.
- Java mods (ZombieBuddy jars) are loaded at JVM start, outside this resolution; not covered here.
