# Research: how the server handles a container / item change, end to end

- **Query**: For container contents (world containers, vehicle part containers, corpses, the player's own inventory)
  and per-item fields/stats/mod data: who originates a change, how it reaches the server, whether the server is
  authoritative (client prediction, rollback), what the server validates and applies, to whom it sends the result,
  which server mutations are never sent, what server-side identity containers/items have, and which thread applies
  it. Input for stamping per-container / per-item versions.
- **Scope**: internal (bytecode of `projectzomboid.jar` via `javap -c -p -l -constants`, JDK 25; game Lua under
  `media/lua/`). Builds on `research/vanilla-sync.md` (packet table, reliability, discard points, hook points); that
  material is not repeated here.
- **Date**: 2026-10-24

Evidence: `Class.method Lnnn` = source line from the class's `LineNumberTable`; `file.lua:line` relative to
`E:/SteamLibrary/steamapps/common/ProjectZomboid/media/lua/`. "Inferred" marks conclusions not visible in one method
body. Class names below drop the `zombie.` prefix.

---

## 0. Short answer

- **Server-authoritative paths (no client prediction)**: inventory transfers (`ItemTransaction`) and every B42 timed
  action that defines `complete()` (239 action files). The client only animates and waits; the item moves on the
  client when the server's `AddInventoryItemToContainer` / `RemoveInventoryItemFromContainer` arrive. A server reject
  has nothing to roll back.
- **Client-authoritative paths (client applies first, server follows)**: client-side `item:syncItemFields()` (renames,
  favourite, remote-controller ids, battery drain on the client, fluid menu), client `ItemStats` (food/drainable
  stats), client `sendRemoveItemFromContainer` (`SyncItemDelete`, admin only). The server applies a subset or rejects;
  **it never tells the client** (no reject packet, `INetworkPacket.sync` is a no-op). So "a client-side change the
  server rejected stays applied on that client" is **real**, but only on these paths.
- **Server-only paths**: loot fill, loot respawn, food cooking/rotting/replacement, drying, drainable depletion,
  server Lua (`complete()`, `OnTick`, global objects), anim-event mutations during actions, fire, thumping.
- **Fan-out**: player inventories (and bags in them) go **to the owner only**; world/vehicle/corpse/floor containers go
  `sendToRelative` (every fully connected client whose relevance area contains the container's x,y), **including the
  originator** for server-originated changes, **excluding the originator** for relays of client-originated packets
  (`RemoveInventoryItemFromContainer`, `SyncItemFields`, `ItemStats`).
- **Mutation and send are always separate calls**, sometimes in the wrong order, often with no send at all (food age,
  heat, freezing, fire `removeAllItems`, bags inside world containers). Item fields are often written directly to
  fields, not through setters.
- **Identity**: `ItemContainer` has no server-side id (`ItemContainer.id` is `-1` from the common constructors, never saved); it is identified by its
  parent (square + object index + container index, vehicle id + part index, corpse square + index, player online id,
  bag item id). Instances are recreated on chunk reload. `InventoryItem.id` is a random int assigned at creation and
  saved/loaded, so it survives reloads and restarts; uniqueness is probabilistic, not enforced.
- **Thread**: one server main thread processes all packets and then runs the world update, Lua, `TransactionManager`
  and `ActionManager`. Only chunk load/save threads touch containers off-thread (deserialize/serialize).

---

## 1. Who originates a change and how it reaches the server

### (a) Player moves items between containers: `ItemTransaction`

Client (Lua `client/TimedActions/ISInventoryTransferAction.lua`):

1. `new`: in MP `maxTime = -1` with the comment "The client completes the transfer after receiving packet
   ItemTransactionPacket from the server" (`ISInventoryTransferAction.lua:854-856`).
2. `start`: `self.action:setWaitForFinished(true)`, then `self.transactionId = createItemTransaction(character, items,
   src, dest)` (`ISInventoryTransferAction.lua:306-314`). `items` may be several queued items of one type
   (`checkQueueList`).
3. Java `LuaManager$GlobalObject.createItemTransaction L9996-10000` -> `TransactionManager.createItemTransaction
   L511-528`: runs the **client-side** `TransactionManager.isConsistent` for every item (L511); if any fails returns `0`
   and sends nothing; else builds an `ItemTransactionPacket` (`Transaction.set L77-122`: state `Request` on the client,
   byte `id` from a static client counter `lastId` L119-122), sends it (L515-518, R_ORD ch1) and queues it locally
   (L520).
4. `update`: polls `isItemTransactionDone(id)` -> `forceComplete()`, `isItemTransactionRejected(id)` -> `forceStop()`;
   takes the duration from the server's Accept (`ISInventoryTransferAction.lua:161-174`).
5. `perform`: on the client **does not move the item** (`if not isClient() then self:transferItem(item)`,
   `ISInventoryTransferAction.lua:501-503`); it calls `removeItemTransaction(id, false)` (L509) and, if more items are
   queued, starts the next transaction (L524-527).
6. `stop` (cancel): `removeItemTransaction(id, true)` (`ISInventoryTransferAction.lua:456`) -> client sends an
   `ItemTransaction` with state `Reject` (`TransactionManager.removeItemTransaction L532-541`).

Server:

1. `ItemTransactionPacket.processServer L151-175`: for each entry calls `TransactionManager.isConsistent(itemId, null,
   source, dest, extra, this, player)` and stops at the first non-zero result (bytecode offsets 13-83).
   - `Request` + consistent -> `setTimeData` (server clock), `TransactionManager.add`, state `Accept`, send Accept back
     to the requester only (`L158-166`).
   - `Request` + inconsistent -> state `Reject`, send Reject to the requester (`L168-172`).
   - incoming `Reject` (client cancel) -> `TransactionManager.removeItemTransaction(id, true)` (`L174-175`), which on
     the server removes **every** queued transaction with that byte id (`lambda$removeItemTransaction$1` compares only
     `id`, not the player). Inferred: ids are per-client counters, so one player's cancel can remove another player's
     pending transaction with the same id.
2. `TransactionManager.update L60-85` (server branch, main thread, called from `IngameState.updateManagers L1661`):
   for each `Accept` whose `endTime <= GameTime.getServerTimeMills()`:
   `Transaction.update()` -> `updateItem(entry)` per entry, stops at the first `false` (`Transaction.update L131-136`).
   - all true -> state `Done`, send the `Done` packet to the requester if its connection `isFullyConnected` (L65-72).
   - false -> state `Reject` (**no packet**, L76); `RuntimeException` -> printed, state `Reject` (**no packet**,
     L79-80). Both are then dropped by `removeIf(Done || Reject)` (L85).
3. `Transaction.updateItem L140-324` does the actual mutation and sends (see section 3).

Client receipt: `ItemTransactionPacket.processClient L136-143` only updates the local transaction state
(`TransactionManager.setStateFromPacket L551-554`) and overlay sprites. Client timeout: a pending transaction is dropped
after `duration + 10 s`, or 20 s without duration (`TransactionManager.update L86-96`,
`lambda$update$1` constants `10000l`, `20000l`), with a noise-level log `"Timeout: %s"`.

Same mechanism: `client/TimedActions/ISGrabItemAction.lua` (floor pickup; 7 transaction API calls).

### (b) Player uses / consumes / modifies an item

Two mechanisms, decided per action by whether the Lua action class has a `complete` function:

- `LuaTimedActionNew.<init> L73-74`: `useCustomRemoteTimedActionSync = (metatable.complete == null)`.
- **Has `complete()`** (239 files under `shared/TimedActions`, `client/TimedActions`): on `start` the client sends a
  `NetTimedAction` (`LuaTimedActionNew.start L129-131` -> `ActionManager.createNetTimedAction L227-229` ->
  `sendAction L206-214`, which first runs the client `isConsistent`). Server: `NetTimedActionPacket.processServer
  L63-86`: consistent -> stop the player's other actions, `ActionManager.start`, `Accept` to the requester; else
  `Reject`. `ActionManager.update L66-96` (server): when `endTime` passes, `NetTimedAction.perform` calls the Lua
  `complete()` (`NetTimedAction.perform L140`); true -> `Done`, false -> `Reject`, each sent to the requester if fully
  connected. On the client `LuaTimedActionNew.complete L164-166` does **not** call Lua `complete` when
  `GameClient.client`; `update L90-99` waits for `ActionManager.isDone/isRejected`. The client-side Lua `perform()`
  is cosmetic: e.g. `ISEatFoodAction:perform` only stops sounds and resets job delta, the old
  `self.character:Eat(...)` line is commented out; `complete()` calls `self.character:Eat(...)`
  (`shared/TimedActions/ISEatFoodAction.lua`); same shape in `ISDrinkFromBottle`, `ISFixAction`, `ISCraftAction`,
  `ISTakePillAction`, `ISInsertMagazine`.
  - Server-side mutations can also happen **during** the action from emulated anim events, e.g.
    `ISUnloadBulletsFromFirearm.lua:72-80` (`if not isClient()`: add bullet, decrement ammo,
    `sendAddItemToContainer`, `syncHandWeaponFields`).
- **No `complete()`** (legacy / custom sync, e.g. `ISInventoryTransferAction`, `ISGrabItemAction`,
  `ISDetachItemHotbar`, `ISOpenContainerTimedAction`): the action runs on the client and syncs by itself.

Client-side direct modifications (applied locally first, then a packet to the server):

| Origin (examples) | Packet | Server handling |
|---|---|---|
| `item:syncItemFields()` on the client: rename food/bag (`client/ISUI/ISInventoryPaneContextMenu.lua:2724-2727, 2746-2749`), remote controller ids (`:2214-2219, 2255`), fluid menu (`client/ISUI/ISFluidContainerMenu.lua:78`), `InventoryItem.setFavorite(b, true)` (`InventoryItem L3559-3561`) | `SyncItemFields` (R ch1), only if the item's outermost container's parent is an `IsoPlayer`, or the item is an `InventoryContainer` (`InventoryItem.syncItemFields L4348-4357`) | section 3.3 |
| `Food.syncItemFields` / `DrainableComboItem.syncItemFields` on the client | `SyncItemFields` + `ItemStats` (R_ORD ch0) (`Food L2245-2264`, `DrainableComboItem L397-401`) | section 3.4 |
| Battery/drainable drain while equipped: `DrainableComboItem.update` calls `Use(..., GameClient.client)` on both sides (`L253`, `L271`), `Use` ends in `syncItemFields()` (`L364`, `L391`) | as above | as above (inferred: both sides simulate the drain and each side's `syncItemFields` fires) |
| Lua `syncItemFields(player, item)` on the client (`client/TimedActions/ISDetachItemHotbar.lua:40`) | `SyncItemFields` to server (`LuaManager$GlobalObject.syncItemFields L10204-10205`) | section 3.3 |
| Lua `sendRemoveItemFromContainer(c, item)` on the client (`client/DebugUIs/ISRemoveItemTool.lua:277`; any mod) | `SyncItemDelete` (`GameClient.sendRemoveItemFromContainer L2265`) | requires `Capability.EditItem` (section 3.2) |
| `ItemContainer.removeItemOnServer` / `GameClient.addToItemRemoveSendBuffer` | `RemoveInventoryItemFromContainer` (`GameClient.addToItemRemoveSendBuffer L2151`) | no Lua caller found (`grep removeItemOnServer` = 0) |

Client Lua `sendAddItemToContainer`, `sendAddItemsToContainer`, `sendReplaceItemInContainer`, `sendItemStats`,
`syncItemModData`, `syncHandWeaponFields`, `sendItemsInContainer` are **server-only** (`GameServer.server` guard,
`LuaManager$GlobalObject L10228, L10240, L10254, L3970, L10197, L10213`; `sendItemsInContainer L4744` calls
`GameServer.sendItemsInContainer`, which returns when `udpEngine` is null, `GameServer L2517-2518`). On a client they
do nothing, so a client-side `container:AddItem(x)` plus `sendAddItemToContainer` is a local-only change.

### (c) Server-side game logic with no client request

| Logic | What it mutates | Send (if any) |
|---|---|---|
| Loot fill on first open: client `requestServerItemsForContainer` (`ItemContainer L3356-3360`) | `ItemPickerJava.fillContainer` | `AddInventoryItemToContainer` with all items, `sendToRelative` (`RequestItemsForContainerPacket.processServer L44-53`); skipped when already explored (L46) |
| Loot respawn (`LootRespawn.respawnInContainer`) | `fillContainer` (L204) | `AddInventoryItemToContainer` of the new items, `sendToRelative` (L227) |
| Food cooking (`Food.update`) | heat, cookingTime, cooked/burnt, replace-on-cooked | `ItemStats` once per game minute while cooking (`L375-379`), on burn (`L499-500`), on tainted-water change (`L535-536`); replacement `sendAddItemToContainer` + `sendRemoveItemFromContainer` (`L411-417`) |
| Food ageing (`Food.updateAge`, server: `Food.update L369-370`) | `age`, `heat` lerp, `freezingTime` (`updateFreezing L843-866`) | `ItemStats` only at the end of `updateAge` (`L779-783`; inferred from the `offAge`/`offAgeMax` comparisons before/after, L771-778: when freshness state changes). `age` is **not a field of `ItemStats` or `SyncItemFields`** (`ItemStatsPacket.applyItemStats L520-575`, `SyncItemFieldsPacket.setData L134-202`) |
| Food rotting (`Food.updateRotting`, returns on client L658-659) | replace-on-rotten, rotten removal after `daysForRottenFoodRemoval` | `sendAddItemToContainer` (L689-690) and `destroyThisItem` -> `sendRemoveItemFromContainer` only if `container.getParent() != null` (`Food.destroyThisItem L2352-2358`); world items via `RemoveItemFromMap` (L2345-2346) |
| Drying (`InventoryItem.update`) | `wetCooldown`; replace with dry item | `sendReplaceItemInContainer` (L1358-1359); `sendItemStats` after `setWet(false)` (L1366-1367) |
| Item heat (`InventoryItem.update L1377-1394`, `DrainableComboItem.update L161-171, L227-238`) | `itemHeat` / `heat` | none; `ItemStats` only on tainted-water purification (`InventoryItem L1399-1417`) |
| Drainable depletion (`DrainableComboItem.Use`) | uses, replace-on-deplete, remove | `sendReplaceItemInContainer` (L355-356), `sendRemoveItemFromContainer` (L380-381), `syncItemFields` (owner only, L364/L391) |
| `InventoryItem.Use` (generic) | uses, replace-on-use, remove (direct `container.items.remove`, L1250) | `sendAddItemToContainer` (L1233-1234), `sendRemoveItemFromContainer` (L1254-1255), `syncItemFields` (L1261) |
| Eat (`IsoGameCharacter.Eat`, from `complete()`) | food | `Food.UseAndSync` (L5828, L5835), `syncItemFields` (L5848) |
| Weapon wear (`CombatManager.attackCollisionCheck L1200`, `processMaintenanceCheck L552-556`) | condition, sharpness, head condition | `checkSyncItemFields` -> `syncItemFields` (`InventoryItem L4367`); owner only. Which side(s) run it was not traced |
| Washer / dryer (`ClothingWasherLogic`, `ClothingDryerLogic`) | wetness/dirt | the only callers of `GameServer.sendSyncItemFields` (`sendToRelative`, `GameServer L3204-3223`) |
| Worn clothing wetness | wetness | separate `ClothingWetness` packet (`characters/ClothingWetnessSync`), not in scope |
| Fire (`IsoGridSquare.BurnWalls L6527-6545`) | `removeItemsFromProcessItems`, `removeAllItems`, `removeAllContainers` on burning objects | **no container packet** in the method; how clients learn about the burnt object was not traced |
| Thump destroys object (`IsoObject.Thump L5944-5956`) | container items moved to the floor, object removed | `AddWorldInventoryItem` (map add) + `transmitRemoveItemFromSquare`; no container packet |
| Zombie / corpse | corpse inventory | full inventory inside `ZombieDeath` (`DeadCharacterPacket.writeCharacterInventory L173`, client clears and reloads, L179-182) and `AddCorpseToMap` (`IsoDeadBody.writeToRemoteBuffer`, `AddCorpseToMapPacket.write L115`); later changes via container packets (`ContainerType.DeadBody`) |
| Vehicles | part containers (trunk, seats, glovebox) | ordinary container packets, `ContainerType.Vehicle` (vid + part index); `BaseVehicle.update L3693` `sendRemoveItemFromContainer`; part items/condition via `VehicleUpdate` (not container packets) |
| B42 entity crafting | inputs taken from containers, outputs into resource slots | `ResourceItem.offerItem L381-401` (`sendRemoveItemFromContainer` for the source, then `Resource.sync` -> `SyncEntityResource`); `CraftRecipeData.processDestroyAndUsedItems L581-582` (`sendItemStats`); resource slots are not `ItemContainer`s |
| Compost, doors/windows/curtains/light switches (sheets, bulbs), radios, animals | container items | `IsoCompost`, `IsoDoor`, `IsoWindow`, `IsoCurtain`, `IsoLightSwitch`, `DeviceData`, `AnimalData` reference `GameServer.sendAdd/RemoveItem*` (class-level grep) |
| Generators, farming | object state / object mod data | not container or item packets (Lua global objects, `SyncIsoObject`, `ObjectModData`) |

Class-level references (binary grep of the extracted jar): `GameServer.sendAddItemToContainer` is referenced by 20
classes, `sendRemoveItemFromContainer` by 23, `sendItemStats` by 9, `syncItemFields` by 10.

### (d) Lua

- **Server Lua** (`server/`, and `shared/` code under `isServer()` / `not isClient()` / inside `complete()`): the usual
  pattern is mutate, then send, as two calls, e.g. `ISTransferAction:transferItem`: `srcContainer:DoRemoveItem(item)`
  then `if isServer() then sendRemoveItemFromContainer(srcContainer, item)` (`shared/TimedActions/ISTransferAction.lua:97-101`),
  `ISUnloadBulletsFromFirearm.lua:77-79` (`AddItem` then `sendAddItemToContainer`). Vanilla call counts (files):
  `sendAddItemToContainer` shared 50 / server 6, `sendRemoveItemFromContainer` shared 60 / server 3 / client 1,
  `syncItemFields` shared 23 / client 3, `sendItemStats` shared 14 / server 1, `sendReplaceItemInContainer` shared 4 /
  server 1, `syncItemModData` shared 1.
- Lua global semantics (server):
  - `sendAddItemToContainer` / `sendAddItemsToContainer` / `sendRemoveItemFromContainer` / `sendRemoveItemsFromContainer`
    / `sendReplaceItemInContainer` -> `GameServer.*` (fan-out in 3.1).
  - `syncItemFields(player, item)` -> `SyncItemFields` **to that player only** (`L10206-10207`,
    `INetworkPacket.send(IsoPlayer, ...)`), whatever the container.
  - `item:syncItemFields()` (Java method) -> owner only when the outermost container's parent is an `IsoPlayer`;
    `ItemStats` `sendToRelative` when the item is a world item; **nothing** for items in world/vehicle/corpse containers
    (`InventoryItem.syncItemFields` bytecode 0-188).
  - `syncItemModData(player, item)` -> `SyncItemModData` `sendToRelative` around the **player's** x,y
    (`L10197-10198`).
  - `syncHandWeaponFields(player, weapon)` -> `SyncHandWeaponFields` to that player (`L10213-10214`).
  - `sendItemStats(item)` -> `GameServer.sendItemStats L3181-3201`: owner if the item's direct container parent is an
    `IsoPlayer`; world item -> relative to the square; else relative to the outermost container's `sourceGrid` or
    parent; else `sendToAll`.
  - `sendItemsInContainer(obj, container)` -> all items, `sendToRelative`, additive (`GameServer L2517-2553`).
- Items have no `transmitModData`; item mod data travels inside `SyncItemFields` (`moddata`, applied by wipe + copy,
  `SyncItemFieldsPacket.processModData L574-588`), inside full item serialization (`AddInventoryItemToContainer`), or
  in `SyncItemModData` (server -> client only).
- `IsoGameCharacter.saveChange/loadChange`: the `ObjectChange` item ops on characters are disabled with
  `"The removeItem change type in the IsoGameCharacter.saveChange function was disabled. The server must use the
  sendRemoveItemFromContainer function."` (`IsoGameCharacter L10694-10775`).

---

## 2. Is the server authoritative?

| Path | Client applies before confirmation? | On server reject | Rollback on client |
|---|---|---|---|
| `ItemTransaction` (transfer, grab) | No. Client waits; item moves only when the server's Add/Remove arrive | Early reject (Request inconsistent): `Reject` packet -> `forceStop` | Nothing to roll back |
| `ItemTransaction`, late failure at apply time (`updateItem` false or exception) | No | State `Reject` on the server, **no packet** (`TransactionManager.update L76-80`) | Nothing applied locally; the action waits until the client timeout, then `isDone` is true for the vanished id (stream `allMatch` on an empty stream; `TransactionManager.isDone L483-485`) so the action `forceComplete`s. Inferred: entries before the failing one in a multi-item transaction are already applied and sent (partial apply) |
| `ItemTransaction`, client-side inconsistent | `createItemTransaction` returns 0, nothing sent (L511, L528) | - | `isDone(0)` is true -> the action completes immediately without moving anything (inferred from the same `allMatch`) |
| `NetTimedAction` with `complete()` | No (client `perform()` is cosmetic; Lua `complete` not run on the client) | `Reject` packet (`NetTimedActionPacket.processServer L75-81`, `ActionManager.update L88-96`) -> `forceStop` | Nothing to roll back |
| Client `SyncItemFields` | **Yes** (client sets fields, then sends) | Item/container unresolved -> `isConsistent` false (`SyncItemFieldsPacket.isConsistent L616`) -> warn + `sync()` no-op (`PacketType.onServerPacket L982-985`) | **No**. Also: server applies only a subset (3.3), so uses, weight, wetness, dirt, food hunger/weight from the client are **never** applied on the server even when accepted |
| Client `ItemStats` | **Yes** | container or item missing -> silent return (`ItemStatsPacket.processServer` offsets 9-28) | **No** |
| Client `SyncItemDelete` (Lua `sendRemoveItemFromContainer`) | **Yes** (caller removes locally) | Non-admins fail `requiredCapability = EditItem` -> `PacketAuthorization.onUnauthorized` -> `AntiCheat.Capability.act` (`PacketTypes$PacketAuthorization L276-286`), packet dropped | **No**; the removal stays on that client and the anticheat counter rises |
| Client `RemoveInventoryItemFromContainer` / accepted `SyncItemDelete` | **Yes** | container null on the server -> nothing removed there, **but still relayed** to other relevant clients (offsets 19 -> 119); ids already gone -> `RemoveContestedItemsFromInventory` to the sender, which removes those ids from its own player inventories (`RemoveContestedItemsFromInventoryPacket.processClient L49-53`) | No re-add |
| Client-side `AddItem` / `Remove` with no packet (mods, UI) | Yes | server never hears of it | Persists until the client reloads that container (relog/chunk reload) |

So the PRD's open question is settled: **real, but only for client-applied paths** (client `SyncItemFields`,
`ItemStats`, `SyncItemDelete`/remove, and unsynced local mutations). Transfers and `complete()` actions cannot leave a
rejected change on the client, because the client never applied it.

---

## 3. What the server does on receipt, and to whom it sends

Packet entry (all paths): `GameServer.mainLoopDealWithNetData` -> `PacketType.onServerPacket L965-1008`:
`PacketAuthorization.isAuthorized` (capability; failure -> `AntiCheat.Capability`), rate limit log-only,
`parseServer`, `isConsistent` (false -> warn + no-op `sync`), per-packet anticheats (none on item/container packets),
`processServer`. Capabilities: `ItemTransaction`, `NetTimedAction`, `SyncItemFields`, `ItemStats`,
`RemoveInventoryItemFromContainer`, `RequestItemsForContainer` = `LoginOnServer`; `SyncItemDelete` = `EditItem`
(`@PacketSetting` via `javap -v`).

### 3.1 Container-content fan-out helpers

`GameServer.sendAddItemToContainer L2426-2434` (same shape: `sendAddItemsToContainer L2437-2445`,
`sendReplaceItemInContainer L2448-2456`, `sendRemoveItemFromContainer L2459-2467`, `sendRemoveItemsFromContainer
L2470-2478`), bytecode branch order:

1. `container.getCharacter() instanceof IsoPlayer` -> `INetworkPacket.send(player, ...)`: **owner only**.
   `ItemContainer.getCharacter L3543-3549` = parent if it is a character, else recurse through `containingItem`'s
   container, so bags anywhere inside a player's inventory also go to the owner only.
2. else `container.getParent() != null` -> `sendToRelative(parent.x, parent.y)`: world object containers, vehicle part
   containers (parent = `BaseVehicle`), corpses (parent = `IsoDeadBody`).
3. else `container.inventoryContainer.getWorldItem() != null` -> `sendToRelative(worldItem.x, y)`: a bag lying on the
   floor.
4. else **nothing is sent**. Inferred: a bag stored inside a world/vehicle/corpse container (its inner container has
   no parent and the bag has no world item) never gets container packets; `ContainerID.set L124-139` would address it
   as the outer object (`setObject` without the bag's item id), so it is not addressable either.

`INetworkPacket.sendToRelative(type, x, y)` = `sendToRelative(type, null, x, y)`: all connections that are
`isFullyConnected()` and `isRelevantTo(x, y)`, no exclusion. `sendToRelative(type, conn, x, y)` /
`sendToRelativeClients(type, conn, x, y)` exclude `conn` by GUID (`INetworkPacket L213-216, L322-325`).
`send(IsoPlayer, ...)` / `send(IConnection, ...)` do not check `isFullyConnected` (`L232-264`).

### 3.2 Container changes

| Trigger | Validation | Apply order | Sent to |
|---|---|---|---|
| `ItemTransaction` Request | `TransactionManager.isConsistent L192-399` (reasons below), only at Request time | deferred to `endTime` | Accept/Reject to requester |
| `ItemTransaction` apply (`Transaction.updateItem`) | re-resolves `ContainerID.findObject` (L140-141); item present in source; vehicle distance (L247-252); floor distance (L320) | normal move: send **Remove** (L275-278) -> `source.Remove(item)` (L281) -> `dest.addItem(item)` (L290) -> floor: `square.AddWorldInventoryItem` (L291-293) -> send **Add** (L300-303). Corpse-item to hands: `setPrimary/SecondaryHandItem` (L306-311). Clothing extra option: remove + `CreateItem(extra)` + add, then `GameServer.sendRemove/sendAdd` (L208-230). Moveables dispatch to Lua `OnProcessTransaction` (`scrapMoveable`, `pickUpMoveable`, `rotateMoveable`, `placeMoveable`, `dropOnFloor`; L143-205) | Remove/Add: player inventory -> owner (`INetworkPacket.send(player)`, L276/L301); otherwise `sendToRelative(ContainerID.x, y)` **including the requester** (L278/L303). Then `Done` to requester (ch1, after the Add/Remove on the same channel) |
| Client `RemoveInventoryItemFromContainer` / `SyncItemDelete` (`RemoveInventoryItemFromContainerPacket.processServer L99-135`) | none besides authorization; no distance/ownership check | for each id: `getItemWithID` -> `Remove` or record in static `alreadyRemoved`; `setExplored`, `setHasBeenLooted` | every connection with a different GUID that `isRelevantTo(ContainerID.x, y)`, **excluding the sender, no `isFullyConnected` check** (offsets 122-225); `RemoveContestedItemsFromInventory` back to the sender for ids already gone (L129-130); log line `item` (L135) |
| `RequestItemsForContainer` | container resolves and not explored (L44-47) | `setExplored`, `fillContainer` | `AddInventoryItemToContainer` with **all** items, `sendToRelative` incl. requester (L53) |
| Server Lua / Java helpers | none | caller mutates, then calls the helper (or not) | 3.1 |

`TransactionManager.isConsistent` reasons (all `DebugType.Objects.noise` unless noted): safehouse loot denied for
source/destination (L196-203); "destination container can't be found" (IsoObject-addressed entries, L206-212); "source
container is not contain the item" (L218-220); "destination container can't contain the item" (L223-225, L378-380);
"this item is not on the floor" (L228-234); character-to-object distance (L237-247); item already in another pending
transaction: "item to inventory" / "item from inventory" / "object to inventory" (L252-304); floor full (`warn`,
L335-340); clothing extra option (L351-357); "Transaction failed: item is null" (`error`, L361-362); capacity
(L366-395). Result 0 = consistent.

Echo to the originator:

- **Server-originated changes** (transactions, `complete()`, server Lua, ticks): the originator is just one of the
  recipients (owner or relevant), so it receives the change and that receipt **is** its apply.
- **Client-originated relays** (`RemoveInventoryItemFromContainer`, `SyncItemFields`, `ItemStats`): the originator is
  excluded; it is assumed to have applied the change already.
- Client receive of an Add for an id it already holds: skipped with `System.out.println` (except corpse
  `InventoryContainer` swap), so an Add is never a field refresh for an existing item
  (`AddInventoryItemToContainerPacket.processClient L96-108`).

### 3.3 `SyncItemFields` from a client (`SyncItemFieldsPacket.processServer L530-569`)

- `isConsistent`: container resolves and contains the item id (L616).
- Applied on the server: condition, head condition, remote control id, current ammo, repaired count, custom name flag,
  favourite, name, weapon blood level, literature pages, attached slot/slot type/model, clothing (`processClothing`),
  `Clothing.fullyRestore` when condition is max and the sender has `Capability.EditItem` (L562-563), mod data (wipe +
  copy, L566). **Not applied** although sent: uses, weight, wetness, dirtiness, clothing colour, food hunger change and
  actual weight, sharpness, fluid container (these are only applied by `processClient L463-524`).
- Relay: only when the item's container has **no parent and a `sourceGrid`** (bytecode offsets 219-278):
  `sendToRelativeClients(SyncItemFields, sender, sourceGrid.x, y)`. Items in player inventories (parent = player) and in
  world/vehicle/corpse containers (parent set) are **not relayed**.

### 3.4 `ItemStats` from a client (`ItemStatsPacket.processServer L478-494`)

- No `isConsistent` override (default `true`, `INetworkPacketField.isConsistent`); container or item missing -> silent
  return.
- `applyItemStats` (all stats: condition, uses, fluid, heat, food flags/values, wetness, fertilization; L520-575).
- Relay to relevant clients **excluding the sender** at the container's x,y, or the owner's x,y when the container's
  parent is a player (L487-494). Inferred: for player-inventory items the relay reaches nearby players who do not hold
  that inventory and drop it silently.

### 3.5 Server-side item fields: senders and recipients

| Sender | Packet | Recipients |
|---|---|---|
| `InventoryItem.syncItemFields` (server) | `SyncItemFields` | owner if outermost container parent is a player; `ItemStats` relative if world item; else none |
| `Food/DrainableComboItem.syncItemFields` (server) | + `ItemStats` | owner only (`Food L2252-2253`, `DrainableComboItem L402-403`); `Food` world item relative (L2257) |
| `GameServer.sendItemStats` | `ItemStats` (R_ORD ch0) | see 1(d) |
| `GameServer.sendSyncItemFields` (washer/dryer only) | `SyncItemFields` | `sendToRelative` around `sourceGrid` or parent (L3204-3222) |
| Lua `syncItemFields(player, item)` | `SyncItemFields` | that player |
| Lua `syncItemModData(player, item)` | `SyncItemModData` | relative to the player's position |
| Container Add (`AddInventoryItemToContainer`) | full serialized item | as 3.1 (a full field snapshot for newly added items only) |

---

## 4. Server mutations that are not sent

Item fields:

- **Food age** (`Food.updateAge`, server only from `Food.update L369-370`): `age` is never in an item-field packet. Sent
  only inside full item serialization. Clients compute age themselves when the inventory pane renders
  (`client/ISUI/ISInventoryPane.lua:2266, 2573` call `item:updateAge()`; inferred: both sides derive age from world
  time). `ItemStats` is sent at the end of `updateAge` (L782-783; inferred: on freshness change).
- **Freezing time** (`Food.updateFreezing L843-866`): no send; the frozen flag reaches clients only with the next
  `ItemStats`.
- **Heat** of items and drainables (`InventoryItem.update L1377-1394`, `DrainableComboItem.update L161-171, L227-238`):
  no send, except cooking minutes (`Food L375-379`) and purification (`InventoryItem L1416-1417`).
- **Anything changed on an item in a world/vehicle/corpse container** and synced with `item:syncItemFields()`: no
  packet (owner-only branch only; `InventoryItem.syncItemFields`). Only `sendItemStats` or a container Add reaches
  observers.
- **Client-sent fields the server ignores** (3.3): server keeps its own values, client keeps its own.
- Item mod data changed by server Lua without `syncItemModData` / `syncItemFields`.

Container contents:

- Bags inside world/vehicle/corpse containers (3.1 step 4).
- `Food.destroyThisItem` when the container has no parent (L2356-2358).
- Fire: `IsoGridSquare.BurnWalls L6542-6545` empties and removes containers with no container packet.
- `IsoObject.Thump L5944-5956`: contents become floor items (map packets), container emptied silently.
- `BaseVehicle.softReset L3255`, ignition-key removal (`L9111, L9133, L9197`): `removeAllItems` / `DoRemoveItem` on
  the ignition container; not checked for sends.
- Any `ItemContainer.AddItem` / `Remove` / `DoRemoveItem` / `removeAllItems` / `clear` / `emptyIt` / `setItems` or
  direct `container.items` access without a following helper call (`InventoryItem.Use L1250`,
  `DrainableComboItem L284, L376` write the public `items` list directly, though they do send).

How clients learn about unsent changes: only through a later full snapshot of the container or item — chunk
(re)download, `AddInventoryItemToContainer` of that item (new add only), `ZombieDeath`/`AddCorpseToMap` for corpses,
`sendItemsInContainer` (additive; existing ids skipped). Opening a container does not refresh an explored one
(`RequestItemsForContainerPacket.processServer L46-47`; `ItemContainer.requestSync` is an empty stub).

---

## 5. Server-side identity

Containers:

- `ItemContainer` has a public `int id`, set to `-1` by the no-id constructors (`iconst_m1`) and to the caller's
  value by `ItemContainer(int)` and `ItemContainer(int, String, IsoGridSquare, IsoObject)` (`iload_1`; callers not
  traced; corrected on review 2026-10-24), not written by `save L2739-2748` or read by `load L2756-2769`; used only in
  `TransactionManager.chainContainsContainingItem L177`. **Not a usable identity.**
- `ObjectIDType` declares `Item` and `Container` types (index 2 / 3, permanent, `ObjectIDInteger`), but the only
  `IIdentifiable` implementor in the jar is `IsoDeadBody` (binary grep for `IIdentifiable`): **unused for containers
  and items** (inferred from the grep).
- The wire identity is derived from the parent each time a packet is built (`ContainerID.set L119-250`):
  `PlayerInventory` (player online id), `InventoryContainer` (player id + bag item id), `IsoObject` / `ObjectContainer`
  (square x,y,z + `square.getObjects().indexOf(parent)` + `getContainerIndex()`), `DeadBody` (square +
  `getStaticMovingObjectIndex()`; the body also has a `DeadBody` `ObjectID` used by corpse add/remove packets but not
  by `ContainerID`), `WorldObject` (square + world item's item id), `Vehicle` (`vehicleId` + part index),
  `ObjectInVehicle` (vid + part index + bag item id), `Floor`.
- Server-side, the stable things are: the `ItemContainer` Java instance while its chunk is loaded; its parent object
  instance; the square coordinates; the vehicle part. Inferred: on chunk unload the instances are discarded and a
  reload (deserialized on the `LoadChunk` thread, `ServerChunkLoader.<init> L99-102`) creates new instances, so any
  per-instance state (e.g. a version stored on the instance or in an identity map) is lost unless persisted or keyed by
  location. Object indices shift when objects are added/removed on the square.

Items:

- `InventoryItem.id` (public int) = `Rand.Next(2146250223) + 1233423` at creation
  (`InventoryItemFactory.createItemInternal L152`, also `CreateItem L192`); written by `save L1602`, read by `load
  L1850`; `createCloneItem L2075-2084` keeps the clone's new id. So ids survive chunk unload/reload and restarts, and
  are not reused by a counter. Uniqueness is probabilistic: there is no global registry; the only collision check is
  per container (`ItemContainer.AddItem L484-486`: `"Error, container already has id"`, returns the existing item).
- Items created on a client get client-random ids, but client-created items do not reach the server (no client add
  packet).

---

## 6. Concurrency

- One main thread: `GameServer.main` loop drains `MainLoopNetDataHighPriorityQ`, `MainLoopPlayerUpdateQ`,
  `MainLoopNetDataQ` and calls `mainLoopDealWithNetData` for each (`GameServer.main L904-973`), then the frame:
  `IngameState.update` (`L1045`) -> `IsoWorld.update` (`IngameState L1550`; `IsoCell.ProcessItems L2622-2639` runs
  `InventoryItem.update` for items in `processItems`) and `updateManagers` -> `TransactionManager.update`,
  `ActionManager.update` (`IngameState L1661-1662`). Lua runs on the same thread.
- So packet handling, transaction/action completion, item ticking and Lua are serialized; a version counter touched
  only from these paths needs no locking (inferred).
- Off-thread: `ServerChunkLoader` `LoadChunk` (deserializes chunks, hands them to the main thread through a queue),
  `SaveChunk`, `RecalcAll` threads (`ServerChunkLoader.<init> L99-113`); `PlayerDownloadServer$WorkerThread` reads
  unloaded chunks from disk (prior doc). They create or serialize containers; no mutation of live containers was found
  (not exhaustively checked). `TransactionManager.transactions` / `ActionManager.actions` are `ConcurrentLinkedQueue`s
  but are only touched from the main thread in the paths traced.

---

## Files found

| File / class | Role |
|---|---|
| `core/TransactionManager` | client/server transaction queue, consistency checks, timeout, reject |
| `core/Transaction` | `set`, `update`, `updateItem` (server apply + sends) |
| `network/packets/ItemTransactionPacket` | Request/Accept/Reject/Done handling |
| `core/ActionManager`, `core/NetTimedAction`, `network/packets/NetTimedActionPacket`, `characters/CharacterTimedActions/LuaTimedActionNew` | server-run timed actions (`complete()`) |
| `network/GameServer` | `sendAdd/Remove/ReplaceItem*`, `sendItemStats`, `sendSyncItemFields`, `sendItemsInContainer`, main loop |
| `network/packets/INetworkPacket` | fan-out (`send`, `sendToRelative`, exclusion, `isFullyConnected`) |
| `network/packets/RemoveInventoryItemFromContainerPacket`, `SyncItemDeletePacket`, `RemoveContestedItemsFromInventoryPacket`, `RequestItemsForContainerPacket`, `AddInventoryItemToContainerPacket` | container deltas |
| `network/packets/SyncItemFieldsPacket`, `ItemStatsPacket`, `SyncItemModDataPacket` | item field snapshots |
| `network/PacketTypes$PacketAuthorization` | capability check -> `AntiCheat.Capability` |
| `network/fields/ContainerID` | wire identity of a container |
| `inventory/ItemContainer`, `inventory/InventoryItem`, `inventory/InventoryItemFactory`, `inventory/types/Food`, `DrainableComboItem`, `Clothing` | mutation and sync methods, ids |
| `iso/IsoCell.ProcessItems`, `gameStates/IngameState.updateManagers` | per-frame item updates, manager updates |
| `LootRespawn`, `iso/IsoGridSquare.BurnWalls`, `iso/IsoObject.Thump`, `entity/components/resources/ResourceItem`, `entity/components/crafting/recipe/CraftRecipeData` | server-only mutations |
| `client/TimedActions/ISInventoryTransferAction.lua`, `shared/TimedActions/ISTransferAction.lua`, `ISEatFoodAction.lua`, `ISUnloadBulletsFromFirearm.lua` | Lua MP branches |
| `client/ISUI/ISInventoryPaneContextMenu.lua`, `client/ISUI/ISFluidContainerMenu.lua`, `client/DebugUIs/ISRemoveItemTool.lua` | client-applied item changes |

## Related specs

- `.trellis/spec/mod/zabric-hooks.md`, `.trellis/spec/mod/access-widening.md` (hooking `ItemContainer` / `GameServer`
  methods and public fields; referenced by the prior doc, not re-read here).

## Caveats / Not found

- Which side(s) run `CombatManager.attackCollisionCheck` / weapon `damageCheck` in MP was not traced.
- `IsoGridSquare.BurnWalls`: how the burnt object's new state reaches clients was not traced.
- Vehicle ignition / `softReset` container paths not checked for sends.
- Freshness-change trigger of `Food.updateAge`'s `ItemStats` is inferred from the comparisons; not stepped through.
- The client-side age computation being equal to the server's is inferred (both use world age hours), not verified.
- Off-main-thread mutation of live containers: none found, but the search was limited to the chunk loader and
  download worker.
- `SyncHandWeaponFields` handlers and `PlayerInventory` / `InvMng*` admin inventory packets were not traced.

---

## Implications for versioning

- **Where to bump a per-container version so every client-visible change is covered**: the container send points,
  not `ItemContainer` mutators. Every client-visible container change goes through `GameServer.sendAdd/AddItems/
  Remove/RemoveItems/ReplaceItemInContainer`, the direct sends in `Transaction.updateItem` (L276, L278, L301, L303),
  `RequestItemsForContainerPacket.processServer L53`, `LootRespawn.respawnInContainer L227`,
  `GameServer.sendItemsInContainer L2553`, and the relay loop in `RemoveInventoryItemFromContainerPacket.processServer`
  (offsets 122-225). The common choke point is `PacketType.send` for packet types 123-126 (prior doc).
  Mutator-side hooks would see changes that are never sent (fire, thump, bags in containers, `destroyThisItem` without
  parent, direct `items` access) and would also need to cover a public list.
- **Per-item version**: same reasoning; bump at `SyncItemFields` / `SyncItemModData` / `SyncHandWeaponFields` /
  `ItemStats` send and in the item serialization of container Adds. Item fields are mutated through direct field
  writes (`age`, `heat`, `itemHeat`, `freezingTime`) every tick on the server, so a mutation-time item version would
  change constantly and produce gaps for changes vanilla deliberately never sends (inferred).
- **Recipient sets differ per packet**, so one global counter per container/item yields expected gaps:
  owner-only vs `sendToRelative`; `isRelevantTo` evaluated at send time; `isFullyConnected` required by
  `sendToRelative` but not by owner sends or by the Remove relay. A client must only treat a gap as loss when it was a
  recipient of the skipped number, or the server must track per-connection sequences (inferred design constraint).
- **Originator exclusion**: relays of client-originated `RemoveInventoryItemFromContainer`, `SyncItemFields` and
  `ItemStats` skip the sender, while the sender already applied the change. If the relay advances the version, the
  sender sees a gap at the next update unless it is told the new version (an ack) or the stamp is excluded for it.
- **Never sent at all** (versioning cannot detect these; only a periodic or on-open digest compare would): food age,
  freezing time, heat; fields of items in world/vehicle/corpse containers changed via `item:syncItemFields()`; bags
  inside world containers; fire `removeAllItems`; thump-to-floor; client-sent fields the server ignores
  (uses/weight/wetness/food via `SyncItemFields`); client-side unsynced or rejected changes (`SyncItemDelete` by
  non-admins, inconsistent `SyncItemFields`, missing-target `ItemStats`).
- **Identity**: a per-container version cannot live in an `ItemContainer` field across chunk reloads without being
  saved; the stable server key is the parent location (square + object + container index, vehicle + part, corpse,
  player). Item versions can key on `InventoryItem.id` (stable, persisted; collisions possible but rare).
- **Thread**: all in-scope sends and mutations run on the server main thread; no locking needed for counters
  (inferred).
