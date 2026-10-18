# Tunnel tests and refactor

Parent: [10-19-codebase-structure-review](../10-19-codebase-structure-review/prd.md). Evidence:
[research/tunnel.md](../10-19-codebase-structure-review/research/tunnel.md) (T1–T4). Runs after
10-19-build-conventions (uses `pz-junit`); independent of children 2 and 3.

## Goal

`tunnel`'s game-free code is covered by in-repo tests, and the duplicated local-end plumbing of the TCP and KCP
transports exists once, with no change to wire format, ports or player-visible behavior.

## Requirements

- R1 (T1). JUnit tests in `tunnel/src/test`:
  - (a) KCP + FEC in-process under random loss (10 % and 20 % each way): every datagram delivered in order, with
    parity on and off. Ported from the out-of-repo harness (`10-18-kcp-tunnel/research/kcp-harness.md`), sized to run
    in seconds.
  - (b) `Wire` / `KcpWire` hello round-trip and version mismatch.
  - (c) Loopback end-to-end of TCP `Relay` and `KcpSession` (both channels, both directions, close).
  - (d) `TunnelStore` parse/write round-trip and released-file migration, with the storage folder injectable; a
    fixture file documents the format shared with `Dont39AllInOne_Tunnel.lua`'s parser.
- R2 (T2). `LocalChannels` owns the local sockets, peers, receive loop (header size as a parameter), delivery and
  close; `Relay` and `KcpSession` use it. Also: one `closeQuietly`, one `UDP_BUFFER`, one server loopback-target
  setup, one peer-address formatter.
- R3 (T3). The tunnel-status table is built outside `TunnelClient` (a small builder next to it); its keys are written
  down once (KDoc on the builder, linked from the network-status spec text); `fecExpected`/`fecMissing`/`srtt` are
  renamed to match their meaning in the writer and in `Dont39AllInOne_NetworkStatus.lua` together.
- R4 (T4). `Dont39AllInOne_Tunnel.lua` splits into join guard, server dialog and server-list files plus one shared
  module for `trim`, `portText`, `readStore`, `MODE_TEXT`; each part keeps its own disable-on-error flag.
- R5. Untouched: `kcp/Kcp.kt`, `fec/rs/*`, `Transport` interface shape, wire format, port options, translations.

## Acceptance Criteria

- [ ] AC1. `./gradlew :tunnel:test` passes in under ~60 s; R1 (a)–(d) exist; (c) passes before and after R2.
- [ ] AC2. Parent AC1 (build gate, `smokeTest`, `serverSmokeTest`).
- [ ] AC3. In game against the Linux test server: join through a TCP tunnel and a KCP tunnel; the add/edit dialog and
  server-list tags work; the network-status icon tooltip shows the renamed stats; or each item reported as not done.
- [ ] AC4. Lua files load with no errors in `console.txt` during `smokeTest` (status lines of the tunnel's Lua parts
  present).
- [ ] AC5. `quality-guidelines.md#tests` describes the in-repo tunnel tests instead of the out-of-repo harness;
  `directory-structure.md` lists the new Lua files if it lists files.
