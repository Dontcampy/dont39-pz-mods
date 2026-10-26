# Digest tuning on `myserver1`'s mod list (AC6, AC11)

- **Goal**: no false mismatches from Sync Guard's item digest in normal play on the user's modded list, while real
  divergence still shows. Follows `modded-transfer-path.md` (run `func-d7b`: 104 snapshots in 3 minutes, all false).
- **Setup**: local `sgtest` server (copy of `myserver1`, 244 mods plus Sync Guard and the driver), one `-nosteam`
  client, driver at the prison spot taking, putting back, moving and eating items (harness `README.md`). Runs land in
  `%TEMP%\sgtest\runs\<label>\`.
- **Method**: `-Dsyncguard.debugFieldDiff=true` now logs, per differing field group, the differing values (mod data
  by key path, three levels deep) on the client, both at the mismatch and after the snapshot, against the snapshot's
  saved copy; each item after the snapshot is marked `[equal]` / `[differs]` against the server's own item digest.
  The server, with the same switch, logs each snapshot item's live values (`field values:`), so a field that only
  differs from the saved copy (the copy is rebuilt by `load` on the client) can be told apart from a live one.
- **Date**: 2026-10-24.

## Field decisions

Per-run tallies of the differing fields come from the `field diff` lines (`tune1`: 322 `modData.usedDelta`, 234
`food.poisonDetectionLevel`, 137 `modData.RPGInventoryDiscovered`, 91 `customColor`, 16 `food.actualWeight`, 8
`weaponStats.maxDamage`, 15 item-list differences of transfers in flight).

| Field | Seen (run) | Origin, evidence | Decision |
|---|---|---|---|
| mod data `usedDelta` = 0 on worn clothing (client only) | `tune1`, every inventory mismatch, the same 7 worn items each time | Vanilla: `Clothing.getUsedDelta()` writes `usedDelta = 0.0` into the item's mod data when it is absent (`javap zombie.inventory.types.Clothing`, `getUsedDelta` L1461-1464 calls `setUsedDelta(0)`, which `rawset`s the key). Client Lua reads it every update (`MS/.../MS_NoxiousExposure.lua:202`, tooltips); the server never reads it for these items, so the key exists only on the client and a snapshot's `SyncItemModData` (wipe + load) removes it until the next read | Pair left out at the top level only when its value is 0; a value set through `setUsedDelta` (filters, tanks) still counts |
| mod data `RPGInventoryDiscovered` (client only) | `tune1`, items in every world container the driver touched | RPGInventory's loot search marks seen items on the client: `RPGInventory/42/media/lua/shared/RPGInventory/LootSearchRules.lua:54-57` (`L.reveal`), called from `client/RPGInventory/LootSearch.lua:39, 57` | Key left out |
| mod data `RPGInventoryTransferRules.<slot>.appearance` / `.learned` = `{}` (client only) | `tune3`, a trash bag | The client rewrites the record from its cache of the server's answer, with `R.copyAppearance(...)` / `learned` tables where the server's stored record has none (`client/RPGInventory/TransferConfig.lua:97-98`); the server's own changes go through `R.change` + `syncItemModData` (`shared/RPGInventory/TransferRules.lua:160-176`) | Key left out (the client's copy differs from the server's by construction) |
| `customColor` r/g/b (float precision) | `tune1`, `Base.Tshirt_McCoys`: client g 0.41176474 / b 0.37647063, server 0.4117647 / 0.3764706, `[differs]` after every snapshot | `SyncItemFieldsPacket.write` sends `Color.colorToABGR(color)`, `parse` reads `Color.abgrToColor` (8 bits per channel); the server keeps its floats | Hashed as `Color.colorToABGR(getColor())`, what the packet carries |
| `food.poisonDetectionLevel` client 0, server -1 | `tune1`, every food item after its snapshot, `[differs]` | `ItemStatsPacket.write` writes the level only when it is not -1 (offset 588-609, `if_icmpeq -1`); `parse` leaves the packet's field at 0 then (offset 493-510), and `applyItemStats` sets it. Both poison values are written as bytes | Hashed as `(byte)` with -1 read as 0; poison power as `(byte)` |
| `food.actualWeight` client 0.2, server 0.0 | `tune2`, `SapphCooking.WhiteChocolate`; `tune1` mod foods (`MR.*`, `SapphCooking.*`, `KFC.*`) | `InventoryItem.getActualWeightUnmodded()` returns 0 when `getDisplayName()` (the `name` field) equals the full type. The server's live value was 0.0 (`field values:` line) while the client's translated copy gave 0.2; after an `ItemStats` (which copies the server's name, `applyItemStats` `setName`) the client returns 0 too. So the value follows the item's name, which is per side until the first `ItemStats` | Left out of `food`; also `weight` for a food with `ReplaceOnUse` (`Food.getWeight()` returns `getActualWeight()` then). Why the server's name equals the full type for those mod foods was not traced (inferred: no translation on the dedicated server) |
| `weaponStats.maxDamage` | `tune1`, `Base.IcePick` client 0.675 / server 0.75, `Base.HandScytheForged` 0.7536 / 0.9036, `[differs]` after the snapshot | `HandWeapon.getMaxDamage()` scales `maxDamage` by `getSharpnessMultiplier()` when the weapon has sharpness; `SyncHandWeaponFieldsPacket.setData` sends `getMaxDamage()` and the client's `setMaxDamage` stores that scaled value as its base, so the client scales twice | Left out for weapons with sharpness; kept for the others |
| item lists (`only on the server` / `only on the client`) | `tune1` 15, `tune5` one open check | Transfers in flight: the item moves on the server first and the mod's broadcast or vanilla's add/remove lands later | Not a digest field. Frame entries wait out the grace (`Ledger.GRACE_MS`); an open check does not wait, so a container opened during a transfer into it can mismatch once; its snapshot then finds nothing to change (`tune5`: server `mismatches=1`, client `snapshotsApplied=1 repaired=0`) |
| mod data `customName` | `func-dbg*` (earlier) | `InventoryItem.setCustomName(boolean)` writes the item's name in each side's language | Already left out before this round |

The `Checked` fields of `ItemDigests` (condition, head condition, uses, ammo, blood, visual, weight, pages, sharpness,
mod data, cooked/burnt, wet) showed no false mismatch in these runs, so all stay in. Coverage is limited: no combat,
cooking, rain, reading or firing in the driver's loop.

## Product bugs found on the way

- **Open checks never ran on a client that gets the mod from the server's list.** On such a join the game builds the
  Lua environment (`LuaManager$Exposer.exposeAll`, `tune3` client console line 8873) before ZombieBuddy loads
  `Dont39SyncGuard.jar` (line 8915), so the loader's exposure of `Dont39SyncGuard_containerShown` had already run and
  the open-check Lua found no function (driver: `global=nil`). The main entrypoint now adds the function to that
  environment when it is missing (`LuaGlobals.exposeIfMissing`); later environments get it from the loader.
- **The first open check of the loot window's own floor container disabled the client for the session**
  (`tune4`): its `ContainerID` is `Undefined` (no floor square), and `Containers.keyOf` threw. Undefined is now
  treated like a floor container: not checked.
- The driver's walk to a container never moved the player at the prison (fences): `walkAdj` queues a walk that ends at
  once, so the loot window listed only the floor (`notShown=132` in `func-d7b`, `reach=false` in `tune1`). It now
  teleports to a free square next to the container when the walk does not get there.

## Runs

All 180 s unless noted; server `-Dsyncguard.summarySeconds=30` and, except in the final run,
`-Dsyncguard.debugTouches=true -Dsyncguard.debugFieldDiff=true`; client `-Dsyncguard.debugFieldDiff=true
-Dsyncguard.summarySeconds=30`. Client and server counters are the last summary of the run; `tickNsP99` /
`tickNsMax` are per 30-s window (ticks=300), given as the largest window.

| Run | Change under test | Driver | Client: frames / entries / checks / mismatches / snapshots / openChecks / stuck | Server: snapshotsSent / bytesOut |
|---|---|---|---|---|
| `func-d7b` | before this round | 66 cycles, shown 0 | 219 / 436 / 122 / 90 / 90 / 0 / 29 | 98 / 384,137 |
| `tune1` | key-level field diff | 71 cycles, shown 0 | 240 / 479 / 124 / 88 / 87 / 0 / 35 | 88 / 358,968 |
| `tune2` | mod data keys, colour, poison, max damage; driver teleport | 11 cycles, shown 22 | 39 / 79 / 30 / 1 / 1 / 0 / 0 | 1 / 9,743 |
| `tune3` | food actual weight | 14 cycles, shown 27 | 44 / 86 / 29 / 1 / 1 / 0 / 0 | 1 / 3,596 |
| `tune4` | `RPGInventoryTransferRules`; Lua function exposed | 14 cycles, shown 28 | client disabled at its first open check (Undefined id) | 0 / 104 |
| `tune5` | Undefined ids not checked | 17 cycles, shown 34 | 47 / 95 / 37 / 0 / 1 / 43 / 0 | 1 / 3,433 |
| `tune-final600` (600 s) | final build, server without debug switches | 66 cycles (105 taken, 64 put back, 44 moved, 1 eaten), shown 132 | 205 / 403 / 123 / 0 / 2 / 227 / 0 | 2 / 16,818 |

With walking working, the driver does far fewer cycles per minute (`tune2`+ vs `tune1`), so per-run counts are not
comparable across that change; the mismatch rate per check is (`tune1` 88/124, `tune5` 0/37 frame checks).

Final run, server side: `connections=1 stateEntries=61 checks=234 mismatches=2 frames=210 entries=413 gone=0
changed=407 snapshotsSent=2 tooLarge=0 bytesOut=16818`. Both snapshots answered open checks and changed nothing on the
client (`snapshotsApplied=2 repaired=0`, field diff "no item differs" before and after): open checks of containers the
driver had just moved items into (see the item-list row above). No frame entry mismatched after its grace
(`mismatches=0` on the client), no item was left stuck, no notice was shown.

End-of-tick time (`tickNsP99`, power-of-two buckets capped at the window's maximum): in `tune-final600`, without
server debug switches, every 30-s window's p99 stays under 1 ms: 5-16 us before the player arrives, 0.19-0.51 ms
while the driver works. Single ticks reach 5.1 ms in the window the player arrives (the first frames over the whole
inventory) and 2.3 ms once later. With `debugTouches` and `debugFieldDiff` on the server (`tune1`-`tune5`) the
windows' p99 reaches 1-8 ms: those switches log per touch and per snapshot item and are for diagnosis only.

## Unexplained or open

- Open checks have no grace: opening a container while a transfer into it is in flight costs one snapshot that
  changes nothing (2 in 600 s here). Not a false repair, but a request and a snapshot each; a short wait after a
  content change of the shown container would avoid them if they turn out frequent with real players.
- Why the dedicated server's name of some mod foods equals their full type (making `getActualWeightUnmodded()` 0
  there) was not traced; the digest no longer depends on it.
- The driver covers transfers, moving, looting and eating only. Combat, firing and reloading, cooking, rain (wetness),
  reading, crafting and a second client (AC6 asks for two clients and 30 minutes) are not covered by these runs; the
  `Checked` fields that depend on them are still unproven in game.
- `food.actualWeight` before the first `ItemStats` is a real difference between the sides (vanilla shows the client a
  weight the server does not use); it is no longer checked, so a lost `ItemStats` that only changes that value goes
  unseen.
