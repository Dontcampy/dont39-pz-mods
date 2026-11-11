# Faster Loading on game 42.22

> Child of [11-11-game-42-22-update](../11-11-game-42-22-update/prd.md).

## Goal

Faster Loading's fixes run on game 42.22. Today its revision gate turns all of them off there, as designed.

## Background

[Parent research](../11-11-game-42-22-update/research/impact-42.22.md): all 4 hook targets, 74 used members and 12
widened members are the same code in 42.21 and 42.22. The comparison does not cover methods the mod never names, so
the game code each fix reproduces still has to be read against 42.22 before the gate moves.

## Requirements

- R1. Each fix that replaces or reproduces game code (`TextureHooks`, `DepthHooks`, `LuaHooks`, `ScriptHooks`) is
  checked against the 42.22 code it stands for, including what that code calls. A fix whose game code changed is
  adapted, or stays off with the reason in the README.
- R2. `Session.REVISION` names 42.22's revision `3d39148846`, and the mismatch message names 42.22.
- R3. `versionMin=42.22.0`; the README names 42.22 and its revision; the mod version goes up.

## Acceptance Criteria

- [ ] `./gradlew :faster-loading:build :faster-loading:assembleMod` passes on the 42.22 jar.
- [ ] On 42.22 the game log has no revision mismatch line from Faster Loading and each fix reports itself on.
- [ ] A load to the main menu and a load into a save finish without an error from the mod; the Loading Progress
      reports of before and after are kept (back the existing reports up first).
- [ ] `deployWorkshop` output is staged for the user's upload.

## Dependencies

None on the other children. The repo-wide gate `./gradlew build assembleMod` passes only once the All-in-One and Server
Reborn children are done; until then this child is checked by its own module's tasks.

## Out of Scope

- New fixes, or speed-ups beyond what 42.21 had.
- Game 42.21.

## Open Questions

- None for the user yet. R1's reading decides whether this stays a one-line gate change.
