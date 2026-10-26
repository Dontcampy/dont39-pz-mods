# Research: spikes S2 (digest fields, static part), S3 (touch recording), S4 (held/worn removal)

- **Query**: S3: can a hook at `PacketType.send` reach the packet being sent and its target; per-type fields and
  target resolution; server receive-side hook; `ContainerID` keys and client resolution; end-of-tick hook. S2: what the
  in-scope packets read/write per item and which of those fields the client changes on its own. S4: what vanilla's
  client does when a Remove hits a held/worn item, the exact call sequence for our reconcile without requests, and how
  `AddInventoryItemToContainerPacket.processClient` adds items.
- **Scope**: internal (bytecode of `projectzomboid.jar` via `javap -c -p -l -constants`, JDK 25; game Lua under
  `media/lua/`; Zabric hook API in this repo).
- **Date**: 2026-10-24

Evidence: `Class.method Lnnn` = source line from `LineNumberTable`; `@nnn` = bytecode offset; `file.lua:line` relative
to `E:/SteamLibrary/steamapps/common/ProjectZomboid/media/lua/`. Class names drop the `zombie.` prefix.

---

## 0. Findings that change the design

1. **Zabric has no `INVOKE` injection point.** `@At` accepts only `HEAD`, `RETURN`, `FINALLY`
   (`zabric/src/main/java/com/pzlauncher/zabric/hook/At.java`; `HandlerBinder.java:126` rejects the rest;
   probe `samples/zabric-java-hooks/src/probes/unsupported-at/.../InvokeHooks.java` "Fails to compile"). The design's
   "INVOKE hook on a `GameServer.main` call" cannot be written. A whole-method target is needed (section 4).
2. **`VehicleManager.serverUpdate` runs twice per server tick**: from `IsoWorld.updateInternal L3384-3386` (@10-19,
   `if GameServer.server`) inside `IngameState.update`, and again from `GameServer.main L1046` (@3882). Its RETURN is
   not an end-of-tick point.
3. **The packet object is not reachable at `PacketType.send` HEAD** (only `this` = type and the connection,
   `PacketType.send L945-953`). And one vanilla relay never goes through `INetworkPacket.sendToConnection`: the Remove
   relay writes and sends by hand (`RemoveInventoryItemFromContainerPacket.processServer L117-124`, @193-219:
   `c.startPacket()`, `doPacket`, `this.write(b2)`, `PacketType.RemoveInventoryItemFromContainer.send(c)`). Pairing
   "last in-scope `write` RETURN" with "`PacketType.send` HEAD" covers every path (section 1).
4. **Clients never run `IsoCell.ProcessItems`**: `IsoCell.updateInternal L4904-4907` runs it only when
   `!client && !server` (single player) or on the server every 5000 ms (`lastServerItemsUpdate`). Client-side item
   simulation comes only from `IsoGameCharacter.recursiveItemUpdater` (items implementing `IUpdater`:
   `DrainableComboItem`, `HandWeapon`) and from Lua UI calls (`item:updateAge()`, `item:updateWetness()`).
5. **`ContainerID.hashCode` is unusable as a map key for player inventories**: `equals` compares only player id + bag
   id for `PlayerInventory`/`InventoryContainer` (`ContainerID.equals L573`), but `hashCode` hashes `x,y,z` too
   (`ContainerID.hashCode L590`), and `x,y,z` come from the player's current square at `set` time. The mod needs its
   own key type.
6. **Unwearing through `IsoGameCharacter.removeWornItem` sends `SyncClothing` from the client** (`setWornItem
   L3351-3353`), and `removeAttachedItem` sends `GameCharacterAttachedItem` (`setAttachedItem L3424-3444`,
   `GameClient.sendAttachedItem`). Vanilla's receive-only path for "worn item gone" is `SyncClothingPacket.processClient`
   (section 6).
7. **Food cooking sends `ItemStats` before it advances `cookingTime`/`heat` in the same update** (`Food.update L379`
   send, `L387` `cookingTime +=`). Any field mutated after its send in the same tick gives a false mismatch at tick end;
   this decides several exclusions (section 5).

---

## 1. S3: touch recording at send time

### 1.1 What is reachable at `PacketType.send` HEAD

- `PacketTypes$PacketType.send(Lzombie/network/IConnection;)V` (`L945-953`): locals are `this` (the type) and
  `connection`. On the client it can cancel (`isLimitExceeded` → `cancelPacket`, L946-947); on the server it always
  calls `endPacket(packetPriority, packetReliability, orderingChannel)` (L949).
- `IConnection.getPacket(PacketType)` (`PacketsCache.getPacket L41`) returns the per-connection cached instance. The
  static senders use it: `INetworkPacket.send(IConnection, PacketType, Object...) L230-237` does
  `connection.getPacket(type)` (L232) → `setData(values)` → `sendToConnection` (L234). So for static sends
  (`send(IsoPlayer,…)`, `sendToAll`, `sendToRelative`) the cached instance of the **recipient** connection is the
  written one. Not so for instance-method sends (`sendToClient`, `sendToClients`, `sendToRelativeClients`, the Remove
  relay loop), which write a packet held elsewhere (usually the instance cached on the **sender's** connection). So
  `getPacket` at send HEAD is not reliable.
- `INetworkPacket.sendToConnection` (private interface method, `L130-145`) has packet, type and connection together,
  but (a) the Remove relay bypasses it (finding 3), (b) hooking a private interface method is untested in Zabric
  (no weave test targets an interface; `HandlerBinder` only requires a body).

### 1.2 Pairing that covers all paths

Every in-scope send on the server is `write(b)` immediately followed by `PacketType.send(conn)` on the main thread:

- `sendToConnection L133-138`: `doPacket(b)` → `this.write(b)` → (`isCached` false for all packets in this build) →
  `packetType.send(connection)`. An exception in `write` → `cancelPacket` and no `send` (L140-142).
- Remove relay: `this.write(b2)` (L123) → `PacketType.RemoveInventoryItemFromContainer.send(c)` (L124). The type used
  is `RemoveInventoryItemFromContainer` even when `this` is a `SyncItemDeletePacket` (subclass).

So: `@Inject` at `RETURN` of each in-scope class's `write(ByteBufferWriter)` with `@Self` stores the packet in a
main-thread slot; `PacketType.send` HEAD, when `GameServer.server` and the type is in scope, takes the slot, pairs it
with `connection`, clears the slot. Notes:

- `SyncItemDeletePacket` declares no `write` (inherits); the hook on `RemoveInventoryItemFromContainerPacket.write` runs
  for both (`@Self` typed as the superclass).
- The mod's own calls to these `write` methods (snapshots) also fire the hook; guard with a flag around own writes, or
  compare the writer with the connection's (`UdpConnection.bbw`, private final, would need widening).
- `write` methods are single (no overloads) in all seven classes.

### 1.3 Per-type packet fields and the server-side target

All `containerId` values are filled by `ContainerID.set(ItemContainer)` in `setData` on the server, which stores the
container (`ContainerID.container`, public getter `getContainer()`).

| Packet class (type) | Fields (access) | Server container / items |
|---|---|---|
| `AddInventoryItemToContainerPacket` (123) | `containerId` private final `ContainerID`; `items` private final `ArrayList<InventoryItem>` | `containerId.getContainer()`; the `InventoryItem` instances in `items` (`setData L45-52`) |
| `RemoveInventoryItemFromContainerPacket` (124) and `SyncItemDeletePacket` (126, subclass) | `containerId` private final; `ids` private final `ArrayList<Integer>` | `containerId.getContainer()`; ids are already gone from it (removed before the send) |
| `ReplaceInventoryItemInContainerPacket` (125) | `containerId`, `oldItemId` int, `item` `PlayerItem` (all package-private) | `containerId.getContainer()`; new item = `item.getItem()` (`PlayerItem.getItem()` public); old id gone |
| `SyncItemFieldsPacket` (147) | `playerId`, `containerId`, `itemId` (package-private) | `containerId.getContainer().getItemWithID(itemId)` |
| `SyncItemModDataPacket` (148) | `containerId`, `itemId`, `table` (package-private) | same |
| `SyncHandWeaponFieldsPacket` (149) | `playerId`, `containerId`, `itemId` (package-private) | same |
| `ItemStatsPacket` (134) | `containerId`, `id` (package-private) | `containerId.getContainer().getItemWithID(id)`; for a floor item `setData` uses `ContainerID.setFloor` |

`setData` shapes (for building snapshot payloads with vanilla writers): `SyncItemFieldsPacket.setData(player-or-other,
item)` (2 values; a non-`IsoPlayer` first value clears `playerId`); `ItemStatsPacket.setData(ItemContainer,
InventoryItem)`; `SyncItemModDataPacket.setData(item)`; `SyncHandWeaponFieldsPacket.setData(player, weapon)`;
`AddInventoryItemToContainerPacket.setData(container, item-or-ArrayList)`.

### 1.4 Server receive side (client-originated in-scope packets)

Server-handled in-scope types: Remove (124), `SyncItemDelete` (126), `ItemStats` (134), `SyncItemFields` (147),
`SyncHandWeaponFields` (149). Add/Replace/`SyncItemModData` have no server handler (`handlingType & 1 == 0`,
`onServerPacket` @51-57 skips them).

`PacketType.onServerPacket(ByteBufferReader, UdpConnection) L965-1008`:

- `PacketAuthorization.isAuthorized` false → return without parsing (@20 → @282). This is the non-admin
  `SyncItemDelete` case of AC3 (`requiredCapability = EditItem`). `isAuthorized` has side effects
  (`onUnauthorized` → `AntiCheat.Capability.act`), so the mod must not call it again.
- else `connection.getPacket(this)` (@67-72; a new instance only if `isPostponed()`/`shouldInstantiate()`, both default
  `false` in `INetworkPacket L70/L79` and not overridden by any in-scope class) → `parseServer` (default = `parse`,
  `INetworkPacket L55`) → `isConsistent` false → `logInconsistentPacket` + `sync` (no-op) + return (L982-985) → anticheats
  → `processServer` (L1002).

Hook: `@Inject` HEAD and `FINALLY` on `onServerPacket`, sharing the reader position (`@Share` `LocalRef`):

- HEAD: if `GameServer.server` and type in scope, save `bb.bb.position()` (`ByteBufferReader.bb` is public final).
  Nothing in `onServerPacket` reads the buffer before `parseServer` (`NetworkStatistic.addIncomePacket` reads only
  `limit()`).
- FINALLY: position advanced → vanilla parsed it; the parsed packet is `connection.getPacket(type)` (cast to the
  class), whether it was then accepted, rejected as inconsistent, or threw in `processServer`. Position unchanged →
  unauthorized: reset the position to the saved value and parse a fresh `new ContainerID().parse(reader, connection)`
  (Remove/`SyncItemDelete` start with the `ContainerID`, `write L63-64`); this only reads and resolves
  (`ContainerID.findObject`, which on the server may create a throwaway dummy object/container for an out-of-range index
  but changes no world state).
- Do **not** re-run a whole `parse` for `SyncItemFieldsPacket`: its parse loads the fluid container into the resolved
  item (`SyncItemFieldsPacket.parse L374`, `FluidContainer.load`). `SyncItemModDataPacket.parse` also applies
  (`L59-66`, `KahluaTable.load` into the item's table).
- The per-class `processServer` (`@Self` + connection) would miss rejected packets; it is not enough alone.

### 1.5 `ContainerID`: kinds, key, client resolution

Fields (`ContainerID`): `playerId` (public `PlayerID`), `containerType` (public), `x`, `y` (public int), `z` (public
byte), `index`, `containerIndex`, `vid` (package-private short), `worldItemId` (public int), `floorXY` (public int[]),
`container`, `object` (package-private, public getters).

How the server builds it, `ContainerID.set(ItemContainer) L118-158`:

| Container | Branch | Type and identifying fields |
|---|---|---|
| player main inventory | parent `IsoPlayer` → `set(c, o) L157` | `PlayerInventory`: `PlayerID` (`onlineId` + `playerIndex` byte) |
| bag (at any depth) in a player inventory | `containingItem` and outermost parent is `IsoPlayer` → `setInventoryContainer` | `InventoryContainer`: `PlayerID` + `worldItemId` = the bag item's id |
| bag in a vehicle part | outermost parent `BaseVehicle` → `setObjectInVehicle` | `ObjectInVehicle`: `vid`, `index` = part index, `worldItemId` = bag id |
| bag in a world/corpse container | outermost parent other → `setObject(c, o, sq)` | `ObjectContainer` with `containerIndex = o.getContainerIndex(bag container) = -1` (corpse: `index = -1`); not resolvable, and vanilla sends nothing for it (`server-change-flow.md` 3.1 step 4) |
| bag lying on the floor | `containingItem.getWorldItem()` becomes `o` → `set(c, o)` | `WorldObject`: `x,y,z` + `worldItemId` = bag id |
| corpse | `IsoDeadBody` | `DeadBody`: `x,y,z` + `index` = `getStaticMovingObjectIndex()` |
| vehicle part | `BaseVehicle` | `Vehicle`: `vid` + `index` = part index |
| object container | other `IsoObject`, `getContainerIndex(c) != -1` | `ObjectContainer`: `x,y,z` + `index` = `square.getObjects().indexOf(o)` + `containerIndex` |
| object's main container | `getContainerIndex(c) == -1` | `IsoObject`: `x,y,z` + `index`, `containerIndex = -1` |
| no parent, floor | `getFloorContainer` → `setFloor` | `Floor`: `x,y,z` + `floorXY` (squares of the floor items) |
| no parent, not floor | | `IllegalStateException("Unable to resolve container location.")` (L146) |

Wire form: `ContainerID.write(ByteBufferWriter) L342-383` writes the type enum, then only the identifying fields
(`PlayerInventory`: `PlayerID`; `InventoryContainer`: `PlayerID` + `worldItemId`; others: `x,y,z` then per type). A
`ContainerID.write(ByteBuffer)` overload exists too. `ContainerID.copy(ContainerID)` is public.

Client resolution: `new ContainerID().parse(ByteBufferReader, IConnection) L295-338` reads the same fields and ends in
`findObject()` (`ContainerID.findObject L430-562`), after which `getContainer()` is the client's container or null:

- `PlayerInventory`: `playerId.getPlayer().getInventory()`; `PlayerID.parsePlayer` uses `GameClient.IDToPlayerMap` by
  online id on the client.
- `InventoryContainer`: `player.getInventory().getItemWithIDRecursiv(bagId)` → `InventoryContainer.getItemContainer()`;
  missing → error log `"sendItemsToContainer: can't find inventory container with id=%d"`, container null.
- square-addressed kinds: `IsoWorld.currentCell.getGridSquare(x,y,z)` (server: also `ServerMap.getGridSquare`); square
  null → container null (and `GameClient.delayPacket`, coop players only, L559).
  - `DeadBody`: `square.getStaticMovingObjects().get(index).getContainer()`; bad index → `"ERROR: ContainerID: invalid
    corpse index"`.
  - `WorldObject`: world object whose item id matches; a bag → its container; a plain item → a **new throwaway**
    `ItemContainer("floor")` holding that item.
  - `IsoObject`: index out of range → **new dummy `IsoObject` + dummy `ItemContainer("object")`** (L503); in range →
    `getContainerByIndex(containerIndex)`.
  - `ObjectContainer`: index out of range → dummy object, container **null** (L512); in range →
    `getContainerByIndex(containerIndex)`.
  - `Floor`: **new throwaway** `ItemContainer("floor")` filled from the world items on the listed squares (L462).
- `Vehicle` / `ObjectInVehicle`: `VehicleManager.getVehicleByID(vid).getPartByIndex(index).getItemContainer()` (bag:
  `getItemWithID(bagId)`), with error logs for a bad vehicle/part/bag.

Consequences for keys: `Floor` and plain-item `WorldObject` resolve to throwaway containers (digest comparable,
in-place reconcile impossible; square object lists are out of scope). `IsoObject` out of range yields a dummy that a
digest check would see as an empty container.

---

## 2. S2: what the in-scope packets carry per item

### `SyncItemFieldsPacket` (`setData`, `write`, `parse`, `processClient L463-526`)

Written: `playerId`, `containerId`, `itemId`, `condition`, `headCondition`, `remoteControlId`, `uses`
(`getCurrentUses`), `currentAmmoCount`, `haveBeenRepaired`, `taintedWater` (never set by `setData`, never applied),
`wetness`, `dirtyness`, `bloodLevel`, `isCustomColor`, `color` (ABGR), `hungChange`, `actualWeight`, `weight`,
`alreadyReadPages`, `isCustomName` (+ `customName`), `customPages`, `attachedSlot`, `attachedSlotType`,
`attachedToModel`, `hasFluidContainer` (+ `FluidContainer.save`), `hasSharpness` (+ `sharpness`), mod data (if
`hasModData()`, i.e. non-null and non-empty), `isFavorite`, item visual (per body part: basic/denim/leather patch,
hole, dirt, blood), clothing patches.
Applied by `processClient`: condition, headCondition (if `hasHeadCondition`), remoteControlId, uses, ammo, repaired,
weight, customName flag, favorite, name, clothing wetness/dirtiness/blood/colour, weapon blood, food hungChange and
actualWeight, literature pages, attached slot/type/model, sharpness, mod data (wipe + copy only when the packet has mod
data, `processModData L580`: an emptied table is not propagated), clothing visual/patches; then
`playerId.getPlayer().resetModelNextFrame()` (L520) and Lua `OnContainerUpdate` for bags. The fluid container is loaded
into the item **in `parse`** (L374).

### `ItemStatsPacket` (`setData(container,item)`, `write` bitmask, `applyItemStats`)

Captured/applied: `condition`, `uses` / `usedDelta` (drainables via `getCurrentUsesFloat`), fluid container (capacity +
`copyFluidsFrom`), `heat` (`getItemHeat`, or `Food.getHeat` for food), food: `isFrozen`, `isTainted`, `isCooked`,
`isBurnt`, `cookingTime`, `minutesToCook`, `minutesToBurn`, `hungChange`, `calories`, `carbohydrates`, `lipids`,
`proteins`, `thirstChange`, `fluReduction`, `painReduction`, `endChange`, `foodSicknessChange`, `stressChange`,
`fatigueChange`, `unhappyChange`, `boredomChange`, `poisonPower`, `poisonDetectionLevel`, `extraItems`, `isAlcoholic`,
`baseHunger`, `actualWeight`, `isCustomName` + `name` (`Food.getDisplayName()`), `spices`, `isFertilized`,
`fertilizedTime`; all items: `isWet`, `wetCooldown`. The `freezingTime` field exists but is never set or applied.
`processClient L499-517` finds the item in the container (or the player's hands) and calls `applyItemStats`.

### `SyncItemModDataPacket`

`containerId`, `itemId`, the item's whole mod data table (`KahluaTable.save`); the client's `parse` resolves the item
and `KahluaTable.load`s into its table (L59-66), and `KahluaTableImpl.load` wipes first. No `processClient`.

### `SyncHandWeaponFieldsPacket`

`playerId`, `containerId`, `itemId`, `currentAmmoCount`, `roundChambered`, `containsClip`, `spentRoundCount`,
`spentRoundChambered`, `isJammed`, `maxRange`, `minRangeRanged`, `clipSize`, `reloadTime`, `recoilDelay`,
`aimingTime`, `hitChance`, `minAngle`, `minDamage`, `maxDamage`, weapon parts, mod data. Client applies all, clears and
re-adds parts, wipes and copies mod data, `resetEquippedHandsModels`. Server (`processServer`, from the client) applies
only ammo, chambered, clip, spent count, spent chambered, jammed; no relay.

### `AddInventoryItemToContainerPacket`

`write`: `containerId.write` + `CompressIdenticalItems.save(bb, items, null)`: groups items by full type, writes one
full `InventoryItem.saveWithSize` per group of identical items and the ids of the others. So an Add carries the full
saved item (`InventoryItem.save L1600…`: registry id, id, uses, condition, visual, colour, capacity, mod data,
activated, repaired, name, byteData, extraItems, custom name/weight, keyId, remote id/range, colours, worker,
`wetCooldown`, favorite, stashMap, infected, ammo, attached slot/type/model, maxCapacity, recorded media, worldScale,
initialised, animal tracks, texture/model index, world rotations; plus each subclass's `save`). `parse` reads it back
with `CompressIdenticalItems.load(bb, 249, null, null)` (249 = the world version constant).

Fields in the full save but in no field packet (`age`, `keyId`, `stashMap`, `infected`, `activated`, `worldScale`,
rotations, `wetCooldown` outside `ItemStats` …) can only be repaired by replacing the item instance, not by applying
field payloads.

---

## 3. S2: which fields the client changes on its own

| Source on the client | Runs for | Fields it changes | Evidence |
|---|---|---|---|
| `IsoGameCharacter.updateInternal L9326` → `recursiveItemUpdater` → `InventoryItem.update()` for `IUpdater` items | every non-zombie character's inventory incl. bags, every frame: `DrainableComboItem`, `HandWeapon` only | base `InventoryItem.update`: `wetCooldown`, `isWet`, dry replacement with a **new random id** (`L1347-1367`, no client guard), `itemHeat`, fluid purification amounts (`L1417`) | `IsoGameCharacter.recursiveItemUpdater L9433/L9447` (`instanceof IUpdater`); `IUpdater` implemented only by these two |
| `DrainableComboItem.update` | same | `heat`, `cookingTime`, `cooked`, `burnt`, replace-on-cooked (`AddItem(String)`, new id), `uses` via `Use(..., GameClient.client)` while activated in hand/attached (`L253`), `activated` off when not in hand (`L258`) | `DrainableComboItem.update` |
| Lua `ISInventoryPane` refresh: `item:updateAge()` (`client/ISUI/ISInventoryPane.lua:2266, 2573`) | items shown in inventory panes | `Food.updateAge`: `freezingTime` → `frozen` (`setFreezingTime L2157` calls `setFrozen`), `fertilized` (set false while freezing), `heat`, `age`, `lastAged` | `Food.updateAge L739…` |
| Lua `ISInventoryPane` refresh: `item:updateWetness()` (`ISInventoryPane.lua:2268-2269`) | clothing shown in panes | `Clothing.wetness` (L485), `condition` for `BREAK_WHEN_WET` clothing at 100 wetness (L469) | `Clothing.updateWetness(boolean)` |
| `BodyDamage.UpdateWetness` → `ClothingWetness.updateWetness` | local (and remote) characters' worn clothing | `Clothing.wetness` | no client guard before `updateWetness` (`BodyDamage` @396); synced separately by the `ClothingWetness` packet (out of scope) |
| combat | the attacker's weapon | ranged weapons: `HandWeapon.damageCheck` + `checkSyncItemFields` in `CombatManager.attackCollisionCheck L1200` (side not proven); melee maintenance `processMaintenanceCheck L540` returns on clients | `CombatManager` |
| firing / reloading (client-applied, then `SyncHandWeaponFields`/`syncItemFields` to the server) | local player's weapon | ammo, chambered, clip, spent, jammed | `SyncHandWeaponFieldsPacket.processServer` applies these |
| Lua hotbar | local player | `attachedSlot`/`attachedSlotType`/`attachedToModel` reset on auto-detach (`client/Hotbar/ISHotbar.lua:247-253, 330-333`) without `syncItemFields` | |
| client-applied field paths | local player | name, favorite, remote ids, fluid menu (`syncItemFields` from the client); the server ignores uses, weight, wetness, dirtiness, colour, hungChange, actualWeight, sharpness, fluid from a client `SyncItemFields` (`server-change-flow.md` 3.3) | |

Not simulated on the client: `Food.update` (food is not `IUpdater`; `ProcessItems` does not run), items in world,
vehicle and corpse containers (no client updater), `Clothing.update`.

Server side, unsent mutations that land on touched objects after their send in the same tick: `Food.update` sends
`ItemStats` (L379) before `cookingTime +=` (L387) and `setCooked` (L398); server `ProcessItems` every 5 s also moves
`age`, `heat`, `itemHeat`, `freezingTime` without a send.

---

## 4. End-of-tick hook

`GameServer.main` world tick (offsets in `main`; source lines from its table): `UpdateLimit.Check` @3495 (L982) gates
the tick; then `ServerMap.preupdate` @3599, console commands, removals, `RCONServer.update`,
`MapCollisionData.updateGameState`, `IngameState.update` @3875, `VehicleManager.serverUpdate` @3882 (L1046),
`ObjectIDManager.checkForSaveDataFile`, players into `ServerMap`, `ImportantAreaManager.process`, `setFastForward`, per
connection `calcCountPlayersInRelevantPosition` + `ClientServerMap.characterIn` + `PlayerDownloadServer.update` @4193
(L1087, once **per connection**), stale-player cleanup, connection-attempt timeouts, then `ServerMap.postupdate` @4470
(L1127), `ServerGUI.update`.

- `ServerMap.postupdate()V`: only caller is `GameServer.main` (jar scan; `IsoWorld`, `CoopSlave`, `IngameState` do not
  call it). It unloads `ServerCell`s outside player influence (`ServerMap.postupdate L976-1004`, `ServerCell.Unload`),
  then `NetworkZombiePacker.postupdate`, `ServerChunkLoader.updateSaved`.
- **Target: `@Inject(method = "postupdate", at = @At("HEAD"))` on `zombie.network.ServerMap`**: once per world tick,
  after every in-tick send including `PlayerDownloadServer.update`, before cells unload (keys still resolve).
- Not usable: `VehicleManager.serverUpdate` (twice per tick, finding 2), `PlayerDownloadServer.update` (per
  connection), any `INVOKE` (finding 1).
- The design's "before `PlayerDownloadServer.update`" is not available as a whole-method point between
  `serverUpdate` and the download loop (the code in between is inline in `main`). Running after the download step only
  matters for the chunk-stream race: a chunk snapshotted in this tick already contains the tick's changes.

---

## 5. Digest field list (static part)

Include = set only through packets on the client, so client and server agree after delivery. Exclude = simulated or
reset on one side, or not repairable by field payloads. Check = needs the in-game diff logging of S2.

| Field (packet) | Verdict | Reason |
|---|---|---|
| item id, full type (container digest) | include | container identity |
| condition | check | client ranged-weapon `damageCheck` side unproven; `BREAK_WHEN_WET` clothing on client |
| headCondition | check | weapon wear, side unproven |
| remoteControlId, haveBeenRepaired, isFavorite, isCustomName/customName, customPages, color/isCustomColor, patches | include | packet-only on the client |
| uses / usedDelta | check | activated drainables drain on both sides (`DrainableComboItem L253`); exclude while `isActivated()` at least |
| currentAmmoCount, roundChambered, containsClip, spentRoundCount, spentRoundChambered, isJammed | check | client-applied first, in flight to the server at frame time |
| maxRange … maxDamage, weapon parts (SyncHandWeaponFields) | include | derived from parts, packet-only |
| taintedWater (SyncItemFields) | exclude | never set by `setData`, never applied |
| wetness, dirtyness (Clothing) | exclude | `ClothingWetness` and Lua `updateWetness` on the client; own packet |
| bloodLevel, item visual (blood/dirt/holes) | check | combat visuals may be client-side |
| hungChange, actualWeight (food), calories, carbs, lipids, proteins, thirst…boredom changes, poison, extraItems, isAlcoholic, baseHunger, spices, minutesToCook, minutesToBurn, isTainted | include | server-only changes, sent with `ItemStats`/`SyncItemFields` |
| weight (`getWeight`) | check | may be derived |
| alreadyReadPages | check | reading progress path not traced |
| attachedSlot / attachedSlotType / attachedToModel | exclude | hotbar resets them locally without a sync |
| fluid container contents | include | applied in `parse`/`applyItemStats`; plain fluid items are not updated on clients |
| sharpness | check | melee wear side unproven |
| mod data (SyncItemFields / SyncItemModData / SyncHandWeaponFields) | check | include in principle; mods may write item mod data on the client without a sync; hash order-independently (Kahlua iteration order differs per instance); treat empty == absent (`hasModData`) |
| heat / itemHeat | exclude | simulated both sides, unsent (PRD) |
| age | exclude | not in any field packet; client `updateAge` |
| freezingTime | exclude | never sent by `ItemStats` (field unused) |
| isFrozen | exclude | client `updateAge` → `setFreezingTime` → `setFrozen` |
| isFertilized / fertilizedTime | exclude | client `updateFreezing` clears fertilized |
| cookingTime | exclude | advanced after the send in the same `Food.update`; drainables cook on the client |
| isCooked / isBurnt | check | set after the minute's send; next minute carries it |
| isWet | check | flips on both sides at cooldown end |
| wetCooldown | exclude | decremented on both sides |
| name (`ItemStats`, `Food.getDisplayName`) | check | may include a freshness prefix derived from `age` |
| activated, keyId, stashMap, infected, worldScale, rotations, any save-only field | exclude | not in field packets, so not repairable in place |

---

## 6. S4: held, worn and attached items on the client

### 6.1 What vanilla does when a Remove arrives for an equipped item

- `RemoveInventoryItemFromContainerPacket.processClient L83-95`: `container = containerId.getContainer()`; for each id
  `container.removeItemWithID(id)` (result ignored) and `setExplored(true)`; then for a vehicle part
  `part.setContainerContentAmount(container.getCapacityWeight())`. `SyncItemDelete` uses the same code.
- `ItemContainer.removeItemWithID L3389-3396` → `Remove(item)`.
- `ItemContainer.Remove(InventoryItem) L2321-2359`: **first** `getCharacter()` (parent character, or recursively the
  character owning the bag) `.removeFromHands(item)` (L2321-2322); then removes from `items`, `item.container = null`,
  dirty flags, corpse/mannequin `checkClothing`, feeding trough, `flagForHotSave`.
- `IsoGameCharacter.removeFromHands L3653-3660`: `isPrimaryHandItem` → `setPrimaryHandItem(null)`;
  `isSecondaryHandItem` → `setSecondaryHandItem(null)` (two-handed: both).
- `setPrimaryHandItem L3215-3255` / `setSecondaryHandItem L3612-3629`: equip parent, `setUseHandWeapon`, ballistics
  cache, field, `handItemShouldSendToClients = true`, Lua `OnEquipPrimary`/`OnEquipSecondary`,
  `resetEquippedHandsModels`, `Weapon` anim variable. **No packet on the client**: the flag is read only in
  `IsoGameCharacter.preupdate L8800-8803` under `GameServer.server` (sends `Equip` to the owner). Lua listeners
  (`client/Fishing/FishingHandler.lua:15-20`, `client/RadioCom/ISRadioWindow.lua:407`) send nothing.
- **Worn items are not touched by Remove.** `DoRemoveItem L2362-2385` does not even clear hands.
- Attached (hotbar) items: not touched by Remove; `client/Hotbar/ISHotbar.lua:247-253` (`ISHotbar:update`, when no
  timed action is queued) removes any attached item no longer in the inventory with `self:removeItem(item, false)` and
  `chr:removeAttachedItem(item)`. That client call sends `GameCharacterAttachedItem` (`setAttachedItem L3424-3444`:
  `GameClient.client` and local player → `GameClient.sendAttachedItem`). This is vanilla's own follow-up after any
  server Remove.
- Worn removal reaches the client from the server as `SyncClothing`: server `removeWornItem` → `setWornItem`
  server branch sends `SyncClothing` + `SyncVisuals` to all (L3348-3350). `SyncClothingPacket.processClient L269-275`:
  `process()` (`L225-265`: for each worn item not in the packet's list, `getWornItems().remove(item)`; for packet
  entries not worn, find by id in the inventory or create by type), then `player.onWornItemsChanged()`,
  `player.resetModelNextFrame()`, Lua `OnClothingUpdated`. Nothing is sent back.

### 6.2 Vanilla calls that send from the client (avoid in reconcile)

- `IsoGameCharacter.removeWornItem(item[, bool]) L3362-3368` → `setWornItem(loc, null, bool) L3302-3358`: also
  `cell.addToProcessItemsRemove`, `WornItems.setItem`, Mohawk hair fix, `resetModelNextFrame`, `clothingWetness.changed`,
  then **on a ready client `INetworkPacket.send(SyncClothing, player)` (L3351-3353)**, then `onWornItemsChanged`.
  The server's `SyncClothingPacket.processServer L279-284` replaces the server player's worn list with the client's
  (creating items by type for ids it does not have, L243-247) and relays it.
- `removeAttachedItem L3454-3459` → `setAttachedItem` → `GameClient.sendAttachedItem` (client, local player).
- `EquipPacket.processClient L171-176` echoes the local player's hands back to the server (only when an `Equip`
  packet is received; not triggered by `setPrimaryHandItem`).
- Lua's unequip helpers (`shared/TimedActions/ISTransferAction.lua:86-92`, `client/DebugUIs/ISRemoveItemTool.lua:313-318`)
  call `removeAttachedItem`, `removeFromHands`, `removeWornItem(item, false)` and `OnClothingUpdated`, so they send.

### 6.3 Reconcile sequence for an extra item (client, no requests)

For item `it` in client container `c`, with `owner = c.getCharacter()` (public; recursive through bags):

1. If `owner` is the local `IsoPlayer` and `owner.isEquippedClothing(it)` (`wornItems.contains`):
   `owner.getWornItems().remove(it)` (public `WornItems.remove`); `owner.onWornItemsChanged()` (IsoPlayer override
   `L8329-8332`: `resetModelNextFrame`, ragdoll flag, shoe sound parameter; the base only calls `OnClothingUpdated` on
   the server); `owner.resetModelNextFrame()`; `LuaEventManager.triggerEvent("OnClothingUpdated", owner)`
   (refreshes backpacks and the hotbar, `ISHotbar.lua:836-844`). This is `SyncClothingPacket.processClient`'s order.
2. Remove from the container with vanilla's receive path: `c.removeItemWithID(it.getID())` (or `c.Remove(it)`), which
   clears hands through `removeFromHands` (no packet on the client); then `c.setExplored(true)`; if the key is a vehicle
   part, `part.setContainerContentAmount(c.getCapacityWeight())` (as `processClient L91-92`).
3. Hotbar: do nothing; `ISHotbar:update` detaches it as after any vanilla Remove (and sends
   `GameCharacterAttachedItem` itself, as vanilla does).
4. Optional UI nudge: `LuaEventManager.triggerEvent("OnContainerUpdate")` sets `ISInventoryPage.renderDirty`
   (`client/ISUI/ISInventoryPage.lua:2154-2156`); vanilla's Remove handler does not, the dirty flags suffice.

Vanilla-API alternative (D5's wording "through vanilla's unequip/unwear path"): `owner.removeWornItem(it, false)`
instead of step 1 sends `SyncClothing` with the client's resulting worn list; benign only if that list equals the
server's.

### 6.4 Adding with vanilla's reader

`AddInventoryItemToContainerPacket.processClient L77-127`:

1. return if `IsoWorld.instance.currentCell` is null; `container = containerId.getContainer()`, return if null;
2. a `floor` container whose first item is an `InventoryContainer` → use that bag's container;
3. per item: null → skip; `container.containsID(id)` → for `DeadBody` swap the existing bag's inner container with the
   new one (`setItemContainer`), else `System.out` "dupe" line and skip; otherwise `container.addItem(item)`
   (→ `AddItem L480-516`: rejects duplicate ids, removes from a previous container, adds, `addToProcessItems`,
   `OnAddedToContainer`, `flagForHotSave`), `setExplored(true)`, mannequin `wearItem`, and for each local player
   `ActionManager.replaceObjectInQueuedActions(player, null, item)`;
4. exceptions are logged (`ExceptionLogger`); finally the vehicle part's content amount.

Reuse: `new AddInventoryItemToContainerPacket()`, `parse(new ByteBufferReader(buf), GameClient.connection)`,
`processClient(GameClient.connection)`; all public, the constructor too. Field updates the same way:
`SyncItemFieldsPacket` (`parse`, `isConsistent`, `processClient`), `ItemStatsPacket` (`parse`, `processClient`),
`SyncHandWeaponFieldsPacket` (`parse`, `processClient`), `SyncItemModDataPacket` (`parse` applies). Server side, the
payloads come from the same classes' `setData` + `write(new ByteBufferWriter(buf))` (public constructors on both
wrapper classes). Added items are not equipped or worn; equip state reaches the client by `Equip`/`SyncClothing`.

---

## Decisions for implementation

### Hook targets (Zabric `@Inject`, server unless noted)

| Purpose | Class | Method + descriptor | At |
|---|---|---|---|
| remember the packet being written | `zombie.network.packets.AddInventoryItemToContainerPacket`, `RemoveInventoryItemFromContainerPacket` (covers `SyncItemDeletePacket`), `ReplaceInventoryItemInContainerPacket`, `SyncItemFieldsPacket`, `SyncItemModDataPacket`, `SyncHandWeaponFieldsPacket`, `ItemStatsPacket` | `write(Lzombie/core/network/ByteBufferWriter;)V` with `@Self` | RETURN |
| pair it with the recipient | `zombie.network.PacketTypes$PacketType` (`targets = ["zombie.network.PacketTypes$PacketType"]` or the class literal) | `send(Lzombie/network/IConnection;)V` with `@Self` | HEAD |
| touch on receive (accepted, inconsistent, unauthorized, thrown) | `zombie.network.PacketTypes$PacketType` | `onServerPacket(Lzombie/core/network/ByteBufferReader;Lzombie/core/raknet/UdpConnection;)V`, `@Share` reader position | HEAD + FINALLY |
| end of tick | `zombie.network.ServerMap` | `postupdate()V` | HEAD |
| client open check (design, unchanged) | `zombie.inventory.ItemContainer` | `requestServerItemsForContainer()V` | HEAD |

All handlers return immediately unless `GameServer.server` (resp. client) and the type is in scope; `send` is hot.

### Widening (`.classtweaker`)

```
accessible field zombie/network/packets/AddInventoryItemToContainerPacket containerId Lzombie/network/fields/ContainerID;
accessible field zombie/network/packets/AddInventoryItemToContainerPacket items Ljava/util/ArrayList;
accessible field zombie/network/packets/RemoveInventoryItemFromContainerPacket containerId Lzombie/network/fields/ContainerID;
accessible field zombie/network/packets/RemoveInventoryItemFromContainerPacket ids Ljava/util/ArrayList;
accessible field zombie/network/packets/ReplaceInventoryItemInContainerPacket containerId Lzombie/network/fields/ContainerID;
accessible field zombie/network/packets/ReplaceInventoryItemInContainerPacket oldItemId I
accessible field zombie/network/packets/ReplaceInventoryItemInContainerPacket item Lzombie/network/fields/PlayerItem;
accessible field zombie/network/packets/SyncItemFieldsPacket containerId Lzombie/network/fields/ContainerID;
accessible field zombie/network/packets/SyncItemFieldsPacket itemId I
accessible field zombie/network/packets/SyncItemModDataPacket containerId Lzombie/network/fields/ContainerID;
accessible field zombie/network/packets/SyncItemModDataPacket itemId I
accessible field zombie/network/packets/SyncHandWeaponFieldsPacket containerId Lzombie/network/fields/ContainerID;
accessible field zombie/network/packets/SyncHandWeaponFieldsPacket itemId I
accessible field zombie/network/packets/ItemStatsPacket containerId Lzombie/network/fields/ContainerID;
accessible field zombie/network/packets/ItemStatsPacket id I
# own key built from ContainerID's identifying fields
accessible field zombie/network/fields/ContainerID index S
accessible field zombie/network/fields/ContainerID containerIndex S
accessible field zombie/network/fields/ContainerID vid S
# optional: writer-identity check in the send pairing
accessible field zombie/core/raknet/UdpConnection bbw Lzombie/core/network/ByteBufferWriter;
```

`PacketType.packetReliability`/`orderingChannel` are public and not final (S5 needs no widening). `PlayerID.getID()`,
`ContainerID.getContainer/getObject/getPart/getVehicle/copy/set/parse/write/findObject`, `WornItems.remove`,
`ItemContainer.getCharacter/Remove/removeItemWithID/setExplored`, `LuaEventManager.triggerEvent` are public.

### Keys

- Container key = own value type built on the server from the `ContainerID` vanilla already filled (packet
  `containerId`, or `new ContainerID().set(container)`): `PlayerInventory(onlineId)`, `InventoryContainer(onlineId,
  bagId)`, `ObjectInVehicle(vid, part, bagId)`, `Vehicle(vid, part)`, `DeadBody(x,y,z,index)`,
  `WorldObject(x,y,z,itemId)`, `IsoObject(x,y,z,index)`, `ObjectContainer(x,y,z,index,containerIndex)`,
  `Floor(x,y,z,floorXY)`. Not `ContainerID` itself (finding 5).
- On the wire, write the vanilla `ContainerID` bytes (`ContainerID.write`) so the client resolves with
  `ContainerID.parse` → `findObject` → `getContainer()`; the client echoes the server's bytes in requests. For
  `OpenCheck` the client sends its own `ContainerID`; the server parses it and recomputes the key from the resolved
  container.
- Item entries carry the container key plus `InventoryItem.id`.

### Digest

Include/exclude per section 5; "check" fields start excluded or logged-only until the S2 in-game diff settles them.
Fields outside the field packets must stay out (not repairable in place). Mod data hashed order-independently,
empty == absent.

### Reconcile

- Update existing items: apply snapshot `SyncItemFields` + `ItemStats` + (weapons) `SyncHandWeaponFields` +
  `SyncItemModData` payloads with the vanilla classes' `parse`/`isConsistent`/`processClient` (design listed only
  `SyncItemFields` and `SyncItemModData`; food/drainable stats are only in `ItemStats`, weapon state only in
  `SyncHandWeaponFields`).
- Add: `AddInventoryItemToContainerPacket.parse` + `processClient`.
- Remove extras: section 6.3 (worn via `WornItems.remove` + `onWornItemsChanged` + `resetModelNextFrame` +
  `OnClothingUpdated`; then `removeItemWithID`, `setExplored`, vehicle content amount; hotbar left to `ISHotbar`).

### Risks

- **Same-tick unsent mutations** after a touched send produce false mismatches (`Food.update` cooking, heat);
  mitigated only by the exclusions; AC6 decides the rest.
- **Client-originated changes in flight** (ammo, favorite, name, fluid menu, client `ItemStats`): the frame can be
  older than the client's state; a mismatch then costs a snapshot. With R2's ordered channel, the server has applied the
  client's earlier packet before it answers a `SnapshotRequest` sent after it, so the snapshot does not revert it; the
  cost is traffic and AC6 noise.
- **Local id divergence**: client dry replacement (`InventoryItem.update L1347-1367`) and drainable replace-on-cooked
  create items with client-random ids in the player's inventory; the container digest then mismatches until a
  snapshot.
- **Mods** writing item mod data on the client, or hooking these packets' `write`/`processClient`, change what is
  hashed or applied; the smoke run with the user's mod list (AC10) is the check.
- **Hook on `PacketType.send`** runs for every packet on both sides; the handler must be a cheap early return.
- **Throwaway containers** (`Floor`, plain-item `WorldObject`, `IsoObject` out of range): digest only, report instead of
  reconcile; bags inside world/corpse containers are not addressable at all.
- **Unwear via `removeWornItem`** would send `SyncClothing` and let the client overwrite the server's worn list.

## Caveats / Not found

- Which side runs `CombatManager.attackCollisionCheck` for ranged weapon wear in MP: not traced (only that
  `processMaintenanceCheck` returns on clients).
- Hooking a private interface method (`INetworkPacket.sendToConnection`) in Zabric: untested, not used above.
- `Food.getDisplayName` content (freshness prefix) and `InventoryItem.getWeight` derivation: not read.
- `ContainerID.findObject` lines L430-562 are from
  `vanilla-sync.md`, consistent with offsets 199 → L462, 589 → L503, 708 → L512, 1029 → L559).
- Reading progress (`alreadyReadPages`), sharpness and blood-on-hit sides: not traced; left as "check".
