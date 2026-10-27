# Server Reborn: fix ItemStats stale fields and skip client-only items and mod data

## Goal

Stop the false repairs that Server Reborn's `-Dserverreborn.debugFieldDiff` run found on the live server (client log of
2026-10-27). In each case Server Reborn's check works, but the difference is not a lost update:

1. **Vanilla ItemStats stale fields (game bug).** A food's zero stats leave the packet unchanged, so the previous food's
   values land on the next one. Seen on a sugar packet (endurance/fatigue) and on two unopened cans (hunger, and the
   uses that follow from hunger).
2. **Bandage and wound model items.** The game adds a hidden clothing item (`Base.Bandage_*`, `Base.Wound_*`) to the
   local player's inventory to draw a bandage or wound. Only the client makes it. A repair removes it, the game makes
   it again with a new id, and so the inventory is repaired over and over while a body part is bandaged.
3. **Escape from Kentucky mod data the client writes for itself.** On equip, EFK's client writes eight RPM calibration
   keys `AWCWF_*` and a default `MagzineTypeNow`, without a sync.

Out of scope:

- SCTD's invisible bag weight. It is a separate All-in-One task.
- The ItemStats packet with an empty food header (see `design.md`).

## Requirements

- R1. On whichever side `ItemStatsPacket.parse` runs (client and server alike: `processServer` applies too), every field
  that `parse` sets only when its flag is present starts from the value the writer leaves out. A food that a packet
  omits a stat for gets that stat's omitted value, not the previous packet's.
- R2. The digests both sides compare, the snapshots the server sends and the client's reconcile all ignore the
  character model items the game's bandage update makes. These are the bandage model, its `_Blood` variant, and the
  bite, scratch and laceration wound models of each body part and gender. A repair never removes such an item.
- R3. The mod data digest treats these keys as written by each side for itself:
  - the eight keys of EFK's `ExactAutomaticRPM.lua`: `AWCWF_ConfiguredRPM`, `AWCWF_DisplayRPM`, `AWCWF_AppliedRPM`,
    `AWCWF_CalibratedCyclic`, `AWCWF_RPMCalibrationIndex`, `AWCWF_RPMCategory`, `AWCWF_RPMCategoryScale`,
    `AWCWF_RPMGlobalScale`;
  - `MagzineTypeNow`.

  Other `AWCWF_*` keys stay checked: EFK's shared code writes them on the server too.
- R4. Both sides must hash the same fields, so `Protocol.VERSION` goes up. A client and server of different versions
  already refuse each other.
- R5. The README and the Server Reborn version are updated. No hidden fallback: a hook target that is missing turns
  the mod off, the same as the existing hooks.

## Acceptance Criteria

- [x] Build gate passes (`./gradlew` with JDK 25, as the quality guidelines say), tests included.
- [x] In game, on the test server with the field diff on:
  - Moving a sugar packet and unopened canned food after other foods no longer shows a `food{...}` field diff.
  - With a bandaged body part, the player's inventory is no longer repaired over and over. No `Bandage_*` or
    `Wound_*` item shows as "only on the client".
  - Equipping an EFK gun (M7_cat) shows no `AWCWF_*` or `MagzineTypeNow` mod data diff.
- [x] A lost update is still caught and repaired: `-Dserverreborn.debugDropDelta` still leads to repairs.
