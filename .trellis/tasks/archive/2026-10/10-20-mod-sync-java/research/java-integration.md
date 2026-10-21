# Research: mod-sync-java integration points

- **Query**: How `mod-sync-java` (R1-R7) plugs into the core child's join hold and the Zabric launch override API:
  the interim jar stop, Java detection, the post-pass check, widening, preload mods, failure UI, Rejoin, two servers,
  and gaps between the core child and this PRD.
- **Scope**: internal (worktree code at 8560834, ZombieBuddy 2.3.4 source, game bytecode, vanilla Lua; nothing run)
- **Date**: 2026-10-21

Sources and citation forms:

- Worktree paths are relative to the repo root. `MS:` = `mod-sync/src/main/kotlin/com/dont39/pz/modsync/`,
  `ZA:` = `zabric/src/main/java/com/pzlauncher/zabric/`.
- `ZB:` = `E:/Dev/Project/pz/dont39-pz-mods/external-repos/ZombieBuddy/java/src/main/java/me/zed_0xff/zombie_buddy/`
  (main checkout, commit 9a08a54, `VERSION` 2.3.4; the worktree submodule is empty).
- Game: `projectzomboid.jar` via `javap -c -p -l`, cited as `Class.java:line` from the LineNumberTables. Lua is
  `ProjectZomboid/media/lua/client/OptionScreens/...`.
- Earlier research reused, not repeated: [java-mod-restart.md](../../10-20-server-mod-sync/research/java-mod-restart.md)
  (ZombieBuddy discovery, approvals, shadowing), [mod-discovery.md](../../10-20-server-mod-sync/research/mod-discovery.md)
  (folder resolution, version folders), core [spike.md](../../archive/2026-10/10-20-mod-sync-core/research/spike.md)
  (leave paths).

## Files Found

| File | Role for mod-sync-java |
|---|---|
| `MS:client/JoinSession.kt` | Hold state machine; the interim jar stop (146-151) |
| `MS:client/ModSyncClient.kt` | Hold driver, swap on/off, `loaded` flag, `stop` (OnConnectFailed + forceDisconnect) |
| `MS:client/ClientHooks.kt` | Hooks: `parse`, `setState`, `CheckMods`, `ConnectToServerState.exit`, `ResetLua`, `IngameState.exit` |
| `MS:client/download/Plan.kt`, `LocalCopy.kt`, `Cache.kt`, `ServerStore.kt` | Plan (`jarChanges`), cache tree layout, `servers.json` |
| `MS:client/swap/Swap.kt`, `SwapEntry.kt`, `FolderSwap.kt` | The join-time folder swap |
| `MS:client/Refusal.kt` | R5 refusal (already in core) |
| `mod-sync/src/main/lua/client/Dont39AllInOne_ModSyncJoin.lua` | Connect-screen status and confirm dialog |
| `allinone-common/src/main/kotlin/com/dont39/pz/common/FailJoin.kt` | `failJoin` = `OnConnectFailed(text)` |
| `ZA:mods/JavaMods.java`, `mods/impl/JarHistory.java` | `loaded`, `lastPass`, `servingJar`, phases |
| `ZA:mods/LaunchOverride.java`, `mods/impl/LaunchOverrides.java`, `mods/impl/OverrideRequest.java`, `mods/impl/ItemFoldersHook.java` | One-launch override |
| `ZA:mods/WidenRoots.java`, `mods/impl/WidenRootsFile.java`, `widener/AccessWidener.java`, `widener/ModFolders.java`, `widener/WidenerScan.java` | Widening roots and checks |
| `ZA:entrypoint/Lifecycle.java`, `entrypoint/Preload.java`, `loader/PreloadConfig.java`, `restart/RestartList.java` | Main entrypoint once per JVM, preload registration, restart list |
| `zabric/src/main/lua/client/Zabric_RestartNotice.lua` | Main-menu notice precedent |
| `ZB:Loader.java`, `ZB:JavaModInfo.java` | Pass resolution, status records, preload, `mod.info` rules |
| `zombie/gameStates/ConnectToServerState`, `zombie/core/Core`, `zombie/network/Server`, `Account` | Join `Finish`, `ResetLua`, saved servers |
| `ConnectToServer.lua`, `MultiplayerUI.lua`, `MainScreen.lua`, `MapSpawnSelect.lua` | Failure label, vanilla connect, quit, exit-join |

## 1. mod-sync core today

### The interim rule (the seam to replace)

- In the worker's `prepare` (`MS:client/JoinSession.kt:130-155`), per marked item, in this order:
  1. Manifest lookup by id + timestamp (`:135`, `protocol/Manifest.kt:16-25`).
  2. R5 refusal by Workshop id or mod folder of either copy (`:137-139`, `Refusal.kt:15-32`) → `Failed_Refused`.
  3. Tree path and readiness (`:140-141`); a ready tree is re-planned on its jars only (`:143`).
  4. `LocalCopy.scan` + `Plan.of` (`:144-145`).
  5. **`if (plan.jarChanges.isNotEmpty()) throw StopJoin(Step.Stop("Failed_Jar", ...))` (`:146-151`)**. Text:
     `UI_Dont39AllInOne_ModSync_Failed_Jar` in `mod-sync/src/main/lua/shared/Translate/EN/UI.json`.
- `Plan.jarChanges` (`Plan.kt:22-26`): every `.jar` path (any folder, `isJar` = suffix, `:29`) whose hash differs
  from the client's copy, plus jars only the client has. It is path-based, not "the jar ZombieBuddy loads".
- The check runs **on the worker thread**. `JavaMods`/`LaunchOverride`/`WidenRoots` are documented main-thread-only
  (`ZA:mods/JavaMods.java:15-16`, `LaunchOverride.java:24-26`), so the Java decision cannot stay inside `prepare`.

### What the session knows at that point

| Per item | Where |
|---|---|
| `Marked(id, timestamp, title)`, `label` | `JoinSession.kt:29-32`, from `WorkshopItem` in `ModSyncClient.takeOver` (`ModSyncClient.kt:69-82`) |
| Server files: `ManifestItem(id, timestamp, files[path,size,sha256])`, paths `mods/<mod>/...` | `protocol/ManifestItem.kt:7` |
| Client copy: install folder (`SteamWorkshop.GetItemInstallFolder`, game thread, `:107-109`), `LocalCopy(root, files, hashes)` (hashes only for size-matching paths, `LocalCopy.kt:15-31`) | `JoinSession.kt:144` |
| Tree: `<cache>/Dont39AllInOne/ModSync/items/<host>_<port>/<id>/<timestamp>/<id>/` (`Cache.kt:21-23`), ready = marker + sizes (`:30-46`) | `JoinSession.kt:140-141` |
| `Work.Reused(marked, tree, install)` (no plan kept) / `Work.Build(..., local, plan)` | `JoinSession.kt:35-42`, `:152` |
| `Prepared(fetcher, items, remembered)` with `builds`, `downloads`, totals | `JoinSession.kt:44-50` |

Not known: which mods of the item the server's mod list uses (the triples are read by vanilla `CheckMods` after the
hold, `ConnectToServerState.java:656-667`), `mod.info` contents, `javaPkgName`.

### Hold state machine

- `Phase`: `Start → Preparing(worker) → [Confirm] → Building(worker) → Over` (`JoinSession.kt:70-80`); `Step` per
  poll: `Hold | Ready(entries) | Stop(key,args,why) | Broken` (`:57-68`); `poll` (`:103-119`).
- `prepared` (`:157-178`): no builds → `Ready`; downloads and server not remembered → `Confirm`; else `build`.
- `confirmed` (`:180-189`), `build` (`:191-205`), `ready` builds `SwapEntry.of(id, install, tree)` (`:213-220`).
- `failed` (`:228-237`): `StopJoin`/`SyncException`/`IOException` → `Stop`; anything else → `Broken`.
- `Worker` (`MS:client/Worker.kt:6-43`): daemon thread, volatile `outcome`, cancel = flag + interrupt.
- `JoinStatus.table` (`MS:client/JoinStatus.kt:17-48`): `preparing`/`confirm`/`download`, nil in `Start`/`Over`.
- Driver `ModSyncClient.hold` at `CheckMods` HEAD, cancellable (`ClientHooks.kt:40-48`; `ModSyncClient.kt:89-132`):
  no session or no marks → vanilla; `GameClient.connection == null` → `end(join)` (`:97-101`); feature off →
  `Failed_Inactive` (`:102-105`); `Ready` → `session = null; loaded = false; Swap.on(entries)`, return false so vanilla
  `CheckMods` runs in the same call (`:111-116`); `Stop` → `stop` (`:117-120`); `Broken`/throw → disable + `Failed_Error`.

### Swap on/off

- On: only at `Ready` (`ModSyncClient.kt:114`); `Swap.on` sets the volatile state, resets folder list +
  `ChooseGameInfo`, marks ids stale, verifies (`Swap.kt:36-47`, `:69-86`).
- Off (`Swap.off`, `Swap.kt:49-56`): `joinStarts` (`ModSyncClient.kt:44`), `ResetLua(activeMods != "client")` (`:135-143`),
  `IngameState.exit` (`:147-149`), `ConnectToServerState.exit` while `!loaded` (`:153-158`), `stop` (`:225`).
- `loaded = true` at `ResetLua("client")` HEAD (`:138-139`): from then the join's end keeps the swap on, until a leave.
- The session object is dropped at `Ready` (`:112`): **nothing survives the hold** that knows which jars the join
  expects. A post-pass check (R1) needs a new holder.

### Leaving / failed join

- `stop(join, step)` (`ModSyncClient.kt:220-231`): `end(join)` = `MainScreenState.setConnectToServerState(null)` +
  `join.exit()` (`:213-217`, exit hook ends the session), `Swap.off`, `failJoin(KEY_PREFIX+key, args)`
  (`FailJoin.kt:10-12`: `triggerEvent("OnConnectFailed", text)`), `GameClient.connection?.forceDisconnect("connect-dont39-modsync")`.
- Leave paths after `ResetLua("client")`: exit-join `ResetLua("default","exitJoinServer")`, `IngameState.exit`
  (table in core `spike.md` §1a).

### Seam for mod-sync-java

Replace `JoinSession.kt:146-151` with: keep `plan.jarChanges` (and the item's tree) in `Work`/`Prepared`, and decide
on the game thread (e.g. in `prepared()` or a new step before `ready()`), where `JavaMods` may be called; plus a new
post-pass check outliving the session (section 3).

## 2. Which mods carry Java, and comparing jars

### ZombieBuddy's rules (`ZB:JavaModInfo.java`, `ZB:Loader.java:596-942`)

- Per mod id of the pass: `ChooseGameInfo.getAvailableModDetails(id)` (`Loader.java:625`), then
  `parse(versionDir)` (mod.info and jar relative to the version folder), else `parse(commonDir)` (both relative to
  `common/`), else `parseMerged(commonDir, versionDir)` (common `mod.info`, jar relative to the version folder)
  (`Loader.java:632-638`; `JavaModInfo.java:243-256`, `279-294`).
- `mod.info` parse (`JavaModInfo.java:175-234`): keys case-insensitive by line prefix; first `javaJarFile` wins and must
  end `.jar` (196-207); first `javaPkgName` (208-215); `ZBVersionMin/Max` (216-219, range check 146-149); `name`;
  `javaPreload=true` (224-225).
- Side: a `javaJarFile` containing `media/java/server/` is skipped on the client, `media/java/client/` on a server
  (`JavaModInfo.java:135-145`). Jar must exist (151-155).
- Version folder: `ZomboidFileSystem.getModVersionDirName(Path)` (public; highest `42.x` folder within the game
  version, [mod-discovery.md §1](../../10-20-server-mod-sync/research/mod-discovery.md)); `Mod` ctor sets
  `versionDir`/`commonDir` (ChooseGameInfo.java:520-528).
- One jar per mod; duplicates of a `javaPkgName` within a pass: the last wins (`Loader.java:650-654`, 831-836);
  ZombieBuddy's own package skipped (826-829).
- Preload: `config.json` `preload_mods` entry (absolute `infPath`, `jarPath`), needs `javaPkgName` in the stored
  `infPath`, the jar, and manifest `ZB-Preload: true` (`Loader.java:401-465`). Entries are added by ZombieBuddy itself
  for trusted signed jars with `javaPreload` (`:769-770`) or a dialog line flagged preload (`:315-317`), and by Zabric's
  `PreloadConfig.keep` for mods with a `Zabric-PreLaunch` entrypoint (`ZA:loader/PreloadConfig.java:33-98`, which also
  requires `javaPreload=true` + `ZB-Preload`).

### Reuse or reimplement

- No public parser exists. ZombieBuddy's `JavaModInfo` is package-private (`record JavaModInfo`, outside the seam
  anyway); Zabric's `PreloadConfig.modInfoValue` is private (`PreloadConfig.java:139-154`), `OverrideRequest.ids`
  private (`OverrideRequest.java:189-200`). `buildSrc/.../ModInfo.kt` is build-time only. mod-sync must reimplement the
  small parse (or Zabric grows an API).
- Candidate inputs on the client: the tree (files on disk after build, or the manifest paths before) and the install
  folder; version folder via the public `getModVersionDirName`. `ChooseGameInfo.readModInfo(String)` is public but
  replaces `Mods` entries for folders outside the list (mod-discovery.md §2), so it has side effects.
- The server's mod list is not known in the hold (section 1), so "which of the item's Java mods load this session" is
  only known after the pass.

### Comparing the server's jar with the client's

- Manifest: `sha256` lowercase hex per file (`Manifest.kt:44-46`, regex `[0-9a-f]{64}`); `JavaMods.Jar.sha256` is
  lowercase hex too (`ZA:mods/JavaMods.java:20-24`). Direct string compare works.
- `JavaMods.Jar.path` is the canonical path ZombieBuddy recorded (`toRealPath`, `ZB:Loader.java:859-867`, 886-898);
  compare with `tree.resolve(...).toRealPath()`.
- "Is the package loaded / which copy runs": `JavaMods.servingJar(pkg)` (`JavaMods.java:48-58`): null = never loaded;
  else the jar whose classes the system class loader serves; throws when several jars serve it. `lastPass()` lists
  "loaded" records for the pass's mod ids, including jars loaded in earlier passes (`JarHistory.java:31-37`;
  `ZA:loader/LoadStatus.java:57-78`), so a mod whose jar changed lists both.
- Phases: `PRELOAD` (preloaded at premain, recorded at preLaunch), `STARTUP` (first pass), `LATER`
  (`JarHistory.java:26-37`). R3's "loaded by the startup pass" = `PRELOAD` or `STARTUP`.
- All three throw unless Zabric ran at game start and its entrypoints run (`Lifecycle.requireRunning`,
  `ZA:entrypoint/Lifecycle.java:173-180`).

## 3. The join's pass and the post-pass check (R1)

### When the pass runs

- `Finish` (ConnectToServerState.java:685-758) runs in one frame: `receiveStartLocation`, `receiveServerOptions`
  (→ `Core.ResetLua("client","ConnectedToServer")`, :135), `receiveSandboxOptions`, ... `receiveWorldDictionary`
  (fires Lua `OnConnected`, :176), then `state = Exit` (:756-757). Outer catch (:750-753): `OnConnectFailed("WorldDictionary
  error")` + `forceDisconnect("connection-details-error")`.
- `Core.ResetLua` (Core.java:4101-4185): ... `LuaManager.init` (4130) ... `ZomboidFileSystem.Reset/init` (4141-4142),
  `loadMods("client")` (4143), packs, scripts, ..., `LuaManager.LoadDirBase` (4169), then events **`OnGameBoot`
  (4182), `OnMainMenuEnter` (4183), `OnResetLua(reason)` (4184)**.
- Inside `loadMods(List)`: ZombieBuddy's enter advice runs `Loader.loadMods(mods)` (`ZB:patches/Patch_ZomboidFileSystem.java:31-41`);
  Zabric's callback runs at that method's exit (`ZA:loader/ModsLoadedHook.java:94-99`) → `Lifecycle.modsLoaded` →
  `JarHistory.passLoaded` (`Lifecycle.java:137-167`). So `JavaMods.lastPass()` already describes the join's pass
  before the game's `loadMods(List)` body runs.

### Hook options

| Option | Notes |
|---|---|
| Zabric main entrypoint | Once per JVM per mod (`mainCalled`, `Lifecycle.java:187`); All-in-One's ran at startup. Not usable per pass. Zabric has no public per-pass listener (public API list: `package-info.java` files) |
| `ZomboidFileSystem.loadMods(Ljava/util/List;)V` RETURN | After the pass's jars loaded; before packs, scripts and Lua (`LoadDirBase`). Overloaded with `loadMods(String)`: name the descriptor |
| `Core.ResetLua(String,String)` RETURN with `"client"` | After Lua loaded and `OnResetLua`; still inside `Finish`, before `OnConnected`. mod-sync already hooks HEAD (`ClientHooks.kt:58-64`) |
| `ConnectToServerState.receiveWorldDictionary` / `Finish` | Later in the same frame; private methods (core already hooks private `CheckMods`) |

- Hook gating: generated glue runs a handler when `MOD.active(gate)` (gate = the `ModSync` facade) or, for
  `always = true`, `MOD.open()` (`zabric-processor/src/main/resources/com/pzlauncher/zabric/processor/glue/glue.ftl:100`);
  `open` means "the mod is in the last mod list Zabric processed" (`ZA:hook/ModHooks.java:3-11`,
  `hook/impl/HookRuntime.java` `passProcessed`, `ModState.update`). So **all** of All-in-One's hooks close after a
  pass that does not include `Dont39AllInOne`. The join's pass is `default`'s translation mods + `serverMods`
  (mod-discovery.md §3); a server running the feature has All-in-One in its own mod list, so it is normally in the
  pass. **Not confirmed** in game for the post-pass hooks.

### How a denied approval shows up

- Policy skip writes the record with reason `"(blocked by policy=<p>, modId=...)"` (`ZB:Loader.java:853-857`, 886-898)
  and the jar is not loaded; `LoadStatus` keeps only `"loaded"`/`"preloaded"` (`LoadStatus.java:67-71`), so the tree's
  jar is **absent from `lastPass()`** and `servingJar(pkg)` stays null (R1 case: package never loaded).
- A `"loaded"` record does not prove the jar is on the class path: the record is written before `loadJar`, which can
  still refuse (known path, package missing, hash changed) (`Loader.java:886-898`, 937-941; `addJarToClasspath`
  474-521). `servingJar(pkg) == tree jar` is the stronger check.
- Session decisions are reused in later passes (`applySessionDecisions`, `Loader.java:171`, 670): a denial at one join
  stays for later joins in that launch unless Shift forces the dialog.
- The ImGui dialog during `ResetLua("client")` is **not confirmed** (java-mod-restart.md §3).

### Can the join still be stopped there

- After `ResetLua("client")`, the session's Lua is loaded and the UI was rebuilt (`OnMainMenuEnter` recreates the
  main screen, `MainScreen.lua:91`, 2178); `ConnectToServer:OnConnectFailed` returns early when the screen is not
  visible (`ConnectToServer.lua:307`), so core's `failJoin` text may not show (**not confirmed**).
- `ModSyncClient.stop` → `end(join)` + `forceDisconnect` works mechanically, but `Finish` continues and fires
  `OnConnected` (spawn/character screens, `ConnectToServer.lua:270-295`).
- Vanilla's own way back from that point is `backToSinglePlayer()` + `getCore():ResetLua("default","exitJoinServer")`
  (`MapSpawnSelect.lua:615-616`, `WorldSelect.lua:101-102`, `CharacterCreationProfession.lua:581-582`); mod-sync's
  `ResetLua` HEAD hook turns the swap off on it. Calling `ResetLua` from inside a `ResetLua` RETURN hook is nested
  (**not checked**); doing it on a later frame (Lua `OnConnected`/tick, or after `Finish`) avoids that.
- Throwing out of a hook inside `ResetLua` would leave Lua half-reset (between `LuaManager.init` and `LoadDirBase`) and
  end in the generic "WorldDictionary error"; Zabric hooks report throws to the gate anyway.
- `TunnelClient.stop` only shows the text (`tunnel/.../TunnelClient.kt:82-95`); `ModSyncClient.stop` as above.

## 4. Widening for a tree built at this join

- Premain scan roots (`ZA:widener/ModFolders.java:44-57`): Workshop content folders (`<item>/mods`), `<cache>/mods`,
  `<cache>/Workshop/*/Contents/mods`, this launch's override folders, and `widen-roots.txt` folders that exist
  (`WidenRootsFile.java:45-55`). The mod-sync cache (`<cache>/Dont39AllInOne/ModSync/items/...`) is none of them.
- Lists: `META-INF/zabric/<modId>.classtweaker` in any jar under `<mod>/media/java` or `<mod>/<version>/media/java`
  (`ModFolders.java:60-76`, `WidenerScan.java:80-102`), stored by list mod id → **SHA-256 of the list bytes**
  (`WidenerScan.java:97`).
- When the jar loads and its mod calls `AccessWidener.problem/report(modId, host)` (`AccessWidener.java:87-111`), the
  list is read from the host class's jar and looked up by hash (`:182`):
  - identical bytes found anywhere at premain (e.g. the client's Workshop copy) → in effect (entries are by class
    name), verified (`:199-209`);
  - not found, jar under a scanned root → "restart the game once", `restart = true` → `RestartList.add` (`:191-194`);
  - not found, jar outside the roots (the fresh tree) → "outside the folders Zabric reads at game start", `restart =
    false`, **not** added to `RestartList` (`:196-197`). The mod's feature then reports itself inactive (e.g.
    `zb-kt-utils` `Feature.why`, widened features).
- Mods that ship no list are unaffected.
- Knowing beforehand: `WidenRoots.scanned(treeMods)` (`WidenRoots.java:31-37` → `AccessWidener.scanned`) says whether
  premain scanned that folder (true for a tree registered by `WidenRoots.add` on an earlier launch, or an override
  folder). No public API answers "is this list hash in effect"; mod-sync can read the list entries from the tree's
  jars (after download) and compare their bytes with the same-named entries in the client copy's jars (scanned under
  the Workshop root). `RestartList` is internal (`restart/package-info.java`), so mod-sync cannot add to Zabric's notice.
- Options when a tree's list differs from every scanned one:
  - (a) Treat as restart, like R2: `WidenRoots.add(treeMods)`, stop with the restart reason, record Rejoin; on the
    next launch the tree is scanned and R1 loads it at the join.
  - (b) Always `WidenRoots.add` every tree that has jars when it is built, and still stop as (a) at this join; later
    launches never hit it. Cost: `widen-roots.txt` grows with each tree, and every launch scans them.
  - (c) Let the jar load and let the mod report "not in effect": a silent partial degrade (conflicts with
    `no-hidden-degrade`).
  - R2 and R3 need the same: R3's override folder is scanned automatically (`ModFolders.java:45`), R2's rejoin only if
    `WidenRoots.add` ran before the restart.

## 5. Preload mods inside an item

- Detection: `javaPreload=true` in the used `mod.info` + manifest `ZB-Preload: true`, or a `Zabric-PreLaunch`
  manifest attribute (`ZA:entrypoint/Lifecycle.java:31`); at runtime `JavaMods.loaded()` with phase `PRELOAD`.
- Launch with an override for such an item (traced from code, **not run**):
  1. ZombieBuddy premain preloads from `config.json`'s absolute `jarPath` (the client's Workshop jar), collected before
     any jar loads (`ZB:Loader.java:401-465`, loop at 410); Zabric's override only replaces
     `getInstalledItemModsFolders` entries (`ZA:mods/impl/ItemFoldersHook.java:112`), so it does not affect this.
  2. Startup pass: preload ids come first in the merged list (`Loader.java:615-617`) and resolve through the game, i.e.
     to the override tree; new hash → approval; if approved, the tree jar is appended after the preloaded jar
     (`addJarToClasspath`), `ApplyPatchesFromPackage(pkg, MAIN)` runs `Main.main` from whichever class the class path
     resolves first (the preloaded copy) and applies no patches (phases already non-empty, `:996-1024`).
     `servingJar(pkg)` = the Workshop jar: the server's Java never runs; its Lua does (mixed).
  3. Zabric's `Lifecycle.load` → `Preload.register` (first time per JVM, `Lifecycle.java:182-186`) → `Preload.update`:
     folder "the game uses this session" = the tree (`ZA:entrypoint/Preload.java:37-58`) → `PreloadConfig.keep`
     rewrites `config.json` to the tree's `infPath`/`jarPath` unless byte-identical (`PreloadConfig.java:33-78`,
     `current` 102-117) and adds the mod to `RestartList` (restart notice on the main menu). For a trusted signed
     ZombieBuddy preload mod, ZombieBuddy itself may store the tree path (`Loader.java:769-770`).
- Launch after (no override): premain preloads the **tree** jar (server's code) while the game resolves the Workshop
  copy (client's Lua); `Preload.update` rewrites `config.json` back to the Workshop copy and asks for a restart again.
  So one launch runs mixed in each direction; the state lives in `config.json`, which is ZombieBuddy's file (outside
  Zabric's seam for mod code).
- Options:
  - (a) Refuse: stop the join with a reason naming the preload mod ("loads at game start, cannot be taken from a
    server"), no override. Same shape as R5.
  - (b) A Zabric capability: the override also points the item's preload entry at the tree for that launch and
    restores it. Constraints from code: ZombieBuddy reads `config.json` before Zabric's premain and collects all
    entries before loading any (`Loader.java:401-433`), so the redirect must be written **before the restart**
    (with the request), and restored by Zabric early in the override launch; ZombieBuddy keeps its in-memory config
    and saves it when dirty (`Loader.java:461-464`, 910-913), which can write the tree path back. **Not confirmed**;
    a Zabric design change.
  - (c) Override without preload handling: gives the mixed launches above. Not workable as is.

## 6. Join failure UI

- Today: `failJoin` triggers `OnConnectFailed(text)`; vanilla `ConnectToServer:OnConnectFailed` (`ConnectToServer.lua:302-335`)
  writes it to `connectLabel`, sets the Back button title, `connecting = false`; it returns early when the screen is
  hidden (:307). The status line during the hold is written by `Dont39AllInOne_ModSyncJoin.lua:185-190`.
- `ConnectToServer` is an `ISPanelJoypad` created once per main screen (`MainScreen.lua:91`), `ConnectToServer.instance`
  set in `create`/`prerender` (`ConnectToServer.lua:137`, 145). A mod can add an `ISButton` child to it from Lua (as
  vanilla adds `backBtn`, :124-132) and show it only after a restart-type stop; hide it again when a new connect starts
  (`ConnectToServer:connect`, :218-243) or on Back (`onBackButton`, :197-216). The Java side would need to expose "the
  last stop needs a restart" (today `luaStatus` is nil once the session is gone, `ModSyncClient.kt:166-174`).
- Quit: `getCore():quit()` on the main menu: `saveOptions`, `GameClient.Shutdown`, `SteamUtils.shutdown`,
  `System.exit` (Core.java:2209-2226). The main menu's own Quit uses a confirm dialog then `getCore():quitToDesktop()`
  = `GameWindow.closeRequested = true` (Core.java:2234-2236; `MainScreen.lua:1463-1509`).
- Precedent for a modal: `Zabric_RestartNotice.lua:30-47` (`ISModalDialog`, OK only).

## 7. Rejoin after the restart

- Vanilla connect from the saved list: `MultiplayerUI:connectToServer(server, account)` (`MultiplayerUI.lua:834-856`):
  `setAccountUsed`, asks for the password when `not account:isSavePwd()` (`ISMPEnterPassword` modal on the
  MultiplayerUI), then `ConnectToServer.instance:connect(self, server:getName(), account:getUserName(),
  account:getPwd(), server:getIp(), localIP, port, server:getServerPassword(), useSteamRelay, false,
  account:getAuthType())`. `connect` hides `previousScreen` and Back returns to it (`ConnectToServer.lua:197-243`).
- Saved entries: `getServerList()` = `AccountDBHelper.getServerList()` (LuaManager.java:4275-4285); `Server` has
  `getID()`, `getIp()` (= stored host as typed, `Server.java:87`), `getIp2()` (resolved), `getPort()`, `getAccounts()`;
  `Account` has `getID()`, `getUserName()`, `isSavePwd()`, `getAuthType()`, `getUseSteamRelay()`. Vanilla matches a
  server by `getIp()`+`getPort()` (`MultiplayerUI.lua:693`, 995).
- At the join (Java): `GameClient.ip`, `GameClient.port`, `GameClient.username`, `Core.getAccountUsed()` (the
  `Account` set by `setAccountUsed`, Core.java:5159). Core's key is `serverKey(host, port)` = `"<host>:<port>"`
  (`allinone-common/.../ServerKey.kt:4`).
- To store for "Rejoin <server>": host (as typed), game port, user name (and optionally the DB ids), a display name
  (`server:getName()`/Steam title), and when written (so the offer is not shown in the launch that wrote it). No
  password. Joins whose server is not in the saved list (server browser without saving, `+connect`) have nothing to
  rejoin with; the browser path creates an account entry first (`MultiplayerUI.lua:1474-1508`).
- Where: `ServerStore` (`MS:client/download/ServerStore.kt:15-56`) holds `servers.json` `{format:1, servers:[...]}`
  and **throws on any other format** (:42), so extending it needs a format bump (older readers disable the feature) or
  a separate file next to it under `Cache.root`.
- Showing it: `OnMainMenuEnter` (Zabric's precedent, `Zabric_RestartNotice.lua:49-51`) also fires at the end of every
  `Core.ResetLua`, including the join's `"client"` reset (Core.java:4183), so the offer needs its own "once per launch
  / not during a join" guard. The vanilla `+connect` path is in `LoadMainScreenPanel` (`MainScreen.lua:1801-1819`).

## 8. R7: two servers, two versions of one jar

| State | Scope | Conflict |
|---|---|---|
| Cache trees | per `<host>_<port>/<item>/<timestamp>` (`Cache.kt:21-23`) | none |
| `servers.json` | per `host:port` | none |
| Launch override request | **one file per side**, item id → folder (`OverrideRequest.java:50-52`); `request` replaces the pending map (`LaunchOverride.java:38-41`, `OverrideRequest.java:58-73`) | two servers needing the same item: last request wins; requests for different items must be merged by the caller (`pending()`) |
| This launch's override | applies to every pass of the launch (main menu, single player, any server) (`LaunchOverride.java:14-21`) | joining the other server in that launch needs another restart |
| Loaded classes | one version per package per JVM (java-mod-restart.md §2) | moving between the servers always restarts (R2) |
| `widen-roots.txt` | list of folders; scan keeps every list version by hash | none (union of lists) |
| ZombieBuddy approvals | by jar hash | none |
| `config.json` preload entry | one per mod id | conflicts (section 5) |
| Rejoin record | as designed (one slot or one per server) | one slot: last wins |

## 9. Core design/implementation vs the mod-sync-java PRD

Contradictions or mismatches:

- R2's condition "package already loaded, but not by the startup pass → restart" also matches a package loaded
  **from the same tree** earlier in the launch (a second join to the same server after leaving), and R3's "loaded by
  the startup pass" matches the override launch itself, whose startup pass loaded the tree jar. Taken literally both
  loop into restarts; the condition that matters is whether `servingJar(pkg)` is the tree's jar (by path or hash).
- R1 says "the join's pass loads the cached jar through the swap"; after the pass the core has no session
  (`ModSyncClient.kt:112`) and its stop path (`Connect` screen + `failJoin`) is built for the hold, not for after
  `ResetLua("client")` (section 3).
- The core decision point runs on the worker; the Zabric accessors are main-thread (section 1).
- R5 is already implemented by the core (`Refusal.kt`, core R9 second half); Zabric's override also refuses
  ZombieBuddy/Zabric/ZabricKotlin (`OverrideRequest.java:33`), not All-in-One.
- R6/D11: the core dialog lists only items that download something and is skipped for remembered servers and when
  nothing downloads (`JoinSession.kt:169-177`, `JoinStatus.kt:29`); a jar change from a remembered server, or from a
  reused tree, would never be marked.
- `Plan.jarChanges` counts every `.jar` (server-only jars, jars no `mod.info` names, mods not in the server's list);
  the PRD speaks of "the mod's Java package".

Not covered by the PRD:

- Widening (section 4): a fresh tree's changed list is not in effect at the join, and R2's rejoin needs
  `WidenRoots.add` before the restart.
- Preload mods inside an item (section 5).
- After an R1 load, leaving the server keeps the server's classes for the rest of the launch while Lua returns to the
  client's copy; single player or a server with the client's version then runs mixed (shadowed jar, nothing reports
  it, java-mod-restart.md §2) unless the client stops/restarts there too.
- Hooks after the join's pass only run while `Dont39AllInOne` is in that pass (section 3).
- `JavaMods` throws on a launch where Zabric did not run at game start (`Lifecycle.requireRunning`): the join then
  needs a reason (the first launch after installing Zabric).
- Rejoin needs a saved server entry and account; `+connect`/browser joins without one, and accounts without a saved
  password (vanilla asks).
- Rebuilding a tree whose jar this JVM holds open (`Cache.build` deletes the old tree, `Cache.kt:69-70`) may fail on
  Windows (**not confirmed**).
- `OnMainMenuEnter` fires on every `ResetLua`, including the join's (section 7).

## Approach (from the above)

1. In the hold, keep per item the tree, the plan's jar changes and, on the game thread before `Ready`, classify each
   Java mod of the item (own `mod.info` parse + `getModVersionDirName`): refuse preload mods (5a) unless Zabric grows
   5b; compare the tree jar's widening lists with the client copy's.
2. Decide per Java mod with `JavaMods.servingJar(pkg)`: null → R1 (load at the join); the tree's jar → nothing;
   another jar with phase `LATER` → R2; `PRELOAD`/`STARTUP` → R3. A changed widening list on a fresh tree → restart
   as R2 (4a/4b), with `WidenRoots.add`.
3. Restart cases: download, `WidenRoots.add`, `LaunchOverride.request` (merged with `pending()`) for R3, write the
   Rejoin record, stop the join with the reason and a Quit button on the Connect screen.
4. R1 check after the pass (`loadMods(List)` RETURN or `ResetLua("client")` RETURN): `servingJar(pkg)` must be the
   tree jar; otherwise leave the join the vanilla exit-join way (`backToSinglePlayer` + `ResetLua("default",
   "exitJoinServer")`, on a later frame) and show the reason on the main menu.
5. Main menu: offer "Rejoin <server>" once per launch from the record via `MultiplayerUI:connectToServer` with the
   matched saved `Server`/`Account`.

## Caveats / Not Confirmed

- Not run in game: every post-pass behavior (hooks open after the join's pass, OnConnectFailed visibility after
  `ResetLua("client")`, a stop from `ResetLua` RETURN, nested `ResetLua`), the ImGui approval during the join, the
  preload traces in section 5, Windows locking of a loaded jar.
- `OnConnected` ordering between vanilla's handler and a mod's handler was not checked.
- Whether `AccountDBHelper` entries can be matched reliably by host string (typed host vs resolved `getIp2`) was read
  from Lua, not tested.
- ZombieBuddy source is the main checkout's 2.3.4, matching the installed agent; a newer ZombieBuddy may differ.
