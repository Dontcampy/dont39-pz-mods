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

## Implementation notes

- Commits: pz-junit + injectable store folder; R1 tests; R2 (checklist steps 3 and 4 in one commit: the helpers'
  main users moved into `LocalChannels`); R3; R4; spec text.
- Test classpath: `tunnel/build.gradle.kts` adds the Kotlin stdlib as `testImplementation` (`pz-kotlin` makes it
  compile-only). No game jar: every tested class is game-free.
- `TunnelStore` stays the object that holds the `@LuaGlobal` functions, so the generated `TunnelLua` and all glue
  stay byte-identical; `get` and `set` take the folder, the Lua functions pass Zomboid/Lua.
- `LocalChannels` differs from the sketch: factories `listen(gamePort, udpPort, transport, target)` (client,
  `LOCAL_PORT` failure) and `toGame(gamePort, udpPort)` (server) instead of a nullable target list; the header size
  is a parameter of `receiveLoop`, whose callback gets the buffer and the datagram's length. `deliver` builds a packet
  per call instead of sharing one. After a close, `KcpSession`'s local reader ends at its next receive (sockets
  closed) instead of returning at once; same effect.
- Status table: `TunnelStatus.table(entry, stats)`, keys = `LinkStats` property names, listed in its KDoc. The
  `put(Number)` helper is private to it: one in `zb-kt-utils` would need a ZabricKotlin release first.
- Loss test: no bandwidth limit and no latency figures; 60 s virtual workload with 40 x 1400 B bursts every 5 s, one
  seed. Besides delivery it checks that FEC rebuilt packets only with parity on and that `seqMissing / seqExpected`
  is the link loss (within 3 points). The four runs take about 0.2 s; the whole `:tunnel:test` well under 5 s.
- Lua: the join guard has no disable flag, as before (it fails closed on each call). The shared module also holds
  `GAP`, `MAX_PORT`, `STORE`, `log` and a store version the dialog bumps after saving, which replaces the dialog
  clearing the list's cache directly.
- Lua parser change: `readStore` now refuses lines `TunnelStore` refuses (port above 65535, a chosen tunnel whose
  port is 0, a released port of 0). Before, Lua accepted them and the Java side failed at the join. Checked by running
  `TunnelShared.lua` on Kahlua from `projectzomboid.jar` against the fixtures in a scratch program (not in the repo);
  all Lua files touched also compile on Kahlua.
- AC4: the tunnel's Lua parts log no line on load (they did not before either), so `console.txt` can only be checked
  for Lua errors naming the four files.
