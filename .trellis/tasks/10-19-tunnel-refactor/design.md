# Design: tunnel tests and refactor

## Tests

- Plugin: `pz-junit` from 10-19-build-conventions. Tests touch only game-free classes; where a class reads game
  state (`TunnelStore.folder` from `ZomboidFileSystem`, TunnelStore.kt:44) the dependency becomes a constructor or
  function parameter with the game-backed default at the call site.
- Loss test (a): a lossy in-memory link (seeded `Random`) between two Kcp+Fec endpoints driven by a virtual clock, so
  it is deterministic and fast; no sockets.
- Loopback test (c): real sockets on 127.0.0.1 with ephemeral ports; a fake "game" UDP endpoint on each side.
- Lua parser: no Lua runtime in tests; the fixture file is read by the Kotlin test and referenced from a comment in
  the Lua parser ("change both together" stays).

## LocalChannels

```
class LocalChannels(targets: List<InetSocketAddress>?, headerBytes: Int)
  fun receiveLoop(channel: Int, onDatagram: (channel, buf, off, len) -> Unit)  // remembers latest sender
  fun deliver(channel: Int, buf: ByteArray, off: Int, len: Int)
  fun close()
```

Client side binds listening sockets (peers learned from senders); server side connects to the loopback game targets
(the shared setup from TunnelListener/KcpListener). Header framing stays in `Relay` (3 bytes) and `KcpSession`
(1 byte); `LocalChannels` only skips the header room. Threading unchanged (same daemon threads, same lock in
`KcpSession`).

## Status table

`TunnelStatusTable` (or a `status` function in a new file) builds the KahluaTable; the `put(Number)` extension from
`network-status/ConnectionStatus.kt:74` is not shared across modules (features do not depend on each other); the
tunnel gets its own private one or both use one in `zb-kt-utils` if it fits that module's scope
([ZabricKotlin Utils](../../spec/mod/zb-kt-utils.md)). Renamed keys: `seqExpected`, `seqMissing`, `srttMillis`
(writer field names).

## Lua split

`client/Dont39AllInOne_TunnelShared.lua` (returns a module table via `require`), `Dont39AllInOne_TunnelGuard.lua`,
`Dont39AllInOne_TunnelDialog.lua`, `Dont39AllInOne_TunnelList.lua`. Globals keep the `Dont39AllInOne_` prefix; the
Java-side Lua bridge names are unchanged.

## Rollback

R1 lands first and alone; R2–R4 are separate commits, each gated by AC1 and AC4.
