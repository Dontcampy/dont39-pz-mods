# Appearance override core: render-only hide and look-as for worn items

Child of `10-27-sctd-render-transmog` (read its PRD for the why). First of three; the SCTD takeover and the migration
build on this.

## Goal

An All-in-One feature, independent of SCTD, that lets Lua on the authoritative side (server, or the game in single
player) mark a worn item as **hidden** or as **looking like another item type** (with texture choice, base texture,
tint). The item itself is not changed: gameplay, saves and vanilla network packets see the real item; only the
character's drawn model changes, for its owner, every other player, and the UI previews.

## Requirements

- R1. API (Lua, authoritative side): set or clear an item's override. The override is kept in the item's mod data, so
  it is saved with the item and travels with it (bags, ground, corpses, other players).
- R2. A worn item with an override is drawn hidden, or as the given type with the given texture choice, base texture
  and tint; the real item's blood, dirt, holes and patches are drawn on the substitute. An item without one is drawn
  as vanilla draws it.
- R3. Rendering only: clothing defense, body-part coverage, bite checks, vision/hearing, temperature, wetness, sounds,
  saves and vanilla packets (`SyncClothing`, `SyncVisuals`, the join packet) keep seeing the real item.
- R4. Every client sees every player's overrides, including clients that join later and players far away; a change is
  seen without a rejoin, and a taken-off item stops affecting the look at once.
- R5. Paths drawn: the local player (also during SCTD's own render-time swap of the worn list), remote players,
  UI previews built from the player or a `SurvivorDesc`, corpses and mannequins with the items. Not covered: a zombie
  reanimated from a dead player (drawn from visuals the server copies at reanimation).
- R6. Gating as the other Kotlin features: a status line, `disabled` with an error on any failure, and then the game
  draws as vanilla. A bad override (unknown type, not clothing) is refused at the API with an error, not drawn.
- R7. Cost: nothing per frame beyond a lookup when a model or its textures are rebuilt.

## Acceptance

- [ ] Build gate, unit tests for the pure parts (override parsing, set diffing), `smokeTest` with the feature `ready`
      and its hooks transformed.
- [ ] On myserver2 with the harness: an override set from server Lua on a worn item is drawn (debug output of the
      render scope shows the item removed / substituted) on the owner's client; gameplay values (clothing defense,
      weight, fluid) unchanged; survives restart and rejoin; cleared, the item is drawn again; taken off and another
      item of the same type worn, the new one is drawn normally.
- [ ] Remote view checked with a second client, or reported as not covered.
