# Implementation plan: Server Reborn on game 42.22

Design: [design.md](design.md). Branch `feat/server-reborn-42-22-d64ba2` (this worktree). Only the `server-reborn`
module and this task directory are touched. Each numbered code step is one commit; steps 1-4 need no game and no lock.

## Before `task.py start`

- [x] The map annotation question is answered (PRD D3, design 4).
- [x] The user approved the final planning summary in this session (2026-11-11).

## Status, 2026-11-12

Steps 1-6 and 8 are done (commits `5fe5bab`, `7d57512`, `e586fa3`, `858be8c`; results in
[research/in-game.md](research/in-game.md)); step 7 found no field to drop. Run c was not run: no 0.2.0 build was
staged. Open: the user's decision on `Reconcile` (design, "What changes"), then step 9.

## Checklist

1. Protocol, JDK only, tests first (`network/protocol`):
   - `ContainerWire`: player kinds read as `short` only; encoders for a player inventory and a bag in one;
     `ownerInventory` removed; header and `playerId` comments.
   - `ContainerWireTest`: 2-byte forms, encoder round trip, a 42.21 form refused; `ownerInventory` cases removed.
   - `Protocol`: `VERSION = 3`; the packet type count in `PACKET_ID`'s comment (count `PacketType.entries` on 42.22).
   - `WireKey`'s comment: the server's form.
   - Gate: `:server-reborn:test`.
2. Game code (`network/game`, `network/client`, classtweaker):
   - `server-reborn.classtweaker`: `mutable field` for `SyncItemFieldsPacket.containerId`; its comment.
   - `Containers`: own address in `current` and own lookup in `find` for player-owned containers (design 1);
     `parentThere` without its `BAG` branch; comments of `address`, `find`, `wire`.
   - `DeltaDrop.target`: `ConnectionPlayer`.
   - `Touches.received`: the comment about what the packets need.
   - Gate: `:server-reborn:build :server-reborn:assembleMod`.
3. `ItemDigests` (design 4): the `map` group for a `MapItem` and its `Checked` entry; the header comment. Gate: build.
4. Version and docs: `build.gradle.kts` 0.3.0; `mod/42/mod.info` `versionMin=42.22.0`; `README.md`; `workshop.txt`
   (EN and CN, four lines). Gate: build again; `grep -rn "42\.21" server-reborn` shows nothing.
5. `trellis-check` on the diff (sub-agent), fixes, gate again.
6. In-game checks, all under the lock (below). Results, with the proving log lines quoted, go to
   `research/in-game.md`; no raw consoles in the repo.
7. Field drops, if step 6 shows a `Checked` field differing in normal play (design 7): drop it, rebuild, repeat the
   run that showed it.
8. `git lfs pull`, then `:server-reborn:deployWorkshop`; record the staged folder and jar version.
9. Phase 3: spec update pass, commit (English, no attribution lines, the parent task directory never added), report.
   No push, no PR, no upload, no `task.py archive`.

## Validation

```bash
export JAVA_HOME=~/.jdks/graalvm-ce-25.0.2
./gradlew :server-reborn:test
./gradlew :server-reborn:build :server-reborn:assembleMod
```

The repo-wide `./gradlew build assembleMod` is run once All-in-One compiles on this branch's base; until then it is
reported as not run.

### In the game (step 6)

Read the memory notes first (lock, `-nosteam` local joins, focus loss, Loading Progress reports backup, launcher
rewriting the mod list, Linux server recipe, fake clients, no test logs in `research/`). Take
`~/Zomboid/.dont39-game-test.lock` for every item here and release it between runs. Faster Loading's staged 42.21
build stops its hooks on 42.22 and fails every `smokeTest` until the fixed one is deployed (All-in-One session's
note): run after it, or with it out of `default.txt` and the preload config for the run, restored afterwards.

| # | Run | Pass |
|---|---|---|
| a | `:server-reborn:smokeTest` (client) and `:zb-kt:serverSmokeTest` (server; Server Reborn staged in its mod list, or the harness's `srmin` server start) | `access widener: ok` with one more directive than 0.2.0, `hooks ready`, no `hooks stopped`, no `disabled`, no weave error; server `active on a dedicated server` |
| b | Local `-nosteam` join with the harness (`archive/2026-10/10-27-server-reborn-sync-false-repairs/research/harness`, `srmin`), existing character, driver loop incl. `falseRepairs` | client `active: the server checks ...`; server `checking its sync (Server Reborn 0.3.0 on the client)`; `mismatches=0`, no error from the mod |
| c | Protocol refusal: the staged 0.2.0 client build against the 0.3.0 server, if the 0.2.0 folder is still staged | client `inactive, as the server speaks protocol 3 ...`; else reported as not run |
| d | Linux test server at 42.22, `-Dserverreborn.debugFieldDiff=true` both sides, `-Dserverreborn.debugTouches=true` on the server: nested bags (in the inventory, in a crate, in a bag on the floor), food (cook, eat part), weapons (fire, reload, melee until wear), a map item (annotate, move between containers) | touches name `inventory of player N` with N not 0; no `repaired` line that the field diff does not explain by a dropped update; no exception from the mod |
| e | The same with `-Dserverreborn.debugDropDelta=5` on the client | repairs happen and end `REPAIRED`/`MATCHED`, player inventory and bag keys among them |
| f | Tick rate: sandbox option 20 on the test server | `tick rate on: 20 ticks per second`, `the server ticked 20.00 times per second in its first 10 s` |

Run d needs a player whose online id is not 0: fake clients (`FakeClientManager`) joined before the real client, or a
second client.

## Linux test server

On 42.21, install shared with the live `myserver`. The user decided on 2026-11-11 to update it in place (PRD,
Dependencies). Under the lock, before run d: check its version; if a sibling session has not updated it yet, update
the install to 42.22 and confirm the server's `version=` line. Tests run on the `myserver2` instance with its own
RCON port, as the memory note says; `myserver`'s config and save are not touched.

## Risky files

| File | Risk | Guard |
|---|---|---|
| `Containers.kt` | addresses on both sides; a wrong owner rule makes every player container "not found" or checks the wrong one | runs b, d, e with a non-zero online id; `debugTouches` lines |
| `ContainerWire.kt` | the wire form both sides share | unit tests; `VERSION` 3 |
| `server-reborn.classtweaker` | a bad line leaves the mod idle | run a: `access widener: ok` |
| `ItemDigests.kt` (the map group, field drops) | false repairs | runs d, e under the field diff |

## Rollback

Each step is its own commit; a step that fails its gate is reverted, not patched over. The release is only staged:
nothing reaches players before the user uploads.
