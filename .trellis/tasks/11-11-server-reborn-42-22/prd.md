# Server Reborn on game 42.22

> Child of [11-11-game-42-22-update](../11-11-game-42-22-update/prd.md). Complex task: [design.md](design.md) and
> [implement.md](implement.md) come before `task.py start`.

## Goal

Server Reborn's network sync optimization and server tick rate work on game 42.22, client and server, as they did on
42.21: a lost container or item update is found and repaired, nothing is repaired that was not wrong, and the tick
rate option takes effect.

## Background

Evidence: the [parent's comparison](../11-11-game-42-22-update/research/impact-42.22.md), section "Server Reborn"
(10 of 42 hook targets and 13 used methods changed), and this task's reading of each of them against the mod's code,
[research/r2-assumptions-42.22.md](research/r2-assumptions-42.22.md). Its findings:

- F1. The game's container address of a player inventory, or of a bag in one, no longer means the same on both sides:
  it carries the player's online id from server to client and the client's local player index from client to server,
  and is one byte shorter. The mod uses one form for both directions and for its keys. Unfixed, the mod turns itself
  off at the first touch of a player inventory. Not in the parent's list.
- F2. The mod does not compile: `PinnedContainerID.kt:34` assigns `SyncItemFieldsPacket.containerId`, now
  `private final`.
- F3. `SyncItemFields` now carries a map item's annotations, and 42.22's map UI sends them after each edit. The
  mod's digest did not cover them: in 42.21 they were only in the full item save.
- F4. A debug helper (`DeltaDrop`) misreads the two field packets' new player bytes; comments and docs name 42.21.
- Every other changed target and method was read and holds. The tick rate hooks' targets are unchanged.
- Whether a weapon's ammo, condition, blood and sharpness still differ between the sides in normal play cannot be
  told from the bytecode: 42.22 reworked hits and no longer lets a client set ammo on the server.

## Decisions

- D1, D2: the parent's (42.22 only; all four Workshop items updated).
- D3 (user, 2026-11-11). The digest covers a map item's annotations. They go in as a field the in-game field diff run
  may drop, like the other fields whose side is not settled from the bytecode.
- D4 (user, 2026-11-11). The Linux test server's install, shared with the live `myserver`, is updated to 42.22 in
  place: the live server cannot run as it is.
- D5 (user, 2026-11-12). The in-game runs found that a snapshot breaks an open map window
  ([research/in-game.md](research/in-game.md), "Map annotations"). Both fixes are made: R8 and R9.

## Requirements

- R1. The mod compiles against 42.22 with no reflection added for it.
- R2. Each changed hook target and each changed method the mod calls is read against the mod code that depends on it
  (digests, snapshots, reconcile, the pinned container, the tick rate hooks). Every assumption that no longer holds
  (F1, F2, F4) is fixed; the ones that hold are recorded as checked.
- R3. Whatever both sides must agree on that changed (a key's bytes, digest fields) raises `Protocol.VERSION`, so a
  client and a server of different versions refuse each other.
- R4. No hidden fallback: a target that does not match turns the mod off with the reason, as today.
- R5. `versionMin=42.22.0`; the README and the Workshop text name 42.22; the mod version goes up.
- R6. The digest covers a map item's annotations as `SyncItemFields` carries them (F3, D3).
- R7. A digest field that the field diff run on 42.22 shows differing in normal play is dropped from the digest and
  reported with the log lines that show it.
- R8. A snapshot leaves alone the items whose digest already matches the server's: only items that differ, and items
  it adds, get the server's state.
- R9. A map window that is open while the game reloads its map's annotations from a `SyncItemFields` keeps working.

## Acceptance Criteria

- [x] `./gradlew :server-reborn:build :server-reborn:assembleMod` passes on the 42.22 jar.
- [ ] The repo-wide `./gradlew build assembleMod` passes once the All-in-One child is done (the parent's check after
      the merge; not run on this branch).
- [x] `serverSmokeTest` and a client join on 42.22: no hook target mismatch, no weave failure, the mod stays on, on
      both sides.
- [x] On the Linux test server at 42.22, a session with inventory traffic (nested bags, food, weapons, a map item
      that is annotated) under `-Dserverreborn.debugFieldDiff` reports no false repair and no exception from the mod.
      The checked player's online id is not 0 (F1's client half is invisible at 0).
- [x] With `-Dserverreborn.debugDropDelta` on the client, dropped updates of a player inventory and of a bag in one
      are repaired.
- [x] With a map window open, a repair of the map's container leaves the window usable: an annotation added
      afterwards is placed and synced, and the window shows the server's annotations.
- [x] The server tick rate setting takes effect on 42.22.
- [x] `deployWorkshop` output is staged for the user's upload (`~/Zomboid/Workshop/Dont39ServerReborn`, 0.3.0).

## Dependencies

- None on the other children's code.
- The Linux test server at 42.22 (D4). Its 42.21 jar is saved outside the repo already.

## Out of Scope

- New sync features beyond R6, and 42.22 game bugs the mod did not already address, apart from R9.
- Game 42.21.
