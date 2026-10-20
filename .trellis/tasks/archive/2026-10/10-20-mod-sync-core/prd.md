# Mod sync core: Lua and resource mods from the server on a version mismatch

## Goal

A player whose Workshop copy of an item differs from the dedicated server's joins anyway, playing with the server's
copy; only the files that differ are downloaded. Parent: [10-20-server-mod-sync](../10-20-server-mod-sync/prd.md)
(decisions D1-D6, D10, D12 there; architecture and contracts in its [design.md](../10-20-server-mod-sync/design.md)).

## Requirements

- R1 (D12). New feature module `mod-sync` in Dont39 All-in-One, with the sandbox option `Dont39AllInOne_ModSync.Port`
  (integer, 0 = off, the default). On a dedicated server with a non-zero port it builds the manifest of the server's
  `WorkshopItems` and serves it and the files over HTTP on that port; a co-op host server and port 0 serve nothing and
  log why.
- R2 (D4, D5). While serving, the server appends the location trailer with its HTTP port (parent design, "Location
  trailer") to every connection-details it writes.
- R3 (D1, D3, D5). A client reads the trailer at the join and builds the address from the host it joined and the
  port. Without a trailer (vanilla server, port 0) or without a host (Steam-ID join), the join is vanilla.
- R4 (D2, D6). With a session, a Workshop item that fails with `VersionMismatch` is marked "from server" and the
  Workshop step continues; every other Workshop error stays vanilla.
- R5 (D2). Before vanilla `CheckMods`, the client fetches the manifest, checks it lists each marked item at the
  timestamp the server sent, and plans each item's tree: files whose hash matches the client's own copy are copied,
  the rest downloaded. A marked item missing from the manifest, or at another timestamp, stops the join with a reason.
- R6 (D10). When anything must be downloaded from a server not remembered, the client asks first: item names, sizes,
  "don't ask again for this server". Declining stops the join; accepting with the choice remembers the server.
- R7. Downloads run off the game thread with progress shown on the Connect screen; every file is verified by size and
  SHA-256; a verified tree is reused on later joins. Any HTTP, I/O or hash failure stops the join with a reason.
- R8. With the trees ready, the folder swap is on (parent design, "Folder swap"), and vanilla `CheckMods` and the rest
  of the join run on the server's copies. Leaving the server or a failed join turns it off.
- R9 (interim until `10-20-mod-sync-java`). A marked item whose plan changes a `.jar` stops the join with a reason
  naming the item. Items that carry ZombieBuddy, Zabric, ZabricKotlin or Dont39 All-in-One also stop the join.
- R10. Manifest paths are normalized; an absolute path, `..`, or a path leaving the tree stops the join.

## Acceptance Criteria

- [ ] R1: server log shows the manifest (item count, file count, size, time) and the HTTP port; port 0 logs "off".
- [ ] R2/R3: a vanilla client joins the modded server as before; a modded client joins a vanilla server as before.
- [ ] R4/R5/R8: with an item updated on the Workshop after the server started, a modded client joins; the session's
      Lua checksum passes; `console.txt` shows the item resolved to the cache tree.
- [ ] R5: a change of one Lua file downloads one file; a second join downloads nothing.
- [ ] R6: first join shows the dialog; "don't ask again" skips it next time; declining stops the join with the reason.
- [ ] R7: an interrupted download or a corrupted file stops the join with a reason; the next join recovers.
- [ ] R8: after leaving, the main menu mod list and a single-player game use the client's own Workshop copy.
- [ ] R9/R10: a changed jar, a refused item, or a bad path stops the join with a reason on the join screen.
- [ ] Unit tests: manifest building and parsing, plan (copy vs download), path checks, trailer encode/decode, store
      of remembered servers.
- [ ] `./gradlew build assembleMod` passes; `smokeTest` passes; in-game check on the Linux test server.

## Out of Scope

Everything the parent lists, plus jars (`10-20-mod-sync-java`) and the Zabric override (`10-20-zabric-launch-override`).
