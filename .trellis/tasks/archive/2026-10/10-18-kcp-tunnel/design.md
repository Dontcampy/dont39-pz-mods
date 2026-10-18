# Design: KCP tunnel

Decisions D1-D9 and requirements are in [prd.md](./prd.md). The TCP tunnel's design
([archived](../archive/2026-10/10-17-tcp-connection/design.md)) stays valid where this file does not change it:
two channels, client relay on `127.0.0.1:P` and `127.0.0.1:U`, server relay to `127.0.0.1` DefaultPort/UDPPort,
datagrams moved whole and never inspected.

## Module and names (D8)

- `tcp-tunnel/` becomes `tunnel/` (`git mv`): package `com.dont39.pz.tunnel`, facade `Tunnel`, feature name
  `tunnel`, glue `TunnelGlue`, Lua file `Dont39AllInOne_Tunnel.lua`, Lua globals `Dont39AllInOne_<verb>Tunnel...`,
  translation keys `UI_Dont39AllInOne_Tunnel_*` / `Sandbox_Dont39AllInOne_Tunnel*`. Registered in
  `settings.gradle.kts`, the feature list and `AllInOneMain` in place of `tcp-tunnel`.
- Kept for released servers: the sandbox option `Dont39AllInOne_TcpTunnel.Port`. New options use
  `Dont39AllInOne_Tunnel.<Option>`. The spec's naming rule gets this one exception at the spec update.
- Subpackages: `transport.tcp` (today's `Wire`, `Relay`, `ClientTunnel`, `TunnelListener`, adapted),
  `transport.kcp` (KCP ends and wire), `kcp` (the `ikcp.c` port), `fec` (FEC layer), `fec.rs` (Backblaze core).
  The rest (hooks, store, client/server coordination, logging) is shared.

Shared seam, one per transport:

```kotlin
internal interface Transport {
  // probe + open within the bound; throws Failed(reason key, detail) — the client then stops the join
  fun open(host: String, port: Int, gamePort: Int, log: TunnelLog): OpenTunnel
}
internal interface OpenTunnel { val udpPort: Int; fun close(reason: String) }
```

The server side: `TunnelServer.start()` reads both options and starts each listener that is non-zero; a bind failure
of one does not stop the other.

## Store (D3)

- New file `Zomboid/Lua/Dont39AllInOne_Tunnel.txt`, lines `<host>:<game port>=<mode>,<tcp port>,<kcp port>`, mode
  `off|tcp|kcp`, ports `0` when never set. Same key as today (host as typed, trimmed) and the same atomic write.
- Migration: when the new file does not exist and the released `Dont39AllInOne_TcpTunnel.txt` does, its lines are
  read as `tcp,<port>,0` and the new file is written. The old file is left in place, so an older mod version still
  finds it.
- An entry whose mode is `off` and both ports `0` is removed. A malformed line is an error naming the file and line
  (as today).

## Dialog (D3)

Wraps `ISMPEditServer` as today ([Wrapping Vanilla Lua UI](../../spec/mod/zombiebuddy-patching.md#wrapping-vanilla-lua-ui)):
a "Tunnel" `ISComboBox` (Off / TCP / KCP) and the port entry on one row below the port. The dialog keeps both ports
in its state; switching the combo saves the entry's text into the old transport's slot and shows the new one's. Off
hides or disables the entry but keeps both slots. SAVE sends mode and both ports (moving the key when host or port
changed, as today). Save is disabled when TCP or KCP is chosen without a port in 1..65535.

## Join (D4)

- Replace the `@ModifyVariable` with one cancellable `@Inject` at HEAD of `UdpEngine.Connect`:
  - no entry, mode off, Steam-ID branch (`port == 0` in Steam mode): return, vanilla runs;
  - re-entry (a thread-local flag set while we call `Connect` ourselves): return;
  - open the transport; on success call `engine.Connect("127.0.0.1", port, password, useSteamRelay)` with the flag
    set, then `ci.cancel()`; on failure fire `OnConnectFailed` with the translated reason (`Translator.getText(key,
    args)`) and `ci.cancel()`.
  This avoids ordering two HEAD handlers (connect-failure research). If re-entrant calling turns out not to weave
  (e.g. the glue skips nested calls), fall back to `@ModifyVariable` + a cancelling `@Inject` and verify their order.
- `GameClient.ip`/`port` keep the typed address, as today. `Shutdown` hook unchanged.
- Failure reasons, each a translation key with the host/port as arguments: no answer within the bound, refused, not
  a tunnel of this mod, protocol version mismatch (both versions), server on this machine (set Tunnel to Off), local
  port busy, the feature is disabled (with its reason). The log gets one line per failure, as today.
- Probe bounds: TCP 1 s connect + 1 s hello (unchanged). KCP: hello resent every 250 ms, 2 s total.

## Guard (D5)

`Dont39AllInOne_Tunnel.lua` wraps the `serverConnect` global (all dedicated joins from vanilla Lua call it; the
wrapper calls the original outside `pcall`). Inside `pcall`:

1. Look up `IP:port` in the store file, read by Lua itself (`getFileReader`), so it works without the Java.
2. Mode `off` or no entry: call the original.
3. Tunnel mode: ask `Dont39AllInOne_getTunnelProblem()` (a `@LuaGlobal` returning null when the feature is active,
   else its reason). If the global is missing (Java not loaded) or returns a reason, fire `OnConnectFailed` with that
   reason and do not call the original. Otherwise call the original; the Java hook does the rest.

Fail closed: an error in the guard reports with the Lua stack and refuses that join with a "tunnel check failed" text;
it does not pass the join through. This is the one place where a Lua error does not just disable the feature, because
passing through would be the UDP fallback D4 rules out.

## KCP wire protocol

UDP datagrams, each with a small header; integers big-endian:

| Type | Layout |
|------|--------|
| HELLO (client) | `conv=0 u32`, `"D39K"`, version `u8`, nonce `u32` |
| WELCOME (server) | `conv=0 u32`, `"D39K"`, version `u8`, nonce `u32`, conv `u32`, UDPPort `u16`, parameters (see below) |
| DATA / PARITY | `conv u32`, FEC header, payload |
| CLOSE | `conv u32`, kind `CLOSE`, sent 3 times, best effort |

- The server answers a HELLO with another version too, so the client can name both versions (as the TCP hello).
  A repeated HELLO from the same address and nonce gets the same conv.
- Sessions are keyed by remote address and conv; a datagram with an unknown pair is dropped (counted, not logged).
- Inside KCP (message mode), each message is `channel u8` + one RakNet datagram, as in the TCP frames.
- Idle timeout 60 s without any datagram (RakNet pings a live connection), like the TCP tunnel's `IDLE_MILLIS`.

## FEC (D2, D7)

- Sender: KCP's output packets are grouped in order; a group closes at `FecDataShards` packets or `FecFlushMillis`
  after its first packet, whichever is first. On close it sends `FecParityShards` parity packets coded over the
  group's actual count `k` (Reed-Solomon `(k, parity)`, codecs cached per `k`).
- FEC header, kind first so CLOSE shares its position: data `kind u8, seq u32`; parity `kind u8, seq u32, first
  u32, k u8, index u8` (`Fec.kt`). Data shards are padded to the group's longest packet and carry a `u16` length
  prefix inside the coded bytes only, so a recovered packet has its true length. The transport checks for CLOSE
  before the decoder, which rejects it.
- Receiver: feeds every data packet to KCP at once; keeps recent groups (bounded by count and age) and recovers missing
  data packets when it holds `k` shards of a group, feeding them to KCP. Duplicates are harmless to KCP.
- `FecParityShards = 0` turns FEC off (no header change, no parity).

- KCP takes the caller's clock in `input` and `flush` too (as kcp-go), not only in `update`: with ikcp's
  last-update clock the harness saw many spurious timeout retransmits ([research/kcp-harness.md](./research/kcp-harness.md)).

## Parameters (D6)

Sandbox options, page `Dont39AllInOne`, translated EN/CN. Defaults follow kcptun's fast mode, adapted for a game:

| Option | Default | Range | Meaning |
|--------|---------|-------|---------|
| `Dont39AllInOne_Tunnel.KcpPort` | 0 | 0-65535 | UDP port, 0 = off |
| `.KcpInterval` | 10 | 10-100 ms | KCP update interval |
| `.KcpResend` | 2 | 0-10 | fast resend after this many skips, 0 = off |
| `.KcpNoCongestion` | true | bool | turn off KCP's congestion window |
| `.KcpWindow` | 1024 | 32-8192 | send and receive window, packets |
| `.KcpMtu` | 1350 | 576-1450 | largest datagram the tunnel sends |
| `.FecDataShards` | 10 | 1-64 | data packets per FEC group |
| `.FecParityShards` | 3 | 0-32 | parity packets per group, 0 = no FEC |
| `.FecFlushMillis` | 20 | 1-1000 ms | longest wait before a part-filled group gets its parity |

KCP nodelay is always on. The WELCOME carries all of them; the client applies them as received (the server checked
the ranges). The server logs them once at start, the client once per join.

## Threading

- Server: one thread receives on the KCP socket and dispatches by session; one scheduler thread ticks every
  session's KCP at `KcpInterval` and flushes due FEC groups; per session two local UDP reader threads, as the TCP
  relay. Each session's KCP and FEC state is guarded by the session's lock (kcp is not thread-safe).
- Client: the same with one session. Threads are daemons named with the feature prefix.

## Errors and logging

As the TCP tunnel: network failures (probe, socket errors, idle timeout) close only the affected tunnel with one log
line; unexpected exceptions disable the feature through `TunnelLog.error`; game shape mismatches (hook target,
sandbox option missing or of the wrong type) disable with the reason
([error-handling.md](../../spec/mod/error-handling.md)). Per-datagram events (drops, recoveries, retransmits) are
counted and reported in the tunnel's close line, never logged one by one.

## Third-party code

`kcp` is ported from skywind3000/kcp `ikcp.c` (MIT) at master `b1a7a21` (2026-06-23, newer than tag 2.1.1; adds
the pluggable congestion-control hooks, unused while `KcpNoCongestion` is on), `fec.rs` copied from Backblaze
JavaReedSolomon (MIT) at master `d3c481d` (2025-04-10, no releases; the code is older and stable), only the classes
the default coding loop needs, the static loop list trimmed to it. The FEC layout follows xtaci/kcp-go `fec.go` at
`f3f1bbd` (2026-05-15). Each port names its source commit in its header. Each file keeps its copyright header; both
license texts go into the jar under `META-INF/licenses/`.

## Rollback

Each implement step leaves a working build. Reverting the task restores `tcp-tunnel` as released; the released store
file is never modified, so clients keep their TCP ports.
