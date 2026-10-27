# In-game check, 2026-10-27/28

Test instance `myserver2` on the Linux test server (`-nosteam`, game port 48261, own RCON port 48015, fresh save,
`Map=Muldraugh, KY`; mods ZombieBuddy, Zabric, ZabricKotlin (repo builds, equal to the client's staged jars),
Dont39AllInOne (this branch, uncommitted, `build assembleMod` with JDK 25), ShadowCatTacticsDispatch (copied from the
client's Workshop 3689462578; `LingLi_SCTD_Skins.lua` sha256 `ba9931c0…`, the reviewed version), ShadowCatTacticsGuardian
(Workshop 3609052036, for the body forms; the server's own Workshop copy, same 1873 files and size as the client's),
SGTestDriver). Started from `sctd-start.sh` in tmux `pzsctd`. The live `myserver` was not touched (same process
throughout).

The Windows client `-nosteam` with `-Dappearanceoverride.debug=true`, the same mods copied into `~/Zomboid/mods`,
driven by `harness/` (the archived appearance-override harness plus `run-remote.ps1`, `start-viewer.ps1`,
`stop-viewer.ps1` and the driver tests `sctd*`, see `harness/README.md`), at Rosewood prison. A female character, so
the body form is `Base.FemaleBodyT512` (SCTG's `TriggerClothing`; SCTD itself lists none). Lines of each run in
`in-game-2026-10-27/` (`<run>-driver.txt`: the driver's client and server report lines; `<run>-client-lines.txt`:
All-in-One's lines in the client console; `server-lines.txt`).

Item report fields: id, where (worn slot / inventory / bag), weight, condition, fluid, bite/scratch/insulation, the
item's own visual, `SCTD_Transmog` record, `Dont39AllInOne_Appearance` override, `SCTD_TransmogShown`; on the client
also SCTD's `MatchesBaseFilter` (hidden / transmogged), `IsTransmogged`, the hide button's label (SCTD's
`DrawHideButton` drawn into a list stand-in) and `IsPreviewSkipped`.

## Tests and results

| Run | Test | Seen |
|---|---|---|
| server start | 1 status | `sctd-render-takeover: ready (server)` after `appearance-override sync: server part ready (server)`; no disabled/error line |
| st0 | warm-up join (creates the character) | client: `ready (SCTD's code is the reviewed version; …)` at the menu and again at the join's reload, then `ready (client)`; core `active`, `client part ready` |
| st1 | 2 hide/show | server `SCTD_Transmog_HideItem` on the sprayer (2 L water) and `Jacket_Black` → `true`, same item ids; record `{Dont39AllInOne=1,hidden=true}`, override `{hidden=true}` on both sides; weights 5.0/2.0, fluid 2/16, bite 20 scratch 40 ins 0.40 unchanged; client `filterHidden=true`, button `Show`, `previewSkipped=true`; SCTD's `applied` notice to the client. Client debug: `model of admin: Base.KnapsackSprayer hidden, Base.Jacket_Black hidden`. Then `SCTD_Transmog_ShowItem` on the jacket **from the client** (SCTD's `showItem` command → server): record and override cleared on both sides, `shown=nil` (SCTD writes `SCTD_TransmogShown` only while a body form is worn), set without the jacket |
| st2 | 3 transmog | normal (`Jacket_Black` as `Jacket_Leather_Punk`): A worn, B in the inventory, record `{…,srcDisplayName=Leather Jacket - Punk}`, override = B's look, `filterTransmogged=true`; drawn `Jacket_Black as Base.Jacket_Leather_Punk`. Cross-slot (`Shirt_Lumberjack`, `ignoreBodyLocation`): A stays in `base:jacket`, drawn as the shirt (baseTexture 3). Invert: `Jacket_Leather_Punk` worn in `base:jacket` drawn as `Jacket_Black`, A in the inventory and off the worn list on both sides. Invert with `Jacket_Varsity` in a school bag: moved to the main inventory and worn, drawn as A, keeps its own stats (bite 10). Each `Restore` → record and override cleared, `restored` notice, inventory count 14 → 14 (nothing returned) |
| st3 | 5 skin panel | `SCTD_GetWornClothingWithSkins` lists A with the look type's choices (`itemType=Jacket_Leather_Punk choices=4 currentIndex=0`); client `SCTD_ChangeSkin(A, 2)` → client copy `textureChoice=2` at +200 ms and still at +8 s (no flicker back), server override `textureChoice=2`, client set revision 13 with it; `SCTD_ChangeColor(A, 0.2, 0.6, 0.9)` → tint on both sides, revision 14; list then `currentIndex=2`. Plain hat: `ChangeSkin(hat, 1)` and `ChangeColor` go SCTD's own way (the item's visual: client `1,-1,0.9/0.1/0.1`, server `1,1,0.9/0.1/0.1`), no override |
| st4 | 4 auto-hide | body form on: belt, hat, t-shirt, shirt, socks, shoes, trousers get `{Dont39AllInOne=1,autoHidden=true,hidden=true}` + hidden override (same items, same ids); the transmogged jacket and the manually hidden sprayer untouched; body form off: the seven cleared (7 `restored` notices), sprayer still hidden, jacket still transmogged |
| restart, st5 | 6 restart + rejoin | items, records, overrides (look with skin 2 and the tint) kept on both sides; at the join the client gets revision 1 with both entries and logs `model of admin: Base.KnapsackSprayer hidden, Base.Jacket_Black as Base.Jacket_Leather_Punk` |
| st6 | 6 rejoin, no restart | the same |
| st7c | 7 remote view | second client `viewer` (own `-cachedir`, admin via the server console) 3 squares from admin; its console: `set of admin, revision 1: {Base.Jacket_Black=Look(type=Base.Jacket_Leather_Punk, textureChoice=2, …, tint=(0.2,0.6,0.9)), Base.KnapsackSprayer=Hidden}`, `debug: model of admin: Base.KnapsackSprayer hidden, Base.Jacket_Black as Base.Jacket_Leather_Punk`, same for attached items. The raw `getItemVisuals()` of admin outside a render scope still lists both (expected: only drawing is edited) |
| wrong hash, st8 | 8 disabled path | deployed copy's `HideItem_Server` hash changed → server `disabled after an error while checking SCTD's code: HideItem_Server at media/lua/shared/LingLi_SCTD_Skins.lua:2796 is not the reviewed version (found length 4444, hash 939850532, 2047066782)`; `SCTD_Transmog_HideItem_Server` is SCTD's again (`LingLi_SCTD_Skins.lua:2797`), `SCTD_Transmog_Restore` is the R7a wrapper. SCTD's hide of the hat swaps it to `Base.SCTD_Invis_hat` with SCTD's record (shipped behaviour). SCTD's `Restore` on our sprayer and jacket (made before the restart) → `true`, record and override cleared on both sides, sets 2 and 3 drop them, inventory 16 → 16. Correct copy put back → `ready (server)` |

No `disabled`, `ignored a look edit`, `stopped` or error line from All-in-One or the driver in any run but st8's
intended one and st7b below; the server's other errors are the game's own (map properties, fences, mannequin zone).

## Remote view: the first two attempts

- st7: the viewer's fresh `-cachedir` had no `mods/reset-mods-42_00.txt`, so the game emptied its `default.txt` at the
  first launch (`loadMods(0 mods)`) and the driver never loaded; it sat at the main menu. Fixed in the harness (copy the
  marker), viewer killed and restarted.
- st7b: the viewer's menu list had only the driver, so All-in-One came only from the server's list at the join.
  ZombieBuddy then loaded `Dont39AllInOne.jar` but exposed none of its Lua globals (no `Exposing global functions from
  class: com.dont39…` line; `network-status icon: … the Java part of Dont39 All-in-One is not loaded` too), so
  `appearance-override sync: client part idle: its Java side is not loaded` and `sctd-render-takeover: disabled …:
  appearance-override: its Java side is not loaded`: the viewer drew nothing overridden. Not this feature's code (it
  reports the missing Java side as designed); a loader limitation for every Java-backed All-in-One feature on a
  client that does not enable the mod at the main menu. st7c enabled All-in-One in the viewer's menu list, as the
  first client has it.

## Not covered

- Invert with A in a multi-item slot: the game makes only `bandage`, `wound` and `zeddmg` multi-item (no mod on the
  list adds one), and SCTD refuses those as overlays; the belt is a single-item slot. No suitable pair exists.
- The drawn texture and tint after a skin edit: the core's debug line names types only and is logged when that text
  changes; the evidence is the client's set revisions 13/14 (the store the draw reads). No screenshot.
- SCTD's panels themselves (opening the transmog/skin panel, clicking): the driver calls the functions they call; the
  row closure replacement (`Panel_baseRowDraw`) was not exercised.
- Single player, and the client-side `apply`/`restore` commands (server calls only; `showItem` was sent from the client).
- Observed, SCTD's own path, not the takeover: the plain hat's color (`ChangeColor`) was white again on the server after
  the restart.

## Restore

- myserver2: `Server`, `mods`, `zombie_buddy`, `Saves`, `db`, `Lua`, `options.ini` backed up first
  (`backups-2026-10-27-sctd-takeover/state.tgz`, 7429 entries, with `files.sha256` of 7172 files) and restored after
  the server was stopped: file hashes and the name list identical to the backup, `RCONPort=47015` and no `Mods=` line
  again, `sctd-adminpw.txt` and `sctd-start.sh` removed; the `viewer` account went with `db`. `sctd-console.log` stays
  there (the viewer's password in it replaced by `***`).
- Client: `cleanup.ps1` (13 mod copies removed, 0 differences, the multiplayer save and `sgtest_ids.txt` moved to
  `%TEMP%\sgtest\cleanup\20261028-001551`, `%TEMP%\sgsrv` deleted); against the dated backup
  `~/Zomboid/test-backups/2026-10-27-takeover-ingame`: Loading Progress report list, `default.txt`, ZombieBuddy
  `config.json` and `mod_approvals.json`, `options.ini`, `latestSave.ini`, the `mods` folder list and the
  multiplayer saves unchanged. `C:\pz2c` (the viewer's cache, about 2 GB of mod copies) is left for the user to
  delete: the session's safety check blocked its removal.
