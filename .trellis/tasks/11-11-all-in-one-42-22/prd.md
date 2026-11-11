# Dont39 All-in-One on game 42.22

> Child of [11-11-game-42-22-update](../11-11-game-42-22-update/prd.md).

## Goal

Dont39 All-in-One builds against game 42.22 and every bundled feature works there.

## Background

[Parent research](../11-11-game-42-22-update/research/impact-42.22.md):

- `network-status` does not compile: `PacketTraffic.kt:119` reads `PacketTypes.packetTypes`, which 42.22 removed. The
  game now resolves a type with `PacketTypes.PacketType.getById(short)`. The All-in-One jar bundles the feature, so it
  does not build.
- `appearance-override`: 2 of 13 hook targets changed, `AnimatedModel.setCharacter(IsoGameCharacter)` and
  `FBORenderCorpses$BodyParams.init(IsoMannequin)`.
- `mod-sync`: `IngameState.exit()` no longer clears `Core.lastStand`. Nothing the hook relies on.
- `ime-keyboard-fix`, `missing-anim-folders`, `anim-extends-case`, `tunnel`, `allinone-common`: nothing they touch
  changed. `mod-options-sidebar`, `sctd-render-takeover`: the vanilla Lua they wrap did not change where they use it.

## Requirements

- R1. Network Status names a packet type on 42.22 as it did on 42.21, including the "unknown packet type" text for an
  id the game does not know.
- R2. The two changed hook targets of `appearance-override` are read against the hooks on them. A hook whose
  assumption no longer holds is adapted; one that still holds is recorded as checked.
- R3. Every feature is checked in the game on 42.22, not only the three above: static comparison found nothing for
  the others, and that is not proof.
- R4. `versionMin=42.22.0`; the mod version goes up.

## Acceptance Criteria

- [x] `./gradlew :dont39-all-in-one:build :dont39-all-in-one:assembleMod` and each feature module's `build` pass on
      the 42.22 jar.
- [ ] `smokeTest` passes on 42.22: no hook target mismatch, no weave failure, no feature switched off. Passes with
      the outdated Faster Loading and the updated Shadow Cat Tactics: Dispatch out of the run; with the user's own
      list it waits for those two ([research/in-game.md](research/in-game.md)).
- [x] After a multiplayer session, the log lists received traffic by packet type with real type names.
- [x] An appearance override draws on a player, a mannequin and a corpse as on 42.21. By each render consumer's debug
      line, without an image; the `chunk corpses` consumer did not run.
- [x] `deployWorkshop` output is staged for the user's upload (`~/Zomboid/Workshop/Dont39AllInOne`, 0.14.1).

Result: [research/hook-review.md](research/hook-review.md), [research/in-game.md](research/in-game.md), which also
lists what was not verified in game. The Linux test server was updated to 42.22 in place for the tunnel joins.

## Dependencies

None on the other children. The repo-wide gate `./gradlew build assembleMod` also needs the Server Reborn child.

## Out of Scope

- Target mods that have not been updated for 42.22 themselves (SCTD and others): reported, not worked around.
- New features. Game 42.21.

## Open Questions

- None for the user yet.
