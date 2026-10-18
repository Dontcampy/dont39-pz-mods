# KCP tunnel for the game connection

## Goal

User request: "能不能利用tcp的那个思路，再做一套kcp的". Players whose link loses packets regardless of protocol can
join through the TCP tunnel (PR #16), but TCP stalls on each loss. A KCP tunnel with FEC, built the same way, recovers
faster at the cost of bandwidth. The player picks per server: no tunnel, TCP or KCP.

## Background

- `tcp-tunnel` carries the whole RakNet connection, unchanged, over one TCP connection per player: the client hook
  rewrites only the host of `UdpEngine.Connect` to 127.0.0.1, local relays on the game port (channel 0) and the
  server's UDPPort (channel 1), the server relays each tunnel to 127.0.0.1 DefaultPort/UDPPort. Design and game facts:
  [archived design](../archive/2026-10/10-17-tcp-connection/design.md),
  [game-internals](../archive/2026-10/10-17-tcp-connection/research/game-internals.md),
  [steam-mode-spike](../archive/2026-10/10-17-tcp-connection/research/steam-mode-spike.md).
  Transport-specific code: [Wire.kt](../../../tcp-tunnel/src/main/kotlin/com/dont39/pz/tcptunnel/Wire.kt),
  [Relay.kt](../../../tcp-tunnel/src/main/kotlin/com/dont39/pz/tcptunnel/Relay.kt),
  [ClientTunnel.kt](../../../tcp-tunnel/src/main/kotlin/com/dont39/pz/tcptunnel/ClientTunnel.kt),
  [TunnelListener.kt](../../../tcp-tunnel/src/main/kotlin/com/dont39/pz/tcptunnel/TunnelListener.kt). The rest
  (hooks, store, dialog, sandbox option, two channels) does not depend on the transport.
- KCP is an ARQ protocol over UDP: faster loss recovery than TCP (fast retransmit, no exponential backoff), more
  bandwidth. It does not help where UDP itself is blocked or throttled; the TCP tunnel stays for that.
- All-in-One is on the Workshop (id 3818388200, `modversion=0.9.0`). The staged copy
  `~/Zomboid/Workshop/Dont39AllInOne/` (2026-10-18 17:05) has the TCP tunnel, so its sandbox option
  `Dont39AllInOne_TcpTunnel.Port` and store file `Zomboid/Lua/Dont39AllInOne_TcpTunnel.txt` are treated as released.
  The released tooltip `UI_Dont39AllInOne_TcpTunnel_PortTooltip` promises a UDP fallback.
- Libraries ([research/kcp-fec-libraries.md](./research/kcp-fec-libraries.md)): every Java KCP port with FEC needs
  netty, which the game lacks; the only netty-free Kotlin core (kcp.kt) is new and unproven; Backblaze
  JavaReedSolomon (MIT) is small and established. All mod jars share one class loader, so bundled code lives in our
  own subpackage; All-in-One shades nothing today. kcp-go sends parity only for full shard groups, so at low packet
  rates it gives no FEC. The game ships Java 25.
- Stopping a join ([research/connect-failure.md](./research/connect-failure.md)): a cancellable `@Inject` at HEAD
  of `UdpEngine.Connect` that fires `OnConnectFailed` with translated text leaves the game as vanilla's UnknownHost
  does: the join screen shows the text and a Back button. A disabled feature skips its hooks, and without its Java
  (not approved in ZombieBuddy) there are no hooks at all; every dedicated-server join from vanilla Lua goes through
  the `serverConnect` global (`ConnectToServer.lua:242`, `CoopOptionsScreen.lua:673`).
- Spec: feature modules have no unit tests and a feature task adds no test framework
  ([quality-guidelines.md](../../spec/mod/quality-guidelines.md#tests)).

## Decisions

- D1. KCP over UDP on its own server port, carrying the whole RakNet connection like the TCP tunnel (user: "第一种").
- D2. FEC is in the MVP (user: "并且要加上FEC").
- D3. The add/edit server dialog gets a "Tunnel" dropdown (Off / TCP / KCP) and one port field in place of "TCP
  port". Each server address remembers a TCP port and a KCP port: switching the dropdown shows that transport's port
  and never forgets the other (user: "端口要保存，不能因为切换了就把之前的端口忘了"). Stored TCP ports carry over as
  TCP with that port.
- D4. No fallback to UDP, for TCP too: a join to an entry set to a tunnel that cannot be opened stops and shows why
  (user: "不要默认回退UDP"). Off joins over UDP as before.
- D5. Closing the gap in D4: a join to a tunnel entry is also refused, with the reason, when the feature is disabled
  or its Java is not loaded (user: "漏洞能处理掉吗").
- D6. KCP and FEC parameters have recommended defaults; the server owner can override each in the sandbox options,
  and the server sends them to the client in the handshake (user: "参数写一个推荐的默认值，但是沙盒也可以配置覆盖").
- D7. The KCP core is our own Kotlin port of `ikcp.c` (kcp.kt as a guide only), Reed-Solomon is Backblaze's core
  copied into our package with its license, and the FEC layer is ours with a flush timer for part-filled groups.
- D8. `tcp-tunnel` becomes one `tunnel` feature with both transports; released names (D3 store, the TCP sandbox
  option) keep working.
- D10. In-game feedback (user, 2026-10-18): the dialog's tunnel row was too cramped ("端口ui有点挤"); the server list
  shows which tunnel an entry uses ("服务器列表里使用了隧道应该显示出来").
- D9. Loss testing uses a harness outside the repo, as the TCP task did; adding tests to the repo is proposed
  separately.

## Requirements

- R1 (D1, D6). With the KCP port option non-zero, the dedicated server accepts KCP tunnels on that UDP port and
  relays each to 127.0.0.1 DefaultPort/UDPPort; with 0, nothing listens. The TCP listener works as before; both can
  be on at once.
- R2 (D6). Sandbox options for the KCP port and each KCP/FEC parameter, with defaults and ranges, translated
  EN/CN; the client uses the values the server sends.
- R3 (D3). Dialog: dropdown + port, per-transport ports per address, saved, prefilled on edit, migrated from the
  released store; Save disabled when a tunnel is chosen without a valid port; vanilla fields and saving unchanged.
- R9 (D10). The port gets its own row below the Tunnel dropdown, as wide as the vanilla fields. A server-list entry
  set to TCP or KCP shows the transport and port next to its address; Off entries look as vanilla.
- R4 (D1, D2). A join to a KCP entry probes the server (bounded, at most 2 s) and runs the whole game connection
  through KCP with FEC; FEC recovers lost datagrams also at low packet rates.
- R5 (D4). A failed tunnel open (no answer, refused, wrong protocol or version, server on this machine, local port
  busy) stops the join and shows the reason on the join screen; Back returns to the menu and a retry works.
- R6 (D5). With the feature disabled or its Java missing, a join to a tunnel entry is refused with the reason; Off
  entries join normally.
- R7. A dropped tunnel ends only that player's connection. Feature rules: facade, status line, gated hooks; game
  shape mismatches disable with the reason; network failures log one line each, never per datagram
  ([error-handling.md](../../spec/mod/error-handling.md)).
- R8. Workshop text: the tunnel section covers TCP and KCP, server options and firewall (TCP vs UDP port), the
  dropdown, no fallback, KCP's bandwidth cost, the 127.0.0.1/`banip` caveat. Bundled third-party code carries its
  license.

## Acceptance Criteria

- [x] AC1 (R1). Server console: a KCP listener line with port and effective parameters when set, an "off" line when
  0; the TCP listener line as before.
- [x] AC2 (R2). Changing a KCP/FEC option in `<server>_SandboxVars.lua` and restarting changes the server's
  parameter line, and the client's join line shows the same values.
- [x] AC3 (R3). Dialog: switching Off/TCP/KCP keeps both ports; they survive reopening and a game restart; an Off
  entry saves as vanilla; an entry with a TCP port from the released version opens as TCP with that port.
- [x] AC12 (R9). The dialog's port field is fully readable; the server list shows e.g. "KCP 48301" on a tunnel
  entry and nothing extra on an Off entry.
- [ ] AC4 (R4). (KCP joins of 1.5 and 2 min, TCP 1 min, no problems; not 5 min each, see research/in-game-test.md) KCP join against a Steam-mode dedicated server: client log shows it, the server sees 127.0.0.1, play
  at least 5 minutes. A TCP join still works the same way.
- [x] AC5 (R5). TCP and KCP each with a wrong port and with the server down: the join screen shows the reason within
  2 s (plus DNS), the server gets no UDP join, Back and a retry work.
- [ ] AC6 (R6). (Java missing: passed in game; feature disabled: not run in game) With the Java missing (jar removed in a test copy) and with the feature disabled (temporary local
  build), a tunnel entry's join is refused with the reason; an Off entry joins.
- [x] AC7 (R4). Harness: KCP+FEC core under 10 % and 20 % random loss each way, a steady low-rate stream plus
  bursts: every datagram arrives in order, the report shows p50/p99 latency, FEC recoveries and retransmits, with
  parity 3 vs 0.
- [ ] AC8 (R4). (not done; harness numbers instead) In game under simulated loss (OS-level tool chosen at test time), TCP vs KCP compared by the game's
  ping and play feel; or reported as not done.
- [ ] AC9 (R7). (one client closing closes only its tunnel; second client not done) Killing one client's tunnel disconnects only that client; a second client (KCP, TCP or UDP) on the
  same server at the same time; or reported as not done.
- [x] AC10 (R7). `./gradlew build assembleMod`, `:dont39-all-in-one:smokeTest`, `:zb-kt:serverSmokeTest` pass;
  the feature reports `ready`.
- [x] AC11 (R8). `workshop.txt` has the updated section; the jar carries the KCP and Reed-Solomon licenses.

## Out of Scope

- Wire compatibility with kcptun/kcp-go, encryption, per-player parameters, adaptive FEC.
- Reconnecting or switching transport mid-session; automatic transport choice.
- Real client IPs and IP bans for tunnelled players; Steam-ID joins.
- Unit tests in the repo (D9); version bump and Workshop upload (the user's release step).

## Risks

- The KCP port is the largest piece (estimated 1.5–3 days); a harness under loss is its only automated check.
- KCP retransmits RakNet's unreliable packets too (head-of-line blocking, as in TCP). FEC roughly doubles wire bytes
  on game-like traffic with the defaults, because timer-closed small groups (acks, low-rate packets) still get full
  parity ([research/kcp-harness.md](./research/kcp-harness.md)).
- An unpaced burst faster than the link makes KCP resend the whole queue, and parity lengthens the queue; RakNet
  paces its sends, so this should be rare (same research file).
- Steam-mode routing is native; the two-channel model rests on the TCP task's spike.
- The QR "send secret key" path (`ISMPEditAccount`) does not go through `serverConnect`; it only shows a generic
  failure and is not guarded.
