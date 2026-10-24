# Research: spikes S1 (own raw packets) and S5 (runtime reliability/channel of the in-scope packet types)

- **Query**: S1: receive path of a raw packet with a reserved id on client and server, the narrowest hooks that queue it
  with vanilla packets on the main thread in arrival order without vanilla's unknown-packet handling or anticheat, the
  send API and lock safety, and every client path that defers an in-scope packet. S5: the `PacketType` fields holding
  priority/reliability/channel, where they are set and read, whether runtime writes take effect, and who shares
  channel 1.
- **Scope**: internal (game bytecode via `javap -c -p -l -constants`, JDK 25; repo code and specs). RakNet ordering
  semantics are external (RakNet docs), not re-verified.
- **Date**: 2026-10-24

Evidence: `Class.method Lnnn` = source line from the class's `LineNumberTable`; `@nnnn` = bytecode offset. Jar:
`E:/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid.jar` (byte-identical to the dedicated server's, see
`vanilla-sync.md`).

## Files Found

| File | Description |
|---|---|
| `zombie/core/raknet/UdpEngine` | `threadRun` (receive thread "UdpEngine"), `decode` (first-byte dispatch), `endPacketBroadcast` |
| `zombie/network/GameServer` | `addIncoming`, the three queues, `main` (drain + world tick), `mainLoopDealWithNetData` |
| `zombie/network/GameClient` | `addIncoming`, `MainLoopNetDataQ` / `MainLoopNetData` / `LoadingMainLoopNetData` / `DelayedCoopNetData`, `update`, `mainLoopDealWithNetData`, `gameLoadingDealWithNetData`, `delayPacket`, `sendPlayerConnect` |
| `zombie/network/ZomboidNetData`, `ZomboidNetDataPool` | queued packet record and its pool |
| `zombie/network/PacketTypes$PacketType` | settings fields, ctor, `send`, `doPacket`, `getId`, `onClientPacket` |
| `zombie/network/packets/INetworkPacket` | `sendToConnection` |
| `zombie/core/raknet/UdpConnection` | `startPacket`, `endPacket`, `cancelPacket`, `bufferLock` |
| `zombie/core/raknet/RakNetPeerInterface` | RakNet constants |
| `zombie/network/statistics/data/NetworkStatistic` | per-packet counters (`addIncomePacket`, `addOutcomePacket`) |
| `zombie/network/anticheats/AntiCheat`, `zombie/network/ServerOptions` | `act`, `isEnabled`, `AntiCheatPacketException` default |
| `zombie/network/fields/ContainerID` | `findObject` -> `GameClient.delayPacket` |
| `zabric/src/main/java/com/pzlauncher/zabric/hook/{At,Inject,ModifyVariable,CallbackInfo}.java` | what a hook can express |
| `mod-sync/src/main/kotlin/com/dont39/pz/modsync/{client/ClientHooks.kt,server/ServerHooks.kt}` | existing hooks on `ConnectionDetails`, `GameServer.startServer` |
| `sync-guard/build.gradle.kts`, `sync-guard/src/main/java/com/dont39/pz/syncguard/Mod.java` | current module: registers `checkJdkGlue` |

---

## S1. Receive path

### Network thread: `UdpEngine`

- `UdpEngine.<init>` @279-312 starts a daemon `Thread(ThreadGroups.Network, ..., "UdpEngine")`; `threadRun L92-105`
  loops `Receive()` -> `decode(buffer)`; exceptions are logged "Exception thrown during decode." and the loop goes on.
  Everything below up to the queue `add` runs on that thread, on client and server.
- `UdpEngine.decode L190-346` (private): `getByte() & 255` then a `lookupswitch` over RakNet system ids
  (0,1,16-25,31-33,44-46) and `134`:
  - `134` (`RakNetPeerInterface.ID_USER_PACKET_ENUM = 134`): `getShort()` = `userPacketId` (L316). Server: connection
    from `connectionMap` by `peer.getGuidOfPacket()`; none -> `warn "GOT PACKET FROM UNKNOWN CONNECTION guid=%d
    packetId=%d"` and return (L320-327); else `GameServer.addIncoming(id, buf, con)` (L330). Client:
    `GameClient.instance.addIncoming(id, buf)` (L332).
  - any other byte: `DebugType.Network.warn "Received unknown packet: %s"` (L337); on the server also
    `AntiCheat.PacketRakNet.act(connection, ...)` (L338-341).

### Server

- `GameServer.addIncoming(short, ByteBufferReader, UdpConnection)` (public static) `L3104-3128`:
  1. `ZomboidNetDataPool.get()` (or `getLong(limit)` when `bb.limit() > 2048`, L3106).
  2. `d.read(id, bb, connection)` (L3111).
  3. `d.type == null` -> `AntiCheat.PacketType.act(connection, "...")` inside try/catch, **return without queueing**
     (L3112-3118).
  4. `d.time = currentTimeMillis()` (L3120); `PlayerUpdateUnreliable/Reliable` -> `MainLoopPlayerUpdateQ` (L3122),
     `VehiclePhysicsReliable/Unreliable` -> `MainLoopNetDataQ` (L3124), **everything else ->
     `MainLoopNetDataHighPriorityQ`** (L3126). All three are `private static final ConcurrentLinkedQueue<IZomboidPacket>`.
- `GameServer.main`, per loop iteration (offsets in `main`):
  - @2842-2878 drain `MainLoopNetDataHighPriorityQ` into `MainLoopNetData2`; @2963-3066 process in order: `isConnect()`
    -> `DelayedConnection.connect`, `isDisconnect()` -> `disconnect`, else `checkcast ZomboidNetData` and
    `mainLoopDealWithNetData` (@3060).
  - @3075-3216 the same for `MainLoopPlayerUpdateQ`; @3225-3437 `MainLoopNetDataQ` (vehicle physics), with the
    "Server is too busy" drop after 70 ms (vehicle physics only).
  - World tick (10 Hz, `UpdateLimit(100)` @2695): `IngameState.update` @3875 (L1045), `VehicleManager.serverUpdate`
    @3882 (L1046), `ObjectIDManager.getInstance().checkForSaveDataFile(false)` @3889 (L1047); per-connection
    `PlayerDownloadServer.update` @4193.
  - So all in-scope packet types (none is PlayerUpdate/VehiclePhysics) and a packet of ours put in
    `MainLoopNetDataHighPriorityQ` are processed on the main thread in `decode` order.
- `GameServer.mainLoopDealWithNetData(ZomboidNetData)` (private static) `L1602-1645`: `SystemDisabler` gate (L1602);
  `udpEngine.getActiveConnection(d.connection)` (L1606); **`d.type == null` -> `ZomboidNetDataPool.discard(d)`, return,
  silently** (L1608-1610); connection null -> log, return (L1614-1616); `connection.getUserName() == null` and type not
  one of 5 login types -> log + `forceDisconnect("unacceptable-packet")` (L1618-1630); `type.onServerPacket(buffer,
  connection)` (L1633); exception -> error log + `AntiCheat.PacketException.act` (L1634-1640); `discard(d)` (L1644).

### `ZomboidNetData` / pool

- Fields (all public): `PacketTypes$PacketType type`, `short length`, `final ByteBufferReader buffer`, `long connection`
  (peer GUID), `long time`. Class is public, not final; ctors `ZomboidNetData()` (2048 bytes) and
  `ZomboidNetData(int size)` (`ByteBuffer.allocate(size)`), both public.
- `read(short id, ByteBufferReader bb, UdpConnection c) L32-40`: `type = PacketTypes.packetTypes.get(Short.valueOf(id))`;
  null -> `DebugType.Multiplayer.error("Received unknown packet id=%d")` (L33-34); `connection = c.getConnectedGUID()`;
  `buffer.put(bb.bb); buffer.flip()` (bytes after the 3-byte header). **The raw id is not stored anywhere**; `length` is
  not written by `read`.
- `ZomboidNetDataPool.discard L19-23`: `d.reset()` (type null, length 0, connection 0, `buffer.clear()`), then pooled
  **only if `buffer.capacity() == 2048`**. `get()` returns a pooled instance or a new 2048 one; `getLong(n)` always new.

### Client

- `GameClient.addIncoming(short, ByteBufferReader)` (public, instance) `L1725-1761`: `connection == null` -> return
  (L1725-1726); `WorldStreamer.instance.udpUpdate()` (L1729: moves sent chunk requests to pending, network-thread
  housekeeping run once per incoming packet); `SentChunk`, `NotRequiredInZip`, `ChunkNotReady`, `LoadPlayerProfile`
  handled inline on the network thread (L1731-1748); else pool `get`/`getLong(remaining)` -> `read` -> `time =
  currentTimeMillis()` -> `MainLoopNetDataQ.add` (L1752-1760). Unknown id: the error line from `read`, then queued
  with `type == null`.
- `GameClient.update()` (main thread) `L291-429`:
  - L301-302: drain `MainLoopNetDataQ` into `MainLoopNetData` (`ArrayList`, arrival order).
  - `connectionLost` (L333-356): before `playerConnectSent` every entry goes to `gameLoadingDealWithNetData`; after,
    only `type == Kicked` is read; list cleared.
  - **Loading** (`!playerConnectSent`, L359-368): each entry -> `gameLoadingDealWithNetData`; `false` -> kept in
    `LoadingMainLoopNetData`.
  - **In game**: L370-373 prepend `LoadingMainLoopNetData` ("Processing delayed packets..."); L375-378 prepend
    `DelayedCoopNetData` when `IsoWorld.addCoopPlayers` is empty ("Processing delayed coop packets..."); L381-386 process
    entries with `time + 5 <= now` in list order via `mainLoopDealWithNetData`, younger entries stay.
- `GameClient.mainLoopDealWithNetData(ZomboidNetData)` (private) `L534-554`: save `buffer.position()`; `delayPacket =
  false` (L535); **`type == null` -> `discard(d)`, return, silently** (L537-539); `mainLoopHandlePacketInternal` ->
  `type.onClientPacket(buffer)` (L557-561, gated by `DebugOptions.network.client.mainLoop`); if `delayPacket` was set
  during handling: restore position, `DelayedCoopNetData.add(d)`, return (L545-548); exceptions logged (L550-551);
  `discard(d)` (L553).
- `GameClient.gameLoadingDealWithNetData(ZomboidNetData)` (private, returns boolean) `L933-938`:
  `type.onClientLoadingPacket(buffer)`; any exception (a null `type` gives an NPE) -> `printException("Error with
  packet of type: %s")`, `discard`, return `true` (dropped).

### Deferral of in-scope packets on the client

- `PacketType.onClientPacket L1011-1040`: `isPostponed()` -> `postpone()` (L1031-1032). Overridden only by
  `DeadAnimal/DeadCharacter/DeadPlayer/DeadZombie/RemoveCorpseFromMap/VehicleHit*` packets (jar scan); **none of the 8
  in-scope packets**, all use the `INetworkPacket` defaults (`isPostponed` false, `postpone` empty, L62/L70).
- `GameClient.delayPacket(int x, int y, int z) L521-531`: sets `delayPacket = true` only if some
  `IsoWorld.addCoopPlayers` entry `isLoadingThisSquare(x, y)` (split-screen co-op player joining). Callers:
  `ContainerID.findObject L558-559` (client, square null), `ObjectChangePacket`, `RemoveItemFromSquarePacket`,
  `AddCorpseToMapPacket`, `ReceiveModDataPacket`. All 8 in-scope packets hold a `ContainerID`
  (`SyncItemDelete` through its superclass `RemoveInventoryItemFromContainerPacket`), so each can be deferred this way:
  the handler already ran against a null container, the packet goes to `DelayedCoopNetData` and is replayed at the
  front of `MainLoopNetData` once `addCoopPlayers` is empty, i.e. after packets (vanilla or ours) queued later.
- Loading-time deferral: all 8 have `handlingType` 2 or 3 (no `ClientLoading` bit), so before `PlayerConnect` they land
  in `LoadingMainLoopNetData` and are replayed first after it (L359-373). Ours are never sent before `Hello`
  (`design.md`), so they cannot overtake those.
- The 5 ms age rule (L384) applies to every queued entry; an entry of ours must carry `time = currentTimeMillis()` like
  vanilla's, or it is processed in the same pass as older-but-younger-than-5-ms vanilla entries ahead of it would be
  skipped, i.e. ours could overtake them.
- No other client deferral path for these types was found.

### Vanilla peer receiving our id (should not happen by design)

| Peer | 134 + reserved short | Other first byte |
|---|---|---|
| Vanilla client, in game | `ZomboidNetData.read L33-34` error line "Received unknown packet id=%d", queued, discarded silently (`mainLoopDealWithNetData L537-539`) | `decode L337` warn "Received unknown packet: %s" |
| Vanilla client, loading | same error line, then NPE in `gameLoadingDealWithNetData` -> stack trace "Error with packet of type: null" (L933-938), dropped | same warn |
| Vanilla server | error line, then `AntiCheat.PacketType.act` (`GameServer.addIncoming L3114`), not queued | warn + `AntiCheat.PacketRakNet.act` (`decode L338-341`) |

- `AntiCheat.act L163-181`: only if `isEnabled()` (L102: option value != 4; on a co-op host also `Core.antiCheats`),
  `connection.isFullyConnected()`, and the role lacks `CantBeKickedByAnticheat`; then `PacketValidator.report`, log;
  unless `Core.debug`, `doLogUser` and, once the counter reaches `maxSuspiciousCounter`, option 1 -> ban, 2 -> kick.
- Both `AntiCheat.PacketType` and `AntiCheat.PacketRakNet` use `ServerOptions.antiCheatPacketException` with
  `maxSuspiciousCounter = 1` (`AntiCheat.<clinit>` @148-216): one packet is enough. Default value **4 = disabled**
  (`ServerOptions.<init>` @2284-2292: `EnumServerOption("AntiCheatPacketException", 4, 4)`); the user's
  `~/Zomboid/Server/myserver1.ini` and `servertest.ini` have `AntiCheatPacketException=4`.

### Sending

- `UdpConnection.startPacket()` public `L215-217`: `bufferLock.lock()` (a `ReentrantLock`, `<init>` @5-12),
  `bbw.clear()`, returns the connection's single shared `ByteBufferWriter` (1,000,000 bytes, `<init>` @16-36).
- `UdpConnection.endPacket(int priority, int reliability, byte ordering)` public `L328-329` -> private `endPacket
  L332-337` (reads the short at position 1 for `NetworkStatistic.addOutcomePacket`) -> `flipSendUnlock L340-344`
  (`RakNetPeerInterface.Send(bb, pri, rel, ord, guid, false)`, result ignored, then `unlock`).
- `UdpConnection.cancelPacket()` public `L310-311`: `bufferLock.unlock()` only. Vanilla calls it on any exception
  between start and end (`INetworkPacket.sendToConnection L140-142`).
- Shortcuts: `endPacket()` = (1 HIGH, 3 RELIABLE_ORDERED, 0) L366; `endPacketImmediate` (0,3,0); `endPacketUnordered`
  (2,2,0).
- Header: `PacketType.doPacket L956-958` = `putByte(134)` + `putShort(getId())`; `getId L961` = `(short) ordinal()`.
  298 constants, ids 0-297.
- RakNet constants (`RakNetPeerInterface`): priority `IMMEDIATE 0, HIGH 1, MEDIUM 2, LOW 3`; reliability `UNRELIABLE 0,
  UNRELIABLE_SEQUENCED 1, RELIABLE 2, RELIABLE_ORDERED 3, RELIABLE_SEQUENCED 4, ..._WITH_ACK_RECEIPT 5-7`;
  `ID_USER_PACKET_ENUM 134`.
- Statistics: `NetworkStatistic.addOutcomePacket L139-150` and `addIncomePacket L125-136` look the id up in
  `PacketTypes.packetTypes` and do nothing for an unknown id (no exception). So our packets are absent from the game's
  per-packet `sentBytes`/`receivedBytes` counters, but included in the RakNet-level `ZNetStatistics`
  (`lastActualBytesSent`, `lastUserMessageBytesSent`, ...) that `NetworkStatistic.updateConnection` reads per
  connection. AC7's "mod's own share" must be counted by the mod.
- `bufferLock` safety:
  - Every vanilla send holds the lock from `startPacket` through `write` to `endPacket` on one thread
    (`INetworkPacket.sendToConnection L130-145`, legacy `doPacket`+`send` pairs).
  - Because the lock is reentrant, a send of ours on the **same thread while a vanilla packet is open on the same
    connection** would not block: `startPacket` would `clear()` vanilla's half-written packet and our `endPacket` would
    unlock once, leaving vanilla to send our bytes. That happens only if we send from inside a hook that runs between a
    vanilla `startPacket` and `endPacket`: `PacketType.send` HEAD, any packet `write`, `doPacket` (S3's touch hooks).
  - At HEAD of `mainLoopDealWithNetData` (either side) and at the end-of-tick point, no vanilla packet is open on the
    main thread, so sending there is safe. Other threads that send through `startPacket` block on the lock until we
    release it; `PlayerDownloadServer.sendPacket` uses its own buffer and `SendRaw` (no `bufferLock`); pings use
    `bufferLockPing`.

### Hook expressiveness (Zabric)

- `At` is `HEAD`, `RETURN` or `FINALLY` only (`At.java`); there is **no INVOKE injection point**. `@Inject` handlers
  may cancel at HEAD (`cancellable = true`); non-void targets get `CallbackInfoReturnable.setReturnValue`.
  `@ModifyVariable` is HEAD, args only.
- Private and nested targets are hookable by name (`.trellis/spec/mod/zabric-hooks.md`, `mod-sync` `ClientHooks.kt`);
  methods are never widened, only fields/classes/ctors (`access-widening.md`).
- The generated glue binds every argument of a hooked method with its declared type (`WeaveGlue.java`,
  `zabric-hooks.md` "A preloaded mod's glue names only JDK types"). `sync-guard/build.gradle.kts` registers
  `checkJdkGlue` and makes `check`/`jar` depend on it, and `Mod.java` says so; hooks on `addIncoming`
  (`ByteBufferReader`, `UdpConnection`) or `mainLoopDealWithNetData` (`ZomboidNetData`) produce glue naming game types,
  which that check rejects. The check exists for preloaded mods (`CheckJdkGlue.kt` KDoc); Sync Guard is not preloaded
  (`design.md`: `Zabric-Main` only). mod-sync, also not preloaded, hooks `ConnectionDetails.write(UdpConnection, ...)`
  without such a check.
- Existing repo precedent: no module widens or hooks `GameServer`/`GameClient` queues or `UdpEngine`; mod-sync hooks
  `GameServer.startServer` RETURN and `ConnectionDetails.write/parse`; tunnel hooks `GameServer.startServer` RETURN;
  network-status reads `UdpConnection` publicly. No classtweaker lists a network class.
- `GameServer` and `GameClient` load after premain (the server's main class is `GameServer`, which Zabric's
  `PreLaunchHook` requires to be unloaded at premain, `PreLaunchHook.java:43`), so field widening applies to them.

### Reserved id vs other first byte

| | 134 + reserved short (recommended) | Own first byte (135-255) |
|---|---|---|
| Hook target | `GameServer.addIncoming` / `GameClient.addIncoming`, public, called only for 134 packets | `UdpEngine.decode`, private, runs for every inbound message incl. RakNet system ids; must peek and rewind the byte |
| Connection | resolved by vanilla (server passes `UdpConnection`) | resolve ourselves (`getActiveConnection(peer.getGuidOfPacket())`) |
| Transport | same path as every game packet, incl. Steam mode | not exercised by vanilla (Steam mode native path not examined) |
| Vanilla client, loading | NPE stack trace per packet | warn line only |
| Vanilla server | `AntiCheat.PacketType` | `AntiCheat.PacketRakNet`, same option |

---

## S5. Reliability/channel fields

- Fields of `PacketTypes$PacketType`: `public int packetPriority`, `public int packetReliability`, `public byte
  orderingChannel` (also `public int handlingType`, `public AntiCheat[] anticheats`, `public boolean isCached`,
  `private Capability requiredCapability`). **Public, non-final, non-volatile**: no widening needed.
- Set only in the main ctor `PacketType(String,int,int,int,byte,Capability,int,...,Class) L909-934`: ctor args first
  (L911-913), then, when a handler class is given, the `@PacketSetting` values overwrite them (L925-931,
  `getPacketSetting L937-941` via `Class.getAnnotationsByType`). The handler-class ctor (L898-903) passes defaults
  (2, 0, 0); legacy ctors pass their own (L881-885, L891-895). Nothing else reads `PacketSetting` (jar scan: only
  `PacketTypes$PacketType` references its methods).
- Readers (jar scan for the field names, then bytecode):
  - `PacketType.send(IConnection) L945-953`: `getfield` of all three on every call, passed to
    `IConnection.endPacket(IIB)` (L949). Client only: `isLimitExceeded` -> `cancelPacket` before that (L945-947).
  - `PacketType.getFlags L1072-1092`: display/logging string only.
  - `PlayerDownloadServer.sendPacket L580...`: `packetPriority`, `packetReliability` with ordering 0 via `SendRaw`, for
    chunk-streaming types only.
  - `UdpEngine.endPacketBroadcast L154-158`: `packetPriority` (passed as both priority and reliability), used for
    `ServerQuit`.
  - `zombie/spnetwork/UdpConnection`, `RakNetPeerInterface`: parameter names only.
  - `INetworkPacket.sendToConnection L130-145` does not read them; it calls `packetType.send(connection)` (L138).
- **Writing the fields after class init takes effect for every later `send`**: nothing copies or caches them (the only
  cache-like flag, `isCached`, is false for every type and keys on payload hash, not settings). Writes happen on the
  main thread; the in-scope types are sent from the main thread (`server-change-flow.md`), so visibility is not an
  issue there (not checked for any off-main-thread sender of these types).
- The setting travels with each message chosen by the **sender**; a receiver never consults its own fields for
  ordering. Client-side changes affect only client->server sends, server-side only server->client.

### Current settings, in-scope types (annotation values via `javap -v`, ids from `PacketType.<clinit>`)

| Id | Type | Priority | Reliability | Channel | handlingType |
|---|---|---|---|---|---|
| 123 | AddInventoryItemToContainer | 1 HIGH | 3 R_ORD | 1 | 2 (C) |
| 124 | RemoveInventoryItemFromContainer | 1 HIGH | 3 R_ORD | 1 | 3 (S+C) |
| 125 | ReplaceInventoryItemInContainer | 1 HIGH | 3 R_ORD | 1 | 2 |
| 126 | SyncItemDelete (own annotation, `requiredCapability = EditItem`) | 1 HIGH | 3 R_ORD | 1 | 3 |
| 134 | ItemStats | 1 HIGH | 3 R_ORD | **0** | 3 |
| 147 | SyncItemFields | 1 HIGH | **2 R** | 1 | 3 |
| 148 | SyncItemModData | **2 MEDIUM** | **2 R** | 1 | 2 |
| 149 | SyncHandWeaponFields | 1 HIGH | **2 R** | 1 | 3 |

Changes needed for R2: `packetReliability = 3` on 147, 148, 149; `orderingChannel = 1` on 134.

### Everything on channel 1 today (19 types, all `@PacketSetting`; no legacy ctor uses channel 1)

| Id | Type | Pri | Rel |
|---|---|---|---|
| 33 | PlayerDropHeldItems | 1 | 3 |
| 56 | AnimalCommand | 1 | 3 |
| 118 | ItemTransaction | 1 | 3 |
| 119 | GeneralAction | 1 | 3 |
| 120 | NetTimedAction | 1 | 3 |
| 121 | BuildAction | 1 | 3 |
| 122 | FishingAction | 1 | 3 |
| 123-126 | Add/Remove/Replace item in container, SyncItemDelete | 1 | 3 |
| 127 | AddItemToMap | 1 | 3 |
| 128 | AddObjectToMap | 1 | 3 |
| 130 | RemoveItemFromSquare | 1 | 3 |
| 141 | SyncItemActivated | 1 | 3 |
| 147, 149 | SyncItemFields, SyncHandWeaponFields | 1 | 2 |
| 148 | SyncItemModData | 2 | 2 |
| 195 | ObjectModData | 2 | 3 |

Legacy ctor calls with an explicit ordering: only `SyncIsoObject` (193) and `ChunkObjectStateResponse` (211), both
channel 3 (`PacketType.<clinit>` @3723-3740, @4184-4197).

---

## Decisions for implementation

### Packet id and wire format

- Header `134` + reserved short, written as vanilla does (`putByte(134)`, `putShort(ID)`). Proposed `ID = (short)
  0x5347` (21319, "SG"): far above the 0-297 ordinal range. Payload starts with the protocol version byte and a message
  kind byte (`design.md#own-packets`).
- Send (main thread only): `b = conn.startPacket(); try { b.putByte(134); b.putShort(ID); <payload>;
  conn.endPacket(1 /*HIGH*/, 3 /*RELIABLE_ORDERED*/, (byte) 1); } catch (Throwable t) { conn.cancelPacket(); throw }`.
  Client: `GameClient.connection`. `Hello` goes on **channel 0** RELIABLE_ORDERED so it arrives after `PlayerConnect`
  (also channel 0, R_ORD); everything else on channel 1.
- Never send from inside a hook that runs while a vanilla packet is open (`PacketType.send`, `doPacket`, any
  `write`): record there, send at the end of tick or from our own message handler.

### Hooks (all `@Inject`)

| # | Side | Target class | Method / descriptor | At | Cancel | Handler does |
|---|---|---|---|---|---|---|
| H1 | server | `zombie.network.GameServer` | `addIncoming(SLzombie/core/network/ByteBufferReader;Lzombie/core/raknet/UdpConnection;)V` (public static, UdpEngine thread) | HEAD | yes, only for `id == ID` | build a `SyncGuardNetData` (subclass of `ZomboidNetData`, created with `super(0)`, own payload copy of `bb.bb` remaining bytes, `connection = con.getConnectedGUID()`, `time = currentTimeMillis()`), `GameServer.MainLoopNetDataHighPriorityQ.add(it)`, cancel. Skips `read`'s error line and `AntiCheat.PacketType.act` |
| H2 | server | `zombie.network.GameServer` | `mainLoopDealWithNetData(Lzombie/network/ZomboidNetData;)V` (private static, main thread) | HEAD | no | if `d instanceof SyncGuardNetData`: resolve `udpEngine.getActiveConnection(d.connection)`, ignore unless fully connected (and active, except for `Hello`), dispatch. Vanilla then sees `type == null` and discards silently (L1608-1610); capacity 0 != 2048 keeps it out of the pool |
| H3 | client | `zombie.network.GameClient` | `addIncoming(SLzombie/core/network/ByteBufferReader;)V` (public, UdpEngine thread) | HEAD | yes, only for `id == ID` | return if `GameClient.connection == null`; same record with `time = currentTimeMillis()`; `GameClient.MainLoopNetDataQ.add(it)`; cancel. Optionally call `WorldStreamer.instance.udpUpdate()` first to keep vanilla's per-packet housekeeping (L1729) |
| H4 | client | `zombie.network.GameClient` | `mainLoopDealWithNetData(Lzombie/network/ZomboidNetData;)V` (private, main thread) | HEAD | no | if ours: dispatch. Vanilla discards silently (L537-539) |
| H5 (optional) | client | `zombie.network.GameClient` | `gameLoadingDealWithNetData(Lzombie/network/ZomboidNetData;)Z` (private) | HEAD | yes, only ours | `setReturnValue(false)` keeps it for the in-game replay instead of vanilla's NPE trace. Unreachable by design (server sends only after `Hello`, which follows `PlayerConnect`) |
| H6 | client | `zombie.network.GameClient` | `sendPlayerConnect(Lzombie/characters/IsoPlayer;)V` (public) | RETURN | no | `playerConnectSent` is true and `PlayerConnect` was sent (@26, @46): send `Hello` when the trailer was seen |
| H7 | server | end of world tick, no INVOKE available | `zombie.network.id.ObjectIDManager.checkForSaveDataFile(Z)V` (public) HEAD: called once per tick right after the second `VehicleManager.serverUpdate` (`GameServer.main` @3889 L1047) and from `ServerMap.QueuedQuit` (shutdown). Alternative: `zombie.gameStates.IngameState.update()Lzombie/gameStates/GameStateMachine$StateAction;` RETURN gated on `GameServer.server` (the client also runs it through the state machine). `VehicleManager.serverUpdate` is not unique: also called from `IsoWorld.updateInternal` (server) and `BaseVehicle` key methods | | | flush touched set, send `SyncFrame`s and snapshots |

S5 writes need no hook beyond activation: at `GameServer.startServer` RETURN (as mod-sync/tunnel) set the fields on
`PacketType.SyncItemFields/SyncItemModData/SyncHandWeaponFields` (`packetReliability = 3`) and `PacketType.ItemStats`
(`orderingChannel = 1`); on the client when activated for a join, and restore the original values when the join ends
or is inactive, so a later join to a vanilla server sends vanilla settings.

### Widening (`META-INF/dont39/sync-guard.classtweaker`)

```
accessible field zombie/network/GameServer MainLoopNetDataHighPriorityQ Ljava/util/concurrent/ConcurrentLinkedQueue;
accessible field zombie/network/GameClient MainLoopNetDataQ Ljava/util/concurrent/ConcurrentLinkedQueue;
```

Nothing else: `PacketType` settings fields, `UdpConnection.startPacket/endPacket/cancelPacket/getConnectedGUID`,
`ZomboidNetData` and its ctor/fields, `GameServer.udpEngine` (public static), `UdpEngine.getActiveConnection`,
`GameClient.connection` (public static) are public.

### Build

- `checkJdkGlue` in `sync-guard/build.gradle.kts` (and the "JDK types only" note in `Mod.java`) rejects glue for
  H1-H4/H6, whose targets take game types. It applies to preloaded mods; Sync Guard is not preloaded. The main agent
  decides whether to drop it from this module.

### Risks

- Cancelling at `addIncoming` HEAD: allowed by `zabric-hooks.md` only for the call shapes fully replaced (our id
  only), on methods nothing on the target mod list patches (check the `[ZB] patching` lines of `myserver1`'s mods), and
  stated in the mod's README.
- Gate closed (mod failed) mid-session: H1/H3 stop running, so a peer still sending our id hits vanilla: error line
  per packet, and on the server `AntiCheat.PacketType.act` (no-op at the default option 4; a kick or ban at 1/2 for a
  fully connected, non-exempt client). The server side must stop sending once its gate closes; the client side the
  same.
- Reentrant `bufferLock`: a send from inside a vanilla send corrupts that vanilla packet without any error.
- Co-op split-screen joins (`delayPacket`): in-scope deltas can be replayed after later packets; a sync frame may then
  report a transient mismatch. Pending/retry (R5) has to absorb it.
- Channel 1 is shared with transactions/timed actions/map add-remove/`ObjectModData`; under loss, our frames and the
  four changed types wait behind them (the cost `design.md` already states). Ordering on one RakNet channel follows the
  order of `Send` calls; mixing priorities (148 is MEDIUM) can only add latency (RakNet semantics, external, not
  verified).
- Another mod using the same raw id: not checked (no scan of Workshop mods).

## Caveats / Not Found

- RakNet per-channel ordering and priority interplay are from RakNet's documented behaviour; `RakNet64.dll` /
  `ZNetJNI64.dll` (incl. Steam mode) not disassembled.
- Whether `DebugType.Multiplayer.error` lines reach `console.txt` with default debug filters: not checked.
- `GameServer.receivePlayerConnect` timing of `setFullyConnected` relative to a `Hello` sent on channel 0 right after
  `PlayerConnect`: not traced; H2 should keep a `Hello` from a not-yet-fully-connected connection pending rather than
  drop it.
- Off-main-thread senders of the 4 changed types: none known, not exhaustively searched.
