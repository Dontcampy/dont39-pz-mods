# In-game check, 2026-10-28

Only the lines that prove each result are quoted. The raw consoles and driver output stay outside the repo: the client
runs in `%TEMP%\sgtest\runs\pr*`, and the server console in `myserver2_Data/pr34-console.log` on the test server.

## Setup

- **Server:** the test instance `myserver2` on the Linux test server: `-nosteam`, game port 48261, RCON 48015, Muldraugh
  map. Started from `migration-start.sh` in tmux `pzmig`. Two fresh saves: one for steps 1, 3 and 4, a second for step
  2. Mods: ZombieBuddy, Zabric, ZabricKotlin, Dont39AllInOne, ShadowCatTacticsDispatch, ShadowCatTacticsGuardian
  (SCTD and SCTG copied into `myserver2_Data/mods`, because a `-nosteam` server does not load its Workshop folder),
  SGTestDriver.
- **Live server:** the live `myserver` (pid 1812831) was not touched.
- **Build:** this working tree, `build assembleMod` with JDK 25. Every takeover, appearance-override and common Lua file
  in the build equals its source. Jars: `Zabric.jar` `c03e05c3…` (Zabric 0.7.3 with this task's changes; equal to the
  client's Workshop staging, which the client's ZombieBuddy preloads), `ZabricKotlin.jar` `1f134bcf…`,
  `Dont39AllInOne.jar` `933280c9…`.
- **Same files on all sides:** the whole mod folders hash equal on the client (`~/Zomboid/mods`), the viewer
  (`C:\pz2c\mods`) and the server. Dont39AllInOne `975241c0…`; Zabric, ZabricKotlin, SGTestDriver, SCTD, SCTG and
  ZombieBuddy also equal.
- **Clients:** the Windows client and the viewer run `-nosteam` with `-Dappearanceoverride.debug=true`, driven by the
  takeover's archived harness.

Driver additions (harness README of the archived takeover task):
- **Placeholder check:** every UI line ends with `invisAfter=…`, recorded after each panel helper call
  (`MatchesBaseFilter` ×2, the `DrawHideButton` label, `IsPreviewSkipped`). It reads where `SCTD_Transmog_IsInvisVariant`
  is now (`getFilenameOfClosure`/`getFirstLineOfClosure`). SCTD's own function reads as `LingLi_SCTD_Skins.lua:2619`,
  because `getFirstLineOfClosure` gives the line after the header at 2618.
- **Panel:** `panelRows` builds SCTD's transmog panel through `SCTD_TransmogPanel:new/initialise/instantiate`, fills it
  with `refreshBases`, and calls the panel's row closure for every row. The list's draw methods are captured, so the
  row's texts and colors are logged. `panelClick` clicks an item's hide/show button through `SCTD_Transmog_ClickHide`.
- **Set messages:** every appearance `set` message the client receives is logged as
  `appearance set <user> revision=n entries=n`.

## 1. Takeover basics (fresh save, correct hash)

Server: `[Dont39AllInOne] sctd-render-takeover: ready (server)`.

| Test | Result | Lines |
|---|---|---|
| `sctdHide` | Pass, same as the takeover's st1 | `sctd hide Base.KnapsackSprayer#1119627385 -> true Base.KnapsackSprayer#1119627385` (same id); record `{Dont39AllInOne=1,hidden=true}` and override `{hidden=true}` on both sides; `fluid=2.000/16.000` unchanged; client `filterHidden=true … button=Show previewSkipped=true`. |
| Panel rows (hidden) | Pass | `row closure from Dont39AllInOne_SctdRenderTakeoverUi.lua:48`; hidden items get the Show label (`显示`), the others Hide (`隐藏`); no transmog mark |
| `ClickHide` | Pass | `panel show ClickHide row 7 Base.Jacket_Black#620802295 returned true`, then `notice SCTD_Transmog.restored item=620802295`; record and override cleared on both sides |
| `sctdTransmog` | Pass, same as st2 | Normal: `override={…textureChoice=0,…,type=Base.Jacket_Leather_Punk}`, `filterTransmogged=true`, row `皮质夹克 (黑) [已幻化](0.40/0.95/0.85)` (SCTD's transmog mark and colour). Cross-slot: `type=Base.Shirt_Lumberjack, baseTexture=4`. Invert: `sctd apply Base.Jacket_Black#620802295 -> true Base.Jacket_Leather_Punk#1868594791`, punk jacket worn and drawn as `Base.Jacket_Black`, A in the inventory. Invert from the bag: `Jacket_Varsity#292657910` worn, drawn as A, bite 10. Each restore clears the record and override. |
| `sctdSkin` | Pass, same as st3 | `itemType=Jacket_Leather_Punk choices=4 currentIndex=0` → after `ChangeSkin A 2`: client override `textureChoice=2` from +200 ms to +8000 ms (no flicker back); server the same. `ChangeColor A` → `tint={1:0.2…,2:0.6…,3:0.8999…,4:1}` on both sides; list `currentIndex=2`. The plain hat goes SCTD's own way: server `vis=1,1,0.90/0.10/0.10 … override=nil`, and no set message. |
| `sctdAutoHide`, body form on | Pass: 7 items hidden, **one** set message | 7 × `notice SCTD_Transmog.applied … record={Dont39AllInOne=1,autoHidden=true,hidden=true}` (same item ids), then a single `appearance set admin revision=15 entries=9` (it was 14; 7 auto-hidden + sprayer + jacket look). Client core: `set of admin, revision 15` is the only set line between revisions 14 and 16. |
| `sctdAutoHide`, body form off | Pass: **one** set message | 7 × `notice SCTD_Transmog.restored`, then a single `appearance set admin revision=16 entries=2` |
| SCTD's placeholder check restored after each helper | Yes, in every UI line and row draw of every test | Every `invisAfter` value reads `LingLi_SCTD_Skins.lua:2619` (shown as `NOT:…2619` in the first run, `sctd` after the driver's constant was corrected): SCTD's own `SCTD_Transmog_IsInvisVariant` |

Every apply, restore, hide and show gave exactly one set message, raising the revision by 1.

## 2. Migration (second fresh save)

| Check | Result | Lines |
|---|---|---|
| Wrong hash: old-style items | Made: same set as the migration task's re-run (hidden sprayer and satchel, jacket transmog recolored by SCTD, cross-slot hat, inverted shoes, gloves placeholder in the carried school bag, scarf placeholder on the floor, 5 auto-hidden) | `sctd-render-takeover: disabled after an error while checking SCTD's code: HideItem_Server at media/lua/shared/LingLi_SCTD_Skins.lua:2796 is not the reviewed version …`; `made server: Base.SCTD_Invis_hands_acb2bb8d#1712091596 in:Base.Bag_Schoolbag#1452234506`; `made compare: client=20 server=20 … clientOnly=[] serverOnly=[]` |
| Correct hash, restart, join: one `converted` line | Yes | `sctd-render-takeover: converted admin: 8 hidden, 2 transmogs, 1 inverts (0 left as themselves), 0 left (see errors)`, about 1.1 s after `online admin at 1793173415709`. It is the only `converted` line in the whole server console. |
| Ids equal, including the bag item | Yes | `join compare: client=23 server=23 same=3 differ=20 clientOnly=[] serverOnly=[]` and `join30s` the same; the differences are display names only. Gloves: `Base.Gloves_LeatherGloves#490477943 in:Base.Bag_Schoolbag#1452234506 … record={Dont39AllInOne=1,hidden=true}` on both sides. Recolored jacket: `override={…textureChoice=2,tint={1:0.2…,2:0.6…,3:0.898…,4:1},type=Base.Jacket_Leather_Punk}`. |
| No packet failures | None | `send failed` / `NullPointerException`: 0 lines in the whole server console |
| Second join and two more rejoins | Nothing converted, no growth in behavior | Three more joins: each `online admin at …` (a new player object every time), no `converted` line, no SCTD notices; `compare: client=23 server=23 … clientOnly=[] serverOnly=[]` after each, at join and +30 s |
| Per-player state holds only online players | Not read | The migration's `scanned` table is local to its module and has no accessor; reading it would need a code change. The behavior above (no repeat conversion, no left-item reports across four player objects) is the only evidence. |

## 3. Zabric: Lua globals for a Java mod that arrives only at the join (viewer)

The viewer's menu list enabled only the driver (`[ZB] ZomboidFileSystem.loadMods(1 mods)` at the menu). All-in-One
came from the server's list at the join (`loadMods(7 mods)`, then `loading Dont39AllInOne`). The `viewer` account was
made admin through the server console.

| Check | Result | Lines (viewer console) |
|---|---|---|
| Globals exposed late | Yes | `[Zabric] Lua: exposed the global functions of com.dont39.pz.allinone.AppearanceOverrideLua, whose jar ZombieBuddy loaded after the game started Lua`, and the same for `ModSyncLua`, `TunnelLua`, `NetworkStatusLua` |
| Client part and takeover ready | Yes | `appearance-override sync: client part ready`, `sctd-render-takeover: ready (client)` |
| No "Java side is not loaded" | 0 such lines | (count over the viewer console) |
| Viewer draws the admin's overrides | Yes | `set of admin, revision 16: {Base.Jacket_Black=Look(type=Base.Jacket_Leather_Punk, textureChoice=2, baseTexture=-1, tint=Tint(r=0.2, g=0.6, b=0.9, a=1.0), …), Base.KnapsackSprayer=Hidden}`; `debug: model of admin: Base.KnapsackSprayer hidden, Base.Jacket_Black as Base.Jacket_Leather_Punk`; the same for attached items and textures. Driver: `test sctd view: admin present=true`, admin 2.0 squares away for 60 s. |

This is what failed in the takeover's st7b ("client part idle: its Java side is not loaded").

## 4. Restart and rejoin (first save)

| Check | Result | Lines |
|---|---|---|
| Overrides kept, drawn the same | Yes | client `set of admin, revision 1: {Base.Jacket_Black=Look(type=Base.Jacket_Leather_Punk, textureChoice=2, …, tint=Tint(r=0.2, g=0.6, b=0.9, a=1.0) …), Base.KnapsackSprayer=Hidden}`; `debug: model of admin: Base.KnapsackSprayer hidden, Base.Jacket_Black as Base.Jacket_Leather_Punk` |
| Custom tint equal on both sides | Yes | `rejoined client` and `rejoined server`: `override={baseTexture=-1,hue=0,textureChoice=2,tint={1:0.20000000298023224,2:0.6000000238418579,3:0.8999999761581421,4:1},type=Base.Jacket_Leather_Punk}` |
| Records and UI | As before | sprayer `record={Dont39AllInOne=1,hidden=true} … button=Show previewSkipped=true`; jacket `filterTransmogged=true`; hat (SCTD's own color) `vis=1,1,0.90/0.10/0.10` |

## Observations

- **Extra ERROR block on the wrong-hash start.** The server prints a second ERROR block for the same message:
  `ERROR: General … Lua((MOD:Dont39 All-in-One)).cut> Exception thrown` /
  `se.krka.kahlua.vm.KahluaException: HideItem_Server at media/lua/shared/LingLi_SCTD_Skins.lua:2796 is not the reviewed version …`,
  with the stack `cut (Dont39AllInOne_SctdRenderTakeoverSourceGate.lua:138)` ← `check (…SourceGate.lua:135)` ←
  `check (Dont39AllInOne_SctdRenderTakeoverShared.lua:261)`. The `disabled after an error while checking SCTD's code`
  line follows as before, so the result is unchanged. The earlier build printed only the `disabled` line. The engine
  logs this block when the `error` leaves the gate's visitor through a Java call boundary.
- **Two set messages at a join.** After the restart the client gets two `appearance set admin revision=1 entries=2`:
  the snapshot answer and the join's publish. The second does not replace the first (same revision).
- **Hidden T-shirt tint on the server.** After a reload, the server's drawn-tint read of the hidden custom-colored
  T-shirt is white (`vis … 1.00/1.00/1.00` on the server, 0.32/0.41/0.38 on the client), as in the migration task's
  runs. The T-shirt is hidden, so nothing draws it.

## Not covered

- The migration's per-player state by a direct read (no accessor; would need a code change).
- `ClickHide` in the hide direction (only show was clicked). The row closure was called with captured draw methods,
  not drawn on screen. SCTD's `MigrateLegacy` batch, single player, and the takeover's disabled path for its own
  items (covered in the migration task's runs).

## Restore

- **myserver2:** `Server`, `mods`, `zombie_buddy`, `Saves`, `db`, `Lua`, `options.ini` were backed up first
  (`backups-2026-10-28-pr34-review`, file list identical to the earlier runs' backup) and restored after the server was
  stopped. The 7172 file hashes and the name list are identical, `RCONPort=47015`, no `Mods=` line, and the mods are
  back to ZombieBuddy, Zabric, ZabricKotlin, Dont39AllInOne. The start script, the password file and the correct-copy
  file were removed. `pr34-console.log` (the viewer password in it replaced by `***`) and new `Logs/` folders are left
  there.
- **Client:** `cleanup.ps1` removed the 7 copies (0 differences) and moved the multiplayer save and `sgtest_ids.txt` to
  `%TEMP%\sgtest\cleanup\20261028-155042`; the first save's client copy is in `%TEMP%\sgtest\cleanup\pr34-phase1`.
  Against `~/Zomboid/test-backups/2026-10-28-pr34-review`, these are hash-equal: the Loading Progress reports (24),
  `default.txt` (no SGTestDriver), ZombieBuddy `config.json` and `mod_approvals.json`, `options.ini`,
  `latestSave.ini`. The `mods`/`Lua` names and multiplayer saves are unchanged.
- **Viewer cache `C:\pz2c`:** test-only, kept from the takeover's run.
  - Its earlier mod copies and its two multiplayer saves were moved aside for the run and moved back; its `default.txt`
    is restored (hash-equal). Its mods list, saves list and `Lua` names equal the ones from before.
  - The run's new viewer save and Lua files are in `%TEMP%\sgtest\cleanup\pr34-viewer`.
  - Its earlier viewer save was first moved back inside the run's save folder (same name), then moved out to its own
    place again.
