# Research: SCTD transmog / hide integration points for a render-only takeover

- **Query**: SCTD entry points, record fields, state readers, transmog details, MP flow, migration data; end with a takeover plan
- **Scope**: internal (installed Workshop copy of SCTD + vanilla Lua)
- **Date**: 2026-10-27
- **Source read**: Workshop 3689462578, `mods/ShadowCatTacticsDispatch/common/media/`. File dates: `LingLi_SCTD_Skins.lua`
  2026-10-19 23:36 (sha256 `ba9931c0b4c45f88…`, same as design.md), `LingLiandLightja_SCTD_Menu.lua` and
  `SCTD_Craft_Server.lua` 2026-10-21, `LingLi_SCTD_Menu_2.lua` 2026-10-18. `mod.info` `versionMin=42.20.0`.

Path abbreviations (all under `…/3689462578/mods/ShadowCatTacticsDispatch/common/media/lua/`):

| Short | File |
|---|---|
| `Skins` | `shared/LingLi_SCTD_Skins.lua` (core: transmog, hide, auto-hide, sync, slot hooks, `SCTD_ClothingAppearance`) |
| `Srv` | `server/SCTD_Craft_Server.lua` (client-command handlers) |
| `Menu` | `client/LingLiandLightja_SCTD_Menu.lua` (`SCTD_TransmogPanel`, skin panel, previews) |
| `Menu2` | `client/LingLi_SCTD_Menu_2.lua` (transmog panel filters / hide button) |
| `3D` | `client/LingLi_SCTD_3DViewer.lua` |
| `CF` / `CFA` | `shared/SCTD_ContentFilter.lua` / `shared/SCTD_ContentFilter_Actions.lua` |
| `Hide` | `client/LingLi_ClothingHide.lua` (older TM-texture hide, `SCTD_Hidden`) |
| `Holster` | `client/LingLi_FHolsterModelSwitch.lua` (and the `M` twin, same line numbers) |
| `ASC-F/M` | `client/AutoSwitchClothing/LingLi_{F,M}AutoSwitchClothing.lua` |
| vanilla | `E:/SteamLibrary/steamapps/common/ProjectZomboid/media/lua/shared/TimedActions/ISClothingExtraAction.lua` |

Important structural fact: SCTD already has a **Lua render-swap** (`SCTD_ClothingAppearance`, Skins:3708-4428). Each
client tick (`OnTick` → `A.Render` → `A.Prepare`) it swaps the player's `WornItems` (local) or `ItemVisuals` (remote)
with a display list of `instanceItem(item:getFullType())` copies, and restores the real list at
`OnPostRender`/`OnPreUIDraw`/`OnTickEvenPaused`/`OnSave` (Skins:4413-4419). Since the type-conversion hide was added,
this swap only handles TM hide (`SCTD_Hidden`), wound/bandage overlays and "clean" (no holes/patches) mode
(Skins:4174-4176). Many SCTD readers call `SCTD_ClothingAppearance.Restore()` first so they see real items.

---

## 1. Entry points

"Global" = called by global name at call time (a replaced `_G` entry is picked up). "Local" = upvalue, not wrappable.

### 1a. Commands and the functions behind them

| Action | Function (def) | Callers (side, how) |
|---|---|---|
| Transmog apply | `SCTD_Transmog_Apply(player, base, src, ignoreBodyLocation, invertStats)` Skins:2311 (global) | Panel `SCTD_TransmogPanel:onApplyClick` Menu:10444-10445 (client, global). On MP client it only sends `SCTD_Transmog`/`apply` {baseId, srcId, ignoreBodyLocation, invertStats} (Skins:2332-2343) and returns `false`. Server: `onTransmogCommand` Srv:1945-1954 (local handler, calls the global). SP: the panel call runs the authoritative branch directly. |
| Transmog restore | `SCTD_Transmog_Restore(player, item, opts)` Skins:2454 (global) | Panel `onRestoreClick` Menu:10464-10467; server `restore` command Srv:1955-1962 (worn first, else inventory by ID); `SCTD_Transmog_ShowItem` Skins:2968 (`pcall(SCTD_Transmog_Restore, …)`, global lookup); `SCTD_AutoHide_Reconcile` Skins:3039 (global); content filter `P.Reconcile` CFA:57-58 (global, checked `and SCTD_Transmog_Restore`). MP client branch sends `restore` {itemId} Skins:2466-2473. |
| Hide (manual) | `SCTD_Transmog_HideItem(player, item)` Skins:2937 → `SCTD_Transmog_HideItem_Server` (global, via `pcall`) Skins:2948 | Panel row button `SCTD_Transmog_ClickHide` Menu2:2357-2373 (global, itself called from `baseList.onMouseDown` closure Menu:10137); server `hideItem` Srv:1966-1971 (worn only). Busy flag set around it (local `SCTD_AutoHide_SetBusy`). |
| Show | `SCTD_Transmog_ShowItem(player, item)` Skins:2956 | `SCTD_Transmog_ClickHide` Menu2:2369; server `showItem` Srv:1972-1977. Calls global `SCTD_Transmog_Restore`, then writes `SCTD_TransmogShown = SCTD_WearsBodyForm(player) or nil` on the returned item (Skins:2969-2971). |
| Hide (authoritative core) | `SCTD_Transmog_HideItem_Server(player, item, opts)` Skins:2796 (global) | `SCTD_Transmog_HideItem` Skins:2948; `SCTD_AutoHide_Reconcile` Skins:3026 (`{autoHidden=true, deferSync=true}`); `SCTD_AutoHide_MigrateLegacy` Skins:3067 (`{deferSync=true}`). All global lookups. Returns `false` on MP client (Skins:2797). |
| Auto-hide reconcile | `SCTD_AutoHide_Reconcile(player)` Skins:3000 (global; no-op on MP client, Skins:3001) | `OnGameStart` handler Skins:3191 (SP/host); `OnClothingUpdated` server branch Skins:3208 and client branch Skins:3224 (SP); `ISWearClothing.complete` wrapper Skins:3401-3409 (installed `OnGameBoot`/`OnGameStart`, calls global); `A.SetAutoHideNonSct` Skins:3962. Uses **locals** `SCTD_Transmog_RefitInvis_Server` and `SCTD_AutoHide_CanAutoHide`. |
| Legacy migration | `SCTD_AutoHide_MigrateLegacy(player)` Skins:3049 (global) | Only the `OnGameStart` handler Skins:3190. `OnGameStart` does not fire on a dedicated server (SCTD's own note Skins:3369), so on a dedicated server it never runs (Inferred from that note + single caller). |
| Refit placeholder | `SCTD_Transmog_RefitInvis_Server(player, item, deferSync)` Skins:2898 (**local**) | Only inside `SCTD_AutoHide_Reconcile` Skins:3012-3018, for every worn `SCTD_Invis_*`. |
| Content-filter restore | `P.Reconcile(player)` CFA:51 (field of `SCTD_ContentFilter`) | `OnClothingUpdated` CFA:111-113; every 30 ticks of `OnTickEvenPaused` on the authoritative side CFA:115-125. For a protected item with a record it calls global `SCTD_Transmog_Restore(player, item)` and uses its 2nd return as the new item (CFA:57-59); then clears `SCTD_Hidden`/`SCTD_TransmogHidden`/`SCTD_Original*` (CFA:61-70). |
| Slot repair | `SCTD_Transmog_RepairWornSlots(player)` Skins:3122 (global) | `OnGameStart` Skins:3186; `OnClothingUpdated` both branches Skins:3206, 3222; `SCTD_Transmog_ResnapPlayer` Skins:3240; server `repairSlots` command Srv:1963-1965. On an MP client it only sends `repairSlots` (throttled 2 s, Skins:3139-3146). |
| Resnap stats | `SCTD_Transmog_ResnapContainer` Skins:3097 (**local**) | `OnGameStart` Skins:3185; `OnClothingUpdated` client branch Skins:3221; `SCTD_Transmog_ResnapPlayer` (global) Skins:3233-3241. Server branch of `OnClothingUpdated` does not resnap (Skins:3204-3210). |
| Skin / color change | `SCTD_ChangeSkin` Skins:695, `SCTD_ChangeColor` Skins:1660 (globals); server: anonymous `OnClientCommand` handler Skins:1694-1704 → locals `onSkinChangeServer` Skins:653, `onColorChangeServer` Skins:1619 | Skin panel in Menu. The server handler is an anonymous function (cannot be `Events.Remove`d). |
| TM hide (older) | `SCTD_HideClothing` / `SCTD_ShowClothing` / `SCTD_ToggleClothingHidden` Hide:328/416/303 (globals) | Skin-panel toggle Menu:9816; auto-switch mutex hide (ASC-F:1445-3112, ASC-M:1410-2948); holster switch Holster:229. Server `hideClothing`/`showClothing` Srv:1138-1200. |
| Variant switch compat | `ISClothingExtraAction:createItemNew` wrapper Skins:3080-3091 | Vanilla `complete` → `createItem` → `createItemNew` (vanilla:57-60, 121). |

### 1b. Event handlers that drive the above (all anonymous unless noted, so not removable; they call globals)

| Event | Where | Calls |
|---|---|---|
| `OnGameStart` | Skins:3176-3194 | `InstallSlotHooks`, `ResnapContainer`(local), `RepairWornSlots`, `MigrateLegacy`, `Reconcile` for `getSpecificPlayer(0..3)` |
| `OnClothingUpdated` | Skins:3202-3229 | server: `RepairWornSlots`, `Reconcile`; client: `TransmogSync.FlushPlayer`, `.ReconcileWorn`, `ResnapContainer`, `RepairWornSlots`, `Reconcile` |
| `OnClothingUpdated` | Skins:3362-3365 | `SCTD_FixRandomTransparentSkinAll` |
| `OnClothingUpdated` | Skins:4374-4386 | `A.Restore`, `A.Invalidate`, server `A.Publish` |
| `OnServerCommand` | Skins:2202 `SCTD_TransmogSync.Receive` (named field); Menu:10575-10586 `SCTD_Transmog_OnServerCommand` (local) | sync / panel reselect |
| `OnContainerUpdate`, `OnTickEvenPaused` | Skins:2203-2204 `SCTD_TransmogSync.Flush` | pending record apply |
| `OnClientCommand` | Srv:1981 `onTransmogCommand` (local) | `apply`/`restore`/`repairSlots`/`hideItem`/`showItem` |

---

## 2. The record and other modData keys

### 2a. `modData.SCTD_Transmog` (one table on the worn item)

| Field | Written by | Read by / purpose |
|---|---|---|
| `baseFullType` | Apply Skins:2359/2395; HideItem_Server Skins:2828 | Restore: type to rebuild Skins:2506, 2530 (absent ⇒ "legacy visual record": just clear + return source, Skins:2504-2526); `GetSavedBodyLocation` fallback Skins:1848-1861; `hiddenRecordOf` Skins:567; `hiddenBaseAsset` Skins:574; `HiddenAttachments` Skins:1874; `RefitInvis` Skins:2901-2911; `A.IsForbidden` Skins:3754-3755; `CF.IsProtected` CF:146-148; Menu list name fallback (no); ASC-F:285-290, ASC-M:281-286, HelmetFlashlight:72-80, StartCharacter:937, StartCharEquip_Server:133,144 (all via `IsInvisVariant(type) and data.hidden`) |
| `srcFullType` | Apply Skins:2360/2396 (absent for hide, Skins:2833) | Restore: return source Skins:2486-2499, 2508-2519, 2589-2602; `CF.IsProtected` CF:148 |
| `baseBodyLocation` (string, e.g. `base:jacket`) | Apply Skins:2361/2397; Hide Skins:2829 | `GetSavedBodyLocation` Skins:1845 → slot hooks, `RefreshSlot`, `RepairWornSlots`, `GetBodyLocation`; Restore target slot Skins:2538-2545 |
| `baseStats` | Apply (A's snapshot) Skins:2411-2412; invert: **B's** snapshot Skins:2362; Hide Skins:2830 | `ApplyStats` in Restore Skins:2570-2572 (no keepCondition ⇒ condition reset to snapshot), `ResnapContainer` (keepCondition) Skins:3106, `createItemNew` wrapper Skins:3085-3086, `A.Prepare` display copies Skins:4250-4253, RefitInvis Skins:2917 |
| `origStats` | invert only Skins:2363 | invert restore Skins:2477 |
| `inverted` (true) | invert only Skins:2364 | Restore branch Skins:2476; panel label Menu:10419 |
| `srcDisplayName` | Apply Skins:2366/2399 | panel info label Menu:10418-10420 |
| `srcCondition`, `srcFavorite` | Apply Skins:2367-2368/2400-2401 | Restore: returned source's condition/favorite (legacy branch ignores favorite, Skins:2512) |
| `baseTextureChoice`, `baseBaseTexture` | Apply Skins:2403-2409 / 2369-2375; Hide Skins:2839-2840; hidden skin edit `setHiddenTexture` Skins:581-597 | Restore Skins:2553-2558; skin panel current index for hidden Skins:1438-1444 |
| `hidden` (true) | Hide Skins:2831 | `hiddenRecordOf` Skins:567; `HiddenAttachments` Skins:1874; RefitInvis Skins:2901; Restore (tint + icon) Skins:2550, 2559; all external readers above |
| `autoHidden` (true/nil) | Hide via Reconcile `opts.autoHidden` Skins:2832 | Reconcile restore loop Skins:3034-3035 (only autoHidden ones are restored when the body form is removed or auto-hide is off) |
| `baseDisplayName` | Hide Skins:2835 | placeholder `setName` Skins:2882, RefitInvis Skins:2924; panel row name Menu:10351-10352. Not applied by Restore. |
| `baseTint` {r,g,b}, `baseCustomColor` | Hide Skins:2842-2844; `setHiddenTint` Skins:599-602 | Restore (hidden only) Skins:2559-2566; skin panel color for hidden Skins:1463-1465 |
| `baseCustomIcon` | Hide Skins:2849-2852 | Restore writes it back to `customInventoryIcon` Skins:2550 |

Not in the record: A's decal, A's tint/color for a type transmog (A's tint is copied onto B at apply by `CopyState`
Skins:2217, and B's current tint is copied back at restore), A's custom name, source B's visuals/modData/contents/name.

### 2b. Other keys

| Key (on) | Writers | Readers / purpose |
|---|---|---|
| `customInventoryIcon` (item) | Hide: placeholder gets A's icon path Skins:2848, 2883 (`SCTD_Transmog_IconPathFor` Skins:2746-2771) | vanilla `getTexture()`; Restore puts back `baseCustomIcon` Skins:2550 |
| `SCTD_TransmogShown` (item) | `ShowItem` Skins:2970; cleared on hide Skins:2877 and by `SetAutoHideNonSct(true)` Skins:3952-3957 | `CanAutoHide` exemption Skins:2992 |
| `SCTD_TransmogHidden` (item) | none today (retired per-frame hide) | `MigrateLegacy` Skins:3056, cleared there Skins:3066, on hide Skins:2876, by CF CFA:64-65 |
| `SCTD_Hidden`, `SCTD_OriginalTextureChoice`, `SCTD_OriginalBaseTexture` (item) | TM hide Hide:382-384, Srv:1153-1155, `reapplyHide` Skins:780-782 | `A.Prepare` drops `SCTD_Hidden` items from the display list Skins:4096, 4128, 4180; `SCTD_IsClothingHidden` Hide:270-299; `FixRandomTransparentSkin` exemption Skins:3281; Holster:128; `CF.MakeVisible` CF:326-345; cleared on hide Skins:2878 and by CF |
| `SCTD_HideClothingWear`, `SCTD_HideBandage`, `SCTD_AutoHideNonSct` (player) | `A.SetWearHidden/SetBandageHidden/SetAutoHideNonSct` Skins:3916-3964 (server/SP), mirrored to clients by `A.Publish`/`A.ApplyLocalState` Skins:3798-3811, 3995-4002 | `A.IsWearHidden/IsBandageHidden/IsAutoHideNonSct` Skins:3766-3786 |
| `SCTD_AutoHideDisabled` (player) | Hide:113-118 | auto-switch TM mutex hide only (unrelated to transmog auto-hide) |

---

## 3. Readers of transmog state and what a render-only record does to them

Assumption for column 3: a hidden or transmogged item is the real base item A (its own type), carrying a record.

| Reader | What it does | With real A + record |
|---|---|---|
| `SCTD_Transmog_IsTransmogged(item)` Skins:1718 | `modData.SCTD_Transmog ~= nil` | Works (true for hidden and transmogged). Blocks re-apply Skins:2317, re-hide Skins:2805, source listing Skins:1808, auto-hide Skins:2987, legacy migration Skins:3057. |
| `SCTD_Transmog_GetData(item)` Skins:1726 | returns record | Works. |
| `SCTD_Transmog_IsInvisVariant(fullType)` Skins:2618 | **string** prefix `Base.SCTD_Invis_` | **Breaks**: hidden A is not detected. It takes a type string, not an item, at all 22 call sites, so wrapping it cannot fix them. Affected: Menu2:2164/2168 filter, Menu2:2342 button text, Menu2:2368 click (would call Hide on a hidden item → rejected by `IsTransmogged`, so "Show" never happens), Menu:10099 row "transmogged" mark (hidden A would be marked transmogged), Menu:10439 label, Menu:10499 sig, Skins:565 `hiddenRecordOf`, Skins:1179 preview skip, Skins:1376 skin list, Skins:3014/3033 Reconcile (refit + **auto-hide restore loop never finds hidden A**). Harmless for ASC-F/M, HelmetFlashlight, StartCharacter, StartCharEquip (they only needed it because the type changed; A's own type now matches directly). |
| `SCTD_Transmog_GetWornItems` Skins:1734 | `A.Restore()`, then worn items with a `ClothingItem` | Works. |
| `SCTD_Transmog_GetSources` Skins:1784 | same/any slot, excludes worn, transmogged, forbidden, overlays, invis types, same type | Works. |
| `GetSavedBodyLocation` Skins:1841, slot hooks `HookSlotMethods` Skins:1885-1919 (installed on every `__classmetatables` entry, Skins:1921-1936), `RefreshSlot` Skins:1926, `GetBodyLocation` Skins:1939 | Lua-only overrides of `getBodyLocation`/`isBodyLocation`/`canBeEquipped`/`getAttachmentsProvided` for record items | Harmless if the record's `baseBodyLocation` equals A's worn slot. If a later swap copies the record to a type with another slot (holster switch, extra variant), slot hooks and `RepairWornSlots` move it back to the recorded slot (same as today). `getAttachmentsProvided` override only for invis types. |
| `RepairWornSlots` Skins:3122 | re-seats record items worn at a slot ≠ saved | No-op for render-only A worn in its own slot. |
| `ResnapContainer` Skins:3097 / `ResnapPlayer` Skins:3233 | `ApplyStats(baseStats, keepCondition)` + `RefreshSlot` on every record item | Would freeze A's stats at the snapshot if the record keeps `baseStats`. Without `baseStats` it only refreshes the slot. Invert mode still needs `baseStats` (PRD problem 1 stays for it). |
| `createItemNew` wrapper Skins:3081 | re-applies `baseStats` after a variant switch | Same as above. Note today a transmogged item exposes **B's** extras in the context menu; with A, A's extras. Vanilla `createItemNew` copies modData and fluids (vanilla:99-107), so the record and fluid follow A. |
| `SCTD_TransmogSync.*` Skins:2085-2207 | see §5 | Works unchanged (keys on item ID + `fullType`, which stays A). |
| `A.IsForbidden` Skins:3751 | protected or forbidden-hide list by own type or `baseFullType` | Works. |
| `CF.IsProtected` CF:139-153 | protected by own type, `baseFullType`, `srcFullType` | Works if the record keeps `srcFullType` (a protected look B is then restored by `P.Reconcile`). |
| `CF.CanWear/CanUnequip` CF:212-254 | checks on real worn list | Not relevant to a render-only change (no wear happens). |
| `A.Prepare` Skins:4062-4289 | per-frame display copies `instanceItem(item:getFullType())` with **empty modData** (comment Skins:1354-1355), `ApplyStats(baseStats)` on them Skins:4250-4253; copies made for every worn item in "clean" mode (`SCTD_HideClothingWear`) and for SCTD clothing / protected items otherwise (Skins:4167-4173, 4214) | **Risk for a Java hook**: during the swap window the worn list holds copies of A without the record, so a hook that reads the record from the rendered item sees nothing. `SCTD_ContentFilter.ResolveDisplayItem(player, copy)` Skins:3732-3737 maps a copy to the real item. Remote players: copies are `ItemVisual`s (Skins:4221, 4238-4240). |
| Holster model switch Holster:113-237, Srv:1458+ | swaps worn holster by **type name** when a weapon is attached; copies modData (Holster:206-208, Srv:~1534) | Today a transmogged holster is type B and is not switched; with A it is switched and the record is copied to the new type (record `baseFullType` then names the old type). |
| Body-expression swaps `SCTD_Lips_Swap` Skins:3615 (blink/lips via `SCTD_IsBodyExprSwap`) | rebuild by type, `CopyState(…, true)` copies modData, `ApplyStats` own snapshot | Record follows the new item. Same type-keyed caveat as holster. Whether body forms can be transmog bases: `GetWornItems` does not exclude them; only `A.IsForbidden` (forbidden list / protected) does (not verified per config). |
| Skin / color: `SCTD_GetWornClothingWithSkins` Skins:1350, `SCTD_ChangeSkin` Skins:695, `SCTD_ChangeColor` Skins:1660, server Skins:653/1619 | hidden placeholder: lists **A's** choices from the record and edits record fields; normal item: edits its own visual | Hidden A: listed as a normal item and edits go to A's own visual — correct for render-only (A's look returns on show). Transmogged A: panel lists and edits **A's** textures, not the displayed B look. To keep "skin panel edits the transmog look", these globals need wrapping and the look must live in the record. The server path is reached via SCTD's `changeSkin`/`changeColor` commands (anonymous handler), so a wrapped client function must send its own command instead. |
| `SCTD_IsPreviewSkipped` Skins:1173 (global) | skips overlays and invis types in 3D character/hair/clothing previews; callers Menu:4150, 4384, 10000, 11804, 3D:841, 3D:924 | Hidden A would show in previews unless wrapped. Transmogged A shows as A in previews (they build a `SurvivorDesc` from real worn items, 3D:919-933) unless the Java hook also covers avatar rendering (Inferred). |
| `SCTD_IsEquipmentSkipped` Skins:1341 | placeholders still shown in equipment UI | Fine. Placeholder name/icon tricks (`setName`, `customInventoryIcon`) are not needed for A. |
| Panel `SCTD_TransmogPanel` Menu:10024-10586 | list (`GetWornItems` + `MatchesBaseFilter`), row draw closure `baseList.doDrawItem` built in `createChildren` (Menu:10090-10124), `updateInfo` Menu:10408, `onApplyClick`/`onRestoreClick` Menu:10428-10484, `_sctdComputeSig` Menu:10488, `update` Menu:10514 | Row mark and hide-button state depend on `IsInvisVariant`. Replaceable: `SCTD_Transmog_MatchesBaseFilter`, `SCTD_Transmog_DrawHideButton`, `SCTD_Transmog_ClickHide` (globals); methods on the class table (`createChildren`, `_sctdComputeSig`, `onApplyClick`). The row closure needs a `createChildren` wrapper that replaces `self.baseList.doDrawItem`. Panel reselect after apply/restore matches `v.item == newItem` (Menu:10449-10456, 10471-10478): returning the same A object keeps the selection. |
| `SCTD_FixRandomTransparentSkin` Skins:3270 | fixes TM texture index on real worn items not `SCTD_Hidden` | Independent. |
| `SCTD_WearsBodyForm` Skins:1197 | worn types or remote `ItemVisual:getItemType()` in `TriggerClothing` | Independent. |

---

## 4. Transmog details

- **Type transmog** (Skins:2393-2450): snapshot A's stats; `instanceItem(srcFullType)`; `CopyState(A, B', skipTextureIndices=true, skipContainerContents=true)` (Skins:2212-2269: tint from A, dirt/blood/holes/patches, color, condition, favorite, **all modData**, wetness, `copyPatchesTo`; no texture indices/decal; no fluid; no name); `ApplyStats(B', baseStats)`; `CF.CanWear`; empty A's container to inventory if B' is not a container; empty source B's container; remove B from its container; move A's contents into B' if both are containers; remove A; write record; `RefreshSlot`; wear at A's slot with sync; `Notify("applied")`. Returns `true, newItem`.
- **Displayed look of B'**: textures are not copied (B' keeps its new instance's visual; an uninitialised `textureChoice` is chosen randomly at render per SCTD's note Skins:3246-3251); tint and color come from A. Later edits via the skin panel go straight to B' (it is a normal worn item: Skins:1383, 605-650, 653-692, 1575-1616), so B's look lives on B' itself and is saved with it.
- **`ignoreBodyLocation`**: only lifts the same-slot filter in `GetSources` (Skins:1815). B' is still worn at A's slot (`baseBodyLoc` = `worn:getLocation(A)`, Skins:2348, 2447). Because B's script slot differs, the Lua slot hooks report the recorded slot, `RefreshSlot` sets `setCanBeEquipped` for containers (Skins:1930-1933), and `RepairWornSlots` re-seats it when the engine put it elsewhere (Skins:3120-3174).
- **`invertStats`** (Skins:2353-2391): no type change; A gets B's stats (`baseStats` = B's snapshot, `origStats` = A's), B is consumed (contents emptied, removed), `inverted = true`. Restore writes `origStats` back, clears the record and returns a new B (Skins:2476-2502). Purely a stat feature, no rendering.
- **Source B**: consumed in both modes. Kept: `srcFullType`, `srcDisplayName`, `srcCondition`, `srcFavorite`. On restore B is a fresh `instanceItem(srcFullType)` with condition and favorite only (its visuals, name, modData, holes are lost; its contents were dumped into the main inventory at apply).
- **Restore of a type transmog** (Skins:2528-2605): `instanceItem(baseFullType)`, `CopyState(B', A', true, true)` (so A' gets B's current tint/color), slot from `baseBodyLocation`, record cleared, `customInventoryIcon` reset only if `hidden`, textures from `baseTextureChoice/baseBaseTexture`, tint from `baseTint` only if `hidden`, `ApplyStats(A', baseStats)` **without keepCondition** (condition becomes the snapshot value from apply/hide time), contents moved or emptied, wear, return source, `Notify("restored")`. Returns `true, restored`.
- **Hide = placeholder** (Skins:2796-2894): type from `SCTD_Transmog_InvisTypeFor` (Skins:2687-2740: signature table `SCTD_InvisVariants.byType`/`cover`/`parts`/`bags`, generated file `shared/SCTD_InvisVariants.lua`; 686 items in `scripts/clothing/SCTD_Invis.txt`, `ClothingItem = SCTD_Invis`, script stats/tags/capacity per signature so engine script-level fields match A). Copies state, applies A's stats, bag contents move into a bag variant, record written, name and icon of A set, `KeepOnDeplete` forced on all invis scripts (Skins:2623-2650).
- **TM / `SCTD_Hidden` (older visual hide)**: sets the item's own texture index to a texture named `TM` from its XML choices (only clothing whose XML has one) and stores the original indices (Hide:328-414, Srv:1138-1200, `reapplyHide` Skins:738-806); the `A.Prepare` swap also drops `SCTD_Hidden` items from the display list each frame (Skins:4177-4181, 4211-4213). Used by the skin-panel hide toggle, auto-switch mutex hiding and holster switch. Separate from transmog hide; the content filter clears it on protected items.
- **Auto-hide** (Skins:2979-3045): authoritative only. `shouldHide = SCTD_WearsBodyForm(player) and A.IsAutoHideNonSct(player)` (body form = type in `TransparentSkin.TriggerClothing`, Skins:1186-1228; switch default on, Skins:3781-3786). Hide candidates (`CanAutoHide`, local): not invis, not wound/bandage overlay, **vanilla-only script** (`SCTD_IsVanillaClothingType`, all script bodies from `pz-vanilla`, Skins:1316-1337), not transmogged, has a `ClothingItem`, not forbidden, not `SCTD_TransmogShown`. Otherwise worn invis items with `autoHidden` are restored. Each step uses `deferSync`, then one `SCTD_Transmog_BatchSync` (Skins:2785-2790). Re-entry guarded by a local weak busy table (Skins:2775-2782).

---

## 5. Multiplayer flow (today)

1. Client UI → `SCTD_Transmog_Apply/Restore/HideItem/ShowItem` → `sendClientCommand("SCTD_Transmog", apply|restore|hideItem|showItem, {ids…})`, returns `false` (Skins:2332-2343, 2466-2473, 2939-2946, 2958-2965).
2. Server `onTransmogCommand` resolves items by ID in the sender's worn list / inventory (Srv:1903-1938, 1940-1979) and calls the same global.
3. Authoritative change: `sendRemoveItemFromContainer` for removed items, `sendAddItemToContainer` (full item incl. modData) then `setWornItem` + `sendClothing` (worn summary), `syncVisuals` (Skins:2276-2306). The record is written before the add so the inventory packet carries it (Skins:2443-2446).
4. `SCTD_TransmogSync.Notify(player, "applied"|"restored", item)` sends to **the acting player only** `{playerId, itemId, fullType, revision, record|false, stats}` (Skins:2087-2096). `stats` = server snapshot of the new item.
5. Client `Receive` (Skins:2165-2186): validates shape, drops older revisions per item, queues pending, calls `FlushPlayer`.
6. `FlushPlayer` (Skins:2133-2163): `ReconcileWorn` (replaces a worn reference with the inventory instance of the same ID and type, because a worn packet arriving first creates a container-less stand-in, Skins:2111-2131), then for each pending entry found in the inventory with matching `fullType`: set record (`false` ⇒ nil), `ApplyStats(stats)`, `RefreshSlot`, `ReconcileWorn`, `resetModelNextFrame`, mark panel dirty and reselect. Entries not found expire after 30 s. Retried on `OnContainerUpdate` and `OnTickEvenPaused` (Skins:2188-2204).
7. Menu's own `OnServerCommand` sets the panel's `_sctdSelectId` (Menu:10575-10586).

What the client needs from the server: the record (arrives in the item packet anyway), and the instance stats, which the item packet does not carry (SCTD's notes Skins:1964-1965, 2083-2084, 3196-3201). Other clients: SCTD states remote players' `getWornItems()` is usually not synced, only `ItemVisuals` (Skins:1217-1219), and the remote branch of `A.Prepare` works from `ItemVisual:getItemType()` with no access to `baseType` (Skins:3758-3759). So remote clients today see a hide/transmog only because the item **type** in the visual changed (Inferred from those notes). Player-level toggles (clean/bandage/autoHide) are broadcast by `SCTD_ClothingAppearance` `state` commands (Skins:3788-3811, 4387-4400).

---

## 6. Migration data

### Hidden placeholder (`Base.SCTD_Invis_*`, `data.hidden`)

| Property | Source for rebuilding A | Notes |
|---|---|---|
| type | `data.baseFullType` | |
| slot | `data.baseBodyLocation` | |
| instance stats | `data.baseStats` | snapshot at hide time; SCTD Restore applies it including `Condition` (Skins:2570-2572, 2045-2081) |
| condition | placeholder's own condition (`CopyState`) **then overwritten** by `baseStats.Condition` in SCTD Restore | Inferred consequence: damage taken while hidden is undone by SCTD's restore |
| textures | `data.baseTextureChoice`, `data.baseBaseTexture` (kept current by hidden skin edits) | |
| tint / custom color | `data.baseTint`, `data.baseCustomColor` | applied via local `applyAuthoritativeTint` (Skins:1557-1572) |
| decal | not recorded | lost (Inferred: new instance's default) |
| name | `data.baseDisplayName` (A's display name at hide) | SCTD Restore does not apply it; a custom (renamed) name is lost there |
| icon | `data.baseCustomIcon` | placeholder's own `customInventoryIcon` must not be carried over |
| dirt/blood/holes/patches/wetness/color/favorite | placeholder item/visual (`CopyState`) | |
| contents | bag variant: its container (`takeItemsFrom`); non-bag A: already dumped to inventory at hide | |
| fluid | lost (placeholder has no `FluidContainer`) | per PRD |
| other modData | placeholder modData (copied from A) | minus `SCTD_Transmog`, `customInventoryIcon` |
| item ID | new ID on rebuild | anything keyed on ID (e.g. ASC `mutexHiddenItems`) loses its key |

### Type-transmogged item (`data.baseFullType`, `data.srcFullType`, type = B)

| Property | Source |
|---|---|
| A type / slot / stats / A textures | `baseFullType`, `baseBodyLocation`, `baseStats`, `baseTextureChoice/baseBaseTexture` |
| A tint, color, decal, name | not recorded (A gets B's current tint/color in SCTD Restore) |
| condition, holes, blood, dirt, patches, wetness, favorite, modData | the B' item |
| displayed look of B (for the render override) | B' visual: `getTextureChoice()`, `getBaseTexture()`, `getTint(clothingItem)`, `getDecal(clothingItem)`, item color / `isCustomColor()` |
| source B to keep consumed or return | `srcFullType`, `srcDisplayName`, `srcCondition`, `srcFavorite` |
| inverted records | type never changed; nothing to migrate for rendering |

### Reusing SCTD's own Restore

`SCTD_Transmog_Restore(player, item, {deferSync = true})` (Skins:2454) does the rebuild, but: authoritative side only
(returns `false` after sending a command on an MP client); only for items currently worn (`worn:contains`, Skins:2458-2459);
calls `SCTD_ClothingAppearance.Restore()` first; **returns source B to the inventory** for a type transmog; resets
condition to the snapshot; sends `Notify("restored")`. After it, a migration would write the new record on the returned
item and send `SCTD_TransmogSync.Notify(player, "applied", restored)` (global; a later revision wins, Skins:2179). Placeholders
not currently worn (in bags, on the ground) are not reachable through SCTD's Restore. SCTD's `MigrateLegacy` shows the
batch pattern (busy flag, `deferSync`, one `BatchSync`; Skins:3049-3072), but its busy flag is local.

---

## Takeover plan

### Globals to replace (authoritative logic) — all looked up by name at call time

| Global | Replacement behaviour |
|---|---|
| `SCTD_Transmog_HideItem_Server` | Keep SCTD's eligibility checks (forbidden, protected, transmogged, overlay, has `ClothingItem`), write the render-only record on A, `Notify("applied", A)`, return `true, A`. Covers `HideItem`, auto-hide and legacy migration. |
| `SCTD_Transmog_Apply` | MP client: keep SCTD's command send (call the original). Authoritative, non-invert: validate as SCTD does, consume B (or not; product decision), write record with look fields from B, `Notify`, return `true, A`. `invertStats`: call the original. |
| `SCTD_Transmog_Restore` | Render-only record: clear it, return source if consumed, `Notify("restored", A)`, return `true, A` (keeps ShowItem's `SCTD_TransmogShown` write, CF's `replacement`, panel reselect). Placeholder / old type-transmog / inverted: call the original (or migrate). |
| `SCTD_AutoHide_Reconcile` | Re-implement: hide via the replaced `HideItem_Server` with `autoHidden`, restore record items with `hidden and autoHidden`; the original's refit/restore loops key on `IsInvisVariant` and would never find hidden A. Its busy flag is local, so the replacement needs its own guard (it is re-entered from `OnClothingUpdated`). |
| `SCTD_IsPreviewSkipped` | also skip items whose record is `hidden`. |
| `SCTD_GetWornClothingWithSkins`, `SCTD_ChangeSkin`, `SCTD_ChangeColor` | only if the skin panel must edit the transmog look: list B's choices from the record and write look fields; send an own command (SCTD's server handler is anonymous and would edit A's visual). |
| `SCTD_Transmog_MatchesBaseFilter`, `SCTD_Transmog_DrawHideButton`, `SCTD_Transmog_ClickHide` | "hidden" = record `hidden`, not `IsInvisVariant(type)`. |
| `SCTD_TransmogPanel.createChildren` (wrap, then replace `self.baseList.doDrawItem`), optionally `_sctdComputeSig`, `onApplyClick` | row "transmogged" mark must exclude hidden records. |

Not needed to replace: `IsTransmogged`, `GetData`, `GetWornItems`, `GetSources`, `ShowItem`, `HideItem`, `TransmogSync.*`,
`RepairWornSlots`, `ResnapPlayer`, `ClothingAppearance.*` (but see risks).

### Record format compatible with SCTD's readers (Inferred from §2/§3)

```
modData.SCTD_Transmog = {
  baseFullType = <A's own fullType>,      -- A.IsForbidden, CF.IsProtected, GetSavedBodyLocation fallback
  baseBodyLocation = <A's worn slot id>,  -- slot hooks stay no-ops
  srcFullType = <B> | nil,                -- CF.IsProtected; nil for hide
  srcDisplayName, srcCondition, srcFavorite,  -- panel info / source return (transmog only)
  hidden = true | nil, autoHidden = true | nil,
  baseDisplayName = <A's display name>,   -- panel row name
  -- no baseStats/origStats (else Resnap/createItemNew/Prepare re-apply a frozen snapshot); no inverted
  -- look of B for the renderer: textureChoice, baseTexture, tint {r,g,b}, customColor, decal
  -- a marker so the takeover can tell its own records from SCTD's placeholder / type-transmog records
}
```

Alternative found in SCTD: a record **without** `baseFullType` makes SCTD's own `Restore` take its "legacy visual"
branch (Skins:2504-2526): clear record, return `srcFullType` with `srcCondition`, `Notify("restored")`, return `true, item`
— i.e. render-only restore semantics already exist there. Cost: loses `baseFullType` for `A.IsForbidden`/`CF.IsProtected`
(they also check the item's own type, which is A) and does not return `srcFavorite`.

### Risks

- **SCTD updates often** (Skins 10-19, Menu 10-21, Menu2 10-18 this month; the earlier `sctd-transmog-flush-fix` died when
  `SCTD_TransmogSync.Flush` changed, commit `99e1b67`). Every replaced global is a full re-implementation that drifts; the
  panel closure replacement depends on `createChildren` internals.
- `IsInvisVariant` takes a type string at 22 sites; only sites inside replaceable globals/methods can be fixed. New SCTD
  code that checks hidden-ness by type will silently treat hidden A as visible.
- `SCTD_ClothingAppearance` render swap puts record-less `instanceItem(A)` copies (local) or new `ItemVisual`s (remote) into
  the worn list during the render window; a Java hook keyed on the rendered item's modData misses them unless it resolves
  copies (`SCTD_ContentFilter.ResolveDisplayItem`) or reads from the real list (`A.GetRealWorn`).
- Remote players: per SCTD's own comments, other clients get only `ItemVisuals`; a modData record does not reach them, so the
  override needs its own broadcast (Inferred).
- Type-keyed SCTD features now see A: holster model switch and body-expression swaps rebuild A by type and copy the record
  (stale `baseFullType`); `ISClothingExtraAction` offers A's extras instead of B's.
- Local helpers cannot be reused by a replacement (`CopyState`, `ApplyStats`, `SnapshotStats`, `RemoveWorn`, `WearNoSync`,
  `RefitInvis_Server`, `CanAutoHide`, `applyAuthoritativeTint`, busy flags): they must be copied or reached through an
  original global that uses them.
- Migration through SCTD's Restore: worn items only, returns source B (a type transmog must re-consume it), resets condition
  to the snapshot, new item IDs, decal and custom names lost, fluid already lost; legacy `MigrateLegacy` never runs on a
  dedicated server.
- On a dedicated server `OnClothingUpdated` runs `RepairWornSlots` + `Reconcile` only; any replacement that relies on
  `OnGameStart` has the same dedicated-server gap.

## Caveats / Not Found

- Vanilla B42 behaviour of `instanceItem` visual initialisation and `ItemVisual`-only sync for remote players is taken from
  SCTD's comments, not verified in bytecode here.
- Whether SCTD body forms can be selected as transmog bases depends on the `ClothingHide.ForbiddenHide_*` config and content
  filter state; not checked per config.
- Server holster handler (Srv:1458-1560) was only scanned for `instanceItem`/`copyModData`/`setWornItem`.
- No `.trellis/spec` file covers SCTD beyond `.trellis/spec/mod/directory-structure.md:146-150` (rules for a Lua-only
  feature that fixes another mod's Lua: source-text gate, not in `loadModAfter`, `luaTargeted`).
