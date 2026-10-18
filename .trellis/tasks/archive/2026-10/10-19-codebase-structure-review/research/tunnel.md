# tunnel and network-status

Overall: well structured; most size is ported/vendored code. Value is in tests and one shared local-loopback helper.

## Leave alone

- `tunnel/.../kcp/Kcp.kt` (540): port of skywind3000 ikcp.c b1a7a21 (header :1-10), documented deviations, "Not
  thread-safe" (:18).
- `tunnel/.../fec/rs/*.java` (~1,280): Backblaze JavaReedSolomon d3c481d, MIT (ReedSolomon.java:1-8).
- `KcpSession.kt` (235): cohesive (one conversation's KCP+FEC+local sockets under one lock :57).
- `Dont39AllInOne_NetworkStatus.lua` (401): cohesive sections; loss math (:74-155) is pure.
- `Fec.readInt` (big-endian) vs `Kcp.readInt` (KCP byte order): different on purpose.
- Transport abstraction (`Transport.kt:7-25`) fits both TCP and KCP. `LinkStats` (:39-48) KCP-only but nullable;
  mode→transport mapping in `TunnelClient.kt:61-65`, `TunnelServer.kt:15`, `TunnelStore.kt:36-41` — fine at two.
- Threading: blocking sockets + daemon threads, fine at this scale. Server `KcpListener.dispatch` → `session.input`
  runs FEC decode for every player on one receive thread (:84,120): head-of-line blocking only matters at high player
  counts. Client probe blocks the game thread by design (TcpTransport.kt:11, KcpTransport.kt:14).
- network-status ↔ tunnel: no Kotlin dependency by design (network-status-icon `design.md:12-15`); linked by the
  `Dont39AllInOne_getTunnelStatus` Lua global, NS works without it (NS.lua:90).
- Shared Lua helpers across Tunnel / NetworkStatus / ModOptionsSidebar (`log`, disable-once `fail`, file read, `trim`,
  `reportedNoJava`): ~10 lines each, but a shared Lua file couples independently packaged features. Not worth it.

## T1. Tests (prerequisite for any tunnel refactor)

`tunnel` has no `src/test`; the KCP loss harness was kept out of repo (10-18-kcp-tunnel prd.md:61, D9). JUnit wiring
to copy: `zabric/build.gradle.kts:42-49`; `pz-*` plugins have none.
- (a) Kcp + FecEncoder/FecDecoder under random loss, in-process.
- (b) `Wire`/`KcpWire` hello and version mismatch.
- (c) Loopback end-to-end of Relay and KcpSession.
- (d) TunnelStore round-trip + released-file migration; needs `folder` (TunnelStore.kt:44, `ZomboidFileSystem`)
  injectable; shared fixture to pin the Lua parser (`Tunnel.lua` `readStore` :62-92 deliberately re-implements
  `TunnelStore.parse`/`entry` :123-146, "change both together" :14).

## T2. TCP / KCP duplication

- Local end: `peers: AtomicReferenceArray` + CHANNELS check + `closed` in Relay.kt:29-35 and KcpSession.kt:50-55;
  receive loop in Relay.udpToTcp (:72-93) and KcpSession.fromLocal (:182-208); peer lookup + send in Relay.tcpToUdp
  (:110-113) and KcpSession.deliver (:218-221). Proposal: `LocalChannels` (sockets + peers, `receiveLoop(channel,
  headerBytes, onDatagram)`, `deliver`, `close`), header size as parameter (3 vs 1 byte). ~60 lines; needs T1(c).
- Server socket setup `listOf(gamePort, udpPort).map { InetSocketAddress(LOOPBACK, it) }` + connected
  `datagramSocket(0)`: TunnelListener.kt:79-80, KcpListener.kt:141-144.
- `opened.forEach { runCatching { it.close() } }` ×5: TunnelIo:50, ClientTunnel:58, TunnelListener:95,
  KcpListener:146, KcpClient:80 → `closeQuietly`.
- `KcpClient.UDP_BUFFER` (:26) duplicates private `TunnelIo.UDP_BUFFER` (:29). Peer address formatting
  TunnelListener:98 / KcpListener:179. Error-throttled accept loops TunnelListener:46-70 / KcpListener:78-102:
  borderline, leave.

## T3. Tunnel status table contract

Untyped table written in `TunnelClient.kt:134-144`, read in NS.lua:76,128-133,196,210-213. Names drift:
`seqExpected`→`fecExpected`, `seqMissing`→`fecMissing` (misleading: counted with FEC off, FecDecoder.kt:197),
`srttMillis`→`srtt`. `ConnectionStatus.put` (:74) `KahluaTable.put(Number)` extension; TunnelClient converts with
`.toDouble()` by hand — helper could live in zb-kt-utils. Write the key list down once.

## T4. Dont39AllInOne_Tunnel.lua (489) holds three features

Join guard + store parser :41-136; add/edit server dialog :138-~400; server-list tags :400-489 (own `listDisabled`,
`listFail` :403-412). Split into guard / dialog / list needs a shared module table for `trim`, `portText`,
`readStore`, `MODE_TEXT`.

## Minor

- `TunnelClient.status()` (:130-147) builds the Lua table inside the orchestrator; optional `TunnelStatusLua`.
- `tunnel.kcp` vs `tunnel.transport.kcp` naming mildly confusing; optional rename to `tunnel.arq`.
