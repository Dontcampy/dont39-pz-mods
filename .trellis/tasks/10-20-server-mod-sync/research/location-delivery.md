# Research: how the server hands the download location to the client before `CheckMods`

- **Query**: how a B42 dedicated server running the Zabric feature can give each joining client a small piece of data
  (download location: port or URL, maybe a manifest version) that the client has before `ConnectToServerState` reaches
  `WorkshopUpdate` / `CheckMods`, without breaking vanilla clients; evaluate candidate channels for timing, vanilla
  client effect, modded client vs vanilla server, hook complexity; recommend one.
- **Scope**: internal (game bytecode via `javap -c -p -l`, vanilla Lua, repo `tunnel/`, `.trellis/spec/mod/`)
- **Date**: 2026-10-20

Java citations are `Class.method (File.java:line)` from the LineNumberTables of the client jar
`E:/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid.jar`. The dedicated server jar has byte-identical
(md5) `ConnectionDetails`, `RequestDataPacket`, `RequestDataManager`, `RequestDataManager$RequestData`,
`ZomboidNetData`, `UdpEngine`, `GameClient`, `GameServer`, `LoginPacket`, `ByteBufferReader`, `ConnectToServerState`,
so the citations hold for both sides.

## Files Found

| File | Description |
|---|---|
| `zombie/network/ConnectionDetails.class` | `write` (server) and `parse` (client) of the connection-details data; both `public static` |
| `zombie/network/packets/RequestDataPacket.class` | Carries connection-details as `RequestID.ConnectionDetails` (full or chunked) |
| `zombie/network/RequestDataManager.class` (+ `$RequestData`) | Chunked transfer: server queues parts, client reassembles into an exact-size buffer |
| `zombie/gameStates/ConnectToServerState.class` | Copies the buffer in its constructor; `Finish` reads the last part |
| `zombie/world/WorldDictionary.class` | `saveDataForClient` / `loadDataFromServer`: the last part, length-prefixed |
| `zombie/network/GameServer.class` | `receiveClientConnect` sends spawn regions then connection-details; `addIncoming`; `setupSteamGameServer` (Steam rules) |
| `zombie/network/packets/connection/LoginPacket.class` | `processServer` calls `receiveClientConnect` after login checks |
| `zombie/core/raknet/UdpEngine.class` | `connected()` sends Login; `decode` routes id 134 packets |
| `zombie/network/GameClient.class` | `addIncoming`, `update` (loading vs main-loop dispatch), `doConnect` (`ip`, `port`) |
| `zombie/network/ZomboidNetData.class` | `read`: packet id -> `PacketType` lookup |
| `zombie/network/PacketTypes$PacketType.class` | Packet header (`134`, `short ordinal`), `onClientLoadingPacket` |
| `zombie/core/raknet/RakNetPeerInterface.class` | Reliability / priority constants |
| `zombie/network/anticheats/AntiCheat.class` | `act`: what the server does on an unknown client packet |
| `zombie/core/znet/ServerBrowser.class`, `SteamGameServer.class` | Steam server rules (A2S) |
| `media/lua/client/OptionScreens/MultiplayerUI.lua:1594-1606` | Server list requests Steam rules |
| `tunnel/src/main/kotlin/com/dont39/pz/tunnel/ClientConnect.kt`, `TunnelServer.kt`, `ServerStart.kt` | Repo example: client connect hook, server start hook, sandbox-option port |

---

## 1. When connection-details is sent and how it reaches `ConnectToServerState`

### Server side order

1. Client: RakNet `connection-request-accepted` -> `UdpEngine.connected()` (UdpEngine.java:168-187) sends
   `PacketType.Login` (172). On `connection-request-accepted` `decode` calls `connected()` directly only outside Steam
   mode (UdpEngine.java:231-237); in Steam mode it stores the Steam id and `connected()` runs after the Steam auth
   step (that step not traced). The `askPing` / `sendQR` / `askCustomizationData` variants are server-list probes, not
   joins.
2. Server: `LoginPacket.processServer` (LoginPacket.java:48-...) runs the checks (version, already connected, full,
   ban, password, ping limit; each failure sends `AccessDenied`), then `GameServer.receiveClientConnect(connection,
   logonResult)` (LoginPacket.java:115 and 200; with 2FA it sends `GoogleAuthRequest` at 198 and `GoogleAuthPacket`
   calls `receiveClientConnect` later).
3. `GameServer.receiveClientConnect` (GameServer.java:2678-2720): free slot (or `AccessDenied ServerFull` +
   disconnect, 2680-2684), slot/id maps, `PlayerDownloadServer`, then **one `SpawnRegion` packet per spawn region**
   (2705-2715), then `new RequestDataPacket().sendConnectingDetails(connection, logonResult)` (2718-2719).
4. `RequestDataPacket.sendConnectingDetails` (RequestDataPacket.java:160-169): `largeFileBb.clear()` (static 50 MB
   buffer, `allocateLargeFileBB` 120-123), `ConnectionDetails.write(connection, logonResult, largeFileBbWriter)`,
   `doSendRequest(connection)`.
5. `doSendRequest` (232-241): `largeFileBb.position() < 1024` -> one `FullData` packet; otherwise
   `RequestDataManager.putDataForTransmit(id, connection, largeFileBb)` -> `PartData` chunks. `RequestData(id, bb,
   guid)` (RequestDataManager.java:44-46) sizes its copy by `bb.position()`, i.e. **everything `write` put into the
   buffer, including anything appended after the vanilla parts, is sent**. Connection-details always exceeds 1024
   bytes in practice (sandbox options + world dictionary), so it goes as `PartData`.

So the packets a joining client receives before connection-details are: `AccessDenied` / `GoogleAuthRequest` /
login-queue packets (only on those paths) and the `SpawnRegion` packets. Nothing else is sent in between.

### Client side reassembly

- `RequestDataPacket.parse` (181-197): `FullData` -> copies the packet's remaining bytes into `largeFileBb` with
  `limit = size`; `PartData` -> reads `dataSize, dataSent, partSize`.
- `processClient` / `processClientLoading` (314-324): `FullData` -> `largeFileBb.position(0)`,
  `doProcessData(largeFileBbReader)`; `PartData` -> `doProcessPart` -> `RequestDataManager.receiveClientData`
  (RequestDataManager.java:114-148), which writes into a `RequestData` whose buffer is
  `ByteBuffer.allocate(fileSize)` + `clear()` (`RequestData(id, int, long)`, 48-52, `fileSize` = server's
  `dataSize`), and when all bytes arrived returns it with `position(0)` (145-148).
- `doProcessData` (336-337): `ConnectionDetails.parse(reader)`.
- **At `ConnectionDetails.parse` HEAD the reader has `position == 0` and `limit == exact byte count the server wrote`**
  on both paths (`FullData`: `limit(size)`; `PartData`: capacity = limit = `fileSize`).
- `ConnectionDetails.parse` (ConnectionDetails.java:49-56) -> `new ConnectToServerState(b)`, `enter()`,
  `MainScreenState.setConnectToServerState`.
- `ConnectToServerState.<init>` (ConnectToServerState.java:62-66): `ByteBuffer.allocate(b.capacity())`, `put(b.bb)`,
  `rewind()`. After `rewind` the copy's `limit` is its **capacity**, not the data length: on `PartData` it equals the
  data length, on `FullData` it is 52,428,800 (the static buffer's capacity) with zeros after the data. **Reading "from
  the end" must therefore use the `parse` argument's `limit()`, not the copy's capacity.**
- These run on the main thread: `GameClient.update` hands pre-login packets to `gameLoadingDealWithNetData` while
  `playerConnectSent` is false (GameClient.java:360-363), which calls `PacketType.onClientLoadingPacket` ->
  `processClientLoading`.

### What vanilla does with bytes after the last part

- `Finish` (ConnectToServerState.java:685-758) reads start location ... berries, then `receiveWorldDictionary` (745),
  then sets `state = Exit` (757). No `remaining()` / `limit()` check anywhere.
- `WorldDictionary.loadDataFromServer` (WorldDictionary.java:229-234) reads `int length` then exactly `length` bytes.
  `saveDataForClient` (237-257) writes a back-patched length, so the part is self-delimiting.
- **Confirmed: vanilla ignores any bytes after the world dictionary.** They are copied into
  `ConnectToServerState.connectionDetails` and never read.

---

## 2. Packet dispatch and unknown packets

- Header: `PacketType.doPacket` (PacketTypes.java:956-958) writes `byte 134` + `short getId()`, and `getId()` is the
  enum `ordinal()` (961). 298 constants in this build; ids are not stable across builds.
- `UdpEngine.decode` (UdpEngine.java:190-346): `134` -> `getShort` -> `GameServer.addIncoming` (server) or
  `GameClient.addIncoming` (client) (316); an unknown first byte only logs `"Received unknown packet: %s"` on the
  client; on the server it also runs `AntiCheat.PacketRakNet.act` (337-345).
- `GameClient.addIncoming` (GameClient.java:1725-1761): special-cases `SentChunk`, `NotRequiredInZip`, `ChunkNotReady`,
  `LoadPlayerProfile`, else `ZomboidNetData.read` and queues it.
- `ZomboidNetData.read` (ZomboidNetData.java:32-40): `PacketTypes.packetTypes.get(id)`; null -> `DebugType.Multiplayer
  .error("Received unknown packet id=%d")` (33-34), `type` stays null, packet still queued.
- Vanilla client, unknown id:
  - pre-login (`playerConnectSent == false`): `gameLoadingDealWithNetData` calls `d.type.onClientLoadingPacket` on a
    null `type` -> `NullPointerException` caught -> `printException("Error with packet of type: %s")` and discard
    (GameClient.java:933-938). **No disconnect; one error line + stack trace in the vanilla client's console.**
  - in game: `mainLoopDealWithNetData` discards null-type packets silently (537-539).
- Vanilla server, unknown id from a client: `GameServer.addIncoming` (GameServer.java:3104-3114) calls
  `AntiCheat.PacketType.act(connection, ...)`; `AntiCheat.act` (AntiCheat.java:163-181) is gated by the anti-cheat
  being enabled, `connection.isFullyConnected()` and the role's `CantBeKickedByAnticheat`, then logs / kicks / bans per
  the server option. So a modded client sending an unknown packet to a vanilla server can get kicked once fully
  connected (not traced: the exact default option value).
- Reliability: `RakNetPeerInterface` constants `RELIABLE = 2`, `RELIABLE_ORDERED = 3`. `RequestDataPacket` is
  `@PacketSetting(ordering = 4, priority = 1, reliability = 2, handlingType = 7)`: **RELIABLE, not ordered**. A packet
  the server sends right before connection-details is therefore **not guaranteed to arrive before it**.
  `UdpConnection.endPacket(int priority, int reliability, byte ordering)` is public, so a raw packet can be sent with
  any setting.

---

## 3. What the client knows about the address at that point

- `GameClient.doConnect` (GameClient.java:1811-1829) stores the typed host trimmed in `GameClient.ip` (1813) and the
  typed port in `GameClient.port` (1815); `startClient` passes them to `UdpEngine.Connect` (243-249). Both are
  `public static`.
- With the tunnel, `ClientConnect.kt` only replaces the `Connect` call (RakNet goes to `127.0.0.1`); the comment there
  states the game's own fields keep the typed address. So `GameClient.ip` is the server's typed host also through the
  tunnel, while `GameClient.connection`'s address is `127.0.0.1`.
- `GameClient.doConnectCoop(steamId)` (1833-1838) puts a **Steam ID string** in `ip` and `0` in `port` (hosted / Steam
  friend joins). For those joins there is no host to combine a port-only value with.
- So a port-only value can be combined with `GameClient.ip` for typed-address joins (hostname or IP, possibly a LAN
  address); a full URL from the server does not depend on it.

---

## 4. Candidates

### a. Trailer appended after the last part of connection-details

- **Server hook**: `@Hooks(ConnectionDetails::class)`, `@Inject(method = ["write"], at = [At("RETURN")])` on the
  `public static write(UdpConnection, ServerWorldDatabase.LogonResult, ByteBufferWriter)` (all three types public;
  `LogonResult` is a `public` nested class). The `return` is after the method's `try/catch` (ConnectionDetails.java:
  28-46), so RETURN runs only when all vanilla parts were written. Append e.g. `[payload][int payloadLength][long
  magic]` with `ByteBufferWriter.putUTF/putInt/putLong` (public). `doSendRequest` sends `position()` bytes, so the
  trailer goes out with no other change. `write` gets the `UdpConnection`, so the payload can differ per client.
  The same pattern as `tunnel/.../ServerStart.kt` (public method on a server class).
- **Client hook**: `@Hooks(ConnectionDetails::class)`, `@Inject(method = ["parse"], at = [At("HEAD")])` on the
  `public static parse(ByteBufferReader)`. Read with absolute `ByteBuffer` gets at `b.limit() - 8` (magic),
  `b.limit() - 12` (length), and the payload before it (`b.bb.duplicate()` for a UTF read), leaving `position` at 0.
  `ByteBufferReader.bb` and `limit()` are public. No widening needed.
- (1) Timing: `parse` runs once the whole packet is reassembled and **before** `ConnectToServerState` exists
  (ConnectionDetails.java:52-54), i.e. before `Start`, `WorkshopInit`, `WorkshopUpdate` and `CheckMods`. It is the
  earliest point the join state machine can know anything, and it is the same data unit, so there is no ordering race.
- (2) Vanilla client: trailing bytes are never read (section 1). No log line, no behavior change.
- (3) Modded client, vanilla server: the packet ends with the world-dictionary bytes; no magic at `limit - 8` ->
  "no data", detected synchronously and without a timeout. A 64-bit magic (plus the length bound check
  `length <= limit - 12`) makes a false match on vanilla data negligible. Works in `-nosteam` too (the trailer sits
  after the Steam-dependent part, so its position does not depend on the mode).
- (4) Complexity: two hooks on public static methods of a public class, both sides; no nested classes, no private
  targets, no packet ids.
- Caveat: reading the copy inside `ConnectToServerState` instead (e.g. at `<init>` RETURN) is wrong on the `FullData`
  path (copy capacity 50 MB, section 1); read at `parse` HEAD.
- Caveat: another mod appending its own trailer would need the same "magic at the very end" convention to coexist;
  the last writer wins the end position (not a concern in this repo; not checked in other mods).

### b. Custom packet before (or around) connection-details

- Server send: hook `GameServer.receiveClientConnect` HEAD or `RequestDataPacket.sendConnectingDetails` HEAD (both
  public), `connection.startPacket()`, `putByte(134)`, `putShort(<id not in packetTypes>)`, payload,
  `connection.endPacket(priority, reliability, ordering)`.
- Client receive: `GameClient.addIncoming(short, ByteBufferReader)` HEAD, `cancellable`, for the custom id (public
  method), runs on the network thread.
- (1) Timing: sent before connection-details, but connection-details is RELIABLE unordered, chunked; arrival order is
  not guaranteed (section 2). In practice it would be there long before `CheckMods`, but not provably before `parse`.
- (2) Vanilla client: not a crash or disconnect, but `"Received unknown packet id=%d"` error plus an NPE stack trace
  `"Error with packet of type: null"` per packet in the vanilla client's log (ZomboidNetData.java:33-34,
  GameClient.java:933-938).
- (3) Modded client, vanilla server: nothing arrives; "no data" can only be concluded by a timeout or by waiting until
  some later point (e.g. holding `CheckMods`), since the packet and connection-details are not ordered.
- Id choice: packet ids are enum ordinals (`getId`), so a future build that grows `PacketType` past the chosen id
  would make vanilla misparse it as a real packet; an id near `Short.MAX_VALUE` avoids that in practice.
- (4) Complexity: two hooks plus a raw packet format; more moving parts than (a).
- Variant b' (client asks the server with a custom request): a vanilla server routes the unknown id to
  `AntiCheat.PacketType` (GameServer.java:3111-3114), which can kick once the connection is fully connected; rejected.

### c. Encode in an existing early field

- `writeServerDetails` (ConnectionDetails.java:59-67): the server name is written only for a Steam co-op slave
  (61-63); a dedicated server has no free-text field there. `Role.send` is the client's role.
- `writeGameMap` (70-71): `TestTCP` stores it in `GameClient.gameMap` and derives `Core.gameMap` from it (splits on
  `;`); extra text changes the vanilla client's map list. Breaks vanilla.
- `writeWorkshopItems`: a fake item makes vanilla subscribe to a non-existent item -> join fails.
- `writeMods` (82-114): a fake `(modId, workshopId, name)` entry makes vanilla `CheckMods` fail with
  `Mod "<name>" is not installed`; editing a real entry's name changes the failure text only, but the name is shown
  only on failure and `workshopId` is used for the Workshop mapping. Breaks or misleads vanilla.
- `writeServerOptions` / `writeSandboxOptions` (124-133): read only in `Finish` (after `CheckMods`). A modded client
  could peek them at `parse` time only by parsing every earlier part itself (server details incl. `Role`, map,
  Workshop list, mods, start location, then options), which ties the feature to the whole layout. A mod sandbox option
  is harmless to vanilla clients that have the Lua mod, but the peek is fragile.
- Verdict: no early field is free; (c) breaks vanilla or needs a full re-parse.

### d. Things the client already queries before connecting

- Steam server rules: `GameServer.setupSteamGameServer` (GameServer.java:1217-1261) publishes key/values with
  `SteamGameServer.SetKeyValue` (`description`, `version`, `mods`, `modCount`, `lastWipe`, ...); a mod could add a key.
  The client side is `ServerBrowser.RequestServerRules(host, port)` -> `onRulesRefreshComplete`, called from the
  server list (`MultiplayerUI.lua:1594`, handler 1606). Only in Steam mode, async over Steam's query port (UDP, not
  through the tunnel), only if the server is registered with Steam, not run on a direct join, and the client would have
  to issue it itself and wait. Not confirmed: value length limits.
- Ping probe: `GameClient.askPing` makes a separate short connection from the server list; `GameServer.answerPing`
  (1930-1942) replies with `Ping` (ip, player count, max players). Not part of the join connection; changing its
  layout affects vanilla server lists. Rejected.
- Login response: on success the server sends no login-specific reply; it goes straight to spawn regions +
  connection-details (section 1). `SpawnRegion` packets (GameServer.java:2705-2715, a `KahluaTable.save` each) arrive
  earlier but are unordered too and feed the spawn selection; not evaluated further.
- Verdict: nothing in this group is earlier and reliable for a direct join.

### e. Out of band: HTTP on a well-known port derived from the game port

- E.g. TCP on the same number as the UDP game port (TCP and UDP port spaces are separate), `GET /...` returns the
  location (JSON or a redirect to the real file server).
- (1) Timing: the client can fetch it whenever it likes (e.g. in parallel with the join), so timing is fine.
- (2) Vanilla client: unaffected (never connects).
- (3) Modded client, vanilla server: connection refused or timeout; something else may listen on that port (false
  positive unless the response is validated).
- Trade-offs: the owner must open / forward one more fixed TCP port (or the derived rule must be configurable, which is
  what D4 says the client must not need); needs a host, so it fails for Steam-ID joins (`ip` is a Steam ID, section 3)
  and bypasses the tunnel unless the tunnel also carries it; it is not "the server sends it" (D4) and the fixed port is
  the one thing a later static server (D5) could not move.

---

## 5. Comparison

| | (1) before `Workshop*`/`CheckMods` | (2) vanilla client | (3) modded client, vanilla server | (4) hooks |
|---|---|---|---|---|
| a. trailer | yes, at `parse`, same data unit | ignored silently | magic absent, synchronous | 2, public static, no widening |
| b. custom packet | likely, not guaranteed (RELIABLE unordered) | error + NPE trace in log | timeout only | 2 + raw packet id |
| c. early field | yes | breaks / misleads, or needs full re-parse | n/a | 1-2 + full layout parse |
| d. Steam rules | client must query and wait | unaffected | timeout / no key | Steam mode only, not on direct join |
| e. derived HTTP port | yes | unaffected | refused / timeout / false positive | client code only, but extra port, no Steam-ID joins |

## Recommendation

**(a) the connection-details trailer.** A server `RETURN` hook on `ConnectionDetails.write` appends a self-describing
record at the very end (payload, its length, a fixed 64-bit magic last); a client `HEAD` hook on
`ConnectionDetails.parse` reads it from `b.limit()` backwards without moving the position. It is the only channel that
is atomic with the join data, arrives before every `ConnectToServerState` state, is invisible to vanilla clients, and
lets a modded client tell "vanilla server" apart synchronously. The record can carry the location string (a URL, or a
port the client combines with `GameClient.ip`), a format version and a manifest version, and can differ per
connection since `write` receives the `UdpConnection`.

## Related Specs

- `.trellis/spec/mod/zabric-hooks.md` — hook API rules (injection points, gating).
- `.trellis/spec/mod/access-widening.md` — not needed for (a): every type involved is public.
- Prior research: [workshop-hook-points.md](./workshop-hook-points.md) §1 (packet layout, state machine), §4 candidate
  #6 (peek at `parse` / `<init>`); [mod-discovery.md](./mod-discovery.md).

## Caveats / Not Found

- Not confirmed in game: a weave of `ConnectionDetails.write` RETURN / `parse` HEAD (public static targets, so the
  common path); and that no other installed mod appends to connection-details.
- The `FullData` (< 1024 bytes) path was traced but cannot occur for a real connection-details packet; the trailer read
  at `parse` HEAD is correct on both paths anyway.
- Not traced: the default value of the anti-cheat option behind `AntiCheat.PacketType` (relevant only to b').
- Not confirmed: Steam rule value length limits; whether `useSteamRelay` joins keep a routable host in `GameClient.ip`
  (the field holds the typed string; Steam-ID co-op joins hold a Steam ID).
- `SpawnRegion` packets as a carrier were not evaluated beyond their timing.
