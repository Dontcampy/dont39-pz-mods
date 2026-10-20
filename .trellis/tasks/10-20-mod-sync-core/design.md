# Design: mod sync core

Contracts (trailer, HTTP, cache, swap, failure model) are the parent's
[design.md](../10-20-server-mod-sync/design.md); this file adds the module's shape.

## Module

`mod-sync/` (package `com.dont39.pz.modsync`, facade `ModSync.kt`), modeled on `tunnel/`. Game-free parts are plain
classes with JUnit tests; game-facing parts are hook objects and thin adapters. The sandbox option and its texts are in
`mod-sync`'s own `sandbox-options.txt` and `Sandbox.json`, which the All-in-One build merges with the other
features' (`MergeMedia`). JSON uses
kotlinx-serialization, which ZabricKotlin ships. Network and bad-server-data failures are `SyncException`
(an `IOException`): they stop the join; anything else disables the feature.

| Part | Game-free | Notes |
|---|---|---|
| `Trailer` | yes | encode/decode `[payload][int][long magic]`, payload v1 = version byte + port; base address from host + port |
| `Manifest` | yes | build from a folder walk, JSON read/write (format 1), lookup by item id + timestamp |
| `Plan` | yes | per item: local files by hash vs manifest → copy list, download list, sizes, jar change |
| `SafePath` | yes | normalize manifest paths against a tree root |
| `ServerStore` | yes, folder injected | `servers.json`, as `TunnelStore` takes its folder |
| `HttpService` | yes | `jdk.httpserver`; serves the manifest bytes and files by hash from a hash → path map |
| `Fetcher` | yes | `java.net.http` worker: manifest, then files into a temp file, verify, move into the tree |
| `Refusal` | yes | items never taken from a server (R9) |
| `JoinSession` | yes, Steam lookup and log injected | one per join: location, marked items, state machine polled from `CheckMods` |
| `ModSyncServer`, `ServerHooks` | no | `GameServer.startServer` RETURN: option, co-op check, manifest from `GameServer.WorkshopItems` and their install folders, start `HttpService`; `ConnectionDetails.write` RETURN: append trailer |
| `ModSyncClient`, `ClientHooks`, `Swap`, `JoinStatus` | no | `ConnectionDetails.parse` HEAD (in `ServerHooks`), `WorkshopItem.setState` HEAD, `ConnectToServerState.CheckMods` HEAD (cancellable), `ZomboidFileSystem.getInstalledItemModsFolders` / `getModDir` / `Reset` RETURN, leave hooks; the Lua globals |
| Lua | — | `Dont39AllInOne_ModSyncJoin.lua`: confirm dialog and progress line on the Connect screen, one poll |

## Join State Machine (`JoinSession`)

`Idle → Manifest → Plan → (Confirm) → Download → Ready | Failed`, advanced by the `CheckMods` hook each frame:

- No marked items: the hook does nothing (vanilla `CheckMods` runs).
- Not `Ready`/`Failed`: cancel; vanilla calls `CheckMods` again next frame
  ([workshop-hook-points.md §4](../10-20-server-mod-sync/research/workshop-hook-points.md)).
- `Ready`: swap on, reset the folder list and id caches, let vanilla run.
- `Failed`: end the join (`MainScreenState.setConnectToServerState(null)`, `exit()`), `OnConnectFailed(reason)`,
  `forceDisconnect`, cancel; swap stays off.
- The worker thread only writes the session's state; game objects are touched on the game thread only.

The session is created at `parse` HEAD and dropped on join failure, on leaving, and when the next join starts.

## Hook Details

Signatures, descriptors and citations: [research/spike.md](./research/spike.md).

- Mark: `@ModifyVariable` at HEAD on `ConnectToServerState$WorkshopItem.setState`; reads the item's `id` and `error`.
  Returns `Ready` for a taken-over `VersionMismatch`, and clears `error`. In Kotlin the target is written
  `"zombie.gameStates.ConnectToServerState\$WorkshopItem"`.
- Swap: RETURN on `getInstalledItemModsFolders(List)`, replacing entries in the list argument while the swap is on.
  It can run off the game thread (`getAllModFolders` is synchronized and called from file loading), so the swap state
  is an immutable value in a volatile field: the one exception to "game objects on the game thread only".
- Swap off (each at HEAD): `Core.ResetLua(String, String)` when the first argument is not `"client"` (main-menu resets,
  the Lua `exitJoinServer` exits); `IngameState.exit()` (the in-game leave reloads mods without `ResetLua`);
  `ConnectToServerState.exit()` when the join ends before `ResetLua("client")` (vanilla `CheckMods` failure, ours, the
  `update()` catch). `ResetLua("client", …)` from `receiveServerOptions` only records that the session load started.
- The `CheckMods` hold also ends the join when `GameClient.connection == null` (Back button, lost connection): vanilla
  keeps polling `ConnectToServerState` then, so the hold would otherwise spin forever.
- Ending a join: `MainScreenState.getInstance().setConnectToServerState(null)` and `join.exit()`, both public, which is
  what vanilla's `Exit` state leads to one frame later (`MainScreenState.update`). Not by writing
  `ConnectToServerState.state`: its enum `$State` does not widen at runtime
  ([spike §4](./research/spike.md#4-what-cannot-be-widened-at-runtime)).
- Cache resets, on and off alike: `ZomboidFileSystem.instance.resetModFolders()` and `ChooseGameInfo.Reset()` (both
  public). Never `ZomboidFileSystem.Reset()`: it also clears the loaded file map, mods list and `ActiveMods`.
- The id → folder map (`ZomboidFileSystem.modIdToDir`, private) is not cleared: `ZomboidFileSystem` is loaded before
  Zabric's widener, so it cannot be widened ([spike §4](./research/spike.md#4-what-cannot-be-widened-at-runtime)).
  Instead every swap on and off marks the map stale, and a RETURN hook on `getModDir(String)` (`always`) returns null
  while it is stale; a RETURN hook on `ZomboidFileSystem.Reset()` (`always`) clears the mark. With null,
  `ChooseGameInfo.getModDetails` scans the current folder list, so a stale entry (the main menu's install folder during
  the join's `CheckMods`, a tree folder after a failed join) is never used. The only other client reader of
  `getModDir`, `loadMod`, runs only from `loadMods` after a `Reset()` (`Core.ResetLua`, `IngameState.exit`;
  `GameWindow.init` before any swap). The failed-join path back to the main menu runs no `Reset()` (Connect screen's
  Back is `backToSinglePlayer()` only), which is why the mark lasts until the next one.
- Widening (`.classtweaker`): `$WorkshopItem` and its `id`, `serverTimeStamp`, `error`, `details`; `accessible class`
  for `$WorkshopItemState`. Nothing on `ZomboidFileSystem` or `ConnectToServerState`'s own members.
- Server: `GameServer.workshopInstallFolders` / `workshopTimeStamps` are null in `-nosteam` mode: no Workshop items.
- Watch in game: for the frame between swap on and `ResetLua("client")`, swapped items' install paths drop out of
  `allowedPrefixes` ("Invalid prefix found" in `console.txt`).

## Server Manifest

- Built at `startServer` RETURN on the game thread, before the HTTP port opens; SHA-256 of every file under each item's
  `mods/`. Logged with counts, total size and time. A missing install folder or read error disables the feature.
- The hash → path map is the only thing `HttpService` serves.

## UI

- The Lua polls `Dont39AllInOne_getModSyncJoin` (keys in `JoinStatus.kt`) from `OnFETick` at most four times a second,
  and answers through `Dont39AllInOne_answerModSyncJoin`. `OnFETick` runs on every frame the join holds:
  `MainScreenState.update` (MainScreenState.java:783-792) returns before it only on the frame
  `ConnectToServerState.update` returns `Continue` (the join ends); a held `CheckMods` returns `Remain`, so the event
  fires after it in the same frame. It is the only driver of `ConnectToServerState.update`, so the Lua ticks whenever
  the join does. No UI wrap is needed.
- The join never depends on the Lua to end: Back (`backToSinglePlayer` → `GameClient.doDisconnect`, which nulls
  `GameClient.connection`) ends a held join at the next poll in any phase. A Lua error answers "failed" whatever the
  phase; the Java side keeps that for the game session, so a confirm reached later stops at once with the dialog's
  reason instead of waiting for Back.
- Progress: one line on the Connect screen, e.g. "Downloading the server's Workshop items: 3 / 12 files, 1.2 / 4.0 MB",
  written to `ConnectToServer.instance.connectLabel` as vanilla's own state handler does. Not the
  `OnConnectionStateChanged("FormatMessage", …)` route: that reads `UI_servers_<message>`, outside this mod's key prefix.
- Confirm dialog: a Lua panel listing the items that download anything (Steam's title, else the id), their sizes, and a
  "don't ask again for this server" box; the join holds in `Confirm`. Mouse only.
- Texts in `mod-sync`'s `Translate/EN` and `CN` `UI.json`, keys
  `UI_Dont39AllInOne_ModSync_*`; the reason texts are chosen in `JoinSession` (`Step.Stop.key`).

## Decisions Made in Implementation

- Cache trees end in the item id, `items/<host>_<port>/<item id>/<timestamp>/<item id>/mods/...`: the game takes a
  mod's Workshop id from the name of the folder above `mods/` (`ChooseGameInfo$Mod` constructor, via
  `SteamUtils.isValidSteamID`), so a tree ending in the timestamp would report the timestamp as the Workshop id.
  Root `ZomboidFileSystem.instance.getCacheDir()` + `Dont39AllInOne/ModSync`, i.e. under `Zomboid/` (the folder
  `TunnelStore` also uses), which `allowedPrefixes` admits.
- The client's own install folder: `SteamWorkshop.instance.GetItemInstallFolder(id)`, read on the game thread at the
  first `CheckMods` poll; null for an item Steam reported installed is an error.
- `always = true` on `parse`, `CheckMods`, `ConnectToServerState.exit`, the swap and the leave hooks: once the feature
  is off, a join with taken items must stop instead of going on with the client's own copy (the hold fails closed,
  error-handling.md), and the swap must stay consistent with what the session loaded until it turns off. `setState`,
  `startServer` and `write` stay gated. `parse` also turns off a swap left on (a `Finish` failure after
  `ResetLua("client")`), so the next join resolves the client's own copies.
- The hold catches everything in `ModSyncClient.hold`: an error disables the feature and stops the join.
- Swap on verifies through `getInstalledItemModsFolders` that each install folder is gone and each tree listed; a tree
  without `mods/` removes the entry instead of replacing it (vanilla adds only existing folders).
- R9: refused by Workshop id (ZombieBuddy 3619862853, Zabric 3820726385, ZabricKotlin 3820734224, Dont39 All-in-One
  3818388200) or by a mod folder of that name in either copy (`Refusal.kt`). Jars are compared also for a tree that is
  ready, on a jars-only scan, since the client's own copy may have changed after the tree was built.
- `jdk.httpserver` starts its dispatcher as a non-daemon thread when started from the main thread, so `HttpService`
  starts it from a daemon thread; nothing needs stopping at server shutdown.
- Widening adds `$WorkshopItem.details` (Steam's title for the dialog and the reasons).
