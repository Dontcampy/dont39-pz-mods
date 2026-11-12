# Impact of game 42.22 on the mods

Static evidence only, gathered 2026-11-11. Nothing was run in the game on 42.22.

## Builds compared

| | Version | Revision | Source |
|---|---|---|---|
| Old | 42.21 | `4a0e9546ec` (`Session.REVISION` in `faster-loading`) | `projectzomboid.jar` of the Linux test server, Steam build 25485538, not yet updated |
| New | 42.22 | `3d39148846` (`Core.<clinit>`) | local install, updated 2026-11-10 22:26, Steam build 26486631 (client), 26486687 (server) |

## Method

1. `./gradlew build assembleMod --continue` against the new jar.
2. Every game member the mods touch, old jar against new jar: hook targets (`META-INF/zabric/hooks/*.txt`), widened
   members (`*.classtweaker`), and every field and method the compiled mod classes reference. A method counts as changed
   when its `javap -c` code differs after constant pool indices, offsets and jump targets are removed; a member also
   when its access flags differ. `network-status` and `server-reborn` did not compile, so their references come from
   the jars of the last build before the update.
3. `diff -rq` of the vanilla `media/lua`, then the changed files that define a global the mods' Lua names.

The two limits of step 2: a method the mods never name is not compared, even when a hooked method calls it; and equal
code says nothing about data files or natives.

## Build gate on 42.22

| Failure | Cause |
|---|---|
| `:network-status:compileKotlin`, `PacketTraffic.kt:119` | `PacketTypes.packetTypes` (a `Map`) is gone; the game now calls `PacketTypes.PacketType.getById(short)` |
| `:server-reborn:compileKotlin`, `PinnedContainerID.kt:34` | `SyncItemFieldsPacket.containerId` is now `private final`; the classtweaker makes it accessible but not mutable |
| `:zabric:test`, `RelaunchTest.aRealHelperAnswersAndWaitsForTheGame` | JUnit could not delete its temp dir (`Zabric\relaunch.log` still open). Not caused by the game |

`dont39-all-in-one` bundles `network-status`, so the All-in-One jar does not build either.

## Per module

`HOOK` = hook target, `use` = member the mod's code references, `widen` = classtweaker entry.

| Module | Workshop item | Result |
|---|---|---|
| `server-reborn` | Server Reborn | Does not compile. 10 of 42 hook targets changed, 13 used methods changed, 1 widened field now final |
| `network-status` | All-in-One | Does not compile (1 used field gone). 2 hook targets unchanged |
| `appearance-override` | All-in-One | 2 of 13 hook targets changed |
| `mod-sync` | All-in-One | 1 of 11 hook targets changed (`IngameState.exit`, see below) |
| `loading-progress` | Loading Progress | 5 of 77 hook targets changed |
| `faster-loading` | Faster Loading | All 4 hook targets, 74 used members and 12 widened members unchanged. Its revision gate turns every fix off on 42.22 |
| `ime-keyboard-fix`, `missing-anim-folders`, `anim-extends-case`, `tunnel`, `allinone-common` | All-in-One | Nothing they touch changed |
| `mod-options-sidebar`, `sctd-render-takeover` | All-in-One | Lua only; see Lua below |
| `zabric`, `zb-kt`, `zb-kt-utils` | Zabric, ZabricKotlin | Nothing they touch changed, apart from `Core.getVersion` (the version text) |
| `zb-skip-online-checks` | ZB Skip Online Checks | Nothing it touches changed |

## Changed hook targets

### Server Reborn

| Target | Change in 42.22 |
|---|---|
| `ZomboidNetData.read(S, ByteBufferReader, UdpConnection)` | looks the type up with `PacketType.getById` instead of the `packetTypes` map |
| `PacketTypes$PacketType.onServerPacket(ByteBufferReader, UdpConnection)` | `INetworkPacket.sync` now takes an `IConnection` |
| `SyncItemFieldsPacket.write(ByteBufferWriter)` | `playerId: PlayerID` became `player: ConnectionPlayer`; a new trailer: a boolean and, for a `MapItem`, `MapItem.saveData` |
| `SyncHandWeaponFieldsPacket.write(ByteBufferWriter)` | `playerId: PlayerID` became `player: ConnectionPlayer` |
| `ItemStatsPacket.parse(ByteBufferReader, IConnection)` | fluid container world version 249 became 251 |
| `ItemContainer.AddItem(InventoryItem)`, `AddItemBlind(InventoryItem)` | new guard `chainContainsContainingItem`: refuses an item that contains this container |
| `GameServer.disconnect(UdpConnection, String)` | `TransactionManager.cancelAllRelevantToUser` takes the online id |
| `ServerMap.preupdate()` | `ServerCell.Load2` returns void; a cell that fails to load is logged, unloaded and dropped |
| `IngameState.exit()` | no longer clears `Core.lastStand` |

Used methods that changed: `ContainerID.<init>`, `parse`, `write(ByteBuffer)` (now delegates to
`write(ByteBufferWriter)`); `SyncItemFieldsPacket.<init>`, `setData` (reads the `MapItem`);
`SyncHandWeaponFieldsPacket.<init>`, `setData`; `AddInventoryItemToContainerPacket.parse`;
`ItemContainer.getItemWithIDRecursiv` (now cycle-safe through `getItemWithIDRecursive(int, int, Set)`),
`ItemContainer.getCharacter`, `InventoryItem.getOutermostContainer`.

### Loading Progress

| Target | Change in 42.22 |
|---|---|
| `GameWindow.enter()` | `Core.tileScale` and the 1x texture packs (`Tiles1x`, `Overlays1x`, `JumboTrees1x`, `Tiles1x.floor`) are gone; only the 2x packs load |
| `GameLoadingState.enter()` | game mode is the `GameMode` enum, not a string; `Core.isLastStand` is gone |
| `IngameState.enter()` | `Core.isTutorial()` replaces the string compare |
| `IngameState.exit()` | no longer clears `Core.lastStand` |
| `LuaManager$Exposer.setExposed(Class)` | also records an enum class in `exposedEnums` / `enumToId` |

### Appearance Override (All-in-One)

| Target | Change in 42.22 |
|---|---|
| `AnimatedModel.setCharacter(IsoGameCharacter)` | sets `character` and calls `setGrappleable` in one more place |
| `FBORenderCorpses$BodyParams.init(IsoMannequin)` | `IsoGridSquare.getObjects()` returns a `List`; the loop reads it with `get(i)` |

## Lua

382 vanilla Lua files changed. Three define a global the mods' Lua names; none of the changes is in what the mods use:

| Vanilla file | Mod Lua | Change |
|---|---|---|
| `OptionScreens/MainOptions.lua` | `mod-options-sidebar` wraps `MainOptions:addModOptionsPanel` | zoom levels: 1x branch removed |
| `OptionScreens/ConnectToServer.lua` | `mod-sync` reads `ConnectToServer.instance` | new early exit when the player profile was not received |
| `OptionScreens/MainScreen.lua` | `mod-sync` reads `MainScreen.instance.multiplayer`, `.inGame` | Last Stand removed, `GameMode` enum |

Across the game, Last Stand is replaced by Challenge (`client/Challenge`, `ChallengeList.lua`), and game mode strings
by the `GameMode` enum. The mods' Lua calls neither `getGameMode` nor `isLastStand`.

## Not checked

- Anything in the game on 42.22: `smokeTest`, `serverSmokeTest`, a join, the weave check.
- ZombieBuddy on 42.22 (`ZBVersionMin=2.3.4`; the local `ZombieBuddy.jar` is from 2026-10-13).
- Target mods of the All-in-One features (SCTD and others) on 42.22.
- The Linux test server is still on 42.21. It is the only copy of the old jar; updating it removes the reference.
