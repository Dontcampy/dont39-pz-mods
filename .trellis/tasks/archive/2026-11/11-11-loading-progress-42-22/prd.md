# Loading Progress on game 42.22

> Child of [11-11-game-42-22-update](../11-11-game-42-22-update/prd.md).

## Goal

Loading Progress shows a correct progress bar and writes a correct load report on game 42.22.

## Background

[Parent research](../11-11-game-42-22-update/research/impact-42.22.md): the mod compiles and all 77 hook targets still
exist with the same descriptors. 5 changed inside:

| Target | Change in 42.22 |
|---|---|
| `GameWindow.enter()` | `Core.tileScale` and the 1x texture packs are gone; only the 2x packs load |
| `GameLoadingState.enter()` | game mode is the `GameMode` enum; `Core.isLastStand` is gone |
| `IngameState.enter()` | `Core.isTutorial()` replaces a string compare |
| `IngameState.exit()` | no longer clears `Core.lastStand` |
| `LuaManager$Exposer.setExposed(Class)` | also records enum classes |

## Requirements

- R1. Each of the 5 targets is read against the hooks on it and against what the mod counts or times there (steps,
  boundaries, expected totals). What no longer matches 42.22 is adapted.
- R2. The bar reaches each boundary in order and ends full, on a launch to the main menu, a load into a save, and a
  join.
- R3. `versionMin=42.22.0`; the mod version goes up if anything in the jar or mod folder changes.

## Acceptance Criteria

- [x] `./gradlew :loading-progress:build :loading-progress:assembleMod` passes on the 42.22 jar.
- [x] On 42.22 the log shows no hook target mismatch or weave failure for Loading Progress.
- [x] Launch, load and join each show a bar that moves forward only and ends full; the report of each has no missing
      or zero-length step that 42.21's report had (back the existing reports up first).
      The bar of the save load was not seen on screen, only its log line and report:
      [research/in-game.md](research/in-game.md).
- [x] `deployWorkshop` output is staged for the user's upload, if the mod changed.

## Dependencies

None on the other children. Faster Loading's check uses this mod's reports, so a wrong report here is found there
too.

## Out of Scope

- New progress steps for things 42.22 added (Challenge mode and others), unless a load is wrong without them.
- Game 42.21.

## Open Questions

- None for the user yet.
