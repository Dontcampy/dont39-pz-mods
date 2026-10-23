# Per-packet-type client download stats in Network Status

## Goal

Count the multiplayer client's received bytes per game packet type, to find what dominates download volume.

## Background

- Asked 2026-10-24 after "多人游戏时，单客户端的数据下载量就不小": find out which data is large before deciding what to
  optimize (research for `10-24-mp-sync-loss-recovery`).
- Every game packet the client receives passes `GameClient.addIncoming(short id, ByteBufferReader)` on the UdpEngine
  thread, including chunk parts (`SentChunk`) that `WorldStreamer` handles there; it returns early while
  `GameClient.connection` is null. `PacketTypes.packetTypes` maps the id to its `PacketType`.
- `GameClient.doDisconnect` ends a session; a kick while loading clears `connection` in `GameClient.update` without it.

## Requirements

- Part of the `network-status` feature (client only, same gating).
- Per connection, count received payload bytes and packets per packet type, from the first packet of the connection.
- The hover tooltip's Debug section shows the session's received total, how much of RakNet's received bytes the game
  packets account for, and the top packet types by bytes with share and packet count.
- The full table, sorted by bytes, is written once per session to `console.txt` at the info level when the session
  ends (disconnect, or a new connection replacing it). Not debug: in `-debug` mode the game's default severity is
  General, so debug lines need a `debuglog.cfg` edit.
- No per-packet logging; counting is safe for the network thread writing while the game thread reads.

## Acceptance Criteria

- [x] Build gate passes.
- [x] In a multiplayer session the tooltip lists the top packet types with plausible numbers (chunk parts while moving).
- [x] Leaving the server logs the full table once.

Verified 2026-10-24 on local `-nosteam` servers: a 4-mod server (tooltip screenshot, table at quit) and one with the
live server's 244 mods and insane zombies (8.2 min tour, 23.6 MB, table at quit).

## Out of Scope

- Breaking `ClientCommand` / ModData traffic down by Lua module.
- Server-side or upload stats.
