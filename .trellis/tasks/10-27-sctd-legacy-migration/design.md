# Design: SCTD legacy migration

Evidence: `../10-27-sctd-render-transmog/research/sctd-integration.md` §2 and §6 (what SCTD's records hold), SCTD's
`Restore` (Skins:2454-2606), `CopyState` (Skins:2212-2269), `ApplyStats`/`SnapshotStats` (Skins:1977-2081), and the
takeover as built (`archive/2026-10/10-27-sctd-render-takeover/`, module `sctd-render-takeover/`).

## Shape

Part of the `sctd-render-takeover` module, not a module of its own: it may run only while the takeover is active
(R7), uses the takeover's record, look and gate, and its `Apply` for the invert case.

| File | Does |
|---|---|
| `shared/Dont39AllInOne_SctdRenderTakeoverMigration.lua` | module: find old-style items in a player's inventory, convert one item, convert a player |
| `server/Dont39AllInOne_SctdRenderTakeover.lua` | after a successful authoritative install: register the migration's events |

Authoritative side only (dedicated server, single player). Its own `stopped` flag: an error stops the migration for
the session, the takeover keeps running (R6).

## What is converted

Every item in the player's inventory, recursively through bag containers (worn, held or carried), collected first and
converted after the walk. An item qualifies when its `SCTD_Transmog` record is a table, not ours, with a non-empty
`baseFullType`:

| Kind | Test | Becomes |
|---|---|---|
| Hidden placeholder | `IsInvisVariant(type)` and `hidden` | new A (`baseFullType`), our `hidden` (+ `autoHidden`) |
| Type transmog | type ≠ `baseFullType`, `srcFullType` set | new A, look = the item's own visual (it is of type B), a fresh B in the inventory |
| Invert | `inverted == true` (type is A) | the same A, record cleared; a fresh B; when A is worn, the takeover's invert (B worn, drawn as A) |

Anything else with an SCTD record (the "legacy visual" record without `baseFullType`, an inverted or transmog record
missing its source) is left for SCTD's own Restore, which our `Restore` hands it to.

## Converting one item

**Stats: the new item keeps its own script stats; SCTD's `baseStats`/`origStats` snapshots are not applied.** This
changes PRD R2/R4 ("the record's stats", "with `origStats`"). Instance stats are not saved: a real item's stats are
its script values on the server after every load (the parent's first problem). Applying a snapshot would put client
and server apart again. A snapshot taken from a vanilla item equals its script values anyway, condition aside, and
condition comes from the item itself.

**Rebuild (placeholder, type transmog)**, before anything leaves the inventory:

1. `instanceItem(baseFullType)`; nil (the type's mod is gone) → one error line for that item, left as it is.
2. Copy state from the old item with a copy of SCTD's `CopyState(old, new, skipTextureIndices = true,
   skipContainerContents = true)`: tint, dirt, blood, holes, patches, color, condition, favorite, mod data, wetness.
3. Mod data: drop `SCTD_Transmog`, `SCTD_TransmogHidden`, `SCTD_TransmogShown`; `customInventoryIcon` = the record's
   `baseCustomIcon` for a placeholder (the placeholder borrowed A's icon), else left as copied.
4. Textures from `baseTextureChoice`/`baseBaseTexture`. A placeholder's `baseTint`: with `baseCustomColor`, the copy
   of SCTD's `applyAuthoritativeTint`; else `setTint`. A transmog keeps the tint `CopyState` brought (SCTD's restore
   does the same).
5. Name: `baseDisplayName` only when it differs from the new item's own display name, i.e. A had a custom name. The
   placeholder's own name is the server's language for A's name and is not kept (parent's finding).
6. Contents: into the new item's container when both are containers; otherwise SCTD's global
   `SCTD_Transmog_EmptyContainer` moves them to the main inventory. Fluid: a type transmog's B' fluid goes to A when
   both have a fluid container (engine copy, the API to confirm with `javap`); a placeholder never had one.
7. Our record and the override written into the new item's mod data before it is sent: placeholder
   `{hidden = true}`; transmog the look read from the old item (`lookOf`, type B), checked with
   `Dont39AllInOne_checkAppearanceOverride`. An invalid look → error line, item left.

**Swap**: the new item is added to the old item's container (`sendAddItemToContainer`, the packet carries the record
and the override), then the old one is removed. A worn old item: taken off the same way the takeover's invert takes A
off (incl. a multi-item slot), the new item worn at the record's `baseBodyLocation` (resolved as SCTD's Restore does,
else the new item's own slot). Add before remove: an error between the two leaves a duplicate, never a lost item.

**Source**: a type transmog and an invert get a fresh `instanceItem(srcFullType)` with `srcCondition` and
`srcFavorite` in the main inventory, as SCTD's own Restore returns it (its look, name and contents were lost when SCTD
consumed it).

**Invert**: A is the real item already; only its record is cleared. Its in-memory stats are B's only in a session in
which SCTD applied them; the server restarted to load this version, so they are A's script values there, and
`SCTD_TransmogSync.Notify(player, "restored", A)` gives the owner's client the server's values (SCTD's client
`ResnapContainer` had put B's stats there at its game start). Then, when A is worn, the takeover's
`SCTD_Transmog_Apply(player, A, B, true, true)`: B worn in its own slot drawn as A, A to the inventory. If Apply
refuses (forbidden, B's slot would take off another worn item) A stays worn as itself and B stays in the inventory;
counted, not an error. A not worn: B gets our record and the look of A, so wearing B later draws A.

## When

- Server: a once-a-second `OnTick` check (as the appearance core's) over online players not yet scanned in this
  session (weak-keyed by player object: a rejoin is a new object, scanned again, and finds nothing the second time).
  The inventory is loaded before the player shows in the online list (superseded design's bytecode notes).
- Single player: `OnGameStart` for the local players, `OnCreatePlayer` for a later one.
- Both: `OnClothingUpdated(player)` scans that player's worn items, for an old-style item worn after the scan (picked
  up from the ground, a corpse, a container).
- Items outside player inventories are converted when they reach one this way (R1).

Per player: one walk, the conversions, then, if worn items changed, one `resetModelNextFrame`, `syncVisuals`,
`OnClothingUpdated` (it runs the takeover's auto-hide on the new items) and `Appearance.publish`. The takeover's busy
flag is held during the conversions so its auto-hide does not run half-way. One log line per player with something
converted: `converted <user>: n hidden, n transmogs, n inverts (n left as themselves), n left (see errors)`.

No `Notify("applied")` for rebuilt items: the add packet carries the record and the override, and SCTD's panel
refreshes on its own signature (worn item ids change).

## Gate

The takeover's block table grows by the SCTD code this part copies or relies on: `CopyState`,
`applyAuthoritativeTint`, `SCTD_Transmog_EmptyContainer`, `SCTD_Transmog_IsInvisVariant`, and SCTD's swap helpers
whose item moves the swap mirrors (`SCTD_Transmog_RemoveWorn`, `SCTD_Transmog_WearNoSync`). The record's writers
(`HideItem_Server`, `Apply`) and `Restore` are already in it. A mismatch disables the takeover, and with it the
migration.

## Errors (R6)

- Per item data problems (type not found, invalid look): an error line naming the player, the item type and id; the
  item stays as SCTD made it and still works through SCTD's own Restore.
- Anything thrown: an error line, the migration stops for the session (takeover unaffected); items already converted
  stay converted, the rest stay as they are.

## Compatibility

- One way (R8): nothing makes placeholders again. `workshop.txt` asks server owners to back up the save before the
  first start with this version.
- Takeover disabled (SCTD changed): nothing is converted; SCTD keeps working on its own items.
- Offline players: converted on their next join (R9). Items on the ground, in world containers and vehicles stay until
  a player carries them.
- Item ids of rebuilt items are new (SCTD's own restore does the same); anything keyed by the old id (SCTD's
  auto-switch `mutexHiddenItems`) loses its entry.

## Risks

- Rebuilding needs SCTD's record to be complete; a record from an older SCTD (no `baseBodyLocation`) falls back to the
  new item's script slot, as SCTD's own `GetSavedBodyLocation` does.
- Duplicate on an error between add and remove (chosen over loss).
- A fresh source B has script stats and no look; SCTD's restore gives the same.
