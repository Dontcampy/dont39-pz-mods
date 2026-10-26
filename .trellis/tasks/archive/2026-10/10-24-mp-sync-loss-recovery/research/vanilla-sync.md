# Research: vanilla B42 multiplayer sync, loss modes and repair paths

- **Query**: Is it true that PZ B42's own MP data sync "has no version control; incremental sync loses packets and
  cannot detect or resend them"? Packet reliability per type, which packets are deltas, existing resync paths, where
  loss really happens, and the narrowest hook points for a client+server Java mod.
- **Scope**: internal (game bytecode via `javap -c -p -l -constants`, JDK 25), plus RakNet semantics (external, from
  RakNet's documented API; not re-fetched).
- **Date**: 2026-10-24

## Source and method

- Client jar `E:/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid.jar` and dedicated server jar
  `.../Project Zomboid Dedicated Server/java/projectzomboid.jar` are **byte-identical** (SHA-1
  `4af3e9e450623d2fde3ac0fbcc35962b96f43117`, 65,682,033 bytes). Everything below holds for both sides.
- Evidence is cited as `Class.method Lnnn` = source line from the class's `LineNumberTable`. "Inferred" marks
  conclusions not directly visible in one method body.
- The native transport is stock RakNet (`RakNet64.dll` embeds `raknet/Source/ReliabilityLayer.cpp` paths);
  `ZNetJNI64.dll` implements `RakNetPeerInterface.sendNative` and imports `SteamNetworkingSockets012`,
  `SteamNetworkingMessages002`, `SteamNetworking006` for Steam mode. The native side was not disassembled.

## Short answer

- **"No version control"**: true. No state-sync packet carries a sequence, revision, version or checksum field
  (field scan of all 244 packet classes, `ContainerID`, `MovingObject`, `Transaction`; only login/checksum/ticket
  packets have such fields). Nothing on the receiver compares versions.
- **"Packets lost on the wire"**: mostly not. 283 of 298 packet types are RakNet `RELIABLE` or `RELIABLE_ORDERED`;
  RakNet retransmits those until acked or the connection drops (external semantics). The game ignores `Send`'s return
  value, so it never learns about a native-side failure either.
- **What actually loses updates** (all silent or log-only, none resent):
  1. the **receiver discards** an update whose target it cannot resolve (container, item, square, object index),
     with no log or a debug/warn line, and never asks for a refresh;
  2. **cross-reliability / cross-channel reordering**: item field/mod-data updates are `RELIABLE` (unordered) while the
     add/remove of the item is `RELIABLE_ORDERED`, and several related types use different ordering channels, so a
     follow-up can overtake the packet that creates its target and is then discarded (inferred from RakNet semantics);
  3. **index-based addressing** of world objects and object containers (square + object index): one missed
     add/remove shifts indices, so later updates hit the wrong object, or a throwaway dummy container;
  4. **never sent**: server deltas go only to `isFullyConnected()` and `isRelevantTo(x,y)` connections; and a chunk
     snapshot taken before a delta can be loaded on the client after the delta arrived (inferred race);
  5. **sender-side drops**: client-side per-type rate limit (`MaxPacketsPerSecond`, default 300/s per type) cancels
     outgoing packets; write exceptions and >1,000,000-byte payloads cancel the packet with a log line.
- **Repair paths**: vehicles have one (unknown vehicle -> `VehicleRequest` -> `VehicleFullUpdate`). Containers have
  none once explored (`RequestItemsForContainer` only fills unexplored containers; `ItemContainer.requestSync` is an
  empty stub). World objects are only refreshed by a chunk reload (full snapshot) plus `ChunkObjectState` on chunk load.
  Player stats/XP/body are full snapshots and self-heal on the next send.

---

## 1. How packets are sent

### Mechanism

- `zombie.network.PacketTypes$PacketType` is an enum of **298** constants; the wire id is `ordinal()`
  (`PacketType.getId L961`), written after a `byte 134` marker (`PacketType.doPacket L956-958`).
- 244 constants name an `INetworkPacket` class (`PacketType(String,int,Class) L898-903`). Its settings come from the
  class annotation `@zombie.network.PacketSetting` (RUNTIME, TYPE):
  `int priority(); int reliability(); byte ordering(); Capability requiredCapability(); int handlingType();
  AntiCheat[] anticheats() default {None}; boolean isCached() default false`.
  The main constructor reads it with `Class.getAnnotationsByType` (`PacketType.getPacketSetting L937-941`) and copies
  each value into `packetPriority / packetReliability / orderingChannel / requiredCapability / handlingType /
  anticheats / isCached` (`PacketType.<init> L920-931`). A class without the annotation keeps defaults and logs
  `"The %s class doesn't have PacketSetting attributes"` (L923); none in this build lacks it.
- 54 "legacy" constants pass `(priority, reliability[, ordering], capability, serverLambda, clientLambda,
  loadingLambda)` (`PacketType.<init> L881-885`, `L891-895`); ordering defaults to 0 and `handlingType` is derived from
  which lambdas are non-null (`PacketSetting$HandlingType.getType`).
- Value meaning, from `PacketType.getFlags L1072-1092` (`I/H/M/L`, `U/US/R/RO/RS`), equal to RakNet's enums:
  priority `0 IMMEDIATE, 1 HIGH, 2 MEDIUM, 3 LOW`; reliability `0 UNRELIABLE, 1 UNRELIABLE_SEQUENCED, 2 RELIABLE,
  3 RELIABLE_ORDERED, 4 RELIABLE_SEQUENCED`. `handlingType` bits: `1 Server, 2 Client, 4 ClientLoading`.
- `isCached = true` would make `INetworkPacket.sendToConnection L135-136` drop a send whose `ByteBufferWriter.hashCode`
  equals the last one sent for that type on that connection. **No packet in this build sets `isCached`.**

### Send path (one choke point)

1. `INetworkPacket.sendToConnection L130-145` (private interface method, used by `sendToServer`, `sendToClient`,
   `sendToClients`, `sendToRelativeClients`, static `send/sendToAll/sendToRelative`): `connection.startPacket()`
   (locks `UdpConnection.bufferLock`, clears the shared writer, `UdpConnection.startPacket L215-217`) ->
   `packetType.doPacket(b)` -> `this.write(b)` -> `packetType.send(connection)`. Any exception -> `cancelPacket()` +
   `printException` (L140-142): **packet dropped, logged**.
2. `PacketType.send(IConnection) L945-953`: on the client, `connection.isLimitExceeded(this)` -> `cancelPacket()` and
   return (**dropped**); else `connection.endPacket(packetPriority, packetReliability, orderingChannel)`.
3. `UdpConnection.endPacket L328-337` -> `flipSendUnlock L340-344` -> `RakNetPeerInterface.Send(bb, pri, rel, ord,
   guid, false)`; the int result is popped (ignored). `RakNetPeerInterface.Send L167-185`: payload larger than its
   1,000,000-byte direct buffer -> `System.out "Packet data too big."` and return (**dropped**, L169-172); exception ->
   `"Other weird packet data error."` (L183). `UdpConnection.bb` is also 1,000,000 bytes (`UdpConnection.<init> L54`);
   overflowing it during `write` lands in step 1's catch.
- Legacy senders in `GameServer`/`GameClient` also call `PacketType.X.doPacket` + `PacketType.X.send(connection)`.
  Direct `endPacket*` calls outside `PacketType.send` exist only in `FakeClientManager` (test client) and
  `UdpEngine.endPacketBroadcast` (used by `ServerMap` for `ServerQuit`).

### Receive path

- Server: `GameServer.mainLoopDealWithNetData L1602-1640` -> `PacketType.onServerPacket(ByteBufferReader,
  UdpConnection) L965-1008`: authorization (`PacketAuthorization.isAuthorized`, L967) -> limit check **log only**
  (L969-970) -> `parseServer` -> `isConsistent` false -> `logInconsistentPacket` (warn) + `sync()` and return
  (L982-985) -> each enabled anticheat `isValid` false -> warn `"The packet %s is not valid"` + `sync()` and return
  (L992-997) -> `processServer`. Handler exceptions: `GameServer.mainLoopDealWithNetData L1635-1640` logs and calls
  `AntiCheat.PacketException.act` (counts toward a kick).
- Client: `GameClient.update` moves `MainLoopNetDataQ` into `MainLoopNetData`, processes entries older than 5 ms in
  arrival order (L384-386) via `mainLoopDealWithNetData L534-554` -> `PacketType.onClientPacket L1011-1040`:
  `parseClient` -> `isConsistent` false -> `EventManager.report("The packet %s is not consistent: %s")` and return
  (L1022-1024) -> `postpone()` if `isPostponed` -> `processClient`. Exceptions: logged and discarded
  (`GameClient.mainLoopDealWithNetData L550-551`).
- `INetworkPacket.sync(PacketType, UdpConnection)` is an empty default (L117). Only `packets.hit.PlayerHit` overrides
  it. So on the server a rejected packet is just dropped.
- `EventManager.report L23-24` only forwards to registered `IEventController`s; the classes referencing
  `IEventController` are `DiscordBot`, `StackBot`, `GameServer` (server side). Inferred: on a client the report goes
  nowhere, so a client-side inconsistent packet is dropped **without any log line**.
- Packets arriving before `PlayerConnect` is sent are not lost: those the loading handler does not take are kept in
  `LoadingMainLoopNetData` (`GameClient.update L359-363`) and replayed after (`L370-371 "Processing delayed
  packets..."`). Coop-player squares being loaded defer packets via `GameClient.delayPacket L521-531` (only
  `IsoWorld.addCoopPlayers`, not the main player's chunk streaming).

### Counts

| Reliability | Packet types |
|---|---|
| RELIABLE (2) | 200 |
| RELIABLE_ORDERED (3) | 83 |
| UNRELIABLE (0) | 11 |
| UNRELIABLE_SEQUENCED (1) | 4 |
| RELIABLE_SEQUENCED (4) | 0 |

Ordering channels in use: 0 (249 types), 1 (19: item transactions/actions, container add/remove/replace/delete,
map add/remove, item fields/mod data, `ObjectModData`, `PlayerDropHeldItems`, `AnimalCommand`), 3 (`SyncIsoObject`,
chunk object state), 4 (chunk streaming), 5 (player update/body damage), 7 (animals), 8 (vehicle physics/sound),
9-14 (player injuries, damage, XP, stats, effects, health: one channel each).

### Full table

Columns: `#` = wire id (ordinal), Rel `U / U_SEQ / R / R_ORD`, Pri `IMM / HIGH / MED / LOW`, Ch = ordering channel,
Handled = `S` server, `C` client, `CL` client while loading. Legacy rows name the receive lambdas.

| # | PacketType | Class / handler | Rel | Pri | Ch | Handled | Anticheats / cached |
|---|---|---|---|---|---|---|---|
| 0 | Checksum | `service/ChecksumPacket` | R_ORD | HIGH | 0 | S+C+CL |  |
| 1 | Login | `connection/LoginPacket` | R_ORD | HIGH | 0 | S |  |
| 2 | LoginQueueRequest | `connection/QueuePacket` | R_ORD | IMM | 0 | S+CL |  |
| 3 | LoginQueueDone | `connection/LoginQueueDonePacket` | R_ORD | IMM | 0 | S+CL |  |
| 4 | LoadPlayerProfile | `connection/LoadPlayerProfilePacket` | R_ORD | HIGH | 0 | S+C |  |
| 5 | CreatePlayer | `character/CreatePlayerPacket` | R_ORD | HIGH | 0 | S+C+CL |  |
| 6 | PlayerConnect | `connection/ConnectPacket` | R_ORD | HIGH | 0 | S |  |
| 7 | ConnectedPlayer | `connection/ConnectedPacket` | R_ORD | HIGH | 0 | C |  |
| 8 | ConnectCoop | `connection/ConnectCoopPacket` | R | HIGH | 0 | S |  |
| 9 | ConnectedCoop | `connection/ConnectedCoopPacket` | R | HIGH | 0 | C |  |
| 10 | GoogleAuthKey | `connection/GoogleAuthKeyPacket` | R | IMM | 0 | S+C+CL |  |
| 11 | GoogleAuth | `connection/GoogleAuthPacket` | R | IMM | 0 | S |  |
| 12 | GoogleAuthRequest | `connection/GoogleAuthRequestPacket` | R | IMM | 0 | CL |  |
| 13 | PlayerUpdateReliable | `character/PlayerPacketReliable` | R_ORD | HIGH | 5 | S+C | Power, Speed, NoClip, Player |
| 14 | PlayerUpdateUnreliable | `character/PlayerPacketUnreliable` | U_SEQ | MED | 5 | S+C | Power, Speed, NoClip |
| 15 | PlayerDataRequest | `character/PlayerDataRequestPacket` | R | HIGH | 0 | S |  |
| 16 | HumanVisual | `HumanVisualPacket` | R | HIGH | 0 | S+C |  |
| 17 | SyncClothing | `SyncClothingPacket` | R_ORD | HIGH | 0 | S+C |  |
| 18 | SyncVisuals | `SyncVisualsPacket` | R_ORD | HIGH | 0 | S+C |  |
| 19 | Equip | `EquipPacket` | R_ORD | HIGH | 0 | S+C |  |
| 20 | AddXPMultiplier | `AddXPMultiplierPacket` | R | MED | 0 | C |  |
| 21 | HaloText | `HaloTextPacket` | R | HIGH | 0 | C |  |
| 22 | PlayerDeath | `character/DeadPlayerPacket` | R_ORD | IMM | 0 | C |  |
| 23 | ChangeSafety | `SafetyPacket` | R | HIGH | 0 | S+C |  |
| 24 | BodyDamageUpdate | `BodyDamageUpdatePacket` | R | HIGH | 5 | S+C |  |
| 25 | GameCharacterAttachedItem | `GameCharacterAttachedItemPacket` | R | HIGH | 0 | S+C |  |
| 26 | VariableSync | `VariableSyncPacket` | R_ORD | HIGH | 0 | S+C+CL |  |
| 27 | SyncPlayerStats | `SyncPlayerStatsPacket` | R | HIGH | 0 | S+C |  |
| 28 | SyncPlayerFields | `SyncPlayerFieldsPacket` | R | HIGH | 0 | C |  |
| 29 | ForageItemFound | `character/ForageItemFoundPacket` | R | HIGH | 0 | S |  |
| 30 | ForageRequestZone | `foraging/ForageRequestZonePacket` | R | HIGH | 0 | S |  |
| 31 | ForagePool | `foraging/ForagePoolPacket` | R | HIGH | 0 | C |  |
| 32 | ForageSpot | `foraging/ForageSpotPacket` | R | HIGH | 0 | S |  |
| 33 | PlayerDropHeldItems | `character/PlayerDropHeldItemsPacket` | R_ORD | HIGH | 1 | S |  |
| 34 | ClothingWetness | `character/ClothingWetnessPacket` | R | HIGH | 0 | C |  |
| 35 | RequestMedicalCheck | `RequestMedicalCheckPacket` | R | HIGH | 0 | S+C |  |
| 36 | HiddenAuthors | `HiddenAuthorsPacket` | R_ORD | HIGH | 0 | S+C |  |
| 37 | ServerMap | `ServerMapPacket` | R_ORD | IMM | 4 | C+CL |  |
| 38 | SentChunk | `SentChunkPacket` | R | IMM | 4 | - |  |
| 39 | RequestZipList | `RequestZipListPacket` | R | HIGH | 4 | S |  |
| 40 | NotRequiredInZip | `NotRequiredInZipPacket` | R | IMM | 4 | S |  |
| 41 | RequestData | `RequestDataPacket` | R | HIGH | 4 | S+C+CL |  |
| 42 | ChunkNotReady | `ChunkNotReadyPacket` | R | HIGH | 4 | - |  |
| 43 | ZombieList | `character/ZombieListPacket` | U | HIGH | 0 | C |  |
| 44 | ZombieSimulationUnreliable | `character/ZombieSimulationUnreliablePacket` | U | MED | 0 | S |  |
| 45 | ZombieSimulationReliable | `character/ZombieSimulationReliablePacket` | R | IMM | 0 | S |  |
| 46 | ZombieSynchronizationUnreliable | `character/ZombieSynchronizationUnreliablePacket` | U | MED | 0 | C |  |
| 47 | ZombieSynchronizationReliable | `character/ZombieSynchronizationReliablePacket` | R | IMM | 0 | C |  |
| 48 | ZombieDeleteOnClient | `character/ZombieDeleteOnClientPacket` | R | HIGH | 0 | C |  |
| 49 | ZombieRequest | `character/ZombieRequestPacket` | R | HIGH | 0 | S |  |
| 50 | ZombieStagger | `character/ZombieStaggerPacket` | R_ORD | IMM | 0 | S+C |  |
| 51 | ZombieDeath | `character/DeadZombiePacket` | R_ORD | IMM | 0 | C |  |
| 52 | SlowFactor | `SlowFactorPacket` | R | HIGH | 0 | C |  |
| 53 | ZombieControl | `character/ZombieControlPacket` | R | IMM | 0 | C |  |
| 54 | Thump | `character/ThumpPacket` | R | HIGH | 0 | S+C |  |
| 55 | ZombieFall | `character/ZombieFallPacket` | R_ORD | IMM | 0 | S+C |  |
| 56 | AnimalCommand | `character/AnimalCommandPacket` | R_ORD | HIGH | 1 | S+C |  |
| 57 | AnimalOwnership | `character/AnimalOwnershipPacket` | R_ORD | IMM | 7 | C |  |
| 58 | AnimalPacket | `character/AnimalPacket` | U | MED | 7 | S+C |  |
| 59 | AnimalUpdateReliable | `character/AnimalUpdateReliablePacket` | R | IMM | 7 | S+C |  |
| 60 | AnimalUpdateUnreliable | `character/AnimalUpdateUnreliablePacket` | U | MED | 7 | S+C |  |
| 61 | AnimalEvent | `actions/AnimalEventPacket` | R | MED | 7 | S+C |  |
| 62 | AnimalDeath | `character/DeadAnimalPacket` | R_ORD | IMM | 0 | C |  |
| 63 | AnimalTracks | `character/AnimalTracksPacket` | R | HIGH | 0 | S+C |  |
| 64 | AddTrack | `AddTrackPacket` | R | HIGH | 0 | C |  |
| 65 | ManageAnimalTrackOnWorldMap | `ManageAnimalTrackOnWorldMapPacket` | R | MED | 7 | C |  |
| 66 | VehicleRequest | `vehicle/VehicleRequestPacket` | R | HIGH | 0 | S |  |
| 67 | VehicleFullUpdate | `vehicle/VehicleFullUpdatePacket` | R_ORD | HIGH | 0 | C |  |
| 68 | VehicleUpdate | `vehicle/VehicleUpdatePacket` | R_ORD | HIGH | 0 | C |  |
| 69 | VehiclePhysicsReliable | `vehicle/VehiclePhysicsReliablePacket` | R_ORD | HIGH | 8 | S+C | Speed |
| 70 | VehiclePhysicsUnreliable | `vehicle/VehiclePhysicsUnreliablePacket` | U_SEQ | MED | 8 | S+C | Speed |
| 71 | VehicleTowingAttach | `vehicle/VehicleTowingAttachPacket` | R_ORD | HIGH | 0 | C |  |
| 72 | VehicleTowingDetach | `vehicle/VehicleTowingDetachPacket` | R_ORD | HIGH | 0 | C |  |
| 73 | VehicleTowingState | `vehicle/VehicleTowingStatePacket` | R | HIGH | 0 | C |  |
| 74 | VehicleRemove | `vehicle/VehicleRemovePacket` | R_ORD | HIGH | 0 | C |  |
| 75 | VehicleCollide | `vehicle/VehicleCollidePacket` | R | HIGH | 0 | S |  |
| 76 | VehiclePassengerPosition | `vehicle/VehiclePassengerPositionPacket` | R | HIGH | 0 | S+C |  |
| 77 | VehiclePassengerRequest | `vehicle/VehiclePassengerRequestPacket` | R | HIGH | 0 | S |  |
| 78 | VehiclePassengerResponse | `vehicle/VehiclePassengerResponsePacket` | R | HIGH | 0 | C |  |
| 79 | VehicleEnter | `vehicle/VehicleEnterPacket` | R_ORD | HIGH | 0 | S+C |  |
| 80 | VehicleExit | `vehicle/VehicleExitPacket` | R_ORD | HIGH | 0 | S+C |  |
| 81 | VehicleSwitchSeat | `vehicle/VehicleSwitchSeatPacket` | R_ORD | HIGH | 0 | S+C |  |
| 82 | VehicleSoundAddVehicle | `zombie/vehicleNetworkSound/AddVehiclePacket` | R | MED | 8 | C+CL |  |
| 83 | VehicleSoundRemoveVehicle | `zombie/vehicleNetworkSound/RemoveVehiclePacket` | R | MED | 8 | C+CL |  |
| 84 | VehicleSoundUpdateVehicle | `zombie/vehicleNetworkSound/UpdateVehiclePacket` | R | MED | 8 | C+CL |  |
| 85 | State | `actions/StatePacket` | R_ORD | IMM | 0 | S+C |  |
| 86 | Helicopter | `actions/HelicopterPacket` | R | HIGH | 0 | C |  |
| 87 | SmashWindow | `actions/SmashWindowPacket` | R | HIGH | 0 | S+C |  |
| 88 | EatFood | `actions/EatFoodPacket` | R | HIGH | 0 | S+C |  |
| 89 | Drink | `legacy: GameServer.receiveDrink` | R | HIGH | 0 | S |  |
| 90 | BurnCorpse | `actions/BurnCorpsePacket` | R | MED | 0 | S |  |
| 91 | SneezeCough | `actions/SneezeCoughPacket` | U | LOW | 0 | C |  |
| 92 | AddBlood | `actions/AddBloodPacket` | R | LOW | 0 | C |  |
| 93 | RemoveBlood | `actions/RemoveBloodPacket` | R | MED | 0 | S+C |  |
| 94 | WakeUpPlayer | `actions/WakeUpPlayerPacket` | R | MED | 0 | S+C |  |
| 95 | EatBody | `legacy: GameServer.receiveEatBody / GameClient.receiveEatBody` | R | HIGH | 0 | S+C |  |
| 96 | ReadAnnotedMap | `ReadAnnotedMapPacket` | R | HIGH | 0 | S |  |
| 97 | ZombieHelmetFalling | `ZombieHelmetFallingPacket` | R | HIGH | 0 | C |  |
| 98 | TimeSync | `service/TimeSyncPacket` | R | IMM | 0 | S+C+CL |  |
| 99 | SetMultiplier | `service/SetMultiplierPacket` | R | MED | 0 | C |  |
| 100 | AccessDenied | `service/AccessDeniedPacket` | R | IMM | 0 | CL |  |
| 101 | StartPause | `service/StartPausePacket` | R_ORD | HIGH | 0 | C |  |
| 102 | StopPause | `service/StopPausePacket` | R_ORD | HIGH | 0 | C |  |
| 103 | PlayerTimeout | `service/PlayerTimeoutPacket` | R | HIGH | 0 | C+CL |  |
| 104 | SyncClock | `service/SyncClockPacket` | R | HIGH | 0 | C+CL |  |
| 105 | SandboxOptions | `legacy: GameServer.receiveSandboxOptions / GameClient.receiveSandboxOptions` | R | HIGH | 0 | S+C |  |
| 106 | ServerQuit | `service/ServerQuitPacket` | R | IMM | 0 | C |  |
| 107 | MessageForAdmin | `MessageForAdminPacket` | R | MED | 0 | C |  |
| 108 | MetaData | `connection/MetaDataPacket` | R | HIGH | 0 | C+CL |  |
| 109 | MetaGrid | `MetaGridPacket` | R | HIGH | 0 | C |  |
| 110 | WorldMessage | `WorldMessagePacket` | R | MED | 0 | S |  |
| 111 | ReceiveCommand | `legacy: GameServer.receiveReceiveCommand` | R_ORD | MED | 0 | S |  |
| 112 | ReloadOptions | `ReloadOptionsPacket` | R | HIGH | 0 | C |  |
| 113 | Kicked | `KickedPacket` | R | IMM | 0 | C+CL |  |
| 114 | Ping | `legacy: GameServer.receivePing / GameClient.receivePing / GameClient.receivePing` | U | IMM | 0 | S+C+CL |  |
| 115 | SpawnRegion | `legacy: GameClient.receiveSpawnRegion / GameClient.receiveSpawnRegion` | R | HIGH | 0 | C+CL |  |
| 116 | WorldMapPlayerPosition | `legacy: GameServer.receiveWorldMapPlayerPosition / GameClient.receiveWorldMapPlayerPosition` | U_SEQ | HIGH | 0 | S+C |  |
| 117 | WorldMap | `legacy: GameServer.receiveWorldMap / GameClient.receiveWorldMap / GameClient.receiveWorldMap` | R | HIGH | 0 | S+C+CL |  |
| 118 | ItemTransaction | `ItemTransactionPacket` | R_ORD | HIGH | 1 | S+C |  |
| 119 | GeneralAction | `GeneralActionPacket` | R_ORD | HIGH | 1 | S |  |
| 120 | NetTimedAction | `NetTimedActionPacket` | R_ORD | HIGH | 1 | S+C |  |
| 121 | BuildAction | `BuildActionPacket` | R_ORD | HIGH | 1 | S+C |  |
| 122 | FishingAction | `FishingActionPacket` | R_ORD | HIGH | 1 | S+C |  |
| 123 | AddInventoryItemToContainer | `AddInventoryItemToContainerPacket` | R_ORD | HIGH | 1 | C |  |
| 124 | RemoveInventoryItemFromContainer | `RemoveInventoryItemFromContainerPacket` | R_ORD | HIGH | 1 | S+C |  |
| 125 | ReplaceInventoryItemInContainer | `ReplaceInventoryItemInContainerPacket` | R_ORD | HIGH | 1 | C |  |
| 126 | SyncItemDelete | `SyncItemDeletePacket` | R_ORD | HIGH | 1 | S+C |  |
| 127 | AddItemToMap | `AddItemToMapPacket` | R_ORD | HIGH | 1 | C |  |
| 128 | AddObjectToMap | `AddObjectToMapPacket` | R_ORD | HIGH | 1 | S+C |  |
| 129 | AddCorpseToMap | `actions/AddCorpseToMapPacket` | R_ORD | HIGH | 0 | S+C |  |
| 130 | RemoveItemFromSquare | `RemoveItemFromSquarePacket` | R_ORD | HIGH | 1 | C |  |
| 131 | RemoveCorpseFromMap | `character/RemoveCorpseFromMapPacket` | R | HIGH | 0 | S+C |  |
| 132 | RequestItemsForContainer | `RequestItemsForContainerPacket` | R | HIGH | 0 | S |  |
| 133 | RemoveContestedItemsFromInventory | `RemoveContestedItemsFromInventoryPacket` | R | HIGH | 0 | C |  |
| 134 | ItemStats | `ItemStatsPacket` | R_ORD | HIGH | 0 | S+C |  |
| 135 | AddBrokenGlass | `AddBrokenGlassPacket` | R | HIGH | 0 | S+C |  |
| 136 | AddExplosiveTrap | `AddExplosiveTrapPacket` | R | HIGH | 0 | S+C |  |
| 137 | SledgehammerDestroy | `SledgehammerDestroyPacket` | R | HIGH | 0 | S+C |  |
| 138 | SyncPlayerAlarmClock | `SyncPlayerAlarmClockPacket` | R | HIGH | 0 | S+C |  |
| 139 | SyncWorldAlarmClock | `SyncWorldAlarmClockPacket` | R | HIGH | 0 | S+C |  |
| 140 | SyncExtendedPlacement | `SyncExtendedPlacementPacket` | R | HIGH | 0 | S+C |  |
| 141 | SyncItemActivated | `SyncItemActivatedPacket` | R_ORD | HIGH | 1 | S+C |  |
| 142 | GlobalObjects | `GlobalObjectsPacket` | R | HIGH | 0 | S+C |  |
| 143 | ReceiveModData | `service/ReceiveModDataPacket` | R | HIGH | 0 | S+C |  |
| 144 | ReceiveContainerModData | `service/ReceiveContainerModDataPacket` | R | HIGH | 0 | S+C |  |
| 145 | GlobalModData | `service/GlobalModDataPacket` | R | IMM | 0 | S+C |  |
| 146 | GlobalModDataRequest | `service/GlobalModDataRequestPacket` | R | IMM | 0 | S |  |
| 147 | SyncItemFields | `SyncItemFieldsPacket` | R | HIGH | 1 | S+C |  |
| 148 | SyncItemModData | `SyncItemModDataPacket` | R | MED | 1 | C |  |
| 149 | SyncHandWeaponFields | `SyncHandWeaponFieldsPacket` | R | HIGH | 1 | S+C |  |
| 150 | PlayerHitSquare | `hit/PlayerHitSquarePacket` | R_ORD | IMM | 0 | S+C | HitLongDistance |
| 151 | PlayerHitObject | `hit/PlayerHitObjectPacket` | R_ORD | IMM | 0 | S+C | HitLongDistance, HitWeapon |
| 152 | PlayerHitVehicle | `hit/PlayerHitVehiclePacket` | R_ORD | IMM | 0 | S+C | HitLongDistance |
| 153 | PlayerHitZombie | `hit/PlayerHitZombiePacket` | R_ORD | IMM | 0 | S+C | HitDamage, HitLongDistance, HitWeapon |
| 154 | PlayerHitPlayer | `hit/PlayerHitPlayerPacket` | R_ORD | IMM | 0 | S+C | HitDamage, HitLongDistance, HitWeapon, Safety |
| 155 | PlayerHitAnimal | `hit/PlayerHitAnimalPacket` | R_ORD | IMM | 0 | S+C | HitDamage, HitLongDistance, HitWeapon |
| 156 | ZombieHitPlayer | `hit/ZombieHitPlayerPacket` | R_ORD | IMM | 0 | S+C | HitShortDistance, Target |
| 157 | AnimalHitPlayer | `hit/AnimalHitPlayerPacket` | R_ORD | IMM | 0 | C |  |
| 158 | AnimalHitAnimal | `hit/AnimalHitAnimalPacket` | R_ORD | IMM | 0 | C |  |
| 159 | AnimalHitThumpable | `hit/AnimalHitThumpablePacket` | R_ORD | IMM | 0 | C |  |
| 160 | ZombieHitThumpable | `hit/ZombieHitThumpablePacket` | R_ORD | IMM | 0 | S+C | HitShortDistance |
| 161 | VehicleHitZombie | `hit/VehicleHitZombiePacket` | R_ORD | IMM | 0 | S+C | HitDamage, HitShortDistance, Speed, HitVehicle |
| 162 | VehicleHitPlayer | `hit/VehicleHitPlayerPacket` | R_ORD | IMM | 0 | S+C | HitDamage, HitShortDistance, Safety, Speed, HitVehicle |
| 163 | VehicleHitAnimal | `hit/VehicleHitAnimalPacket` | R_ORD | IMM | 0 | S | HitDamage, HitShortDistance, Speed, HitVehicle |
| 164 | PlayerDamage | `character/PlayerDamagePacket` | R_ORD | MED | 10 | C |  |
| 165 | PlayerStats | `character/PlayerStatsPacket` | R_ORD | MED | 12 | C |  |
| 166 | PlayerHealth | `character/PlayerHealthPacket` | R_ORD | MED | 14 | C |  |
| 167 | PlayerInjuries | `character/PlayerInjuriesPacket` | R_ORD | MED | 9 | C |  |
| 168 | PlayerEffects | `character/PlayerEffectsPacket` | R_ORD | MED | 13 | C |  |
| 169 | PlayerXp | `PlayerXpPacket` | R_ORD | MED | 11 | S+C |  |
| 170 | AttackCollisionCheckPacket | `hit/AttackCollisionCheckPacket` | R_ORD | IMM | 0 | S |  |
| 171 | PlayerEmptyShot | `character/PlayerEmptyShotPacket` | U | MED | 5 | S+C |  |
| 172 | PlayerSteppedOnGlass | `character/PlayerSteppedOnGlassPacket` | R | MED | 0 | S |  |
| 173 | PlaySound | `sound/PlaySoundPacket` | R | HIGH | 0 | S+C |  |
| 174 | RangedWeaponSound | `sound/RangedWeaponSoundPacket` | R | HIGH | 0 | S+C |  |
| 175 | LoopedRangedWeaponSound | `sound/LoopedRangedWeaponSoundPacket` | R | HIGH | 0 | S+C |  |
| 176 | WorldSoundPacket | `sound/WorldSoundPacket` | R | HIGH | 0 | S+C |  |
| 177 | AddAmbient | `legacy: GameClient.receiveAddAmbient` | R | HIGH | 0 | C |  |
| 178 | ZombieSound | `legacy: GameClient.receiveZombieSound` | R | HIGH | 0 | C |  |
| 179 | PlayWorldSound | `sound/PlayWorldSoundPacket` | R | HIGH | 0 | S+C |  |
| 180 | StopSound | `sound/StopSoundPacket` | R | HIGH | 0 | S+C |  |
| 181 | PlaySoundEveryPlayer | `legacy: GameClient.receivePlaySoundEveryPlayer` | R | HIGH | 0 | C |  |
| 182 | Statistics | `service/StatisticsPacket` | U_SEQ | LOW | 0 | S+C |  |
| 183 | ScoreboardUpdate | `service/ScoreboardUpdatePacket` | R | HIGH | 0 | S+C |  |
| 184 | Weather | `WeatherPacket` | R | HIGH | 0 | C |  |
| 185 | StartRain | `legacy: GameClient.receiveStartRain` | R | HIGH | 0 | C |  |
| 186 | StopRain | `legacy: GameClient.receiveStopRain` | R | HIGH | 0 | C |  |
| 187 | ClimateManagerPacket | `legacy: GameServer.receiveClimateManagerPacket / GameClient.receiveClimateManagerPacket` | R | HIGH | 0 | S+C |  |
| 188 | SyncPuddles | `SyncPuddlesPacket` | R | HIGH | 0 | C |  |
| 189 | IsoRegionServerPacket | `legacy: GameClient.receiveIsoRegionServerPacket` | R | HIGH | 0 | C |  |
| 190 | IsoRegionClientRequestFullUpdate | `legacy: GameServer.receiveIsoRegionClientRequestFullUpdate` | R | HIGH | 0 | S |  |
| 191 | SyncNonPvpZone | `SyncNonPvpZonePacket` | R | HIGH | 0 | S+C |  |
| 192 | SyncThumpable | `SyncThumpablePacket` | R | HIGH | 0 | S+C |  |
| 193 | SyncIsoObject | `legacy: GameServer.receiveSyncIsoObject / GameClient.receiveSyncIsoObject` | R | HIGH | 3 | S+C |  |
| 194 | ClientCommand | `legacy: GameServer.receiveClientCommand / GameClient.receiveClientCommand` | R | HIGH | 0 | S+C |  |
| 195 | ObjectModData | `ObjectModDataPacket` | R_ORD | MED | 1 | S+C |  |
| 196 | ObjectChange | `ObjectChangePacket` | R_ORD | HIGH | 0 | C |  |
| 197 | BloodSplatter | `legacy: GameClient.receiveBloodSplatter` | R | HIGH | 0 | C |  |
| 198 | ZombieDescriptors | `legacy: GameClient.receiveZombieDescriptors` | R | HIGH | 0 | C |  |
| 199 | StartFire | `StartFirePacket` | R | HIGH | 0 | C |  |
| 200 | StopFire | `StopFirePacket` | R | HIGH | 0 | S+C |  |
| 201 | UpdateItemSprite | `legacy: GameServer.receiveUpdateItemSprite / GameClient.receiveUpdateItemSprite` | R | HIGH | 0 | S+C |  |
| 202 | SendCustomColor | `CustomColorPacket` | R | MED | 0 | C |  |
| 203 | SyncCompost | `legacy: GameServer.receiveSyncCompost / GameClient.receiveSyncCompost` | R | HIGH | 0 | S+C |  |
| 204 | GetModData | `GetModDataPacket` | R | MED | 0 | S |  |
| 205 | SyncPerks | `legacy: GameServer.receiveSyncPerks / GameClient.receiveSyncPerks` | R | HIGH | 0 | S+C |  |
| 206 | SyncEquippedRadioFreq | `legacy: GameServer.receiveSyncEquippedRadioFreq / GameClient.receiveSyncEquippedRadioFreq` | R | HIGH | 0 | S+C |  |
| 207 | UpdateOverlaySprite | `UpdateOverlaySpritePacket` | R | HIGH | 0 | S+C |  |
| 208 | AddAlarm | `legacy: GameClient.receiveAddAlarm` | R | HIGH | 0 | C |  |
| 209 | ChangeTextColor | `legacy: GameServer.receiveChangeTextColor / GameClient.receiveChangeTextColor` | R | HIGH | 0 | S+C |  |
| 210 | SyncCustomLightSettings | `SyncCustomLightSettingsPacket` | R | HIGH | 0 | S+C |  |
| 211 | ChunkObjectStateResponse | `legacy: GameClient.receiveChunkObjectState` | R | HIGH | 3 | C |  |
| 212 | ChunkObjectStateRequest | `ChunkObjectStateRequestPacket` | R | HIGH | 3 | S |  |
| 213 | StartFishSplash | `legacy: GameServer.receiveBigWaterSplash / GameClient.receiveBigWaterSplash` | R | HIGH | 0 | S+C |  |
| 214 | FishingData | `legacy: GameServer.receiveFishingDataRequest / GameClient.receiveFishingData` | R | HIGH | 0 | S+C |  |
| 215 | ToxicBuilding | `legacy: GameClient.receiveToxicBuilding` | R | HIGH | 0 | C |  |
| 216 | AddItemInInventory | `AddItemInInventoryPacket` | R | HIGH | 0 | C |  |
| 217 | PlayerInventory | `service/PlayerInventoryPacket` | R | HIGH | 0 | S+C |  |
| 218 | ExtraInfo | `ExtraInfoPacket` | R | HIGH | 0 | S+C |  |
| 219 | Teleport | `TeleportPacket` | R | IMM | 0 | S+C |  |
| 220 | InvMngReqItem | `legacy: GameServer.receiveInvMngReqItem / GameClient.receiveInvMngReqItem` | R | HIGH | 0 | S+C |  |
| 221 | InvMngGetItem | `legacy: GameServer.receiveInvMngGetItem / GameClient.receiveInvMngGetItem` | R | HIGH | 0 | S+C |  |
| 222 | InvMngRemoveItem | `legacy: GameServer.receiveInvMngRemoveItem / GameClient.receiveInvMngRemoveItem` | R | HIGH | 0 | S+C |  |
| 223 | InvMngUpdateItem | `legacy: GameServer.receiveInvMngUpdateItem` | R | HIGH | 0 | S |  |
| 224 | ChangePlayerStats | `legacy: GameServer.receiveChangePlayerStats / GameClient.receiveChangePlayerStats` | R | HIGH | 0 | S+C |  |
| 225 | NetworkUsers | `NetworkUsersPacket` | R | MED | 0 | C |  |
| 226 | PVPEvents | `PVPEventsPacket` | R | MED | 0 | S+C |  |
| 227 | RequestNetworkUsers | `RequestNetworkUsersPacket` | R | MED | 0 | S |  |
| 228 | NetworkUserAction | `NetworkUserActionPacket` | R | MED | 0 | S |  |
| 229 | BanUnbanUserAction | `BanUnbanUserActionPacket` | R_ORD | HIGH | 0 | S |  |
| 230 | TeleportUserAction | `TeleportUserActionPacket` | R_ORD | HIGH | 0 | S |  |
| 231 | TeleportToHimUserAction | `TeleportToHimUserActionPacket` | R_ORD | HIGH | 0 | S |  |
| 232 | Roles | `RolesPacket` | R | MED | 0 | C |  |
| 233 | RequestRoles | `RequestRolesPacket` | R | MED | 0 | S |  |
| 234 | RolesEdit | `RolesEditPacket` | R | MED | 0 | S |  |
| 235 | RequestUserLog | `service/RequestUserLogPacket` | R | MED | 0 | S+C |  |
| 236 | AddUserlog | `AddUserlogPacket` | R | MED | 0 | S |  |
| 237 | RemoveUserlog | `RemoveUserlogPacket` | R | MED | 0 | S |  |
| 238 | AddWarningPoint | `AddWarningPointPacket` | R | MED | 0 | S |  |
| 239 | ConstructedZone | `legacy: GameServer.receiveConstructedZone / GameClient.receiveConstructedZone` | R | HIGH | 0 | S+C |  |
| 240 | RegisterZone | `RegisterZonePacket` | R | HIGH | 0 | S+C |  |
| 241 | SyncZone | `SyncZonePacket` | R | MED | 0 | S+C |  |
| 242 | FactionInvite | `faction/FactionInvitePacket` | R | HIGH | 0 | S+C |  |
| 243 | FactionAccept | `faction/FactionAcceptPacket` | R | HIGH | 0 | S+C |  |
| 244 | FactionRemoveMember | `faction/FactionRemoveMemberPacket` | R | HIGH | 0 | S |  |
| 245 | FactionChangeOwner | `faction/FactionChangeOwnerPacket` | R | HIGH | 0 | S |  |
| 246 | FactionChangeTag | `faction/FactionChangeTagPacket` | R | HIGH | 0 | S |  |
| 247 | FactionChangeTitle | `faction/FactionChangeTitlePacket` | R | HIGH | 0 | S+C |  |
| 248 | FactionDisband | `faction/FactionDisbandPacket` | R | HIGH | 0 | S+C |  |
| 249 | FactionCreate | `faction/FactionCreatePacket` | R | HIGH | 0 | S |  |
| 250 | FactionSync | `faction/FactionSyncPacket` | R | HIGH | 0 | C |  |
| 251 | FactionStats | `faction/FactionStatsPacket` | R | LOW | 0 | S+C |  |
| 252 | AddTicket | `AddTicketPacket` | R | MED | 0 | S |  |
| 253 | ViewedTicket | `ViewedTicketPacket` | R | MED | 0 | S |  |
| 254 | ViewTickets | `ViewTicketsPacket` | R | MED | 0 | S+C |  |
| 255 | RemoveTicket | `RemoveTicketPacket` | R | MED | 0 | S |  |
| 256 | ViewBannedIPs | `legacy: GameServer.receiveViewBannedIPs / GameClient.receiveViewBannedIPs` | R | HIGH | 0 | S+C |  |
| 257 | ViewBannedSteamIDs | `legacy: GameServer.receiveViewBannedSteamIDs / GameClient.receiveViewBannedSteamIDs` | R | HIGH | 0 | S+C |  |
| 258 | RequestTrading | `RequestTradingPacket` | R | HIGH | 0 | S+C |  |
| 259 | TradingUIAddItem | `TradingUIAddItemPacket` | R_ORD | HIGH | 0 | S+C |  |
| 260 | TradingUIRemoveItem | `TradingUIRemoveItemPacket` | R_ORD | HIGH | 0 | S+C |  |
| 261 | TradingUIUpdateState | `TradingUIUpdateStatePacket` | R_ORD | HIGH | 0 | S+C |  |
| 262 | WarStateSync | `WarStateSyncPacket` | R | HIGH | 0 | S |  |
| 263 | WarSync | `WarSyncPacket` | R | HIGH | 0 | C |  |
| 264 | InitPlayerChat | `legacy: GameClient.receiveInitPlayerChat` | R | HIGH | 0 | C |  |
| 265 | PlayerJoinChat | `legacy: GameClient.receivePlayerJoinChat` | R | HIGH | 0 | C |  |
| 266 | PlayerLeaveChat | `legacy: GameClient.receivePlayerLeaveChat` | R | HIGH | 0 | C |  |
| 267 | ChatMessageFromPlayer | `legacy: GameServer.receiveChatMessageFromPlayer` | R | HIGH | 0 | S |  |
| 268 | ChatMessageToPlayer | `legacy: GameClient.receiveChatMessageToPlayer` | R | HIGH | 0 | C |  |
| 269 | PlayerStartPMChat | `legacy: GameServer.receivePlayerStartPMChat` | R | HIGH | 0 | S |  |
| 270 | AddChatTab | `legacy: GameClient.receiveAddChatTab` | R | HIGH | 0 | C |  |
| 271 | RemoveChatTab | `legacy: GameClient.receiveRemoveChatTab` | R | HIGH | 0 | C |  |
| 272 | PlayerConnectedToChat | `legacy: GameClient.receivePlayerConnectedToChat` | R | HIGH | 0 | C |  |
| 273 | PlayerNotFound | `legacy: GameClient.receivePlayerNotFound` | R | HIGH | 0 | C |  |
| 274 | SafehouseInvite | `safehouse/SafehouseInvitePacket` | R | HIGH | 0 | S+C | SafeHouseOwner |
| 275 | SafehouseAccept | `safehouse/SafehouseAcceptPacket` | R | HIGH | 0 | S+C | SafeHousePlayer |
| 276 | SafehouseChangeMember | `safehouse/SafehouseChangeMemberPacket` | R | HIGH | 0 | S | SafeHouseMember |
| 277 | SafehouseChangeOwner | `safehouse/SafehouseChangeOwnerPacket` | R | HIGH | 0 | S | SafeHouseOwner |
| 278 | SafehouseChangeRespawn | `safehouse/SafehouseChangeRespawnPacket` | R | HIGH | 0 | S | SafeHouseMember |
| 279 | SafehouseChangeTitle | `safehouse/SafehouseChangeTitlePacket` | R | HIGH | 0 | S | SafeHouseOwner |
| 280 | SafehouseRelease | `safehouse/SafehouseReleasePacket` | R | HIGH | 0 | S+C | SafeHouseOwner |
| 281 | SafehouseClaim | `safehouse/SafehouseClaimPacket` | R | HIGH | 0 | S | SafeHousePlayer |
| 282 | SafezoneClaim | `safehouse/SafezoneClaimPacket` | R | HIGH | 0 | S | SafeHousePlayer |
| 283 | SafehouseSync | `safehouse/SafehouseSyncPacket` | R | HIGH | 0 | C |  |
| 284 | PopmanDebugCommand | `service/PopmanDebugCommandPacket` | U | HIGH | 0 | S+C |  |
| 285 | ServerDebugInfo | `service/ServerDebugInfo` | U | HIGH | 0 | S+C |  |
| 286 | ServerLOS | `service/ServerLOSPacket` | U | HIGH | 0 | S+C |  |
| 287 | WaveSignal | `WaveSignalPacket` | R | HIGH | 0 | S+C |  |
| 288 | PlayerListensChannel | `legacy: GameServer.receivePlayerListensChannel` | R | HIGH | 0 | S |  |
| 289 | RadioServerData | `legacy: GameServer.receiveRadioServerData / GameClient.receiveRadioServerData` | R | HIGH | 0 | S+C |  |
| 290 | RadioDeviceDataState | `legacy: GameServer.receiveRadioDeviceDataState / GameClient.receiveRadioDeviceDataState` | R | HIGH | 0 | S+C |  |
| 291 | RadioPostSilenceEvent | `legacy: GameClient.receiveRadioPostSilence` | R | IMM | 0 | C |  |
| 292 | SyncRadioData | `legacy: GameServer.receiveSyncRadioData / GameClient.receiveSyncRadioData` | R_ORD | IMM | 0 | S+C |  |
| 293 | BodyPartSync | `BodyPartSyncPacket` | R | HIGH | 0 | C |  |
| 294 | DebugStory | `world/DebugStoryPacket` | R | MED | 0 | S |  |
| 295 | SendItemListNet | `legacy: GameServer.receiveSendItemListNet / GameClient.receiveSendItemListNet` | R | HIGH | 0 | S+C |  |
| 296 | GameEntity | `zombie/entity/GameEntityNetwork` | R_ORD | HIGH | 0 | S+C |  |
| 297 | SyncEntityResource | `SyncEntityResourcePacket` | R_ORD | HIGH | 0 | C |  |

---

## 2. Incremental (delta) vs snapshot packets

"Delta" = applied on top of the receiver's current state, so a lost or discarded one is never corrected by later
packets of the same kind. "Snapshot" = carries the full current value of its target, so the next send of that target
heals it (if the target can be resolved).

### Addressing used by these packets

- `fields.ContainerID` (`parse L295-338`, `findObject L430-562`): `PlayerInventory` (player id), `InventoryContainer`
  (player id + item id), `DeadBody` (square + static-moving-object index), `WorldObject` (square + world-item id),
  `IsoObject` / `ObjectContainer` (**square + object index in `square.getObjects()` + container index**), `Vehicle`
  (vehicle id + part index), `ObjectInVehicle`, `Floor`.
  - Square not loaded -> `container` stays null (`findObject L457`); on the client `GameClient.delayPacket(x,y,z)`
    (L559) defers it only for coop-player loading squares.
  - `IsoObject` / `ObjectContainer` with an index `>= objects.size()` -> a **new dummy `IsoObject` and a dummy
    `ItemContainer("object")`** (`findObject L501-505`, `L510-513`): `getContainer()` is non-null, the update is
    applied to a throwaway container, silently.
  - Corpse index out of range -> `DebugLog "ERROR: ContainerID: invalid corpse index"` (L476); vehicle/part errors log
    `"ERROR: sendItemsToContainer: invalid vehicle id / part index"` (L521, L525).
- `fields.MovingObject` (`getObject L130-175`): players/zombies/animals/vehicles by short id; `ISO_OBJECT` by
  **square + index into `getObjects()`** (L166-170); dead bodies / moving objects by square + list index.
- Items: by `InventoryItem.id` (int) inside a resolved container. Items are the only ID-addressed targets.
- `RemoveItemFromSquare`: payload is only `x, y, z, short index` (`RemoveItemFromSquarePacket.write L61-64`).
- `AddItemToMap`: `IsoObject.writeToRemoteBuffer L4895-4906` writes `x, y, z, objectIndex, isSpecial, isWorld`; the
  receiver inserts at that index, or **appends** if the index is past the end (`IsoObject.loadFromRemoteBuffer
  L4486-4489`).

### Packet-by-packet (state sync families)

| Packet (Rel / Ch) | Kind | Sequence / version field | Receiver when target missing / out of order |
|---|---|---|---|
| `AddInventoryItemToContainer` (R_ORD / 1), C only | Delta: full serialized items to add (`CompressIdenticalItems`) | none | `processClient L77-78` world cell null -> return; container null -> return (L83 -> L127), silent; item id already present -> `System.out.println` (L105), skipped |
| `RemoveInventoryItemFromContainer` (R_ORD / 1), S+C | Delta: list of item ids | none | client `processClient L83-95`: container null -> silent; `removeItemWithID` result ignored (L88). Server `processServer L99-136`: removes, relays to relevant connections, sends `RemoveContestedItemsFromInventory` back for ids already gone (L129-130) |
| `SyncItemDelete` (R_ORD / 1) | Delta (subclass of Remove, own `alreadyRemoved` list) | none | same as Remove |
| `ReplaceInventoryItemInContainer` (R_ORD / 1), C | Delta: remove id + add item | none | `processClient L57-87`: container null -> silent return |
| `ItemTransaction` (R_ORD / 1), S+C | Request/Accept/Reject/Done handshake; server applies, then sends Add/Remove deltas (`Transaction.updateItem L227-228, L276-278, L301-303`) | `Transaction.id` is a **byte** transaction id, not a state version | client: `TransactionManager.setStateFromPacket`; client drops its own pending transactions after `duration+10 s` or 20 s (`TransactionManager.update L87-96`, `"Timeout: %s"` at noise level). Server `isConsistent` false only if player missing (`ItemTransactionPacket.isConsistent L127-131`); `TransactionManager.isConsistent` returns Reject with `"Inconsistent: ..."` reasons |
| `SyncItemFields` (**R** / 1), S+C | Snapshot of one item's fields (condition, uses, ammo, weight, name, colour, wetness, food, pages, sharpness, mod data, clothing) | none | `isConsistent L616`: container null or item id not found -> client: `EventManager.report` + return (silent on client, see 1.); server: warn + dropped |
| `SyncItemModData` (**R** / 1), C | Snapshot of one item's mod data table | none | applied in `parse L57-70`: item null -> silent return (L61-62); container null -> NPE caught/logged by the main loop (inferred from L59 dereference) |
| `SyncHandWeaponFields` (**R** / 1), S+C | Snapshot of weapon fields | none | not traced in detail |
| `ItemStats` (R_ORD / **0**), S+C | Snapshot of food/drainable stats bitfield | none | `processClient L499-517`: container null or item not found (and not in hands) -> silent return |
| `SyncItemActivated` (R_ORD / 1) | Snapshot (activated flag) | none | not traced |
| `AddItemToMap` (R_ORD / 1), C | Delta: add object at index | none | square null -> `loadFromRemoteBuffer` returns (L4472-4473), then `processClient L62` sees no square/obj -> silent; index past end -> appended (L4486-4489) |
| `RemoveItemFromSquare` (R_ORD / 1), C | Delta: remove `objects[index]` | none | square null -> `delayPacket` (coop only) + return (L81-83); index out of range -> `DebugLog "RemoveItemFromSquare: sq is null or index is invalid"` (L100); **index in range but list differs -> removes whatever is at that index** (no identity check, L85-90) |
| `AddObjectToMap` (R_ORD / 1), S+C | Delta: create tile object by sprite | none | `Square.isConsistent` (L69) |
| `ObjectChange` (R_ORD / **0**), C | Mixed: `IsoObjectChange` has deltas (`ADD_ITEM`, `REMOVE_ITEM`, `REMOVE_ITEM_ID`, `REMOVE_ITEM_TYPE`, `REMOVE_ONE_OF`, `ADD_SHEET`, `SWAP_ITEM`, ...) and absolute sets (`SPRITE`, `STATE`, `NAME`, `CONTAINERS`, ...) | none | `parse L90-94`: `IsoObjectChangeTargetNotFoundException` -> `DebugType.error` |
| `SyncIsoObject` (**R** / 3, legacy) | Object-specific `syncIsoObject` payload (state of doors, lights, etc.) | none | `GameClient.receiveSyncIsoObject L1633-1659`: square null -> silent return (L1649-1650); index out of range -> warn (L1656); index-addressed |
| `ObjectModData` (R_ORD / 1), S+C | Snapshot of the object's whole mod data table (`transmitModData`) | none | `parse L57-60`: object null -> `warn "ObjectModDataPacket.parse: object is null"`; object index-addressed via `MovingObject` |
| `ReceiveModData` (**R** / 0) | Snapshot of a square's mod data | none | `parse L43-49`: square null -> `delayPacket` (coop) + return |
| `ReceiveContainerModData` (**R** / 0) | Snapshot of container parent's mod data | none | `isConsistent L69`: container null -> dropped |
| `GlobalModData` (R / 0, IMM) | Snapshot of one global ModData tag (or "missing" flag) | none | `parse L49-59`, fires Lua `OnReceiveGlobalModData` |
| `ClientCommand` (**R** / 0, legacy; Lua `sendClientCommand` / `sendServerCommand`) | Whatever the mod's Lua sends | none | **unordered** between two commands (inferred from RELIABLE) |
| `PlayerStats` (R_ORD / 12), `PlayerXp` (R_ORD / 11), `PlayerHealth/Injuries/Effects/Damage` (R_ORD / 9-14), `SyncPlayerStats` (R / 0) | Snapshots: `Stats.load` + `Nutrition.load` + `BodyDamage.loadMainFields` (`PlayerStatsPacket.parse L45-56`), whole `XP.load` (`PlayerXpPacket.parse L42-52`), bitmask-selected absolute stats (`SyncPlayerStatsPacket.parse L50-62`) | none | self-heal on next send |
| `VehicleUpdate` (R_ORD / 0), C | Flag-selected absolute fields (engine, lights, part condition, part mod data, part item, doors, windows ...), diffed per connection on the server (`VehicleManager.getVehicleState(UdpConnection, BaseVehicle)`) | none | unknown vehicle -> `doRemove`/`doRequest` -> `VehicleManager.sendVehicleRequest` (`VehiclePacket.doRequest L51-55`, `VehicleUpdatePacket.parse L113-119`) |
| `GameEntity` (R_ORD / 0), `SyncEntityResource` (R_ORD / 0) | Component-specific (B42 crafting entities); `Resource.load` snapshot | none | `GameEntityNetwork.parse L129-141`: entity not found -> `debugln "Cannot find registered game entity for id = %d"` + return |

### Ordering hazards (inferred from RakNet semantics + the table above)

- RakNet orders only `*_ORDERED`/`*_SEQUENCED` messages, per ordering channel. A `RELIABLE` message is delivered as
  soon as it arrives and is not ordered against anything; ordered messages on different channels are independent.
  When a datagram is lost and resent, everything ordered behind it on that channel is held back, while unordered and
  other-channel messages keep flowing.
- So under packet loss: `AddInventoryItemToContainer` (R_ORD ch1) followed by `SyncItemFields` / `SyncItemModData`
  (R, unordered) or `ItemStats` (R_ORD ch0) for the same new item can be applied **before** the add -> discarded as
  "item not found". The same holds for `ObjectChange` (ch0) vs container/map ops (ch1), `SyncIsoObject` (ch3) vs
  `AddItemToMap` (ch1), and `ReceiveContainerModData` (R) vs anything.
- These are not wire losses: every packet arrives, but the receiver throws one away because its precondition is not
  met yet, and nothing retries.

---

## 3. Existing vanilla resync / repair paths

| Path | Trigger | What it refreshes | Limits |
|---|---|---|---|
| Chunk download (`PlayerDownloadServer`, `SentChunk` / `NotRequiredInZip` / `RequestData`, ch4) | Client streams a chunk into its grid (WorldStreamer) | Whole chunk: if loaded on the server, serialized from memory on the server main thread (`PlayerDownloadServer.update L512-516`, `IsoChunk.SaveLoadedChunk`), else read from disk by the worker (`IsoChunk.SafeRead`); client cache skipped when CRC matches (`WorldStreamer$ChunkRequest.crc`, `ChunkChecksum`) | Only when the client (re)loads the chunk; nothing triggers a reload on mismatch. Cache/CRC path not traced in detail |
| `ChunkObjectStateRequest` -> `ChunkObjectStateResponse` (R ch3) | Client loads a chunk (`IsoChunk.doLoadGridsquare L4135-4136` queues; `GameClient.update L391-392` sends); server answers now or when the chunk loads (`ChunkObjectStateRequestPacket.parse L36-53`, `IsoChunk.doLoadGridsquare L4099-4114`) | `IsoObject.saveState/loadState` per object, guarded by an object-name hash (`IsoChunk.saveObjectState/loadObjectState`) | Object state only, not containers/items/mod data; only on chunk load |
| `RequestItemsForContainer` (R ch0) | Lua `ItemContainer:requestServerItemsForContainer()` on opening a container (`ISInventoryPage.lua:1110,1414,1516`, `ISOpenContainerTimedAction.lua:29`, server `ISObjectClickHandler.lua:313`); Java `ItemContainer.requestServerItemsForContainer L3356-3360` | Server fills the container with loot and sends `AddInventoryItemToContainer` with all items to relevant clients (`RequestItemsForContainerPacket.processServer L44-54`) | Returns immediately if the container is null **or already explored** (L46-47): no refresh for an explored container |
| `ItemContainer.requestSync()` | Lua when a collapsed inventory page expands (`ISInventoryPage.lua:493, 1193`) | nothing | **Empty stub**: every branch returns (`ItemContainer.requestSync L3348-3353`) |
| `GameServer.sendItemsInContainer(IsoObject, ItemContainer) L2517-2553` | Server Lua (`LuaManager$GlobalObject.sendItemsInContainer`) | `AddInventoryItemToContainer` with all items, `sendToRelative` | Additive only: client skips ids it has and never removes extras (`AddInventoryItemToContainerPacket.processClient L96-106`) |
| `RemoveContestedItemsFromInventory` (R ch0) | Server finds ids already removed when a client removes items (`RemoveInventoryItemFromContainerPacket.processServer L106-107, L129-130`) | Client removes those ids from its own inventory (`RemoveContestedItemsFromInventoryPacket.processClient L49-57`) | Only for client-initiated removes that raced |
| `ItemTransaction` Reject | Server `TransactionManager.isConsistent` fails | Client sees Reject, its pending action ends | Rejects the request; does not resync the container |
| `VehicleRequest` -> `VehicleFullUpdate` | Client gets an update for an unknown vehicle (`VehiclePacket.doRequest L51-55`, `doRemove L59-76`) | `BaseVehicle.load` full state (`VehicleFullUpdatePacket.parse L53-79`) | Vehicle existence/state; container contents of parts still go through container packets |
| `GlobalModDataRequest` -> `GlobalModData` | Lua `ModData.request(tag)` (`GlobalModData.receiveRequest`) | One tag's full table | Pull only; nothing triggers it on mismatch |
| Others | `PlayerDataRequest`, `ZombieRequest`, `IsoRegionClientRequestFullUpdate`, `GetModData` (fires Lua `SendCustomModData` on the server, `GetModDataPacket.processServer L29`) | | |
| Anticheat | `@PacketSetting.anticheats` per packet (`AntiCheat.isValid L106-116`, `act L163-181`) | none: drops the packet, counts, may kick | No state anticheat on item/container/mod-data packets; only movement, hits, safehouses |

No periodic full sync of containers, world objects or mod data exists. No path compares client and server state.

---

## 4. Can reliable packets be lost? Wire loss vs receiver discard

### On the wire

- RakNet `RELIABLE*`: resent until acknowledged; if the peer stops acking, the connection is dropped (external RakNet
  semantics). Within a live connection a reliable message is not silently lost. `UNRELIABLE*` types (player/zombie/
  animal/vehicle movement, ping, map positions, debug) can be lost by design and are periodic snapshots.
- The game never sets RakNet timeouts from Java (`SetTimeoutTime` / `SetUnreliableTimeout` have no Java callers except
  natives; not found), and ignores `Send`'s return value (`UdpConnection.flipSendUnlock L342`).
- Not verified: how `ZNetJNI64` maps RakNet onto Steam networking in Steam mode (native, not disassembled).

### Dropped before the wire (sender side)

| Cause | Where | Visibility |
|---|---|---|
| Client per-type rate limit: more than `MaxPacketsPerSecond` (server option, default 300, range 100-1000) sends of one `PacketType` within 1000 ms | `PacketsCache.isLimitExceeded L49-82` (client adds a timestamp before comparing, L60-64) + `PacketType.send L945-947` -> `cancelPacket` | `DebugType.Multiplayer.warn "Packets limit has exceeded for %s"` (L65) |
| Server side of the same check | `PacketType.onServerPacket L969-970` (server adds only after a pass, `PacketsCache L78-79`) | log only, packet still processed |
| Exception in `write` (incl. `BufferOverflowException` past 1,000,000 bytes) | `INetworkPacket.sendToConnection L140-142` | `printException` |
| Payload > 1,000,000 bytes at RakNet layer | `RakNetPeerInterface.Send L169-172` | `System.out "Packet data too big."` |

Inferred: Lua code that calls `syncItemFields`, `transmitModData`, `sendObjectChange` or `sendClientCommand` for
hundreds of objects in one frame on a client exceeds 300/s for that type, and the excess is dropped with only a warn
line. Vanilla Lua call counts (files): `sendAddItemToContainer` 56, `sendRemoveItemFromContainer` 64,
`syncItemFields` 26, `transmitModData` 23, `sendObjectChange` 31, `sendItemStats` 15.

### Never sent to a client

- `sendToClients` / `sendToRelativeClients` / static `sendToAll` / `sendToRelative` skip connections that are not
  `isFullyConnected()` (`INetworkPacket L197, L215, L291, L324`); relative sends also need `isRelevantTo(x,y)`.
- `isFullyConnected` becomes true in `GameServer.receivePlayerConnect L2861`, after the client's loading screen.
  Inferred: chunks the client downloaded during loading are snapshots; deltas for them between snapshot and
  `PlayerConnect` are not sent. The chunk object-state request after connect (section 3) repairs object state only.
- `UdpConnection.isRelevantTo L228-246`: inside the connect area, or within `relevantRange * 8` tiles of a player's
  relevant position; `relevantRange = clamp(clientByte, 12, 20) / 2 + 2` chunks (`GameServer.receivePlayerConnect
  L2812-2813`). Inferred: this covers the client's loaded chunk grid, so relevance itself is not a likely gap except
  around teleports and position-update lag.
- Owner-only sends for player inventories: `GameServer.sendAddItemToContainer L2426-2434` /
  `sendRemoveItemFromContainer L2459-2467` use `INetworkPacket.send(IsoPlayer, ...)` for a player's own inventory,
  `sendToRelative` for world containers.

### Arrived but discarded (receiver side) - the likely real cause

1. Target not resolvable: container/square/object/item missing -> silent return, debug/warn, or client-side
   `isConsistent` drop (section 2 table; `PacketType.onClientPacket L1022-1024`).
2. Overtaken by a later packet on another reliability/channel (section 2, ordering hazards).
3. Index drift after any earlier miss: wrong object removed (`RemoveItemFromSquare`), mod data/sync applied to the wrong
   object (`MovingObject` ISO_OBJECT, `SyncIsoObject`), container ops applied to a dummy container (`ContainerID`).
   One discard therefore cascades (inferred).
4. Chunk-stream race (inferred): the server snapshots a chunk on its main thread (`PlayerDownloadServer.update
   L512-516`); a change after that is sent as a delta to the relevant client; if the delta is processed before the
   client has put the downloaded chunk into its cell, the square is null and the delta is dropped, while the snapshot
   does not contain it either. `GameClient.delayPacket` only covers coop players.
5. Handler exception -> logged, discarded (`GameClient.mainLoopDealWithNetData L550-551`).
6. Server: `isConsistent`/anticheat reject -> `sync()` no-op (`PacketType.onServerPacket L982-997`), so a client's
   change the server refuses is not rolled back on that client (inferred; the sender keeps its local change).

---

## 5. Hook points (narrowest found)

Hooks are Zabric annotations (`@Inject` HEAD/RETURN/INVOKE, `@ModifyVariable`, MixinExtras-style; see
`.trellis/spec/mod/zabric-hooks.md`). Private members need `.classtweaker` widening
(`.trellis/spec/mod/access-widening.md`).

### (a) Stamping outgoing updates

- `PacketTypes$PacketType.send(Lzombie/network/IConnection;)V` (public) is the single choke point for every game
  packet on both sides (except `ServerQuit` broadcast). At HEAD the payload is complete in the connection's shared
  writer; on the client the rate-limit cancel happens inside this method (L945-947), so a stamp taken at HEAD is also
  consumed by a packet that is then cancelled (that gap is visible to the receiver).
  - The writer is `UdpConnection.bbw` (private; `bb` private too, both 1,000,000 bytes); `ByteBufferWriter.bb` is
    public. `UdpConnection.startPacket` re-locks `bufferLock` and clears, so it cannot be used to reach the writer
    mid-packet.
  - `this` (the `PacketType`) gives the type; `IConnection.getConnectedGUID()` gives the peer.
- Per-type alternatives: each packet's `write(ByteBufferWriter)` RETURN; server senders `GameServer.sendAddItemToContainer
  / sendRemoveItemFromContainer / sendReplaceItemInContainer / sendItemStats L3181-3201 / sendSyncItemFields
  L3204-3223 / sendObjectModData L2929-2933 / sendObjectChange L2954-2978` and `Transaction.updateItem`.
- Wire format for a trailer: vanilla parsers read fields and leave trailing bytes unread (`ByteBufferReader.bb` and
  `limit()` are public); prior research on trailers and custom ids:
  `.trellis/tasks/archive/2026-10/10-20-server-mod-sync/research/location-delivery.md` sections 2 and 4.

### (b) Detecting a gap or a discarded apply

- Gap: `PacketType.onClientPacket(ByteBufferReader)` HEAD (client) and `onServerPacket(ByteBufferReader,
  UdpConnection)` HEAD (server), before `parseClient`/`parseServer`; both public.
- Generic discard points:
  - client `isConsistent` false: `PacketType.onClientPacket L1022-1024` (the `EventManager.report` call, an INVOKE
    target), server: `L982-985` (`logInconsistentPacket` + `sync`).
  - `INetworkPacket.sync(PacketType, UdpConnection)`: vanilla's server-side "this packet was rejected" callback, empty
    default (L117), overridden only by `PlayerHit`.
  - handler exceptions: `GameClient.mainLoopDealWithNetData L550-551`, `GameServer.mainLoopDealWithNetData
    L1635-1640`.
- Packet-specific silent returns (each a RETURN/INVOKE target):
  `AddInventoryItemToContainerPacket.processClient L83 / L105`, `RemoveInventoryItemFromContainerPacket.processClient
  L84 / L88 (ignored boolean)`, `ReplaceInventoryItemInContainerPacket.processClient L57-63`,
  `ItemStatsPacket.processClient L500-514`, `SyncItemModDataPacket.parse L61`, `ObjectModDataPacket.parse L58-60`,
  `ObjectChangePacket.parse L92`, `RemoveItemFromSquarePacket.processClient L81-83 / L100`,
  `IsoObject.loadFromRemoteBuffer L4472-4473`, `GameClient.receiveSyncIsoObject L1649-1650 / L1656`,
  `GameEntityNetwork.parse L131-141`.
- `ContainerID.findObject()` RETURN (public): `container == null`, or `object` is a fresh dummy (not on the square),
  identifies an unresolved container for every container-addressed packet in one place.

### (c) Requesting a full resync

- Vanilla request types usable as-is: `VehicleRequest` (`VehicleManager.sendVehicleRequest(short,short)`),
  `GlobalModDataRequest` (Lua `ModData.request`), `ChunkObjectStateRequest` (`UdpConnection.addChunkObjectState(short)`
  for wx, wy). None refreshes an explored container or a square's object list.
- Server-side full-content senders: `GameServer.sendItemsInContainer` (additive), `sendObjectModData`,
  `sendSyncItemFields`, `sendItemStats`, `VehicleFullUpdate`, chunk download (`PlayerDownloadServer`).
- A client cannot make the server re-send a chunk it already has loaded through any vanilla packet (not found).
- Channel for mod requests: Lua `ClientCommand` (RELIABLE, unordered) or a raw packet id outside `PacketTypes`
  (vanilla server routes unknown ids to `AntiCheat.PacketType`, which can kick: prior research above, section 2).

---

## Caveats / Not Found

- RakNet semantics (unordered `RELIABLE`, per-channel ordering, resend-until-disconnect) are from RakNet's API
  documentation, not from disassembling `RakNet64.dll`/`ZNetJNI64.dll`. Steam-mode transport not examined.
- The ordering hazard and the chunk-stream race are inferred from code structure; not reproduced in a running game.
- `SyncHandWeaponFields`, `SyncItemActivated`, `SyncThumpable`, `SyncPerks`, `ObjectChange` per-change handlers,
  `GameEntity` component handlers and the client chunk cache/CRC logic were not traced in detail.
- Lua-side callers decide much of the server's delta traffic (`media/lua`); only call counts were taken.
- Not found: any Java call to `SetTimeoutTime`/`SetUnreliableTimeout`; any periodic container/object sync; any state
  version field.

---

## Candidate scopes

1. **Container contents (world containers, vehicle part containers, corpses)**: `AddInventoryItemToContainer`,
   `RemoveInventoryItemFromContainer`, `ReplaceInventoryItemInContainer`, `SyncItemDelete` (all R_ORD ch1, deltas)
   after `ItemTransaction`. Loss: container unresolved -> silent; `IsoObject`/`ObjectContainer` index drift -> dummy
   container. Repair: none once explored (`RequestItemsForContainer` skips explored, `requestSync` is a stub,
   `sendItemsInContainer` additive). Most visible: items appear/vanish, duplicates, "item not found" on transfer.
   Hooks: `PacketType.send` / `onClientPacket` HEAD, `ContainerID.findObject` RETURN, the `processClient` returns
   above; server side `GameServer.sendAdd/Remove/ReplaceItem*`, `Transaction.updateItem`.
2. **Per-item field / stats / mod data**: `SyncItemFields`, `SyncItemModData`, `SyncHandWeaponFields` (R unordered
   ch1), `ItemStats` (R_ORD ch0). Snapshots per item, but discarded when they overtake the item's add. Repair: only the
   next send for that item. Visible: wrong condition, uses, ammo, food state, item mod data (mods). Hooks:
   `SyncItemFieldsPacket.isConsistent` false path (`PacketType.onClientPacket L1022`), `SyncItemModDataPacket.parse
   L61`, `ItemStatsPacket.processClient L513`; server `GameServer.sendSyncItemFields`, `sendItemStats`.
3. **Square object lists (world items on the floor, placed/removed objects)**: `AddItemToMap`, `RemoveItemFromSquare`
   (R_ORD ch1, index-addressed deltas), `ObjectChange` (R_ORD ch0), `SyncIsoObject` (R ch3). Loss: square not loaded
   (chunk-stream race), index drift removes the wrong object. Repair: chunk reload only; `ChunkObjectState` covers
   object state, not the list. Visible: ghost or missing floor items/furniture until relog. Hooks:
   `IsoObject.loadFromRemoteBuffer L4472`, `RemoveItemFromSquarePacket.processClient L81 / L85-90`,
   `GameClient.receiveSyncIsoObject L1649`; server `GameServer.RemoveItemFromMap`, `AddItemToMapPacket` senders.
4. **Object and square mod data**: `ObjectModData` (R_ORD ch1, full table but index-addressed via `MovingObject`),
   `ReceiveModData` (R), `ReceiveContainerModData` (R). Loss: object/square missing -> warn or silent; wrong object
   after index drift. Repair: next `transmitModData` of that object. Visible: farming, generators, mod-driven object
   state. Hooks: `ObjectModDataPacket.parse L58-60`, `MovingObject.getObject` RETURN; server
   `GameServer.sendObjectModData L2929-2933`.
5. **Client-originated bursts (any type)**: client rate limit in `PacketType.send L945-947` (300/s per type default),
   e.g. Lua loops over `syncItemFields` / `transmitModData` / `sendClientCommand`. Repair: none; only a warn line.
   Hook: `PacketType.send` HEAD (or the `IConnection.cancelPacket` INVOKE inside it).
