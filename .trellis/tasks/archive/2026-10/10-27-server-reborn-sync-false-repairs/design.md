# Design

Evidence: the client's `console.txt` field diff lines of 2026-10-27, and javap of the 42.21 classes
`ItemStatsPacket`, `IsoGameCharacter$Bandages`, `BodyPartType`, `Food`.

## 1. ItemStats stale fields (R1)

### Cause

- `ItemStatsPacket.write` sets a food stat's header flag and writes the value only when it differs from its omitted
  value. For most stats the omitted value is 0; for `poisonDetectionLevel` it is -1.
- `parse` assigns those fields only when their flag is set.
- `applyItemStats` sets every one of them on the food unconditionally.
- The game reuses one packet instance per connection and type (`UdpConnection.getPacket`; `ItemStatsPacket` does not
  override `shouldInstantiate`). An omitted stat therefore keeps the previous packet's value.
- `Food.getCurrentUses()` derives uses from hunger (`|hungChange × 100|` while `baseHunger != 0`), so a stale hunger
  also shows as wrong uses.

### Fix

A Zabric `@Inject` at `HEAD` of `ItemStatsPacket.parse`, gated by `ServerReborn`, on both sides. Before vanilla
parses, it sets each flag-guarded field to the value the writer omits:

- set to 0: `cookingTime`, `minutesToCook`, `minutesToBurn`, `hungChange`, `calories`, `proteins`, `lipids`,
  `carbohydrates`, `thirstChange`, `fluReduction`, `painReduction`, `endChange`, `foodSicknessChange`,
  `stressChange`, `fatigueChange`, `unhappyChange`, `boredomChange`, `poisonPower`, `baseHunger`, `fertilizedTime`,
  `wetCooldown`;
- set to -1: `poisonDetectionLevel`.

The booleans are always assigned from the header. The lists are cleared by `parse` itself.

Notes:

- The fields are package-private. Each gets an `accessible field` line in `server-reborn.classtweaker`; the startup
  report already checks the list.
- The new hook lives with the game-side code, next to `ItemDigests` (it is not client sync).
- With the reset in place, the client reads -1 for "no detection level". The digest special case that maps -1 to 0
  (`ItemDigests.food`) goes, and both sides hash the raw byte.

### Out of scope

A food whose header has no flag at all. `parse` then skips the whole food block, but `write` still wrote the name
and weight after the header. The rest of the packet is read misaligned. In 42.21 it cannot happen: `write` always
writes `poisonPower` and sets its flag, so a food header is never empty.

## 2. Bandage and wound model items (R2)

### Cause

`IsoGameCharacter.updateInternal` → `updateBandages` → `Bandages.update` → `addBandageModel` creates the item with
`InventoryItemFactory.CreateItem`, adds it to the inventory and wears it. This runs on the client only. When a repair
removes the item, the next frame makes it again with a new id.

### Fix

One predicate, `ItemDigests.counted(item)`, is false for the full types the bandage update can make. The set is built
once from `BodyPartType.FromIndex(0 until MAX)`:

- `getBandageModel()` and that name + `_Blood`;
- for each `CharacterGender`: `getBiteWoundModel`, `getScratchWoundModel`, `getCutWoundModel`;
- blank names are skipped.

Every walk over a container's items for comparing or repairing uses one function, `ItemDigests.items(container)`,
which returns the counted items:

- `ItemDigests.container`: both sides' digests;
- `Snapshots` (server): the item list sent;
- `Reconcile.covered` (client): so `remove` never takes such an item off;
- `ClientSync`'s field diff calls.

## 3. EFK client-only mod data (R3)

Two more cases in `ItemDigests.KAHLUA.ownPerSide`, each with a short comment on why:

- **The eight RPM keys.** `ExactAutomaticRPM.lua` (client only) writes them on first equip, from the gun's script.
- **`MagzineTypeNow`.** The client records the default magazine on equip. EFK syncs real changes itself
  (`MFSMagazineSyncFix` and `MFSMagazineSyncServer`, with request and ack).

Not the whole `AWCWF_` prefix: shared EFK code writes other `AWCWF_*` keys (for example `AWCWF_PartCyclicBonus`).

## Compatibility

- `Protocol.VERSION` goes from 1 to 2. Mismatched versions already refuse each other at join, as today.
- The Server Reborn mod version goes up by a minor step (0.1.0 → 0.2.0).
