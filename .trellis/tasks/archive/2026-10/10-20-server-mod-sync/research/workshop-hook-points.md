# Research: where to intercept the join's Workshop step

- **Query**: where a Zabric hook should intercept Build 42's join-time Workshop step so that a Workshop item whose
  installed version differs from the server's does not fail the join and is instead marked "take from server";
  the full `ConnectToServerState` state machine, every error path, `-nosteam`, hook candidates, and the Lua screen.
- **Scope**: internal (game bytecode via `javap -c -p -l`, vanilla Lua, `zabric/`, `zabric-processor/`, `buildSrc/`)
- **Date**: 2026-10-20

Java citations are `Class.method (File.java:line)` from the LineNumberTables of
`E:/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid.jar` (client, Steam build 25485521). The dedicated
server jar (`Project Zomboid Dedicated Server/java/projectzomboid.jar`, build 25485538) has byte-identical
`ConnectionDetails`, `GameServerWorkshopItems`, `ConnectToServerState` and `ConnectToServerState$WorkshopItem` classes
(md5 compared), so the citations hold for both. Lua paths are relative to `ProjectZomboid/media/lua/`.

## Files Found

| File | Description |
|---|---|
| `zombie/gameStates/ConnectToServerState.class` | The join state machine (`public final`) |
| `zombie/gameStates/ConnectToServerState$WorkshopItem.class` | Per-item Steam subscribe/download/check (package-private `final class`) |
| `zombie/gameStates/ConnectToServerState$WorkshopItemState.class` | Item states enum (package-private) |
| `zombie/gameStates/ConnectToServerState$State.class` | Join states enum (package-private) |
| `zombie/gameStates/ConnectToServerState$ItemQuery.class` | Steam UGC details query callback |
| `zombie/network/ConnectionDetails.class` | Server writes / client parses the connection-details packet |
| `zombie/network/GameServerWorkshopItems.class` (+ `$WorkshopItem`) | Server-side Workshop install at startup, computes the timestamps it sends |
| `zombie/network/GameServer.class` | `main` reads `WorkshopItems=` and calls the install |
| `zombie/gameStates/MainScreenState.class` | Polls `ConnectToServerState.update()` every frame |
| `zombie/ZomboidFileSystem.class` | `loadModsAux` / `loadModAndRequired` (CheckMods' mod lookup), `resetModFolders` |
| `zombie/Lua/LuaManager$GlobalObject.class` | `connectToServerStateCallback`, `forceDisconnect` Lua globals |
| `client/OptionScreens/ServerWorkshopItemScreen.lua` | The "Server Workshop Items" screen and the `OnServerWorkshopItems` handler |
| `client/OptionScreens/ConnectToServer.lua` | Join screen; `OnConnectFailed` shows the reason |
| `client/OptionScreens/MainScreen.lua:164-174` | Creates `ServerWorkshopItemScreen` only when `getSteamModeActive()` |
| `shared/Translate/EN/UI.json:1008-1024, 1105-1106` | Error / prompt texts |

---

## 1. The `ConnectToServerState` state machine

### Who creates and drives it

- `ConnectionDetails.parse(ByteBufferReader)` (ConnectionDetails.java:49-56): on the client's `connection-details`
  packet it does `new ConnectToServerState(b)`, `enter()`, `MainScreenState.getInstance().setConnectToServerState(..)`.
- Constructor (ConnectToServerState.java:62-66) copies the whole packet into its own buffer `connectionDetails`
  (`private final ByteBufferReader`) and rewinds it. Every state reads the next part of that one buffer in order, so the
  states are tied to the packet layout.
- `MainScreenState.update()` (MainScreenState.java:783-788): if `connectToServerState != null` it calls
  `connectToServerState.update()`; on `Continue` it calls `exit()` and drops it. Otherwise it falls through and runs the
  rest of the main-menu frame (`OnFETick`, screen elements, line 792 on). So **`update()` is polled once per frame on
  the main thread, and returning `Remain` keeps the UI running**: a state can wait across frames without blocking.
- `ConnectToServerState.update()` (ConnectToServerState.java:78-112): `switch (state)` calls the state's private method,
  returns `Remain`; `Exit` returns `Continue`. Any `Exception` is caught, logged ("StateAction Exception thrown. Handled
  gracefully") and returns `Continue`, i.e. the state machine silently ends without `OnConnectFailed`.
- `exit()` (785-786) clears `instance`. `enter()` (70-73) sets `instance = this`, `state = Start`.

### Fields

```
private final ByteBufferReader connectionDetails;
private State state;
private final ArrayList<WorkshopItem> workshopItems;   // filled in WorkshopInit
private final ArrayList<WorkshopItem> confirmItems;    // filled in WorkshopConfirm
private ItemQuery query;
public static ConnectToServerState instance;
```

### Packet layout (server `ConnectionDetails.write`, ConnectionDetails.java:28-45)

```
writeServerDetails   isCoopHost, maxPlayers, [hostSteamId, serverName], playerId byte, Role     -> Start, TestTCP
writeGameMap         gameMap UTF                                                                -> TestTCP
if (SteamUtils.isSteamModeEnabled())  writeWorkshopItems                                        -> WorkshopInit
writeMods            int n; n x (modId UTF, workshopId UTF, name UTF)                           -> CheckMods
writeStartLocation, writeServerOptions, writeSandboxOptions, writeGameTime, writeErosionMain,
writeGlobalObjects, writeResetID, writeBerries, writeWorldDictionary                            -> Finish
```

- `writeWorkshopItems` (ConnectionDetails.java:74-79): `putShort(GameServer.WorkshopItems.size())`, then per item
  `putLong(id)`, `putLong(GameServer.workshopTimeStamps[i])`. **`workshopItems` / `serverTimeStamp` come from this part.**
- `writeMods` (82-114): for each `GameServer.ServerMods` id, `ZomboidFileSystem.getModDir(id)` +
  `ChooseGameInfo.readModInfo` → writes `(id, mod.getWorkshopID(), mod.getName())`; ids the server itself cannot find
  are written as `(id, "", id)`. So the mods part maps each mod id to its Workshop id (empty when not from the
  Workshop), which is how a Workshop item maps to the mods it carries.

### States

| State | Method (lines) | What it does | Next |
|---|---|---|---|
| `Start` | `Start` (180-192) | reads isCoopHost, maxPlayers, optional host Steam id + server name (`Core.gameSaveWorld`), player id | `TestTCP` |
| `TestTCP` | `TestTCP` (195-225) | reads the Role; debug client without `ConnectWithDebug` → `OnConnectFailed(UI_OnConnectFailed_DebugNotAllowed)`, `forceDisconnect("connect-debug-used")`, `Exit` (202-207). Reads game map. **`SteamUtils.isSteamModeEnabled()` → `WorkshopInit`, else → `CheckMods`** (218-223) | `WorkshopInit` / `CheckMods` / `Exit` |
| `WorkshopInit` | `WorkshopInit` (512-548) | reads `short count`, then `count x (long id, long timeStamp)` → `new WorkshopItem(id, timeStamp)` into `workshopItems` (514-519). Empty → `WorkshopUpdate` (522-525). Else `SteamWorkshop.CreateQueryUGCDetailsRequest(ids, query)` (537); handle 0 → `OnConnectFailed(UI_OnConnectFailed_CreateQueryUGCDetailsRequest)`, `forceDisconnect("connect-workshop-query")`, `Exit` (542-546) | `WorkshopQuery` / `WorkshopUpdate` / `Exit` |
| `WorkshopQuery` | `WorkshopQuery` (600-621) | waits for the Steam query. Completed → copies each `SteamUGCDetails` onto the matching item's `details` (601-608) → `WorkshopConfirm`. Not completed → `ServerWorkshopItemScreen` + `OnServerWorkshopItems("Error", "ItemQueryNotCompleted")` (614-619) | `WorkshopConfirm` / `ServerWorkshopItemScreen` |
| `WorkshopConfirm` | `WorkshopConfirm` (551-596) | `confirmItems` = items whose Steam state is not exactly `Subscribed|Installed` (with `NeedsUpdate` forced on when `details.getTimeUpdated() != GetItemInstallTimeStamp(id)`, 557-564; test 566-567). Empty → `WorkshopUpdate` (571-574). Otherwise fires `OnServerWorkshopItems("Required", <all item ids as strings>)` (588), state `ServerWorkshopItemScreen` (593), then `OnServerWorkshopItems("Details", query.details)` (594) | `WorkshopUpdate` / `ServerWorkshopItemScreen` |
| `ServerWorkshopItemScreen` | `ServerWorkshopItemScreen` (625) | **empty**: waits for Lua. `FromLua("install")` → `WorkshopUpdate`; `FromLua("disconnect")` → `OnConnectFailed("ServerWorkshopItemsCancelled")`, `forceDisconnect("connect-workshop-canceled")`, `Exit` (761-781). `FromLua` throws `IllegalStateException("state != ServerWorkshopItemScreen")` in any other state (761-762) | `WorkshopUpdate` / `Exit` |
| `WorkshopUpdate` | `WorkshopUpdate` (628-648) | per frame: for each item `item.update()`; `Fail` → state `ServerWorkshopItemScreen` + `OnServerWorkshopItems("Error", Long id, item.error)` and return (631-635); any item not `Ready` → return (wait, 637-638). All `Ready` → `ZomboidFileSystem.instance.resetModFolders()` (642), `OnServerWorkshopItems("Success")` (644) | `CheckMods` / `ServerWorkshopItemScreen` |
| `CheckMods` | `CheckMods` (651-682) | reads the mods part (656-663), sets `GameClient.instance.serverMods` (666-667), `missing = ZomboidFileSystem.instance.loadModsAux(serverMods, list)` (669). Missing → `OnConnectFailed("%s [ModID: %s, WorkshopID: %s]")`, `forceDisconnect("connect-mod-required")`, `Exit` (670-677) | `Finish` / `Exit` |
| `Finish` | `Finish` (685-758) | reads the rest: start location (`OnConnectionStateChanged("Connected")`, `GameClient.connected = true`), server options (then `Core.ResetLua("client", "ConnectedToServer")`, 135), sandbox, game time, erosion, global objects, reset id, berries, world dictionary (fires `OnConnected`, 176). Any exception → `OnConnectFailed("WorldDictionary error")`, `forceDisconnect("connection-details-error")` (750-753) | `Exit` |
| `Exit` | — | `update()` returns `Continue`; `MainScreenState` calls `exit()` | — |

Lua bridge: `connectToServerStateCallback(button)` (LuaManager.java:8260-8261) calls
`ConnectToServerState.instance.FromLua(button)`.

### Server side: what timestamp it sends

- `GameServer.main` (GameServer.java:684-752): only `if (SteamUtils.isSteamModeEnabled())` (684) it initialises the
  Steam game server, parses `ServerOptions.workshopItems` (`WorkshopItems=`) split on `;` into
  `GameServer.WorkshopItems` (713-719), runs Steam, and calls `GameServerWorkshopItems.Install(WorkshopItems)` (751); a
  `false` return ends `main` (752). In `-nosteam` mode the whole block is skipped (jumps to line 756), so
  `WorkshopItems` stays empty and `workshopTimeStamps` is never set.
- `GameServerWorkshopItems.Install` (GameServerWorkshopItems.java:208-290): builds one server-side `WorkshopItem` per id,
  `QueryItemDetails` (blocking loop with `Thread.sleep(33)`, 374-411), then loops `item.update()` until all are `Ready`
  (234-273); a failed item is retried up to 3 times by deleting its install folder (241-249). After that, for each id
  (278-287): `workshopInstallFolders[i] = GetItemInstallFolder(id)` and
  **`workshopTimeStamps[i] = SteamWorkshop.instance.GetItemInstallTimeStamp(id)` (287)** — the server's own install
  timestamp, read **once at server start**.
- Server `WorkshopItem.CheckItemState` (GameServerWorkshopItems.java:70-97) forces a reinstall when
  `details.getTimeUpdated() != GetItemInstallTimeStamp(id)` (74-79) and downloads `NeedsUpdate` items (81-85). So at
  start the server installs the latest Workshop version; the timestamp it then sends never changes until restart.
- Consequence: the client compares its own `GetItemInstallTimeStamp(id)` with the server's start-time value. When an
  author updates an item after the server started, every client that has (or downloads) the new version gets
  `VersionMismatch`. Steam only serves the latest version, so the client cannot fetch the server's.

---

## 2. `WorkshopItem` and every path to an error

### Shape (ConnectToServerState.java:306-505)

```
final class ConnectToServerState$WorkshopItem implements ISteamWorkshopCallback   // package-private
  long id; long serverTimeStamp; WorkshopItemState state = CheckItemState;
  boolean subscribed; long downloadStartTime; long downloadQueryTime;
  String error; SteamUGCDetails details;                                            // all package-private
  WorkshopItem(long id, long serverTimeStamp)          (326-329)
  void update()                                        (332-345)
  void setState(WorkshopItemState)                     (348-350)   logs "<old> -> <new>", then state = newState
  void CheckItemState() / SubscribePending() / DownloadPending()
  public void onItemSubscribed / onItemNotSubscribed / onItemDownloaded / onItemNotDownloaded (+ unused callbacks)

enum ConnectToServerState$WorkshopItemState { CheckItemState, SubscribePending, DownloadPending, Ready, Fail }  // (306-311), package-private
```

`update()` switches on `state`: `CheckItemState` → `CheckItemState()`, `SubscribePending` → `SubscribePending()`,
`DownloadPending` → `DownloadPending()`; `Ready` and `Fail` do nothing (default branch).

`SteamWorkshopItem$ItemState` bit values: `None 0, Subscribed 1, LegacyItem 2, Installed 4, NeedsUpdate 8,
Downloading 16, DownloadPending 32`.

### `CheckItemState()` (353-404)

```
353  itemState = SteamWorkshop.GetItemState(id)
355  if (!Subscribed in itemState):
356    if (SubscribeItem(id, this))  setState(SubscribePending); return          357-358
360    error = "SubscribeItemFalse"; setState(Fail); return                     360-362
364  if (Installed && !NeedsUpdate && details != null && details.getTimeCreated() != 0
367        && details.getTimeUpdated() != GetItemInstallTimeStamp(id))
372    itemState |= NeedsUpdate                                                 ("Installed status but timeUpdated doesn't match!!!")
374  if (NeedsUpdate in itemState):
378    if (DownloadItem(id, true, this)) setState(DownloadPending); downloadStartTime = now; return   379-381
383    error = "DownloadItemFalse"; setState(Fail); return                      383-385
387  if (Installed in itemState):
388    timeStamp = GetItemInstallTimeStamp(id)
389    if (timeStamp == 0)               error = "GetItemInstallTimeStamp"; setState(Fail); return   390-392
394    if (timeStamp != serverTimeStamp) error = "VersionMismatch"; setState(Fail); return          395-397
399    setState(Ready); return
402  error = "UnknownItemState"; setState(Fail)                                 402-404
```

### Asynchronous paths

- `SubscribePending()` (408-416): when `subscribed` (set by `onItemSubscribed`, 458-464) and Steam reports `Subscribed`
  → `setState(CheckItemState)`. **No timeout.**
- `DownloadPending()` (420-438): every 100 ms, while `NeedsUpdate`, `GetItemDownloadInfo(id, progress)` → fires
  `OnServerWorkshopItems("Progress", idString, Long downloaded, Long max(total,1))` (432). **No timeout**: it waits for a
  callback forever.
- `onItemDownloaded(id)` (479-485) → `setState(CheckItemState)` (so the timestamp check runs after the download).
- `onItemNotSubscribed(id, result)` (468-475) → `error = "ItemNotSubscribed"; setState(Fail)`.
- `onItemNotDownloaded(id, result)` (489-496) → `error = "ItemNotDownloaded"; setState(Fail)`.

Every `Fail` writes `error` **before** calling `setState(Fail)` (7 sites: 360/361, 383/384, 390/391, 395/396,
402/403, 473/474, 494/495). `setState` is the single funnel for all of them.

### Error list

| `error` | Set in | Text (`UI.json`) |
|---|---|---|
| `SubscribeItemFalse` | CheckItemState:360 | `UI_ServerWorkshopItemError_SubscribeItemFalse` "SubscribeItem() call failed" |
| `DownloadItemFalse` | CheckItemState:383 | "DownloadItem() call failed" |
| `GetItemInstallTimeStamp` | CheckItemState:390 | "GetItemInstallTimeStamp() call failed" |
| `VersionMismatch` | CheckItemState:395 | "Workshop item version is different than the server's" + detail `UI_ServerWorkshopItemErrorDetail_VersionMismatch` "Possibly the server's version is outdated, requiring the server to be restarted.<br>If your version is outdated, try restarting Steam to get the latest version." |
| `UnknownItemState` | CheckItemState:402 | "Unknown item state" |
| `ItemNotSubscribed` | onItemNotSubscribed:473 | "Error subscribing to workshop item" |
| `ItemNotDownloaded` | onItemNotDownloaded:494 | "Error downloading workshop item" |
| `ItemQueryNotCompleted` | WorkshopQuery:618 (not an item error) | no key; see below |
| timeouts | — | **do not exist**: subscribe and download wait indefinitely; the user can only press Disconnect if the screen is up |

### How an error stops the join

1. `WorkshopUpdate` (631-635): first item found `Fail` → `state = ServerWorkshopItemScreen` (inert) and
   `triggerEvent("OnServerWorkshopItems", "Error", Long.valueOf(id), item.error)`.
2. `ServerWorkshopItemScreen.lua:223-241` (`OnServerWorkshopItems`, registered only `if getSteamModeActive()`,
   247-249): text = `getTextOrNull("UI_ServerWorkshopItemError_" .. errorName)`, else
   `getText("UI_ServerWorkshopItemScreen_Error", errorName or "Unknown")` ("Error: %1").
   - Screen **not visible** (every item was already installed, so `Required` never fired): detail =
     `getText("UI_WorkshopSubmit_ItemID") .. " " .. itemID` plus `UI_ServerWorkshopItemErrorDetail_<name>` if any;
     `triggerEvent("OnConnectFailed", error, detail)`; `forceDisconnect()` (230-238). The Java state stays
     `ServerWorkshopItemScreen` until the disconnect tears the client down. `ConnectToServer:OnConnectFailed`
     (ConnectToServer.lua:302-335) shows `message .. "\n" .. detail` in `connectLabel`.
   - Screen **visible**: label becomes the error, Install disabled (240-241). The join waits until the user presses
     Disconnect → `connectToServerStateCallback("disconnect")` → `FromLua` → `OnConnectFailed("ServerWorkshopItemsCancelled")`
     (ConnectToServer.lua:309-311 goes back) + `forceDisconnect` + `Exit`.
3. `ItemQueryNotCompleted` (WorkshopQuery:618) passes only one extra argument, so Lua sees `itemID = "ItemQueryNotCompleted"`,
   `errorName = nil` → "Error: Unknown" with detail "… ItemQueryNotCompleted"; the screen is not visible yet, so it
   becomes `OnConnectFailed` + `forceDisconnect`.

So a `VersionMismatch` on an already-installed item (the usual case) fails the join directly on the Connect screen; it
never shows the Workshop screen.

---

## 3. `-nosteam`

- Client: `TestTCP` skips straight to `CheckMods` when `!SteamUtils.isSteamModeEnabled()` (ConnectToServerState.java:218-223).
  `WorkshopInit` … `WorkshopUpdate` never run, and no `resetModFolders` happens on this path.
- Server: `ConnectionDetails.write` writes the Workshop part only in Steam mode (ConnectionDetails.java:30-31); `GameServer.main`
  never fills `WorkshopItems` in `-nosteam` (GameServer.java:684). Client and server must agree on the mode or the
  client would misread the buffer. (Not confirmed whether a nosteam client can reach a Steam server at all; the
  transports differ.)
- Lua: `ServerWorkshopItemScreen` is created (MainScreen.lua:164-174) and its event handler registered
  (ServerWorkshopItemScreen.lua:247-249) only when `getSteamModeActive()`.
- `CheckMods` with a mod it cannot find: `ZomboidFileSystem.loadModsAux` (ZomboidFileSystem.java:958-964) calls
  `loadModAndRequired` for each id (929-954), which returns the id when `ChooseGameInfo.getAvailableModDetails(id)` is
  null (940-945), or the first missing `require=` id (947-950). `CheckMods` then builds
  `Translator.getText("UI_OnConnectFailed_ModRequired", modIdToModName.get(missing))` = `Mod "<name>" is not installed`,
  formats `"%s [ModID: %s, WorkshopID: %s]"` (ConnectToServerState.java:671-672), fires `OnConnectFailed(text)`,
  `forceDisconnect("connect-mod-required")`, `state = Exit` (673-676). This is also the error in Steam mode for a
  server mod that is not a Workshop item (or whose Workshop item is not in `WorkshopItems=`).
- `loadModsAux` only resolves the list into the output list (it does not activate mods). Mods take effect later in
  `Finish` → `receiveServerOptions` → `Core.ResetLua("client", "ConnectedToServer")` (ConnectToServerState.java:135),
  using `GameClient.serverMods`. (What `ResetLua` does with `serverMods` was not traced here; see `mod-discovery.md`.)

---

## 4. Hook candidates

### Zabric constraints that apply (from the code)

- Injection points are only `HEAD`, `RETURN`, `FINALLY` (`zabric/.../hook/At.java`); no mid-method `INVOKE`. So a hook
  sits at a method boundary.
- `@Hooks(targets = ["zombie.gameStates.ConnectToServerState\$WorkshopItem"])` names a target that is not a class
  literal; the KSP golden test does exactly this for a nested class (`zb-kt-utils-processor/src/test/resources/golden/src/InnerHooks.kt`).
- Target members are collected from all declared methods, private and package-private included
  (`JavacHookReader.target`, JavacHookReader.java:218-240; runtime `TargetCheck.missing` uses `getDeclaredMethods`).
  No repo hook targets a non-public method yet (all current targets are public), so private targets are **supported by
  the generator but not yet exercised in a shipped feature**; ZombieBuddy inlines advice, which works for private
  methods (not measured here).
- Every type the glue names must be public on the compile classpath (`JavacTypes.accessProblem`,
  JavacTypes.java:110-127). `@Self` must be a supertype of the target (`HandlerBinding.selfAndArgs`, 98-110).
- Access widening (`.trellis/spec/mod/access-widening.md`, `ClassTweaker.java`): `accessible class`, `accessible field`,
  `mutable field`, `accessible method` for constructors only. **Methods cannot be widened**, so feature code cannot call
  `WorkshopItem.setState`, `ConnectToServerState.CheckMods` etc.; it can read/write widened fields.
  `accessible field` on a nested class also makes that class and its enclosing classes public, at build time and in the
  expanded runtime list (`ClassTweakerTasks.makePublic`, ClassTweakerTasks.kt:92-104; InnerClasses attribute rewritten,
  229-231). Runtime widening must happen before the class loads (`WidenerTransform.install`, otherwise "already loaded
  before Zabric ran"); `WorkshopItem` / `ConnectToServerState` load only at join time.
- No existing `.classtweaker` in the repo uses `accessible class` or a nested class (only fields of `UITextBox2` in
  `ime-keyboard-fix`), so nested-class widening is supported but untested in a feature.

Widening list the candidates below would need (internal names, JVM descriptors):

```
accessible field zombie/gameStates/ConnectToServerState$WorkshopItem id J
accessible field zombie/gameStates/ConnectToServerState$WorkshopItem serverTimeStamp J
accessible field zombie/gameStates/ConnectToServerState$WorkshopItem error Ljava/lang/String;
accessible field zombie/gameStates/ConnectToServerState$WorkshopItem state Lzombie/gameStates/ConnectToServerState$WorkshopItemState;
accessible class zombie/gameStates/ConnectToServerState$WorkshopItemState          # constants are already public static final
# only for candidates that read/write the join state or the item list:
accessible field zombie/gameStates/ConnectToServerState state Lzombie/gameStates/ConnectToServerState$State;
accessible class zombie/gameStates/ConnectToServerState$State
accessible field zombie/gameStates/ConnectToServerState workshopItems Ljava/util/ArrayList;
accessible field zombie/gameStates/ConnectToServerState connectionDetails Lzombie/core/network/ByteBufferReader;
```

Public members the handlers can call directly: `ZomboidFileSystem.instance.resetModFolders()`,
`LuaEventManager.triggerEvent(String, Object...)` (1-4 args), `GameClient.connection.forceDisconnect(String)`,
`GameClient.instance.serverMods`, `ByteBufferReader.bb` (`public final ByteBuffer`, so a `duplicate()` can peek
without moving the state machine's position), `ConnectToServerState.instance`.

### Candidates

| # | Target / point | Reads / writes | (a) VersionMismatch → "take from server" | (b) own download step, non-blocking | (c) own failure reason |
|---|---|---|---|---|---|
| 1 | `WorkshopItem.setState(WorkshopItemState)` (pkg-private), `@ModifyVariable` HEAD `argsOnly` on `newState` | `@Self` item: reads `id`, `error`, `serverTimeStamp`; returns `Ready` instead of `Fail` | **Yes, for every error path** (all 7 `Fail` sites funnel here and `error` is set first). Returning `Ready` makes `WorkshopUpdate` treat it as done | No (fires once per transition) | No |
| 2 | `WorkshopItem.CheckItemState()` (pkg-private), `@Inject` RETURN | reads `state`, `error`, `id`; writes `state = Ready` | Yes for `VersionMismatch`, `GetItemInstallTimeStamp`, `SubscribeItemFalse`, `DownloadItemFalse`, `UnknownItemState`; **misses** `ItemNotSubscribed` / `ItemNotDownloaded` (set in the public callbacks 468-475 / 489-496, which would need their own RETURN hooks) | No | No |
| 3 | `ConnectToServerState.WorkshopUpdate()` (private), HEAD | sweeps `workshopItems` (widened) for `Fail` | Partly: catches failures set between frames (callbacks), but not those `CheckItemState` sets inside the same vanilla loop (631 checks right after `item.update()`) | Could hold (cancel) while downloading, but `Success` (644) and `resetModFolders` (642) then run only after our hold; the Workshop screen stays up | Possible, same as #4 |
| 4 | `ConnectToServerState.CheckMods()` (private), `@Inject` HEAD `cancellable` | nothing to read for the hold; for failure writes `ConnectToServerState.state` (widened) | No | **Yes**: cancel while our download runs; state stays `CheckMods`, so `update()` calls it again next frame (MainScreenState.java:783-788) with the UI live. Nothing has been read from `connectionDetails` yet at HEAD. When done: `resetModFolders()` (vanilla only called it at 642, before our files existed), then let vanilla run. Also covers `-nosteam` and server-only mods, since every path reaches `CheckMods` | **Yes**: `OnConnectFailed(text[, detail])`, `GameClient.connection.forceDisconnect(reason)`, `state = State.Exit`, cancel (mirrors 670-676) |
| 5 | `ConnectToServerState.update()` (public), HEAD `cancellable` with `CallbackInfoReturnable<StateAction>` | reads `state` (widened) | No | Yes, equivalent to #4 but runs for every state each frame and must test the state itself | Yes |
| 6 | `ConnectToServerState.<init>(ByteBufferReader)` RETURN, or `ConnectionDetails.parse` RETURN (both public) | `connectionDetails.bb.duplicate()` to peek the Workshop part (ids + server timestamps) and, after it, the mods part (id → workshopId → name) | No (too early), but the earliest place to learn which items/mods the join needs and to start a handshake with the server in parallel with Steam | Starts work only | No |

Notes on the candidates:

- After #1/#2 turn an item `Ready`, the client's own Workshop copy (the different version) is still installed and
  subscribed; which copy `CheckMods`/mod loading then picks is the subject of `research/mod-discovery.md`.
- `WorkshopConfirm` (557-567) also sends a client with an older copy through a Steam download first; the mismatch then
  shows after `onItemDownloaded` → `CheckItemState` (395). A client with a newer copy fails immediately.
- With #4, failing through `OnServerWorkshopItems("Error", id, name)` instead of `OnConnectFailed` would reuse the
  screen's own branching (and a mod-supplied `UI_ServerWorkshopItemError_<name>` key would be picked up by
  `getTextOrNull`), but then `state` must be `ServerWorkshopItemScreen`: if the screen is visible and the user presses
  Disconnect while `state` is anything else, `FromLua` throws (761-762).
- An exception escaping a state method ends the state machine via `update()`'s catch (110-112) with no
  `OnConnectFailed`; Zabric's glue already catches handler exceptions and disables the feature, so that only matters
  for vanilla code after a handler changed state.
- The game connection stays open while a hook holds `CheckMods`. Whether the server times out a client that sits
  pre-login for minutes was **not confirmed**; vanilla holds the same way on the Workshop screen (no client-side
  timeout), which suggests the server tolerates it.

### Recommendation

Two hooks, both on `ConnectToServerState` internals, plus the widening list above:

1. **Mark**: `@ModifyVariable(method = ["setState"], at = At("HEAD"), argsOnly = true)` on
   `@Hooks(targets = ["zombie.gameStates.ConnectToServerState\$WorkshopItem"])`: when `newState == Fail` and the
   item's `error` is one the feature takes over (at least `VersionMismatch`; the download errors are the PRD's open
   question), and the server is known to serve that item, record `id` (and, from the mods part, its mod ids) as "take
   from server" and return `Ready`. It is the one funnel for all seven error sites, so the open question about download
   failures is a list change, not another hook. (Candidate #2 plus RETURN hooks on `onItemNotSubscribed` /
   `onItemNotDownloaded` is the equivalent without `@ModifyVariable`.)
2. **Download and fail**: `@Inject(method = ["CheckMods"], at = [At("HEAD")], cancellable = true)` on
   `ConnectToServerState`: while the server download (started on another thread) is running, cancel; per-frame polling
   comes from `MainScreenState.update`. On success call `ZomboidFileSystem.instance.resetModFolders()` and let vanilla
   `CheckMods` run. On failure fire `OnConnectFailed` with the feature's text, `forceDisconnect`, set `state = Exit`,
   cancel. This one point is reached on every path (Steam, `-nosteam`, no Workshop items), so server-only mods fit here
   too if they come into scope.

Optionally candidate #6 to peek the packet and open the server handshake early, so the mark in (1) knows whether the
server can serve the item before `WorkshopUpdate` reaches it.

---

## 5. `ServerWorkshopItemScreen` (Lua)

- **Not shown when all items are already installed and current**: `Required` (which calls `setRequiredItems` →
  `setVisible(true)`, ServerWorkshopItemScreen.lua:154-172) fires only when `confirmItems` is non-empty
  (ConnectToServerState.java:571-594). With an empty `confirmItems` (or no Workshop items at all) the join goes
  straight to `WorkshopUpdate`; the user sees only the Connect screen. That is the usual `VersionMismatch` case.
- When shown, it lists **all** server items (not only the missing ones: `Required` carries every id, 583-588), waits
  for the Install button (`onButtonInstall` → `connectToServerStateCallback("install")`, 143-146), and is hidden by
  `Success` (242-243), which `WorkshopUpdate` fires before `CheckMods` (644).
- Rows are driven by Steam objects: title, size and state come from `item.item.details` (`SteamUGCDetails`:
  `getTitle`, `getFileSize`, `getState`, 23-50), and the progress bar is drawn only when `details:getState() ==
  "Downloading"` and `Progress` delivered `downloaded/total` (52-62, 188-196). A server download has no
  `SteamUGCDetails` and no Steam state, so these rows cannot show it; a row without `details` draws only its text
  (65-66).
- It only exists in Steam mode (MainScreen.lua:164-174) and needs `ConnectToServer` or `CoopOptionsScreen` visible as
  its previous screen (155-160).
- So reusing it for server downloads does not fit: it is absent in `-nosteam`, usually not visible in the mismatch
  case, already hidden by `Success` before a `CheckMods` hold, and its progress UI reads Steam state. A feature screen
  (or a progress line on the Connect screen) is needed. A cheap vanilla route for a text-only status:
  `ConnectToServer:OnConnectionStateChanged` (ConnectToServer.lua:337-365) sets `connectLabel` for state `"Message"`
  (`getText('UI_servers_'..message)`) and `"FormatMessage"` (`string.format(getText('UI_servers_'..message), arg)`),
  so `triggerEvent("OnConnectionStateChanged", "FormatMessage", <key suffix>, <arg>)` updates the Connect screen's
  label (Connect screen only; the co-op screen has its own handlers, not traced).

## Related Specs

- `.trellis/spec/mod/zabric-hooks.md` — hook API rules (no priority, handlers never catch, injection points).
- `.trellis/spec/mod/zombiebuddy-patching.md` — patch classes in `javaPkgName`, HEAD/FINALLY pairing, gating,
  Lua bridge, server configuration (sandbox option read at `GameServer.startServer` RETURN).
- `.trellis/spec/mod/access-widening.md` — classtweaker rules: fields, classes, constructors only; no methods.
- Reference hook: `tunnel/src/main/kotlin/com/dont39/pz/tunnel/ClientConnect.kt` (HEAD `cancellable` on a public method).
- Prior research: `.trellis/tasks/archive/2026-10/10-18-kcp-tunnel/research/connect-failure.md` (`OnConnectFailed`
  semantics).

## Caveats / Not Found

- Private / package-private hook targets and nested-class widening are supported by the generator and build but no
  shipped feature uses them yet; a weave test or in-game smoke test should confirm before relying on them.
- Thread of Steam callbacks (`onItemNotDownloaded` etc.) not traced; assumed main thread via `SteamUtils.runLoop`.
- Server-side tolerance of a long pre-login hold not measured.
- What `Core.ResetLua` does with `GameClient.serverMods`, and which mod copy wins when a Workshop copy and a
  server-downloaded copy share an id: not traced here (see `mod-discovery.md`).
- Kahlua's rendering of the `Long` item id in the `Error` event detail was not checked.
