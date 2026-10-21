# Design: mod sync Java mods

All of it is in `mod-sync` (client side) plus its Lua and texts. Zabric is used only through its public API
(`com.pzlauncher.zabric.mods`). Research: [java-integration.md](./research/java-integration.md) (cited as §n).

## Flow

```
CheckMods hold (game thread polls, worker builds)
  prepare (worker): manifest, refusal (R4), preload refusal on the client copy (R4b), plan; jar changes are kept
                    per item instead of stopping the join
  prepared (game): confirm when downloads and not remembered, or when any item changes a jar (J4) → build
  built / no builds (game): decide per item that changes a jar
      item's tree is this launch's override (LaunchOverride.current) → check the served jars (R2) → swap on, vanilla
      otherwise → restart path (R1)
restart path (game): preload refusal on the tree (R4b), LaunchOverride.request(pending + items), Rejoin record,
                     stop with Failed_Restart; the Connect screen shows "Quit game"
next launch: Zabric applies the override from premain; the main menu offers Rejoin (R3); the join finds the item in
             LaunchOverride.current() and proceeds
```

## Hold changes (`client/JoinSession.kt`)

- `prepare` no longer throws `Failed_Jar` (`JoinSession.kt:146-151`). `Work` (both kinds) carries `jarChanges` from
  the plan; a ready tree is still compared on its jars only, as today.
- Preload refusal before downloading: for an item that changes a jar, the client copy's mods that `ModInfo` reads as
  preload (below) refuse the item with `Failed_Preload` (mod name). An item whose jars are the same needs no
  override and keeps the core's swap path. The tree is checked again on the restart path, after the build, since the
  server's `mod.info` may differ.
- `prepared`: Confirm also when any item has jar changes, whatever `remembered` and even with nothing to download
  (J4). The answer `REMEMBER` keeps meaning "don't ask for downloads without jar changes". No confirm when every
  jar-changing item is already this launch's override (the Rejoin of an answered join).
- `ready` (game thread, after `built` or directly): runs the Java decision; returns `Ready(entries)` or a `Stop`.
  `JavaMods` / `LaunchOverride` are main-thread APIs (§1), which is why the decision is not in `prepare`.

## Java decision (new `client/java/`)

Per item with jar changes, `tree` = the item's tree `mods` folder:

| Case | Outcome |
|---|---|
| `LaunchOverride.current()[id]` is `tree` | For each Java mod of the tree: `JavaMods.servingJar(pkg)` the tree's jar → fine; another jar → `Failed_NotServed` (mod, the jar that runs); null and the mod in the main menu's mod list → `Failed_NotLoaded` (mod, item); null otherwise → fine. Then the swap goes on as today. |
| anything else | restart path |

- Null with the mod in the main menu's list means the loader skipped the tree's jar at the override launch's startup
  (e.g. its approval was declined). The list is `ActiveMods.getById("default").getMods()`: `loadMods("default")`
  fills it from `default.txt` and passes that same list to `loadMods(List)`, where ZombieBuddy reads it, at startup
  and at every later main-menu pass. Not `ZomboidFileSystem.getModIDs()`: it also holds mods only `require=` pulls in,
  which ZombieBuddy never looks at. Any other null: the join's pass loads it from the tree, checked after the pass
  ("Accepted" below).

- "Java mod of the tree": a mod folder whose `mod.info` (version folder via `ZomboidFileSystem.getModVersionDirName`,
  else `common`) has `javaJarFile` + `javaPkgName`, side folder `media/java/server/` skipped on the client
  (ZombieBuddy's rules, §2). Parsed by a small `ModInfo` reader in mod-sync (no public parser exists, §2); unit
  tests against fixtures.
- Preload: `javaPreload=true`, or the jar's manifest has `ZB-Preload: true` or `Zabric-PreLaunch`.
- A jar-changing item without any Java mod (a jar no `mod.info` names) still takes the restart path: the jar may be a
  library another mod's jar names, and one path keeps the rule simple (J1).
- A throw from Zabric's API goes through `Broken`. A launch where Zabric did not run at game start never gets here:
  Zabric then runs no entrypoints and closes every mod's hooks (`Lifecycle.onZabricMain`), the hold included.

## Restart path

1. Preload refusal on the tree (R4b): `Failed_Preload`.
2. `LaunchOverride.request(pending() + {id → tree} for every item of the join that changes a jar)`, also those this
   launch already takes from their tree, which the next launch needs too: other items' pending requests (another
   server, R6) stay; the same item from this server replaces an older tree.
3. Rejoin record (below).
4. Stop with `Failed_Restart` (the item labels) through the core's `ModSyncClient.stop`, plus a status flag for the
   Lua: `Dont39AllInOne_getModSyncJoin()` returns `{state = "restart", stop = n}` after such a stop until the next
   join or a reset of the mods; `stop` counts the stops, so the screen hides the button once Back or a new connect left
   that stop's screen, and the Connect screen shows the reason and a "Quit game" button (`getCore():quitToDesktop()`, the vanilla
   main menu's way, §6).

The download is already done (the trees are built), so the next launch's startup pass finds every file in place.
The tree is an override folder, so Zabric's premain widening scan covers its lists (Zabric child, R4).

## Rejoin (R3)

- `<cache>/Dont39AllInOne/ModSync/rejoin.json`, its own file (`ServerStore` throws on another format, §7):
  `{"format":1,"host","port","user","name","written"}`; `written` = this JVM's start time
  (`ManagementFactory.getRuntimeMXBean().startTime`), so the launch that wrote it does not offer it. One record: the
  last restart wins (R6 needs a restart per server anyway). Written atomically with the shared helper.
- Main menu (`OnMainMenuEnter`, once per launch, never while a join runs; it also fires at every `ResetLua`, §7): a
  dialog "Rejoin <name>?" with Rejoin / Not now (its own panel: `ISModalDialog` does not wrap text). Rejoin finds the saved `Server` by `getIp()` + `getPort()` and its
  `Account` by user name in `getServerList()` and calls `MultiplayerUI:connectToServer(server, account)`, which asks
  for a password the account does not save. No saved entry: a message to join from the server list. The record is
  deleted when the offer is answered either way.
- Lua globals (`Glue.kt`): read the offer, drop it.

## Confirm dialog (R5)

`JoinStatus` confirm items gain `java = true` for an item with jar changes, and the list includes such items even when
they download nothing (a reused tree). The Lua adds a line "contains Java code that will run on your computer" per
marked item.

## Texts

New keys in `mod-sync/src/main/lua/shared/Translate/{EN,CN}/UI.json`: `Failed_Restart`, `Failed_Preload`,
`Failed_NotServed`, `Failed_NotLoaded`, `ConfirmJava`, `ConfirmItemNoDownload` (a Java item that downloads nothing),
`QuitGame`, `RejoinTitle`, `RejoinText`, `Rejoin`, `NotNow`, `RejoinNoEntry`. `Failed_Jar` is removed.

## Failure model

Every case stops the join with a reason ([error-handling.md](../../spec/mod/error-handling.md)); a Zabric API mismatch
or an unexpected exception goes through the core's `Broken` path (feature disabled). An override that did not apply
shows up as `Failed_NotServed` or, for a mod not loaded at startup, as the restart path again; the log names which.

## Accepted, not handled

- A ZombieBuddy approval denied at this join's pass for a mod not on the main menu (override launch): ZombieBuddy
  skips the jar and the mod's Lua runs without it, as for any Java mod the player denies. mod-sync logs it after
  the pass (`Core.ResetLua("client")` RETURN; `JavaMods.servingJar` not the tree's jar, for mods in
  `GameClient.serverMods`) as a "problem:" line naming the mod (the facade has no error level); no stop (stopping
  after the pass is not clean, §3).
- The override launch keeps the server's copy in single player and other servers too (one version per JVM); a join
  to a server whose copy differs takes the restart path.
- A Workshop update of the item while its tree is loaded: Steam updates the client copy, the tree stays.

## Compatibility

- Needs Zabric with `com.pzlauncher.zabric.mods` (the version after 0.4.1). The existing `require=\Zabric` is
  enough, with Zabric released first (the parent's integration review).
- Servers are unaffected: the wire format and the manifest do not change.
- Players without the feature keep vanilla behavior.

## Risks

- The player starts the next launch the usual way (no relaunch, parent D9), so it gets that entry's arguments, not
  this launch's. Only the cache folder matters: a next launch with another `-cachedir` finds no request, and the
  Rejoin takes the restart path again (the log names the missing override). Zabric's `-cachedir` detection is fixed
  on `claude/cranky-pike-68a148` (not merged); until then a `-cachedir` client keeps the request under `~/Zomboid`.

- Rejoin matching by typed host (`getIp()`) was read from Lua only (§7).
- Deleting an old tree whose jar this JVM holds open may fail on Windows (`Cache.build`, §9): the build then fails with
  an IOException, i.e. `Failed_Network`-style stop naming the path. Seen only if a server changes version again while
  its previous tree runs.
