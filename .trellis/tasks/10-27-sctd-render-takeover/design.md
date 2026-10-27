# Design: SCTD render takeover

Evidence: `../10-27-sctd-render-transmog/research/sctd-integration.md` (SCTD as of Skins 2026-10-19, sha256
`ba9931c0b4c45f88…`, Menu and Srv 2026-10-21, Menu2 2026-10-18; unchanged on 2026-10-27). Line numbers below are
that version's. The appearance API is `appearance-override`'s shared module (archived task
`archive/2026-10/10-27-appearance-override/design.md`).

## Shape

Lua-only All-in-One feature module `sctd-render-takeover` (`pz-bundled-feature`, no Kotlin), in `luaTargeted`:

| File | Loads on | Does |
|---|---|---|
| `shared/Dont39AllInOne_SctdTakeoverRecord.lua` | all | module: our record format, `isOurs`, `isHidden(item)` (our `hidden` or an SCTD placeholder type), the look of an item's visual |
| `server/Dont39AllInOne_SctdTakeover.lua` | server, single player, MP client | gate, install, status line; the authoritative part where `not isClient()`, the UI part where `not isServer()` |

One file under `server/` because that directory loads last everywhere (after every mod's shared and client Lua, MP
clients included), so SCTD's shared, client and server globals all exist when it runs, without load-order entries or
event timing.

## Record

Our items keep SCTD's record key so SCTD's readers see a transmogged/hidden item (`IsTransmogged`, filters, panel
info, `GetSources`/auto-hide exclusions, content filter's own-type checks):

```
modData.SCTD_Transmog = {
  Dont39AllInOne = 1,        -- ours, format 1
  hidden = true | nil,
  autoHidden = true | nil,   -- set by auto-hide; its restore loop clears only these
  srcDisplayName = "…" | nil -- the look's item name, for the panel info line
}
```

No `baseFullType`, `srcFullType`, `baseBodyLocation`, `baseStats`, `origStats`, `inverted`: then SCTD's own code
never swaps or re-stats the item. Its slot hooks, `RepairWornSlots`, `ResnapContainer` and the `createItemNew`
wrapper find no saved slot or stats; SCTD's `Restore` takes its "legacy visual" branch (Skins:2504-2526): clears the
record, returns nothing (no `srcFullType`), `Notify("restored")` (R7a). The drawn look lives only in the appearance
override (`Dont39AllInOne_Appearance`). Cost: the content filter no longer sees the look's type through the record
(`CF.IsProtected` reads `srcFullType`); a protected source is still refused at apply (`IsForbidden`), only a later
protection change is not applied to existing looks.

## Authoritative part (server, single player)

Replaces these globals (SCTD looks every one up by name at call time):

| Global | Replacement |
|---|---|
| `SCTD_Transmog_HideItem_Server(player, item, opts)` | SCTD's eligibility checks (worn, `IsForbidden`, `CF.IsProtected`, not transmogged, not a placeholder type, not a wound/bandage overlay, has a `ClothingItem`); clear `SCTD_TransmogHidden`/`SCTD_TransmogShown`; write our record (`autoHidden = opts.autoHidden`); `Appearance.setHidden`; `Notify("applied", item)`; return `true, item`. Covers the panel, `hideItem` command, auto-hide and SCTD's `MigrateLegacy`. `deferSync` needs nothing: no worn item changes. |
| `SCTD_Transmog_Apply(player, A, B, ignoreBodyLocation, invertStats)` | MP client: the original (it validates and sends `apply`). Authoritative: SCTD's checks (as Skins:2312-2325, 2345-2350), B must have a `ClothingItem`, the look validated with `Dont39AllInOne_checkAppearanceOverride` (bad → `false` and an error line for that item, not a disable). **Normal / cross-slot**: `CF.CanWear(player, B, A, A's slot)`; record on A (`srcDisplayName` = B's name); `setLook(A, look of B)`; `Notify("applied", A)`; return `true, A`. B stays where it is. **Invert**: look of A; B moved into the main inventory if it is in a bag; refused if B's slot would take off a worn item other than A (same slot and not multi-item, or exclusive with it: `CF.Conflicts`); `CF.CanWear(player, B, A, B's slot)`; A taken off (stays in the inventory), B worn in its own slot, `sendClothing` both slots, `syncVisuals`, `OnClothingUpdated`; record on B (`srcDisplayName` = A's name, not `inverted`: the panel then reads "B, current look: A"); `setLook(B, look of A)`; `Notify("applied", B)`; return `true, B`. |
| `SCTD_Transmog_Restore(player, item, opts)` | Worn and ours: clear the record, `Appearance.clear`, `Notify("restored", item)`, return `true, item` (nothing returned to the inventory; `ShowItem` then writes `SCTD_TransmogShown` on it as today). Any other record (SCTD placeholder, type transmog, SCTD invert, legacy): the original. |
| `SCTD_AutoHide_Reconcile(player)` | Re-implemented (the original keys on placeholder types and uses locals). Own weak-keyed busy guard. `shouldHide` as SCTD. Hide: worn items passing a copy of SCTD's local `CanAutoHide` → the global `HideItem_Server(…, {autoHidden = true})`. Otherwise: worn items whose record has `autoHidden` (ours, or an SCTD placeholder) → the global `Restore(…, {deferSync = true})`; one `SCTD_Transmog_BatchSync` only if an SCTD placeholder was restored (it swapped items). SCTD's placeholder refit loop is dropped: placeholders are converted by `10-27-sctd-legacy-migration`. |

`look of X`: from X's visual and `X:getClothingItem()`: `type`, `textureChoice`, `baseTexture`, `tint` (`getTint`,
which honours a custom color), `decal` (`getDecal`), `hue` (`getHue`). An item in an inventory has had its random
values picked (to confirm with `javap`/in game; if not, the implementation picks them on X's visual first, as the next
draw of X would).

Look edits from the skin panel: `OnClientCommand` module `Dont39AllInOne_SctdTakeover`, commands `lookSkin`
`{itemId, textureIndex}` and `lookColor` `{itemId, r, g, b}`. The handler finds the sender's worn item by id, requires
our record and a look override, and calls `setLook` with the new values: `textureIndex` sets `textureChoice` when below
the look type's texture choices count and `baseTexture` when below its base textures count (SCTD's `setHiddenTexture`
rule), neither → ignored; color sets `tint = {r, g, b, 1}`. Single player calls the same function directly.

**Always installed, not gated (R7a)**: a wrapper on `SCTD_Transmog_Restore` outside everything above. It remembers
whether the item has our record and an override; when the inner call returns true and our record is gone, it calls
`Appearance.clear`. Disabled takeover → the inner call is SCTD's own `Restore`, whose legacy branch clears our record;
the override goes with it.

## UI part (MP client, single player)

| Global / field | Replacement |
|---|---|
| `SCTD_Transmog_MatchesBaseFilter`, `SCTD_Transmog_DrawHideButton`, `SCTD_Transmog_ClickHide` | the same with "hidden" = `isHidden(item)` instead of `IsInvisVariant(type)` |
| `SCTD_IsPreviewSkipped(player, item)` | our `hidden` → true; else the original |
| `SCTD_TransmogPanel.createChildren` | wrapper: after the original, replace `self.baseList.doDrawItem` with a copy whose "transmogged" mark excludes `isHidden` (the closure is built inside; `fontHgt` and `panelSelf` recomputed as at Menu:10058, 10063). The closure found must be the one at Menu:10090 (`getFirstLineOfClosure`), else the takeover disables. |
| `SCTD_GetWornClothingWithSkins(player)` | wrapper: the original's list, then for each worn item with our record and a look override, its entry (replaced, or appended when the original skipped it) lists the look type's choices (`SCTD_TextureChoicesFromAsset`), current index (`textureChoice` with a model, else `baseTexture`), tint, no extras or skin pair. Hidden items stay listed as themselves (edits go to the real item, shown again later). |
| `SCTD_ChangeSkin`, `SCTD_ChangeColor` | our record with a look → MP client: write the new look into the local item's override copy (the panel reads it back at once; the server's sync overwrites it) and send `lookSkin`/`lookColor`; single player: the server function; return true. Else the original. |

Not replaced (accepted): the apply-click hint at Menu:10439 ("Mark -> Restore" also for our hidden item); SCTD's 3D
previews draw a transmogged item as itself (they build a `SurvivorDesc`, which the appearance override does not
cover); `_sctdComputeSig` (hide/show changes `IsTransmogged`, so the list still refreshes).

## Gate (R7)

At load, before installing anything:

1. SCTD not enabled (`getModInfoByID`/`isModActive`) → `idle`.
2. `Appearance.problem()` → `disabled` with that problem.
3. For each block below: read the file with `getModFileReader`, find the header line (exactly once), take lines up to
   the first line equal to the header's indent + `end`, join with `\n`, compare length and a 62-bit hash (two
   polynomial hashes mod primes below 2^31, pure Lua; Kahlua has no bit operations or digest) with the reviewed
   values. Globals: `getFilenameOfClosure`/`getFirstLineOfClosure` of the live value must fall inside that block
   (another mod's replacement is not the reviewed text).
4. Any mismatch, missing global or error → `disabled` with the block, file:line and found length/hash; nothing
   installed (the R7a wrapper aside).

Blocks: every replaced function and closure, the copied local `SCTD_AutoHide_CanAutoHide`, and the functions whose
behaviour the replacements rely on: `SCTD_Transmog_HideItem`, `SCTD_Transmog_ShowItem`, `SCTD_TransmogSync.Notify`,
`SCTD_TransmogSync.FlushPlayer`, `SCTD_Transmog_IsTransmogged`, `SCTD_Transmog_GetSavedBodyLocation`,
`SCTD_Transmog_ResnapContainer`, `SCTD_Transmog_RepairWornSlots`, and Srv's `onTransmogCommand`. Every side checks
all blocks (the files are the same everywhere; MP checks them).

The reviewed text of each block is kept in `research/sctd-blocks/` (not shipped) so the next SCTD update can be
diffed against what was reviewed; the `disabled` line gives the found length and hash for updating the table.

Runtime: every replacement runs in `pcall`; an error → error line, all replaced globals put back (the R7a wrapper
stays), status `disabled`, and the failing call returns `false` (SCTD's failure value) instead of falling through to
SCTD's swap halfway.

Status line: `[Dont39AllInOne] sctd-render-takeover: ready (<sides>)`, `idle: …` or an error line with `disabled`.

## Multiplayer flow

Client UI → SCTD's original client branches send `apply`/`restore`/`hideItem`/`showItem` → SCTD's server handler →
our replacements → record + override (one `syncItemModData` carries both, written before the override call) →
`Appearance` publishes the set to every client → `Notify` to the actor (panel reselect; its `stats` snapshot is the
real item's own). Look edits use our own command, because SCTD's `changeSkin`/`changeColor` handler is an anonymous
local that would edit the real item's visual.

## Compatibility

- Takeover disabled later (SCTD update): our items keep their look; SCTD's panel shows them transmogged (hidden ones
  as transmogged, not hidden, since it checks the type), and SCTD's Restore clears them (R7a). Hide/transmog then swap
  as shipped.
- All-in-One removed: the item is drawn as itself; SCTD sees a record it can only clear (R7b).
- SCTD placeholders and type transmogs made before: left to SCTD's own code until the migration child converts them.

## Risks

- Every SCTD update that touches a block disables the takeover until the table is updated (the user's choice). The
  block list is wide on purpose.
- An SCTD feature that rebuilds an item by type (holster switch, body-expression swaps, extra variants) copies mod
  data, so our record and the override follow the new item; a look then applies to the new type.
- `CF.Conflicts` and the move from a bag for invert use the server calls SCTD uses (`sendRemoveItemFromContainer`,
  `sendAddItemToContainer`, `setWornItem` + `sendClothing`); taking A off with `sendClothing(player, loc, nil)` is to
  confirm in game.

## Deviations (implementation)

- **File names follow the spec's naming** (module dir in PascalCase): `shared/Dont39AllInOne_SctdRenderTakeoverShared.lua`
  (record, look, gate), `server/Dont39AllInOne_SctdRenderTakeover.lua`; the client command module is
  `Dont39AllInOne_SctdRenderTakeover`.
- **A third file, `client/Dont39AllInOne_SctdRenderTakeoverCheck.lua`**: `server/` Lua loads only when a game starts
  (`LuaManager.LoadDirBase()` loads shared and client), so at the main menu, where `smokeTest` exits, nothing would log
  a status. It checks the block texts only, not the live globals (SCTD's client files may load after it: files load
  mod by mod in mod-list order), and logs `ready (SCTD's code is the reviewed version; …)`, `idle` or `disabled`. In
  a game the `server/` file runs the full gate and logs `ready (server|client|single player)`.
- **Hash**: base 65536 over `string.byte`, mod 2147483647 and 2147483629: Kahlua strings are Java strings, so
  `string.byte` gives UTF-16 units (SCTD's comments are Chinese). The values come from
  `research/sctd-blocks/BlockHashes.java`, which runs the module's own `cut` and `hash` in the game's Kahlua and writes
  the blocks next to it.
- **The panel's row closure is its own block** (`Panel_baseRowDraw`, also inside `Panel_createChildren`), so the
  check on each new panel has its line range.
- **Invert writes the record before the swap and the override before the events**: `OnClothingUpdated` runs auto-hide,
  which would hide B (a vanilla item with no record yet) while a body form is worn. One `sendClothing`: the packet
  carries the whole worn list whatever slot is passed. A is also taken out of the hands (`removeFromHands` +
  `sendEquip`), as SCTD's own removal does.
- **Busy flag around SCTD's own Restore**: SCTD's flag is local, and its `ShowItem` relied on it so that a placeholder
  shown again is not auto-hidden by the clothing event before it is marked shown. The replacement `Restore` sets this
  feature's flag while it hands an SCTD record to SCTD.
- **Auto-hide stops mid-loop after a failed step**: the failure put SCTD's functions back, which would swap the rest.
- **The R7a wrapper is installed on the authoritative side only**: on a multiplayer client SCTD's `Restore` only sends
  a command and never returns true.
- **Skin panel list**: a transmogged item whose look type has neither skins to choose nor a tintable color is left out,
  as SCTD's own list leaves out such items.
- **Look edits send no `Notify`**: the item's mod data sync carries the new look; the client's panel already shows its
  local copy.
- **Look of an item**: `ItemVisual.getTextureChoice(ClothingItem)`, `getBaseTexture`, `getTint`, `getDecal` and
  `getHue` store a random pick when the value is unset (`javap`), so the look is read through them first.
- **A client's bad look edit** (no item id, an index that is not a whole number from 0, a color outside 0 to 1) is one
  error line and ignored, not a disable; an edit for an item no longer worn or no longer transmogged is ignored.
