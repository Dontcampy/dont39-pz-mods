# TCP tunnel for the game connection

## Goal

Players see high packet loss on the game's UDP connection to the dedicated server. A Dont39 All-in-One feature gives
the client and the server a TCP path for the whole game connection, so a player whose network drops UDP can still
play, while everyone else keeps joining over UDP as before.

## Background

- User request: "我想额外做一套tcp的连接", "客户端+服务端", reason "udp丢包率高".
- The repo has no networking code today (grep over all modules except `external-repos`). Zabric already runs on client
  and dedicated server ([PreLaunchHook.java:21](../../../zabric/src/main/java/com/pzlauncher/zabric/entrypoint/PreLaunchHook.java)).
- All game traffic goes through `zombie.core.raknet.RakNetPeerInterface`, a JNI wrapper over native RakNet; connect,
  handshake, ping, timeouts and disconnects are native. RakNet already retransmits `RELIABLE*` packets.
- TCP only helps if the loss is UDP-specific (ISP QoS/throttling of UDP). On a link that loses packets regardless of
  protocol, TCP turns loss into stalls.
- `ZNetStatistics` (`RakNetPeerInterface.GetNetStatistics`) reports `PACKETLOSS_LAST_SECOND`/`PACKETLOSS_TOTAL`.
- Game details with citations: [research/game-internals.md](./research/game-internals.md).

## Decisions

- D1. In-mod tunnel of the whole game connection: RakNet runs unchanged inside it (approach A). Rejected: replacing
  RakNet's transport (approach B), an external tool (approach C).
- D2. UDP and TCP side by side: the game's UDP port stays as it is, the TCP listener is a separate port, and each
  player joins either way.
- D3. On a join to an address with a TCP port filled in, the client tries the tunnel and uses it when the server
  answers; otherwise it joins over UDP as today.
- D4. Tunnelled players show as 127.0.0.1 and skip the `bannedip` check (`ServerWorldDatabase.authClient`); accepted,
  not handled.
- D5. The mod must be enabled in the client's main-menu mod list (the tunnel is needed before the server's mod list is
  known); accepted as a precondition.
- D6. The TCP port is a per-server "TCP port" field below the port in the add/edit server dialog (`ISMPEditServer`).
  Empty means UDP only. The mod stores it itself.
- D7. The server owner sets the TCP port as a sandbox option of the mod; 0 (default) means no listener.
- D8. The code is a feature module shipped in Dont39 All-in-One.

## Requirements

- R1 (D1, D7). With the sandbox option set to a non-zero port, the dedicated server accepts tunnel connections on that
  TCP port and relays each to the game's RakNet UDP port on localhost. With 0, no listener is opened.
- R2 (D6). The add/edit server dialog has a "TCP port" field below the port; it is saved per server address and shown
  again when the entry is edited. The vanilla fields and saving work as before.
- R3 (D3). A join to an address with a stored TCP port probes the tunnel (bounded wait, at most 2 s) and, on success,
  runs the whole game connection through it; on failure it logs the reason and joins over UDP.
- R4 (D2). Joins without a stored TCP port, Steam-ID joins (Steam mode, port 0), and players on plain UDP are not
  changed, on the same server at the same time as tunnelled players.
- R5. A dropped tunnel ends that player's connection like any lost connection, without affecting other players or the
  server.
- R6. Feature rules: `Feature` facade, status line, gated hooks; game shape mismatches disable the feature with the
  reason ([error-handling.md](../../spec/mod/error-handling.md)). Network failures log one line each, never per
  datagram.
- R7. The Workshop text gets a section: purpose, server option and how to set it, the TCP port field, firewall,
  the 127.0.0.1/`banip` caveat, Steam-mode notes.

## Acceptance Criteria

- [x] AC1 (R1). Server console shows the listener line with the port when set, and an "off" line with no listener
  when 0.
- [x] AC2 (R2). A TCP port typed in the dialog survives closing and reopening the dialog and a game restart; an entry
  without it saves as before.
- [x] AC3 (R3). With a stored TCP port the client log shows a tunnelled join, the server sees the player from
  127.0.0.1, and the player can play for several minutes.
- [x] AC4 (R3). With a wrong or closed TCP port the client logs the fallback, joins over UDP, and the menu freezes at
  most 2 s.
- [ ] AC5 (R4). A second client without a TCP port joins and plays over UDP at the same time as a tunnelled client.
- [x] AC6 (R5). Killing the tunnel (closing the TCP connection) disconnects only that client; the server keeps running.
- [ ] AC7 (R6). (build and serverSmokeTest pass; smokeTest fails only on sctd-transmog-flush-fix after an SCTD update, unrelated) `./gradlew build assembleMod`, `:dont39-all-in-one:smokeTest` and `:zb-kt:serverSmokeTest` pass;
  the feature reports `ready`.
- [x] AC8 (R7). `workshop.txt` has the section, including the sandbox edit path verified in game.
- [x] AC9. Works against a Steam-mode dedicated server (the default launch), proven by implement step 1 and AC3.
- [ ] AC10. Packet loss comparison (`ZNetStatistics`) from the user's lossy network, or reported as not done.

## Out of Scope

- Steam-networking joins by Steam server ID.
- Real client IPs for tunnelled players, IP bans for them (D4).
- Splitting packets between UDP and TCP, replacing RakNet (approach B).
- Switching transport mid-session, reconnecting a dropped tunnel.
- Version bump and Workshop upload (the user's release step).

## Risks

- Steam-mode routing is native. Measured: the client tries RakNet UDP to `<host>:<server UDPPort>` before Steam
  networking, so the tunnel listens on both ports ([research/steam-mode-spike.md](./research/steam-mode-spike.md)).
  If a future game version reorders the attempts, Steam networking would bypass the tunnel.
- Sandbox edits: a dedicated server writes no `map_sand.bin`, so `<server>_SandboxVars.lua` is read on each start
  ([research/in-game-test.md](./research/in-game-test.md)).
- Native RakNet ban list: if 127.0.0.1 ever gets banned natively, all tunnelled players are blocked.
- The Linux server's Java runtime was not inspected (only Windows installs were available).
