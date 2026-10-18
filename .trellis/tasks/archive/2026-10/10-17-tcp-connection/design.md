# Design: TCP tunnel for the game connection

Feature module `tcp-tunnel` in Dont39 All-in-One (package `com.dont39.pz.tcptunnel`, facade `TcpTunnel`, Lua prefix
`Dont39AllInOne_TcpTunnel`). Decisions D1-D8 are in [prd.md](./prd.md); game facts are cited with file:line in
[research/game-internals.md](./research/game-internals.md) (§n below).

## Shape

```
client RakNet --UDP--> 127.0.0.1:P (ch 0) \                                     / ch 0 --UDP--> 127.0.0.1:DefaultPort \ server
client RakNet --UDP--> 127.0.0.1:U (ch 1) / client relay ==TCP==> server:T relay  \ ch 1 --UDP--> 127.0.0.1:UDPPort     / RakNet
```

- `T`: the sandbox option (D7). `P`: the game port as given to `Connect` (normally the server's `DefaultPort`). `U`:
  the server's `UDPPort`, sent by the server in the handshake.
- RakNet runs end to end inside the tunnel, unchanged: handshake, reliability, ping, timeouts. The relays move whole
  datagrams and never look inside them.
- A join without a TCP port, or whose probe fails, calls the game's `Connect` with the original arguments: UDP exactly
  as today (D2, D3).

## Steam mode and the two channels

Measured in [research/steam-mode-spike.md](./research/steam-mode-spike.md):

- Non-Steam: the client sends A2S queries and RakNet to `<host>:<port>` as given to `Connect`; the server's RakNet is on
  `DefaultPort`.
- Steam mode: the client sends A2S to `<host>:<port>`, learns the server's `UDPPort` natively, and connects RakNet UDP
  to `<host>:<UDPPort>` first; only if that fails does it use Steam networking by Steam ID, which bypasses any tunnel.
  With a relay listening on `127.0.0.1:<UDPPort>` a join against the user's Steam-mode Linux server reached the login
  stage through it.

So the tunnel has two channels: 0 = the game port (`DefaultPort` on the server), 1 = `UDPPort`. The client hook only
replaces the host with `127.0.0.1`; the port stays as given, and the client relay listens on `127.0.0.1:P` and
`127.0.0.1:U`.

## Wire protocol

One TCP connection per tunnel.

1. Handshake, client first: magic `D39T` + protocol version byte. The server answers magic `D39T` + version byte +
   its `UDPPort` as `u16` big-endian. Anything else, a close or a timeout fails the probe (client) or drops the
   connection (server). It proves the port is this tunnel.
2. Then both directions carry frames: channel byte (0 or 1) + `u16` big-endian length + datagram bytes. An unknown
   channel, length 0 or over the relay's buffer closes the tunnel. RakNet datagrams stay below its MTU (~1.5 KB).
3. Either side closing TCP ends the tunnel; the other side closes its UDP sockets and RakNet times out as for any lost
   connection.

`TCP_NODELAY` on both ends.

## Client

- **Store**: TCP port per server address, key `<host as typed, trimmed>:<game port>`, in one file under
  `<Zomboid>/Lua/` (`getFileWriter`/`getFileReader`, §8). Keyed by address, not by the saved entry's id: the hook only
  sees host and port (§1), a new entry has no id yet (§2), and entries for the same address are the same server. Joins
  from `+connect`/invites or the server browser to a stored address use it too.
- **UI**: wrap `ISMPEditServer` (§3) the way `mod-options-sidebar` wraps vanilla UI: after the original `initialise`,
  insert a "TCP port" entry below the port, move the password widgets down and grow the dialog; on SAVE, write the
  store, moving the key when host or port changed. Empty or 0 removes the key.
- **Join**: a Zabric `@ModifyVariable(at = HEAD, argsOnly = true)` handler on
  `UdpEngine.Connect(String, int, String, boolean)` replaces the host with `127.0.0.1` when (a) the call takes the IP
  branch (not Steam mode with `port == 0`, §1), (b) the store has a TCP port for host:port, and (c) the probe succeeds
  and both local ports bind. The port argument is unchanged. Only the argument changes, so `GameClient.ip`/`port` keep
  the typed address (save folder name, `ClientPlayerDB`, §1).
- **Probe**: blocking TCP connect + handshake to `host:T` on the game thread (§1), 1 s connect timeout and 1 s
  handshake timeout; a refused port fails at once. Success keeps the socket as the tunnel. Failure logs one line (host,
  TCP port, reason) and the join goes over UDP; so does a local port that cannot be bound (e.g. a server on the
  same machine), with its own line. The freeze is bounded to 2 s and happens only for addresses with a
  stored TCP port; the server-list ping goes through the same `Connect` (§1), so it probes too.
- **Relay**: `DatagramSocket`s on `127.0.0.1:P` (channel 0) and `127.0.0.1:U` (channel 1); one reader thread per
  socket writes frames, one thread reads frames and sends each datagram from its channel's socket to the address
  RakNet last sent from on it. Daemon threads.
- **Lifetime**: one tunnel per `Connect`; a new `Connect` or `UdpEngine.Shutdown` closes the previous one.

## Server

- **Start**: Java exit hook on `GameServer.startServer()` (after `SandboxOptions.load()` from the save, §5), not
  `OnGameBoot` (too early). Reads `SandboxOptions.instance.getOptionByName("Dont39AllInOne_TcpTunnel.Port")`; null
  means the option is not registered, which is a mismatch (disable with reason). 0: no listener. Not in a coop host's
  server process (`CoopSlave`, §5): dedicated server only.
- **Listener** on `0.0.0.0:T`. A bind failure is logged as an error naming the port; the server runs without the
  tunnel and the game's UDP is unaffected.
- **Per connection**: after the handshake, two `DatagramSocket`s on `127.0.0.1:0`, connected to
  `127.0.0.1:GameServer.defaultPort` (channel 0) and `127.0.0.1:GameServer.udpPort` (channel 1), relay threads as on
  the client. In non-Steam mode nothing listens on `udpPort` and channel 1 stays idle. Each player is a distinct `127.0.0.1:<port>` to RakNet; no Java code limits connections
  per IP (§6).
- **Lifetime**: TCP close or relay error closes that player's socket only; the listener closes with the server.
- Players appear as 127.0.0.1 (D4). An admin `banip` on a tunnelled player would ban 127.0.0.1 (§6): documented in the
  Workshop text, not handled.

## Configuration

- `sandbox-options.txt` in the feature's mod assets: integer option `Dont39AllInOne_TcpTunnel.Port`, min 0, max 65535,
  default 0, with translated name and tooltip (format §5).
- Edits: an existing save's `map_sand.bin` may override an edited `<server>_SandboxVars.lua` at restart (§5,
  unverified). Step 6 of implement.md checks both the file and the admin panel's sandbox editor and the Workshop text
  documents the one that works.

## Errors and logging

- Network failures are runtime conditions the user chose to handle by falling back (D3): one log line each, never per
  datagram.
- Game shape mismatches (hook target, sandbox option missing) follow
  [error-handling.md](../../spec/mod/error-handling.md): the feature disables itself with the reason.
- Status line `[Dont39AllInOne] tcp-tunnel: ready`; on the server one line with the listener port or that it is off;
  on the client one line per tunnelled join and per fallback.

## Rollback

Self-contained in its module plus the feature list and `AllInOneMain`. With the sandbox option at 0 and no stored TCP
port, nothing runs beyond the dialog field.
