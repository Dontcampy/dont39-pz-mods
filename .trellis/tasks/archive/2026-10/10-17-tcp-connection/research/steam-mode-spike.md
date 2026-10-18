# Spike: joining through a plain UDP relay (implement step 1)

2026-10-18, Windows 11, local vanilla dedicated server (no mods, no ZombieBuddy) and the player's client (Build 42),
throwaway relay `127.0.0.1:26261 -> 127.0.0.1:16261` and `26262 -> 16262` (one upstream socket per client flow).
Joins by `+connect 127.0.0.1:26261`, test account on the local server only.

## Non-Steam (`-nosteam` server and client)

- Server listens on UDP 16261 only (`server is listening on port 16261`).
- The client's join went through the relay: RakNet open-connection request (`0x05`, 1464-byte MTU probe), replies
  `0x06`/`0x08`, then reliable traffic both ways; server logged `Connected new client`; the client reached the spawn
  selection screen.
- **Result: works, `G = DefaultPort`.** RakNet does not care that the client talks to `127.0.0.1:26261` and the server
  sees `127.0.0.1:<relay port>`.

## Steam mode (default launch)

- Server: `Server is listening on port 16261 (for Steam connection) and port 16262 (for UDPRakNet connection)`; both
  bound. A2S_INFO answers on 16261 with game port 16261 and an anonymous game-server Steam ID, A2S `appid` 0
  (`steam_appid.txt` of the dedicated server: 380870).
- Through the relay: the client's A2S_INFO/A2S_RULES queries went through and were answered. After Connect, native
  ZNet reported the state `GettingServerInfo` (a callback from `ZNetJNI64.dll`; the string is in no Java or Lua code)
  and stayed there; no further datagram reached either relay port.
- **Control, direct to `127.0.0.1:16261` without the relay: same hang at `GettingServerInfo`.** So this local Steam
  setup cannot complete a join at all; the relay is not the cause.
- Native connect logs (`-Dzomboid.znetlog=1`) did not appear in the client's stdout, stderr, `console.txt` or
  `DebugLog`.
- Local result: inconclusive (see the Linux server run below).

## Steam mode against the user's Linux server (Steam mode, DefaultPort 46261, UDPPort 46262, server password set)

No server password was entered: an `InvalidServerPassword` answer proves the connection reached the login stage.
Client states come from the `connectionStateChangedCallback` lines in the client's DebugLog.

- Relay `127.0.0.1:26261 -> server:46261` only. States: `GettingServerInfo` (A2S through the relay, then ~4 s),
  `UDPConnecting` (13 s, nothing reached the relay, timed out), `SteamConnecting` (2 s), `InvalidServerPassword`.
  The connection that answered went **around** the relay over Steam networking (by the server's Steam ID).
- Relay also listening on `127.0.0.1:46262 -> server:46262`. States: `GettingServerInfo` (~4 s), `UDPConnecting`, then
  `InvalidServerPassword` after 34 ms. The RakNet handshake (`0x05` 1464-byte MTU probe, `0x06`-`0x08`, reliable
  traffic) ran through `127.0.0.1:46262`.
- **Result: works.** In Steam mode the client sends A2S to `<host>:<port>` as given to `Connect`, learns the server's
  UDPPort (natively, not from A2S_RULES, whose keys are description, lastWipe, modCount, mods, open, public, pvp,
  version), and connects RakNet UDP to `<host>:<UDPPort>` first; Steam networking is only the fallback. A tunnel that
  listens locally on both the game port and the server's UDPPort carries the whole connection.
