# Research: loading a server's Java mod jar after a restart

- **Query**: When a joining client must use a dedicated server's copy of a Workshop item whose jar differs from its own,
  how can it download the jar into a per-server cache, prompt a restart, and have ZombieBuddy load the cached jar on the
  next launch (through its approval/signature flow) so the player can rejoin? (PRD D7)
- **Scope**: internal (ZombieBuddy source, Zabric/ZBK source, game bytecode and vanilla Lua; nothing was run)
- **Date**: 2026-10-20

Sources:

- ZombieBuddy: the worktree's `external-repos/ZombieBuddy/` is an **empty submodule checkout**. Everything below was
  read from the main checkout `E:/Dev/Project/pz/dont39-pz-mods/external-repos/ZombieBuddy/` (commit `9a08a54`,
  `java/VERSION` = 2.3.4). The installed `ProjectZomboid/ZombieBuddy.jar` manifest also says `Implementation-Version:
  2.3.4`. Paths `ZB:<file>` below are relative to `java/src/main/java/me/zed_0xff/zombie_buddy/`.
- Zabric/ZBK: this worktree (`zabric/src/main/...`, `zb-kt/src/main/...`).
- Game: `projectzomboid.jar` disassembled with `javap -c -p -l`; citations are `Class.method (File.java:line)` from the
  LineNumberTables. Lua is relative to `ProjectZomboid/media/lua/`.

## Files Found

| File | Description |
|---|---|
| `ZB:Agent.java` | `premain`: agent args, policy lock, built-in patches, `patches_jar`, `Loader.preloadMods()` |
| `ZB:Loader.java` | `loadMods(List)`: the per-pass Java mod resolution, approval, load; `preloadMods`; `addJarToClasspath` |
| `ZB:patches/Patch_ZomboidFileSystem.java` | Advice on the game's `ZomboidFileSystem.loadMods(List)` that calls `Loader.loadMods` |
| `ZB:JavaModInfo.java` | `mod.info` fields ZombieBuddy reads; Workshop id from the jar's path |
| `ZB:ZBSVerifier.java` | `.jar.zbs` Ed25519 signature check, uploader binding |
| `ZB:ModApprovalsStore.java`, `ZB:JarDecisionTable.java` | `~/.zombie_buddy/mod_approvals.json`, decisions keyed by jar SHA-256 |
| `ZB:Config.java` | `~/.zombie_buddy/config.json`: `trusted_authors`, `preload_mods` |
| `ZB:JarBatchApprovalProtocol.java` | The batch approval entry shape (mod id, item id, jar path, sha256, ZBS, flags) |
| `ZB:frontend/ModApprovalFrontends.java`, `ImguiModApprovalFrontend.java` | Which approval UI runs |
| `zabric/.../loader/Loader.java`, `LoadStatus.java`, `PreloadConfig.java`, `ModsLoadedHook.java` | Zabric's loader seam over ZombieBuddy |
| `zabric/.../entrypoint/Lifecycle.java`, `Preload.java`, `PreLaunchHook.java` | Zabric phases; preload registration; restart list entries |
| `zabric/.../hook/impl/HookRuntime.java`, `ModState.java` | Hooks open/close per ZombieBuddy pass |
| `zabric/.../restart/RestartList.java`, `RestartNotice.java`, `src/main/lua/client/Zabric_RestartNotice.lua` | The restart dialog |
| `zabric/.../widener/AccessWidener.java`, `ModFolders.java`, `WidenerScan.java` | Premain widening scan of fixed mod roots |
| `zb-kt/.../Main.java`, `KotlinSwap.java` | ZBK's ZombieBuddy `Main` that swaps kotlin-stdlib |
| `zombie/GameWindow.init`, `zombie/gameStates/MainScreenState.main` | Startup `loadMods("default")`; `+connect`/`+password` parsing |
| `zombie/Lua/LuaManager$GlobalObject`, `zombie/core/znet/SteamUtils` | `getServerAddressFromArgs`, Steam join requests |
| `client/OptionScreens/MainScreen.lua`, `BootstrapConnectPopup.lua`, `ServerConnectPopup.lua`, `ConnectToServer.lua`, `MultiplayerUI.lua` | Vanilla connect paths |
| `ProjectZomboid/ProjectZomboid64.json` | This install's JVM args (ZombieBuddy via `-agentlib:zbNative=policy=allow-all,...`) |

## 1. How ZombieBuddy finds jars

### When

- `Agent.premain` (ZB:Agent.java:19-146): parses agent args (28-37), locks the policy (66), sets
  `allow_unsigned_mods` (69, default `true`), picks the approval frontend lazily (73), applies its built-in patches at
  PREMAIN (89), loads `patches_jar` entries (96-124), reads `config.json` (`Loader.initConfig`, 141), then
  **`Loader.preloadMods()` (144)**. No game API is used for mod discovery at premain.
- `preloadMods()` (ZB:Loader.java:401-465) loads only the ids in `config.json` `preload_mods`, from the **absolute
  `jarPath` stored there**, not through the game. Each needs `javaPkgName` in the stored `infPath` (413-418), the jar
  to exist (422-426) and `ZB-Preload: true` in its manifest (427-431); then a ZBS check (444-449; no approval check)
  and `loadJar(..., Phase.PREMAIN)` (450). Failing entries are removed from `config.json`.
- Every other jar is found by **ZombieBuddy's advice on the game's `ZomboidFileSystem.loadMods(List)`**:
  `Patch_loadMods2.enter` calls `Loader.loadMods(mods)` on entry (ZB:patches/Patch_ZomboidFileSystem.java:31-41).
  It runs on **every** call of `loadMods(List)`, so once per "pass":
  - Startup: `GameWindow.init` calls `ZomboidFileSystem.loadMods("default")` at GameWindow.java:1111, after
    `SteamWorkshop.init` (1091) and `resetModFolders` (1092-1093). `loadMods(String)` passes `ActiveMods "default"`'s
    mods (the main-menu enabled list, `default.txt`) to `loadMods(List)` (ZomboidFileSystem.java:862-877).
  - Joining: `Core.ResetLua("client", "ConnectedToServer")` → `loadMods("client")` → translation mods of `default` +
    `GameClient.instance.serverMods` (ZomboidFileSystem.java:854-859) → `loadMods(List)`
    ([mod-discovery.md §3](./mod-discovery.md)).
  - Leaving: `IngameState.exit` → `loadMods("default")` (IngameState.java:1051), and Lua `ResetLua("default",
    "exitJoinServer")`.
  - `CheckMods` uses `loadModsAux`, not `loadMods(List)`, so ZombieBuddy does not run there.

### Which mods and which folder

`Loader.loadMods(List<String> mods)` (ZB:Loader.java:596-942):

1. Optional `autoFixModOrder` (597-599, pins `ZombieBuddy`, `zdk`, `ZModUnbork` first).
2. Merged list = `preload_mods` ids first, then the pass's ids (615-617).
3. For each id: **`ChooseGameInfo.getAvailableModDetails(mod_id)`** (625), then `JavaModInfo.parse(mod.getVersionDir())`,
   else `parse(mod.getCommonDir())`, else `parseMerged(common, version)` (632-638). So ZombieBuddy resolves folders
   **through the game** (`ChooseGameInfo` → `ZomboidFileSystem.getAllModFolders` → `getInstalledItemModsFolders`), i.e.
   the same resolution the planned swap hooks. It is not its own scan, and it covers only the ids of the pass, not all
   installed mods.
4. All ids of the pass are resolved, hashed (`Utils.sha256Hex(jarPath)`, 703), ZBS-checked (710) and approved
   (723-817) **before any jar of the pass is loaded** (937-941).
5. Load: `loadJar` → `addJarToClasspath` (ZB:Loader.java:474-521): canonical path; returns false if the path is in
   `g_known_jars` (483-485); checks the package exists in the jar (486-489); re-hashes against the approved hash
   (501-510); `Instrumentation.appendToSystemClassLoaderSearch` (512). Then `ApplyPatchesFromPackage(pkg, null, MAIN)`
   (989): runs `<pkg>.Main.main` once per class name (1006-1018) and applies patches only on the first phase that
   loads the package (1021-1023); a package already applied in MAIN returns at 1000-1004 (debug log only).
6. Package duplicates within one pass: the last mod with a given `javaPkgName` wins, earlier ones are skipped
   (650-654, 831-836). ZombieBuddy's own package is skipped (826-829).

### `mod.info` fields (ZB:JavaModInfo.java)

| Field | Use |
|---|---|
| `javaJarFile` | Required, must end with `.jar`, first entry wins (196-207); resolved against the folder holding that `mod.info` (`parse`, 243-256) |
| `javaPkgName` | Required (208-215, 131-134); the package `Main`/patches are loaded from; the dedupe key |
| `ZBVersionMin` / `ZBVersionMax` | Range check against ZombieBuddy's version; outside → skipped with an error (146-149) |
| `name` | Display name in the approval dialog (220-223) |
| `javaPreload` | Must be `true` together with manifest `ZB-Preload: true` for preload (224-225; Loader.java:782-787) |
| path `media/java/client/` / `server/` | Skipped on the other side (135-145) |

Workshop item id: from `/content/108600/<id>/` anywhere in the `mod.info` path (25, 58-70), or, for
`<cache>/Workshop/<x>/...`, from that folder's `workshop.txt` `id=` line (38-55, 72-95). Any other folder (e.g. a
per-server cache under `Zomboid/`) gives **no item id**: then no Steam ban lookup ("Workshop id not found in mod path",
not treated as banned, Loader.java:706-709) and no uploader binding in the ZBS check (Loader.java:113 passes
`workshopItemId != null` as "steam mode").

## 2. What happens today when a server's mod list has a Java mod not loaded at startup, or another version

- **Not loaded at startup → ZombieBuddy loads it late, at the join.** The join's `loadMods(List)` pass resolves the
  server's ids through the game (above), prompts for any new hash, appends the jar, runs `Main.main` and applies its
  patches (retransforming loaded classes, [zombiebuddy-patching.md](../../../spec/mod/zombiebuddy-patching.md) "Patch
  Classes"). Zabric handles a mod entering a later pass: `Lifecycle.modsLoaded` (Lifecycle.java:132-160) →
  `HookRuntime.passProcessed` checks a mod's hooks on its first pass and opens them while the mod is in the current pass
  (HookRuntime.java:46-85, ModState.java:70-120); its main entrypoint is called once (`mainCalled`, Lifecycle.java:167).
  No restart is needed in this case, provided the jar's package was never loaded in this JVM.
- **Same package already loaded from another jar (another version) → silently shadowed.** The new jar path is not in
  `g_known_jars`, so `addJarToClasspath` **appends it** (Loader.java:483, 512), but `ApplyPatchesFromPackage` sees MAIN
  already applied and returns (1000-1004): no `Main`, no patches. Classes already loaded stay; classes not yet loaded
  resolve from the first jar on the system class path; a class present only in the new jar would load from it (mixed
  versions). `g_jarLoadStatus` records the new path with reason `"loaded"` (891-898), so Zabric's `LoadStatus.loaded`
  (LoadStatus.java:185-205) reports both jars for that mod id, and `HookRuntime` does not re-check (`firstPass`,
  ModState.java:70-76). **Nothing in ZombieBuddy, Zabric or ZBK detects or reports this.**
- Zabric's restart list today gets only (a) preload registrations (`Preload.update`, Preload.java:36-57) and (b)
  widening lists that appeared or changed while the game ran (`AccessWidener.problem/report`, AccessWidener.java:85-100,
  ~178-181). Neither covers "the session wants a different jar for a loaded package".
- ZombieBuddy's only restart hint is the watermark when a preload entry is added/removed (Loader.java:364, 610).

## 3. Approval and signatures

### Keying and storage

- Decisions are keyed by **jar SHA-256 only**: `JarDecisionTable` built from stored entries' `jarHash`
  (Loader.java:202-210); lookup `disk.get(sha256)` (166-169). `ModEntry` also stores `id`, `workshop_id`, `time`,
  `author_id` as metadata (ModApprovalsStore.java:50-78), not part of the key.
- Stored in `<configDir>/mod_approvals.json` (ModApprovalsStore.java:34, 97-99); `configDir` = agent arg `config_dir`
  or `~/.zombie_buddy` (Agent.java:152-158). Written only when a dialog entry has `MF_PERSIST` (Loader.java:303-306) or
  under `policy=allow-all` (281-285). Session decisions are kept in memory (`g_sessionJarDecisions`, 302) and reused in
  later passes of the same launch (670-671).
- `config.json` (Config.java:14-39) holds `trusted_authors` (SteamID64 list) and `preload_mods` (`id → {infPath,
  jarPath}`).

### Policy (Agent arg `policy`, locked at premain, Loader.java:57-92, 269-294)

| Policy | New/changed hash |
|---|---|
| `prompt` (default) | Batch dialog (723-817) |
| `deny-new` | Blocked, no dialog (286-289) |
| `allow-all` | Allowed and stored; **ZBS checks are skipped entirely** (89-92, 281-285) |

**This install runs `policy=allow-all`** (`ProjectZomboid64.json` vmArgs `-agentlib:zbNative=policy=allow-all,...`),
so on this machine any jar a server supplies would load without a dialog or signature check.

### ZBS signatures (ZB:ZBSVerifier.java)

- Sidecar `<jar>.zbs` next to the jar (97); three lines `ZBS`, `SteamID64:<17 digits>`, `Signature:<128 hex>`
  (286-317). The signed message is `"ZBS:" + sid + ":" + jarSha256` (197): a signature is per jar hash, so every new
  version is signed separately by the author.
- Public key: the known-authors list, else fetched from the author's Steam profile `JavaModZBS:<64 hex>` (179-195,
  244-274), so verification is online.
- Uploader binding only when the jar's path yields a Workshop item id: the sid must equal the item's uploader
  (174-178). A cache path without `/content/108600/<id>/` skips this binding.
- Missing `.zbs`: allowed as "unsigned" when `allow_unsigned_mods=true` (default), else blocked (99-103). Invalid
  signature: blocked (`zbsBlocked`, Loader.java:117-119, 844-847).

### Would a server-supplied jar need a new approval?

- Different version → different SHA-256 → **no stored decision → a new approval**, unless:
  - the ZBS signature is valid **and** the signer is in `trusted_authors` (and not banned, not Shift-forced): approved
    for the session without a dialog, not persisted (Loader.java:765-773); or
  - `policy=allow-all` (auto) / `deny-new` (blocked).
- The dialog shows in whichever pass first sees that hash: at the startup pass ("Waiting for Java mods approval…",
  Loader.java:802-816) if the next launch resolves the mod to the cache, or mid-join inside `ResetLua("client")` if it
  is first loaded at the join. Frontend `auto` = ImGui when the game window exists (ModApprovalFrontends.java:75-83);
  `ImguiModApprovalFrontend.approvePendingMods` blocks the calling thread while pumping its own frames
  (ImguiModApprovalFrontend.java:41-67). Whether that renders correctly during the join's `ResetLua` is **not
  confirmed**. A denied jar is skipped while the mod's Lua still loads.
- The dialog entry carries mod id, item id (null for a cache path), jar path, hash, signer and notice
  (JarBatchApprovalProtocol.java:171-237), so the player sees the cache path and "unsigned"/signer.

## 4. Making the next launch load a cached jar for one mod id

### What runs before the startup pass

The startup pass resolves every `default.txt` id at `GameWindow.init` (GameWindow.java:1111) before loading any jar,
so only code that runs **before** that call can change which folder (and jar) it uses:

| Runs before the startup pass | Notes |
|---|---|
| ZombieBuddy premain: agent args, `patches_jar`, `config.json` `preload_mods` | `preload_mods` needs `javaPreload=true` + `ZB-Preload: true` (Loader.java:427-431), so ordinary mods cannot use it |
| Zabric `PreMain` (preloaded): `AccessWidener.install`, `Lifecycle.install` (PreMain.java:18-22) | Has the instrumentation (`Loader.instrumentation()`, Loader.java:45-59) |
| Zabric preLaunch entrypoints of preloaded mods, at the entry of `MainScreenState.main` (PreLaunchHook.java:321, 370-375; Lifecycle.java:73-101) | Only mods with a `Zabric-PreLaunch` manifest entry, which Zabric keeps preloaded (Preload.java) |
| **Not** Zabric hooks | A mod's hooks open only after a pass is processed (`HookRuntime.passProcessed`, ModState.`open = inPass && !held && !stopped`, ModState.java:118-120), i.e. after the startup pass's resolution |
| **Not** a non-preloaded mod (Dont39 All-in-One today) | Its jar loads inside the startup pass, after all ids were resolved |

So our feature, as an ordinary Zabric mod, **cannot influence the startup pass**. A `getInstalledItemModsFolders`
swap written as a Zabric hook would be closed during it. Making it work needs one of:

- **A. A Zabric capability**: e.g. hooks of a preloaded mod that may open from preLaunch, or a Zabric-owned
  "folder override for this launch" read from a file at premain/preLaunch. Either is a Zabric API change (the spec
  requires missing capabilities to be added to Zabric, not worked around:
  [zombiebuddy-patching.md](../../../spec/mod/zombiebuddy-patching.md) "Patch Classes", last bullet). Option A plus a
  preLaunch entrypoint would also make the feature's jar a preload mod, registered in `config.json`
  (PreloadConfig.java:145-189), with a one-time restart after install.
- **B. A file layout the vanilla scan already prefers**, with no hook: `getAllModFolders` lists the staged folders
  `Zomboid/Workshop/<x>/Contents/mods` first ([mod-discovery.md §1-2](./mod-discovery.md)), so a copy placed there wins
  over the installed Workshop copy in every pass. ZombieBuddy reads the item id from that folder's `workshop.txt`
  `id=` (JavaModInfo.java:51-53), so ZBS uploader binding applies, and Zabric's widening scans that root
  (ModFolders.java:310). Side effects (**not confirmed in game**): it shows in the in-game Workshop upload list; the game
  marks it `source = "Workshop"` with an empty `workshopId`; it applies to every launch and session while it exists.
- Not usable: ZombieBuddy `preload_mods` for ordinary mods (needs the preload flags); `-modfolders` (a launch arg that
  reorders all roots, MainScreenState.main → `setModFoldersOrder`) only works if we control the relaunch.

### When a restart is needed at all

Only when the mismatched mod's `javaPkgName` is already loaded in this JVM:

1. it is in the main-menu list (`default.txt`) or a preload entry, so the startup pass loaded the client's copy; or
2. an earlier join this launch loaded the client's copy (or another server's).

If neither, the join-time swap the PRD already plans makes the join's pass resolve the mod to the cache, and ZombieBuddy
loads the cached jar late, with the approval prompt (section 2). After a restart in case 2, a plain rejoin works with
the join-time swap alone. Only case 1 needs the next launch's **startup** pass changed. ZombieBuddy's `g_jarLoadStatus`
keeps `id`, `jarPath`, `sha256`, `reason` per jar (Loader.java:183-193), so "is a different jar for this package already
loaded?" can be answered from it. Zabric reads that map by reflection (LoadStatus.java:185-233); exposing the hash or
package needs a new accessor in Zabric's loader seam (the spec forbids `me.zed_0xff.*` outside it:
[zombiebuddy-patching.md](../../../spec/mod/zombiebuddy-patching.md) "Loader Seam").

### Scope of the swap on the next launch

- Java classes cannot be unloaded, so a launch whose startup pass loaded the server's jar keeps it for the whole
  launch: main menu, single-player and other servers all run the server's version of that jar. To keep Lua and Java of
  that mod consistent, the folder swap for that mod then has to stay on for the whole launch, not only for the join
  session.
- Ways to scope it, from what the code allows:
  - **One-shot pin**: a file written before the restart, read by the early code (A or B), deleted or ignored after that
    launch, so only the next launch is affected.
  - **Only when launched to rejoin**: the early code checks the launch's `+connect` target. `MainScreenState.main`
    stores `+connect <ip:port>` / `+password <pw>` in System properties `args.server.connect` / `args.server.password`
    (MainScreenState.main, bytecode 874-953), before `GameWindow.init`. The Lua getter **clears** the property when read
    (`getServerAddressFromArgs`, LuaManager.java:4217-4220), so early code would have to read it first or parse
    `sun.java.command`. This only works if the relaunch passes `+connect` (section 5); a manual restart would not.
  - With either, at every later pass (startup or join) compare "the jar this session's folder has" with "the jar
    loaded for that package"; a mismatch means a restart again.

## 5. Restart and rejoin

- **Zabric's restart dialog only asks.** `Zabric_RestartNotice.lua:107-127` shows an `ISModalDialog` (OK only, `yesno =
  false`) on `OnMainMenuEnter` listing `Zabric_restartMods()` (RestartNotice.java:68-76 ← RestartList.java:38-45). It
  neither quits nor relaunches. The game can quit from Lua with `getCore():quit()` (MainScreen.lua:1851); nothing in the
  repo or the game relaunches the game.
- **Launch args to join**: `+connect <ip:port>` and `+password <pw>` (above). On the main menu
  `LoadMainScreenPanelInt` reads them (MainScreen.lua:1801-1819) → `BootstrapConnectPopup:connect` (Bootstrap
  ConnectPopup.lua:58-93): in Steam mode it queries the server (`steamRequestServerDetails`) and then shows
  `ServerConnectPopup` filled from the saved server list entry for that ip:port (ServerConnectPopup.lua:147-169). **The
  player still clicks Connect** (ServerConnectPopup.lua:203-212). Steam join invites arrive the same way
  (`SteamUtils.joinRequestCallback`, SteamUtils.java:223-230 → `OnSteamGameJoin` → `MainScreenPanelJoinSteam`,
  MainScreen.lua:1823-1844, 2189).
- **Rejoining without a relaunch**: after a manual restart our own main-menu Lua can start the vanilla join directly,
  as the server list does: `MultiplayerUI:connectToServer(server, account)` (MultiplayerUI.lua:834-856) →
  `ConnectToServer:connect(...)` → `serverConnect(...)` (ConnectToServer.lua:218-243), using the game's saved server
  and account (`getServerList()`), so the feature stores no password. It asks for the password when the account does
  not save it (MultiplayerUI.lua:836-843).
- **Relaunch via Steam**: whether a mod can relaunch the game with args is **not confirmed**. `steam://run/108600//<args>`
  and `steam://connect/<ip:port>` exist as Steam URLs, but whether Steam appends them to the user's launch options,
  shows its "launch with arguments" confirmation, or refuses while the game is still running was not checked. It
  matters here because ZombieBuddy is often installed through Steam launch options (`-agentlib:zbNative --`,
  Installation.md:80, 183-184). A relaunch that drops them starts the game without ZombieBuddy. This install puts
  ZombieBuddy in `ProjectZomboid64.json` instead.

## 6. Risks

- **Workshop update of the same item later**: the client's Workshop copy changes on disk; the cache stays the server's
  copy. When the server restarts on the new version, timestamps match and D1 takes the vanilla path; a leftover pin
  would then load the old jar against the new Lua. Pins must be one-shot and checked again at the join (server's
  item version/hash vs cache). Whether Steam can update a jar the running JVM holds open (Windows file lock from
  `appendToSystemClassLoaderSearch(JarFile)`, Loader.java:496, 512) is **not confirmed**.
- **Cleanup**: the cache needs a key that includes the server, item id and the server's version, so it can be dropped
  when that server's item matches the Workshop again (the D1 path for that item) or after it goes unused. A pin left by
  a crash would keep applying until something removes it (with option B it applies to every launch while it exists).
- **Two servers with different jar versions**: one JVM holds one version per package, so moving between them in one
  launch always needs a restart; two pins conflict (only one can drive a launch).
- **Preload mods (Zabric itself, any mod with `Zabric-PreLaunch`)**: their jar comes from `config.json`'s absolute
  path at premain (Loader.java:401-465), not from the game's resolution. `Preload.update` re-registers "the copy the game
  uses this session" (Preload.java:36-57), so pinning one to the cache rewrites `config.json` to the cache path (it then
  persists past the pin) and the next normal launch re-registers the Workshop copy, with a restart notice each time.
- **ZBK / Zabric / ZombieBuddy items**: ZombieBuddy skips its own package (Loader.java:826-829; its jar is the game-folder
  agent). ZBK's swap works only before any `kotlin.*` class is loaded (KotlinSwap.java, "Works only while no kotlin.* class
  is loaded") and it runs from a ZombieBuddy `Main` once per JVM (zb-kt Main.java). Our feature runs on all three.
- **Access widening**: widening lists are read at premain from fixed roots (`ModFolders.roots()`, ModFolders.java:301-312)
  and matched by mod id and list hash (AccessWidener.java ~170). A cache outside those roots gets "outside the folders
  Zabric reads at game start" unless its list is byte-identical to one from an installed copy, so a server version whose
  list differs fails `problem()`. A cache root needs adding to `ModFolders.roots()` (a Zabric change), or option B's
  layout.
- **Security**: a server choosing which jar a client runs is code execution on the client. Under `policy=allow-all`
  (this install) there is no dialog and no ZBS check. Under `prompt` with `allow_unsigned_mods=true` an unsigned jar
  only shows "unsigned". The client could check that the server's jar is ZBS-signed by the same SteamID as the client's
  own copy's `.zbs` / the item's uploader, but ZombieBuddy's verifier is outside Zabric's seam today.
- **Denied approval mid-join**: a skipped jar leaves the mod's Lua running without its Java. Zabric's per-pass loaded
  list (`Loader.loadedThisPass`, Loader.java:79-81) can tell after the pass whether the cache jar was loaded.

## Approach (based on the above)

1. At the join, after the folder decision and before `CheckMods` completes, for each mismatched item with a Java mod:
   if no jar with that `javaPkgName` is loaded in this JVM, do nothing extra. The planned join-time swap makes
   ZombieBuddy load the cached jar in the join's pass, with its normal approval. Verify after the pass that it loaded,
   else fail the join with a reason.
2. If a different jar for that package is loaded: download, write a **one-shot pin** (server key, item id, server
   version, cache path), fail the join with "restart to use this server's version of X", and show it on the main menu
   (like Zabric's notice).
3. After a restart, if the package is not in the startup pass (not in `default.txt`, not preloaded), case 1 applies:
   offer "Rejoin <server>" on the main menu through the vanilla `MultiplayerUI:connectToServer` path. No early hook is
   needed.
4. Only if the mod is in the startup pass does the next launch need a startup-pass swap. That needs a Zabric capability
   (option A), or the staged-Workshop-folder layout (option B), both scoped by the pin to one launch. A main-agent/user
   decision; the simpler alternative is to ask the player to disable that mod in the main-menu list for that launch
   (not checked: a dependent enabled in `default.txt` pulls it back in through `require=`, `loadModAndRequired`).
5. Refuse (fail the join with a reason) for ZombieBuddy, Zabric, ZBK and the feature's own mod.

## Related Specs

- `.trellis/spec/mod/zombiebuddy-patching.md`: loader seam (no `me.zed_0xff.*` outside Zabric's loader package), late
  hooks retransform, missing hook capabilities go into Zabric.
- `.trellis/spec/mod/kotlin-runtime.md`: ZBK swap from its ZombieBuddy `Main`, not preloaded, runs once per JVM.
- `.trellis/spec/mod/access-widening.md`: widening scans fixed roots at premain.
- `.trellis/spec/mod/error-handling.md`: fail-fast, no degraded mode.

## Caveats / Not Found

- The worktree's ZombieBuddy submodule is empty; source read from the main checkout (same 2.3.4 as installed).
- Not confirmed: the ImGui approval dialog during the join's `ResetLua`; whether ZombieBuddy's `Exposer` exposes Lua
  classes of a jar loaded late in the join pass (LuaManager.init runs before loadMods in `ResetLua`, Core.java:4130 vs
  4143).
- Not confirmed: Steam relaunch with `+connect` (URL handlers, confirmation dialog, launch-option merging, running
  instance).
- Not confirmed: option B's in-game behavior (Workshop upload UI, `CheckMods`/server checks against `source =
  "Workshop"` with an empty workshop id).
- Not checked: whether `default.txt` dependents pull a removed mod back in (`loadModAndRequired`).
- Class-path shadowing of a second jar for a loaded package is standard `appendToSystemClassLoaderSearch` behavior,
  read from code, not observed.
