# Update mods for game 42.22

## Goal

The game went from 42.21 (revision `4a0e9546ec`) to 42.22 (revision `3d39148846`) on 2026-11-10. Every mod of this
repository works on 42.22 again, and each Workshop item that needs a new build is staged for upload.

This is the parent task: it holds what the update changed, the task map and the checks across the children. The fixes
are made in the children.

## Background

Evidence and method: [research/impact-42.22.md](research/impact-42.22.md). All of it is static (build, bytecode and Lua
comparison against the 42.21 jar). The in-game checks are in the children and in
[research/integration-42.22.md](research/integration-42.22.md).

| Workshop item | Verdict | Why |
|---|---|---|
| Server Reborn | must update | does not compile (`SyncItemFieldsPacket.containerId` is final now); 10 of 42 hook targets changed, among them the packet read path and two packet writers whose wire format changed |
| Dont39 All-in-One | must update | `network-status` does not compile (`PacketTypes.packetTypes` is gone), so the jar does not build; `appearance-override` has 2 changed hook targets; `mod-sync` has 1, trivial |
| Faster Loading | must update | its revision gate (`Session.REVISION`) turns every fix off on 42.22; everything it hooks, calls and widens is unchanged |
| Loading Progress | review | 5 of 77 hook targets changed; `GameWindow.enter` no longer loads the 1x texture packs |
| Zabric, ZabricKotlin, ZB Skip Online Checks | no change found | nothing they touch changed |

## Decisions

- D1 (2026-11-11). The updated mods support game 42.22 only. No code path for 42.21.
- D2 (2026-11-11). All four affected Workshop items are updated in this round, one child task each.

## Task Map

| Order | Child | Size | Planning |
|---|---|---|---|
| 1 | [All-in-One](../11-11-all-in-one-42-22/prd.md) | small to medium | PRD |
| 2 | [Faster Loading](../11-11-faster-loading-42-22/prd.md) | small | PRD |
| 3 | [Loading Progress](../11-11-loading-progress-42-22/prd.md) | small to medium | PRD |
| 4 | [Server Reborn](../11-11-server-reborn-42-22/prd.md) | large | PRD, then `design.md` and `implement.md` with their own review |

No child needs another's code. The order is by how soon each can ship: the three small ones do not wait for Server
Reborn. Each child is checked by its own module's Gradle tasks until both All-in-One and Server Reborn compile, since
the repo-wide gate fails until then.

## Requirements

- R1. Each child meets its own PRD.
- R2. Each of the four updated mods states `versionMin=42.22.0` in its `mod/42/mod.info`, names 42.22 wherever it
  names the game version it is made for, and has a higher mod version.
- R3. Zabric, ZabricKotlin and ZB Skip Online Checks are checked in the game on 42.22 and are not released again
  unless that check finds a problem: their `versionMin=42.21.0` stays true.
- R4. ZombieBuddy and the features' target mods are checked on 42.22 as part of the in-game checks. A problem in one
  of them is reported, not worked around.
- R5. The Linux test server is updated to 42.22 for the server checks, after a copy of its 42.21
  `projectzomboid.jar` is kept outside the repo.
- R6. Workshop content is staged with `deployWorkshop`; the user uploads it.

## Acceptance Criteria

- [ ] All four children are done and archived.
- [x] `./gradlew build assembleMod` passes on the 42.22 jar.
- [x] `smokeTest` of `dont39-all-in-one`, `loading-progress`, `faster-loading` and `server-reborn`, and
      `:zb-kt:serverSmokeTest`, pass on 42.22.
- [x] A client with all of the repository's mods enabled joins the 42.22 test server and plays without a hook
      mismatch, a weave failure or a mod switched off, on either side.
- [x] `grep versionMin` over the four updated mods' `mod.info` shows `42.22.0` only.
- [x] The staged Workshop folders of the four items are reported to the user with their versions.

Checked 2026-11-12 on the integration branch: [research/integration-42.22.md](research/integration-42.22.md).
Two smoke tests pass only with a changed mod list, for reasons the game update did not cause, and the join found a
42.22 game bug that logs errors on the server; both are described there.

## Out of Scope

- Game 42.21.
- New features, and anything 42.22 added that no mod relied on (Challenge mode replacing Last Stand, the `GameMode`
  enum in Lua).
- `:zabric:test`'s `RelaunchTest.aRealHelperAnswersAndWaitsForTheGame`, which failed once on this machine because
  JUnit could not delete its temp dir. The game update did not cause it.
- Uploading to the Workshop.
