# Spike: leave points, cache resets, hook targets, weave of the risky shapes

- **Query**: implement.md step 1. (a) where the folder swap turns off; (b) which caches to reset when it turns on and
  whether that is safe on the main menu; the open question of `mod-discovery.md` §5 "Timing points"; (c) signatures of
  every hook target and field the design names; (2) do the two risky hook shapes weave; (3) `jdk.httpserver`.
- **Scope**: game bytecode (`javap -c -p -l`, client jar `E:/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid.jar`),
  vanilla Lua, ZombieBuddy source (commit 9a08a54), new tests in `zabric`, `zabric-processor`, `zb-kt-utils-processor`.
- **Date**: 2026-10-20

Citations are `Class.method (File.java:line)` from the LineNumberTables. "Reaches" claims come from a call graph of every
`zombie/**` class (invoke instructions plus each method's own lambdas); it follows declared types only, so virtual
dispatch to subclasses, reflection and Lua are not followed.

---

## 1a. Where the swap must turn off

### All ways back to the main menu once the swap may be on

The swap turns on at the end of the `CheckMods` hold, right before vanilla `CheckMods` runs in the same call. From then:

| Path | What vanilla does | Resolution after it |
|---|---|---|
| Vanilla `CheckMods` fails (server mod not found) | `OnConnectFailed`, `forceDisconnect("connect-mod-required")`, `state = Exit` (ConnectToServerState.java:670-676); next frame `update()` returns `Continue` (107) and `MainScreenState.update` calls `ConnectToServerState.exit()` (MainScreenState.java:783-787) | main menu keeps the default mods loaded (no `ResetLua` ran), but the id caches and folder list were reset by the swap: **must be undone by us** |
| `Finish` fails in its outer catch | `OnConnectFailed("WorldDictionary error")`, `forceDisconnect("connection-details-error")` (ConnectToServerState.java:750-753); `state = Exit` always at the end (756-757). The per-part catches only log (689-747), so `receiveServerOptions` → `ResetLua("client")` (135) may already have run | then the client sits on the main menu with the server's mods loaded (vanilla behaves the same); the next `ResetLua("default"/...)` or the next join rebuilds |
| Exception out of a state method | `update()` logs and returns `Continue` (ConnectToServerState.java:110-112) → `exit()` | as the first row |
| Back button on the Connect screen | `ConnectToServer:onBackButton` → `backToSinglePlayer()` (ConnectToServer.lua:197-200) → `GameClient.doDisconnect` (LuaManager.java:3094-3096): `forceDisconnect`, `connection = null`, `client = false` (GameClient.java:1772-1775). **`ConnectToServerState` is not exited**: `MainScreenState.update` keeps polling it (MainScreenState.java:783-787) until the next join replaces it (`setConnectToServerState`, ConnectionDetails.parse) | a `CheckMods` hold would spin forever: the hold must notice `GameClient.connection == null` and end the join itself (state `Exit`) |
| Server drops the connection during the hold | `UdpEngine.decode` → `addDisconnectPacket` → Lua `OnDisconnect` / `OnConnectFailed(...ConnectionLost)` (GameClient.java:306-330); `UdpEngine.removeConnection` → `GameClient.connectionLost` (UdpEngine.java:357) → `connection = null`, `GameWindow.serverDisconnected = true` (GameClient.java:353-354). `GameClient.update` runs every frame from `GameWindow.logic` | same signal as Back: `GameClient.connection == null` |
| Pre-game exits after `ResetLua("client")` (spawn/world/profession screens) | `backToSinglePlayer()` then `getCore():ResetLua("default", "exitJoinServer")` (CharacterCreationProfession.lua:581-582, MapSpawnSelect.lua:615-616, WorldSelect.lua:101-102) | full rebuild in `Core.ResetLua` with `GameClient.client == false` |
| In-game quit or server disconnect | `IngameState.updateInternal` calls `doDisconnect` (IngameState.java:1395-1398) or clears `connected`/`client` (1439-1440) before returning `Continue`; then `IngameState.exit` | full rebuild in `IngameState.exit`: `ChooseGameInfo.Reset` (1005), `ZomboidFileSystem.Reset` (1039), `init` (1045), `loadMods("default")` (1051), ..., `LoadDirBase` (1082). It does **not** call `Core.ResetLua` |
| Failure while loading the world (`GameLoadingState`) | shows an error UI; the key press there calls `GameClient.Shutdown`, `SteamUtils.shutdown`, `System.exit` (GameLoadingState.java:875-890) | the process ends; nothing to undo |
| Other main-menu resets (mod list change, options, "Force", tutorial, continue) | `Core.ResetLua("default"/"currentGame"/"serversettings", ...)` from Lua (MainScreen.lua:423, 1010, 1169, 1237; ModSelectorModel.lua:296-299; ServerSettingsScreen.lua; JoyPadSetup.lua:1011), `DelayResetLua` → `CheckDelayResetLua` (MainScreenState.java:811) → `ResetLua(String, String)` | covered by the `ResetLua` hook |

Java callers of `Core.ResetLua(String, String)`: `Core.ResetLua(boolean, String)`, `Core.CheckDelayResetLua`,
`ConnectToServerState.receiveServerOptions` (the only `"client"` call); every other call comes from Lua.

### Recommended hook points

1. **`Core.ResetLua(String activeMods, String reason)` HEAD**: when `activeMods != "client"`, swap off. Covers the
   three `exitJoinServer` sites and every main-menu reset. With `"client"` (only `receiveServerOptions`), record that
   this join reached its session resolution.
2. **`IngameState.exit()` HEAD**: swap off. The in-game leave does its own reset without `ResetLua`.
3. **`ConnectToServerState.exit()` HEAD**: when the join ends before reaching `ResetLua("client")` (the flag from 1),
   swap off. Covers vanilla `CheckMods` failure, our own failure, and the `update()` exception path.
4. **In the `CheckMods` hold**: `GameClient.connection == null` ends the session (stop the worker, `state = Exit`, no
   `OnConnectFailed`; the Connect screen is gone or shows vanilla's message). Then 3 runs next frame.
5. **`ConnectionDetails.parse` HEAD** (already in the design): drops any previous session first, which also covers a
   join abandoned before its `CheckMods`.

Each swap-off performs the resets of 1b itself (flag off first, then the resets), so it does not depend on which
vanilla reset follows. A `Finish` failure after `ResetLua("client")` keeps the swap on, matching what vanilla has
loaded, until 1, 2 or 5.

## 1b. Cache resets when the swap turns on (and off)

What holds a resolution, and who clears it:

| State | Where | Cleared by |
|---|---|---|
| `ZomboidFileSystem.modFolders` (`private volatile List`), `allowedPrefixes` | `getAllModFolders` (`public synchronized`, ZomboidFileSystem.java:638-674) | `resetModFolders()` (public, 543-545): `modFolders = null`, `allowedPrefixes.reset()`, nothing else |
| `ZomboidFileSystem.modIdToDir`, `modDirToMod` (both `private final Map`) | `setModIdToDir` = `putIfAbsent` (715); `getModDir` = `modIdToDir.get` (987) | `ZomboidFileSystem.Reset` (498-499), `ZomboidFileSystem.update` (1525-1526); no public method clears only them |
| `ChooseGameInfo.Mods`, `MissingMods`, `Maps` (`private static final`) | `getModDetails` / `readModInfo` | `ChooseGameInfo.Reset()` (public static, ChooseGameInfo.java:48-50), clears exactly these three |

Swap on (and off), on the main thread, from the `CheckMods` hook:

```
<flag> ; ZomboidFileSystem.instance.resetModFolders() ; modIdToDir.clear() ; modDirToMod.clear() ; ChooseGameInfo.Reset()
```

- This is vanilla's own main-menu re-resolution in `ZomboidFileSystem.update` (1525-1528), run from
  `MainScreenState.update` (808) whenever a watched mod folder changes, also during a join; vanilla also calls
  `resetModFolders()` inside the join (`WorkshopUpdate`, ConnectToServerState.java:642). So the set is safe on the main
  menu.
- It does **not** touch the loaded state: `activeFileMap`, the loaded id list `mods`, `ActiveMods`, `fileGuidTable`,
  Lua, scripts, textures. The main menu keeps running on the default mods; `Finish` → `ResetLua("client")` loads the
  session from the swapped resolution.
- **Never call `ZomboidFileSystem.Reset()` here**: it also clears `activeFileMap`, `mods`, `allAbsolutePaths`,
  `canonicalUriMap`, `ActiveMods.Reset()` and `fileGuidTable` (ZomboidFileSystem.java:494-506), which only a full
  reload (`ResetLua` / `IngameState.exit`) may do.
- ~~Needs widening of `ZomboidFileSystem.modIdToDir` / `modDirToMod`~~: superseded, that widening cannot take effect
  (§4). The maps are left alone and a `getModDir` hook ignores them while stale (§4).
- Small window: between swap on and `ResetLua("client")` (one frame), `allowedPrefixes` no longer contains the
  swapped items' own `<install>/mods`; a lazy load of one of those files by the main menu would fail `validatePrefix`
  (ZomboidFileSystem.java:1660-1669). The cache trees themselves pass through the `Zomboid/` prefix. Not observed;
  watch for "Invalid prefix found" in the in-game test.

Main-menu state after leaving: both full paths rebuild from scratch with `GameClient.client == false`
(`Core.ResetLua`: `ChooseGameInfo.Reset` 4114, `ZomboidFileSystem.Reset` 4141, `init` 4142, `loadMods("default")`
4143 ... `LoadDirBase` 4169; `IngameState.exit` as in 1a; `loadMods(String)` takes the server list only when
`GameClient.client`, ZomboidFileSystem.java:854-858). The swap is already off at their HEAD, so they resolve the
client's own copies. Known exception, unchanged: texture packs are never unloaded.

### Open question from mod-discovery.md §5: settled

Between `ChooseGameInfo.Reset` (Core.java:4114) and `ZomboidFileSystem.Reset` (4141) `ResetLua` calls
`AttachedLocations.Reset`, `BodyLocations.reset`, `ContainerOverlays.Reset`, `BentFences.Reset`, `BrokenFences.Reset`,
`TileOverlays.Reset`, `LuaHookManager.Reset`, `CustomPerks.Reset`, `PerkFactory.Reset`, `CustomSandboxOptions.Reset`,
`SandboxOptions.Reset`, `WorldMap.Reset`, `AnimalDefinitions.Reset`, `AnimalZones.Reset`,
`MigrationGroupDefinitions.Reset`, `LuaManager.init`, `JoypadManager.Reset`, `Texture.nullTextures.clear`,
`NinePatchTexture.Reset`, and the `Reset` of `SpriteModelManager`, `TileGeometryManager`, `TileDepthTextureManager`,
`SeamManager`, `SeatingManager` (4115-4139). None of them reaches `ChooseGameInfo.getModDetails`,
`getAvailableModDetails` or `readModInfo` (call graph; `LuaManager.init` reaches 309 methods, `WorldMap.Reset` 377).
The same holds for the window in `IngameState.exit` (1005-1039, the same calls plus `SystemDisabler.Reset` and `Core`
setters). Nothing refills `Mods` from the old folders there. With the swap doing its own resets (1b) the window would
not matter anyway.

## 1c. Hook targets and fields

All targets are in the client jar; the dedicated server jar has byte-identical `ConnectionDetails`,
`ConnectToServerState`, `ConnectToServerState$WorkshopItem`, `GameServerWorkshopItems`
([workshop-hook-points.md](../../10-20-server-mod-sync/research/workshop-hook-points.md)).

| Target | Declaration | Descriptor |
|---|---|---|
| `zombie.network.ConnectionDetails.write` | `public static void write(UdpConnection, ServerWorldDatabase$LogonResult, ByteBufferWriter)`; `LogonResult` is `public class` | `(Lzombie/core/raknet/UdpConnection;Lzombie/network/ServerWorldDatabase$LogonResult;Lzombie/core/network/ByteBufferWriter;)V` |
| `zombie.network.ConnectionDetails.parse` | `public static void parse(ByteBufferReader)` | `(Lzombie/core/network/ByteBufferReader;)V` |
| `zombie.gameStates.ConnectToServerState$WorkshopItem.setState` | package-private `void setState(WorkshopItemState)` in `final class` (package-private) | `(Lzombie/gameStates/ConnectToServerState$WorkshopItemState;)V` |
| `zombie.gameStates.ConnectToServerState.CheckMods` | `private void CheckMods()` in `public final class` | `()V` |
| `zombie.gameStates.ConnectToServerState.exit` | `public void exit()` (only `instance = null`, 785-786) | `()V` |
| `zombie.ZomboidFileSystem.getInstalledItemModsFolders` | `public void getInstalledItemModsFolders(List<String>)`; only adds in Steam mode (548-559); called only from `getAllModFolders`, which is `synchronized` and may run off the main thread (the `allowedPrefixes` lazy value behind `validatePrefix`, called by texture and model file tasks; their threads not traced) | `(Ljava/util/List;)V` |
| `zombie.network.GameServer.startServer` | `public static void startServer() throws ConnectException`; called once from `main` (GameServer.java:864), after `GameServerWorkshopItems.Install` (751) | `()V` |
| `zombie.core.Core.ResetLua` | two overloads: `public void ResetLua(String, String)` and `public void ResetLua(boolean, String)` (delegates); the handler takes both `String`s to pick the first | `(Ljava/lang/String;Ljava/lang/String;)V` |
| `zombie.gameStates.IngameState.exit` | `public void exit()` | `()V` |

| Field | Declaration | Widening |
|---|---|---|
| `WorkshopItem.id` | `long id` (package-private) | `accessible field ...$WorkshopItem id J` |
| `WorkshopItem.error` | `String error` | `accessible field ...$WorkshopItem error Ljava/lang/String;` (written to clear it; not final) |
| `WorkshopItem.state` | `WorkshopItemState state` | only if read |
| `WorkshopItem.serverTimeStamp` | `long serverTimeStamp` | for the manifest check |
| `ConnectToServerState$WorkshopItemState` | package-private `final` enum; constants `public static final` | `accessible class` (the handler's signature names it) |
| `ConnectToServerState.state` | `private State state` | `accessible field ... state Lzombie/gameStates/ConnectToServerState$State;` (to write `Exit`), plus `accessible class ...$State`: a field entry implies its owner class, not its type. `$State` does not widen at runtime (§4); not used |
| `ConnectToServerState.workshopItems` | `private final ArrayList<WorkshopItem>` | only if read |
| `ConnectToServerState.instance` | `public static` | none |
| `GameServer.WorkshopItems` | `public static final ArrayList<Long>` | none |
| `GameServer.workshopInstallFolders` | `public static String[]`; **null in `-nosteam`** (set only in `Install`) | none |
| `GameServer.workshopTimeStamps` | `public static long[]`; **null in `-nosteam`** | none |
| `GameServer.server`, `coop` | `public static boolean` | none |
| `GameClient.ip`, `port` | `public static String ip`, `public static int port` | none |
| `GameClient.connection`, `client` | `public static UdpConnection connection`, `public static boolean client` | none |
| `GameClient.connected` | **instance** field `public boolean connected` (`GameClient.instance.connected`) | none |
| `GameClient.serverMods` | instance `public final ArrayList<String>` | none |
| `ZomboidFileSystem.modIdToDir`, `modDirToMod` | `private final Map` | cannot be widened (§4) |

Notes:

- `WorkshopItem.setState` logs `"<old> -> <new>"` through `noise` before assigning (ConnectToServerState.java:348-349), so
  a HEAD `@ModifyVariable` shows the replaced value in that log line.
- `WorkshopUpdate` checks only `item.state` (`Fail` at 631, `Ready` at 637), never `error`.
- ZombieBuddy loads every glue class's advice parameter types when it collects patches
  (`clazz.getDeclaredMethods()`, PatchEngine.java:59), so `WorkshopItem` and `WorkshopItemState` load at game start, not
  at the join, and are retransformed (the weave test log shows the same for its nested target). Zabric's widener is
  installed at premain, before that; a class loaded earlier would show as "already loaded before Zabric ran".
- In Kotlin the target string needs the escape: `targets = ["zombie.gameStates.ConnectToServerState\$WorkshopItem"]`;
  an unescaped `$WorkshopItem` is a string template and KSP reports "@Hooks names no target class".

## 2. Do the risky shapes weave

Not covered before: every weave and golden target method was public; the only nested-class target (`InnerHooks.kt`) was
a KSP golden case without a weave; `WidenerTransform` had no test.

Added, all passing (`JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew :zabric:check :zabric-processor:check
:zb-kt-utils-processor:check`):

| Test | Proves |
|---|---|
| `WeaveTest.modifyVariableOnAPackagePrivateMethodOfANestedClass` (zabric) | `@ModifyVariable` HEAD `argsOnly` on package-private `Nested$Item.setState`, hook class `@Hooks(targets = "...Nested$Item")`, `@Self` reading and writing the receiver's public (widened-shape) fields, weaves through ZombieBuddy's `PatchEngine` (retransformed) and replaces the argument |
| `WeaveTest.headCancelOnAPrivateMethodHoldsUntilReleased` (zabric) | `@Inject` HEAD `cancellable` on `private void step()` cancels on every call while held and lets the body run once released |
| `GoldenTest` (zabric-processor), with `ItemHooks` and `NestedHooks` added | the javac front end generates exactly the reference glue (`WeaveGlue.java` updated, listings added); "hooks ready (6 hook classes)" |
| `WidenerTransformTest.aPackagePrivateNestedClassAndItsFieldBecomePublicAsTheyLoad` (zabric) | at load, `WidenerTransform` makes a package-private nested class and its field public both in the class header (public lookup access check, as linking does) and in the `InnerClasses` entry (reflection). Fails without the list entries |
| `LibraryTargetTest.privateAndPackagePrivateMethodsOfAClassFileAreTargets` (zb-kt-utils-processor) | the KSP front end, which the Kotlin feature uses, reads private and package-private methods and a nested class by binary name from **class files** (as from the game jar), and its glue compiles |

The weave target models the game class after widening (class and fields public, method still package-private, since
lists never widen methods); the widener test covers the step from package-private to public separately. What only the
game can show: the weave of the real `ConnectToServerState` classes and the runtime widening of the real
`$WorkshopItem` (step 7).

## 3. `jdk.httpserver`

- Windows client and Windows dedicated server bundle OpenJDK 25.0.1 with `jdk.httpserver` and `java.net.http`
  (`jre64/bin/java --list-modules`).
- The Linux dedicated server's bundled runtime: **unverified**; check with `jre64/bin/java --list-modules` on the test
  server in step 7 (the client side uses `java.net.http`, which is in both Windows runtimes).

## What changes in design.md

The widening entries below (`modIdToDir`, `modDirToMod`, `ConnectToServerState.state`, `$State`) and writing
`state = Exit` are superseded by §4.

- Leave hooks (design "Hook Details", "Leave"): replace the candidates with `Core.ResetLua(String, String)` HEAD (any
  `activeMods` but `"client"`), `IngameState.exit()` HEAD, and `ConnectToServerState.exit()` HEAD for joins that end
  before `ResetLua("client")`; plus the hold ending on `GameClient.connection == null` (Back button, lost connection),
  since vanilla never exits `ConnectToServerState` in that case.
- Cache resets (design "Hook Details"): exactly `resetModFolders()`, `modIdToDir.clear()`, `modDirToMod.clear()`,
  `ChooseGameInfo.Reset()`, on and off; never `ZomboidFileSystem.Reset()`. Add the two `ZomboidFileSystem` fields to
  the `.classtweaker`, with `ConnectToServerState.state` and the `WorkshopItemState` class.
- The swap state may be read on other threads (`getAllModFolders` is synchronized; see 1c): publish it
  as an immutable value in a volatile field. Design "Join State Machine" says game objects are touched on the game
  thread only; the swap hook is the exception.
- `GameServer.workshopInstallFolders` / `workshopTimeStamps` are null in `-nosteam`: the server side treats that as "no
  Workshop items" (the client never takes the Workshop step there either).
- Parent design "Risks": the `ResetLua` window question is settled (no refill), and the weave risk is covered by tests;
  the hold-time risk and the Linux `jdk.httpserver` check remain.

## 4. What cannot be widened at runtime

Found by the first `smokeTest` with the module (2026-10-20). Both entries were dropped; nothing replaces them with
reflection.

### `zombie.ZomboidFileSystem`

- `smokeTest`: `Dont39AllInOne (...classtweaker): already loaded before Zabric ran, so they keep their access:
  zombie.ZomboidFileSystem`. Zabric's widener installs at its premain and gives up on every listed class already in
  `Instrumentation.getAllLoadedClasses()` (`WidenerTransform.install`).
- Likely cause: ZombieBuddy's premain applies its own `patches` package (`Agent.java:89`, which includes
  `Patch_ZomboidFileSystem` on `loadMods`) before it preloads Zabric (`Loader.preloadMods()`, `Agent.java:144`).
  Whatever the trigger, no member of `ZomboidFileSystem` can be widened by any mod on Zabric.
- What the swap needed it for: clearing `modIdToDir` (and `modDirToMod`). Who reads them (`javap -c -p` of
  `ZomboidFileSystem` and its nested classes; both fields are private and no nested class touches them):
  - `modIdToDir`: read only in `getModDir` (`return (String) modIdToDir.get(id)`, ZomboidFileSystem.java:987),
    written by `setModIdToDir` (`putIfAbsent`, 715) and `searchForModInfo` (`put`, 738, no callers), cleared by
    `Reset` (498) and `update` (1525).
  - `modDirToMod`: keyed by folder (`getModInfoForDir`, 991-996; `isModded`, a prefix test for map files). A stale
    key is only hit by a lookup of that same folder, so it needs nothing.
  - Callers of `getModDir` in the whole jar: `ChooseGameInfo.getModDetails` (ChooseGameInfo.java:127),
    `ZomboidFileSystem.loadMod` (750, 756) and `ConnectionDetails.writeMods` (server).
  - Vanilla `CheckMods` → `loadModsAux` → `loadModAndRequired` → `ChooseGameInfo.getAvailableModDetails` →
    `getModDetails`: `Mods` cache, then `getModDir`; when that is null it scans `getAllModFolders` in order
    (129-136). Nothing on that path reads the field directly.
  - `loadMods(String)` callers: `GameWindow.init` (start-up), `Core.ResetLua` (`Reset` at bytecode 184 / line 4141
    before `loadMods` at 197 / 4143), `IngameState.exit` (`Reset` 1039 before `loadMods` 1051); `loadMods(List)`
    also from `GameServer.doMinimumInit` (server). So `loadMod` on the client always follows a `Reset()`.
- After the swap turns off, a full `Reset()` follows on the `ResetLua(non-"client")` and `IngameState.exit` paths, but
  **not on a join that fails between swap on and `ResetLua("client")`**: after `OnConnectFailed` the Connect screen's
  Back calls `backToSinglePlayer()` (ConnectToServer.lua:197-200) = `GameClient.doDisconnect` +
  `GameClient.client = false` (LuaManager.java:3094-3097), and shows the previous screen. No `ResetLua`, no `Reset()`;
  `ZomboidFileSystem.update` clears the map only after a watched mod file changed (`modsChangedTime`, set by the
  private `onModFileChanged`). The map would keep tree folders learned during `CheckMods` into the main menu.
- Replacement: a stale mark set at every swap on and off, cleared at `ZomboidFileSystem.Reset()` RETURN, and
  `getModDir` RETURN returning null while it is set (design "Hook Details"). `getModDetails` then scans the folder
  list the game currently has. Also covers a join whose `parse` turns off a swap left on by a `Finish` failure.

### `zombie.gameStates.ConnectToServerState$State`

- `smokeTest` after the fix above: `[Dont39AllInOne] access widener: not applied: these are not in effect: accessible
  class zombie/gameStates/ConnectToServerState$State (IllegalAccessException: access violation ..., from public
  Lookup)`. Not reported as already loaded, and not as a failed widening: the class loaded later, untransformed.
- `$WorkshopItemState` has the same header (`0x4030 ACC_FINAL, ACC_SUPER, ACC_ENUM`) and widened fine. It is a
  parameter type of the `setState` glue, so ZombieBuddy's reflection on the glue loads it, outside any
  transformation. `$State` is only the type of `ConnectToServerState.state` (no method signature names it).
  Not confirmed: that it loads inside another agent's transformation of `ConnectToServerState` (ZombieBuddy
  retransforms it for the `CheckMods` and `exit` advice), where Zabric's transformer does not run.
- Replacement: a join ends through `MainScreenState.getInstance().setConnectToServerState(null)` and `join.exit()`,
  both public. `MainScreenState.update` (783-788) re-reads its field only when `update()` returned `Continue`, so
  clearing it inside our cancelled `CheckMods` is safe; `exit()` only clears `instance` (785-786), and runs our exit
  hook.

## 5. ~~`OnFETick` does not fire during a join~~

Wrong, superseded: the branch at bytecode 19 skips to `OnFETick` (38) whenever `update()` returned anything but
`Continue`; only the `Continue` frame returns early. So `OnFETick` fires on every frame the join holds (design "UI";
the dialog and status line work in game, [in-game-test.md](./in-game-test.md)). The original note:


`MainScreenState.update` (bytecode 0-37, lines 783-788): while `connectToServerState != null` it calls its
`update()` and returns `Remain`; `OnFETick` (792), the screen elements, `ZomboidFileSystem.update` (808) and
`CheckDelayResetLua` (811) run only when there is no join. `OnFETick` is fired nowhere else in the jar
(`LuaEventManager` only declares it). So a Lua poll on `OnFETick` does not run while the `CheckMods` hold waits;
`UIManager.render` fires `OnPreUIDraw` / `OnPostUIDraw` every frame instead (not checked further).
