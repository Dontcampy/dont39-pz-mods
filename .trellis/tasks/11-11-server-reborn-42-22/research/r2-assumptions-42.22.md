# R2: the mod's assumptions against game 42.22

Static reading, 2026-11-11. 42.21 (`4a0e9546ec`) against 42.22 (`3d39148846`), each changed class decompiled outside
the repo and read against the mod code that depends on it. Nothing here ran in the game.

Scope: every changed hook target and used method of the
[parent's list](../../11-11-game-42-22-update/research/impact-42.22.md), widened to the whole class where the mod
depends on what a hooked method calls: `ContainerID` and its player field, the seven in-scope packet classes,
`INetworkPacket`, `PacketTypes`, `ZomboidNetData`, `ItemContainer`, `InventoryItem`, `Food`, `DrainableComboItem`,
`MapItem`, `WorldMapSymbols`, `CompressIdenticalItems`, `UdpConnection`, `IConnection`, `GameServer`, `GameClient`,
`ServerMap`, `IngameState`, `ConnectToServerState`, `Transaction`, and the vanilla Lua that calls
`syncItemFields` / `syncHandWeaponFields`.

`:server-reborn:compileKotlin` on the 42.22 jar reports one error (F2). F1, F3 and F4 compile and are wrong at run time.

## Findings that need a change

### F1. A `ContainerID`'s player bytes no longer mean the same on both sides (not in the parent's list)

`ContainerID.playerId: PlayerID` became `player: ConnectionPlayer` (new class, `zombie.network.fields.character`).

| | 42.21 `PlayerID` | 42.22 `ConnectionPlayer` |
|---|---|---|
| Bytes of a player inventory | type, `short` online id, `byte` player index | type, `short` id |
| Bytes of a bag in one | the same, then `int` bag id | type, `short` id, `int` bag id |
| `set(player)` on the server | online id | online id |
| `set(player)` on a client | online id | the local player's index (0-3); for a remote player an error line and -1 |
| `parse` on a client | player by online id | player by online id |
| `parse` on the server | by index on the connection, else by online id | always the index on the connection; with no connection: no player. `findObject` then throws |

So in 42.22 the bytes are an online id from server to client and a local index from client to server. The mod uses one
byte form for both directions and for its keys. What breaks:

| Mod code | On 42.22 |
|---|---|
| `ContainerWire.playerId` (reads `short` + `byte`) | Reads one byte too many. A player inventory's bytes end early: `Containers.keyOf` throws inside a hook and the mod turns off at the first touch of a player inventory. Loud, not hidden |
| `Containers.find(wire, null)` in `Containers.address` (server, content changes) | Parses an online id as an index with no connection: no container. Player inventories' content changes would never be recorded, plus a game error line for each online id above 3 |
| `Requests.parse` (server, a client's snapshot request or open check) | A snapshot request echoes the server's bytes (online id), which the server's parse reads as an index: wrong or no player |
| `Containers.current` on a client (`Lookup.find`'s `live`, open checks) | `set` writes the local index, so the key's owner is the index, not the online id the server's key has. Equal only when both are 0, which is the first player on an empty test server |
| `DeltaDrop.target` (`PlayerID().parse`, debug only) | Compiles, since `PlayerID` still exists, and reads 3 bytes where the packets now have 2: the logged target is wrong |
| `ContainerWire.ownerInventory` | Builds the old 4-byte form |

Unchanged: square, vehicle, corpse and floor addresses (same bytes and lookup on both sides); the game's own packets
inside a snapshot (written on the server, parsed on the client, which is the game's own direction);
`Relevance.relevant` (`UdpConnection.hasPlayer(short)` still compares online ids); `ContainerType`'s constants and
order (`Containers.check`).

### F2. `PinnedContainerID.pin` does not compile

`SyncItemFieldsPacket.containerId` is `private final`. The classtweaker has it `accessible` only.
`AddInventoryItemToContainerPacket.containerId` was `private final` already and has the `mutable field` line. The other
three field packets' `containerId` are still package-private and not final.

Everything else the pin relies on holds: `ContainerID.parse` returns after the type byte for `Undefined` without
calling `findObject`; `write(ByteBuffer)` now delegates to `write(ByteBufferWriter)`, which writes the type as one byte
(`putEnum`); the five payload classes use the same `ContainerID` members as in 42.21 (`set`, `setFloor`, `write`,
`parse`, `getContainer`, and `getPart`, `containerType`, `x`, `y`, which an `Undefined` id answers without a lookup).

### F3. `SyncItemFieldsPacket` carries a map item's data

`write` ends with a boolean and, for a `MapItem`, `MapItem.saveData` (map id, then the user-made symbols without
network info). `parse` applies it to the item at once, on either side: it clears the item's user annotations and loads
the packet's. The vanilla Lua is new too: 42.22's `ISWorldMapSymbols.lua` calls `mapObj:syncItemFields()` after each
edit (13 call sites); 42.21's had none. So in 42.22 a map item's annotations travel client to server in
`SyncItemFields`, and a snapshot's `SyncItemFields` payload writes the server's annotations onto the client's item.

The digest (`ItemDigests`) does not cover them: in 42.21 they were only in the full item save. Whether it should is
the user's decision (PRD, Open Questions). Facts for it: `WorldMapSymbols.save` writes a font table built by
`SymbolSaveData.initFontLookup` before the symbols; whether that table is the same on both sides when one side also
holds default (not user-made) annotations was not settled from the bytecode.

Snapshots need no change for it: the payload is the game's own `write`, and the client's `parse` reads it.

### F4. Comments and docs that name 42.21 facts

- `Protocol.PACKET_ID`: the game has 303 packet types (was 298): six new (`AcceptMedicalCheck`, `RejectMedicalCheck`,
  `PlayerHit`, `HitStatistic`, `AcceptTrading`, `RejectTrading`), `InvMngGetItem` gone. `0x5347` is still far above.
- `ContainerWire`'s header comment and `playerId`'s; the classtweaker comment "the four field packets' are not final".
- `Touches.received`: "the item packets need only a login". `RemoveInventoryItemFromContainer` now needs
  `Capability.EditItem`, and its `processServer` `AddItem`. The code path is the same: a packet the game did not read
  is parsed by the mod from the client's bytes, with the connection, which is the direction the game's parse expects.
- `README.md`, `mod.info`, `workshop.txt`: game version.

## Checked, no change needed

| Target or member | 42.22 change | Mod code | Verdict |
|---|---|---|---|
| `ZomboidNetData.read` | type looked up with `PacketType.getById`, null outside the ordinals | `NetDataHooks.read` cancels at HEAD for the mod's id, sets `type = null` | Holds. `GameClient.addIncoming`, `mainLoopDealWithNetData`, `update`, `sendPlayerConnect` are unchanged |
| `PacketType.onServerPacket` | `INetworkPacket.sync` takes an `IConnection` | `PacketTypeHooks` HEAD and FINALLY, `Touches.receiving` / `received` | Holds: authorization, `connection.getPacket(type)`, `parseServer`, `isConsistent` are in the same order; a read packet is still the connection's cached one |
| `PacketType.send`, `onClientPacket`, `doPacket` | none | `Touches.sending`, `DropHooks` | Holds |
| `SyncItemFieldsPacket.write`, `SyncHandWeaponFieldsPacket.write` | `player: ConnectionPlayer`; the map trailer (F3) | `WriteHooks` at RETURN | Holds: the hook reads only `containerId` and `itemId` |
| `SyncItemFieldsPacket.setData` | casts its first value to `IsoPlayer` (was: clears the id for anything else); reads the `MapItem` | `Snapshots.fields` passes an `IsoPlayer` always | Holds |
| `SyncItemFieldsPacket.processServer` | no longer takes the ammo count from the client | digest field `AMMO` | See "Left to the in-game run" |
| `SyncHandWeaponFieldsPacket` | `handlingType` 3 to 2 and `processServer` removed: the server no longer handles it; the Lua global `syncHandWeaponFields` sends only on the server | `Touches.received`, digest field `AMMO` | Holds: a packet the server does not read leaves the reader where it was, and only remove and delete are parsed by the mod then |
| `SyncItemFieldsPacket.processClient`, `SyncHandWeaponFieldsPacket.processClient`, `ItemStatsPacket`, `SyncItemModDataPacket`, `AddInventoryItemToContainerPacket` | world version 249 to 251 in their loads; otherwise none | `Reconcile` | Holds: both sides are 42.22 (D1) |
| `ItemStatsPacket.parse` | fluid container loaded at 251 | `ItemStatsHooks.parse` resets the stats `write` may leave out | Holds: the packet's fields and the ones `write` leaves out are the same |
| `AddInventoryItemToContainerPacket.parse` | `CompressIdenticalItems.load` refuses a negative or oversized count and more than 64 MiB; `InventoryItem.loadItem` refuses a length past the buffer | `Reconcile.itemList` reads exactly the snapshot's payload | Holds |
| `ItemContainer.AddItem(InventoryItem)`, `AddItemBlind` | return null for an item that contains the container | `ContentHooks` at HEAD; `Reconcile.add` | Holds: a refused add is a touch with no change, and a snapshot item the game refuses leaves the digest differing, reported as unrepairable |
| Other `ItemContainer` mutators the mod hooks | none; `removeItemOnServer` is gone (not hooked); the set of methods that edit the item list directly is the same | `ContentHooks` | Holds |
| `ItemContainer.getItemWithIDRecursiv` | cycle-safe, at most 16 bags deep | `Containers.parentThere` | Holds: the same limit as the game's own lookup; the mod's nested addresses stop at 8 |
| `ItemContainer.getCharacter`, `InventoryItem.getOutermostContainer` | loops with the 16-level limit and a cycle guard | `Snapshots.write`, `Containers.unaddressedBag` | Holds |
| `GameServer.disconnect` | `TransactionManager.cancelAllRelevantToUser` takes the online id | `NetHooks.disconnect` at HEAD | Holds |
| `GameServer.addIncoming`, `mainLoopDealWithNetData` | none | `NetHooks.incoming` | Holds |
| `GameServer.main` | a block for silent players after the queues are taken; `LocaleManager`, `IntegrityReport` | `ServerLoop`, `ServerTickRate` | Holds: `PoolCaps.updateServerCaps` is still the first call of a pass, `ServerMap.preupdate` and `postupdate` are still called once per tick, and `new UpdateLimit(100L)` is still the statement before `PerformanceSettings.setLockFPS(10)` |
| `ServerMap.preupdate` | a cell that fails `Load2` is logged, unloaded and dropped | `TickHooks` at HEAD | Holds |
| `PerformanceSettings.setLockFPS`, `UpdateLimit.<init>(J)`, `Rand.AdjustForFramerate`, `WorldSound.init`, `PoolCaps.updateServerCaps`, `ServerMap.postupdate`, `ConnectionDetails.write` | none (parent's comparison) | tick rate hooks, `DetailsHooks` | Holds |
| `IngameState.exit` | no longer clears `Core.lastStand` | `LeaveHooks` at HEAD | Holds |
| `ConnectToServerState` | `GameMode` enum | `connectionDetails` field read at `sendPlayerConnect` | Holds |
| `UdpConnection` | `havePlayer(IsoPlayer)` renamed `hasPlayer` | `bufferLock`, `startPacket`, `endPacket`, `cancelPacket`, `getPacket`, `hasPlayer(short)` | Holds |
| `InventoryItem.syncItemFields`, `Food`, `DrainableComboItem` | a client sends only for its local player's items | none | Holds: fewer client sends, same packets |

## Left to the in-game run

`ItemDigests.Checked` already says the per-field diff run decides fields whose side is not settled from the bytecode.
42.22 moved these:

- `AMMO`: the server no longer accepts ammo or chamber state from a client (`SyncItemFields.processServer`,
  `SyncHandWeaponFields`). The shared timed actions set them on the server, which sends them. Fewer in-flight
  differences are expected, not proven.
- `CONDITION`, `BLOOD`, `SHARPNESS`: hits moved to new packets (`PlayerHit`, `HitStatistic`); the old
  `GameClient.sendPlayerHit` and `GameServer.sendHitCharacter` are gone. Which side wears a weapon was not traced.

The acceptance run under `-Dserverreborn.debugFieldDiff` covers both with weapons.

## Test caveat from F1

A client whose player has online id 0 hides F1's client half, because its local index is 0 too. The in-game check
needs a player with another online id: a second client, or fake clients joined first.

## Not checked

- Anything in the game on 42.22.
- `FluidContainer.save` at world version 251 (the `fluids` digest group hashes it; both sides are 42.22).
- Target mods' Lua the digest names (`RPGInventory`, Escape from Kentucky keys) on 42.22.
