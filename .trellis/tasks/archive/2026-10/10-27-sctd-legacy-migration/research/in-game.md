# In-game check, 2026-10-28

Test instance `myserver2` on the Linux test server (`-nosteam`, game port 48261, own RCON port 48015, fresh save,
`Map=Muldraugh, KY`), started from `migration-start.sh` in tmux `pzmig`. Mods: ZombieBuddy (myserver2's own copy),
Zabric, ZabricKotlin, Dont39AllInOne (this branch, uncommitted, Zabric 0.7.3; `build assembleMod` with JDK 25),
ShadowCatTacticsDispatch and ShadowCatTacticsGuardian (copies of the server's Workshop 3689462578 / 3609052036, file
by file equal to the client's: `LingLi_SCTD_Skins.lua` sha256 `ba9931c0…`), SGTestDriver. A `-nosteam` server does not
load mods from its Workshop folder (first start: `required mod "ShadowCatTacticsDispatch" not found`), so both were
copied into `myserver2_Data/mods`. The live `myserver` (pid 1812831, started Oct 27 06:28) was not touched: same
process at the end, its ports (46261, RCON 47015) never used.

Same builds on both sides: `Zabric.jar` `2ab64514…`, `ZabricKotlin.jar` `1f134bcf…`, `Dont39AllInOne.jar` `8f899f54…`
(equal to the client's Workshop staging, which its ZombieBuddy preload uses); whole mod folders hashed equal on client
and server for Zabric, ZabricKotlin, Dont39AllInOne, SGTestDriver, SCTD, SCTG. The only difference: during the
"wrong hash" runs the server's `Dont39AllInOne_SctdRenderTakeoverShared.lua` had `HideItem_Server`'s first hash value
changed (939850532 → 939850533), as in the takeover's st8; the correct copy (`74f26724…`, equal to the client's) was put
back for the other runs.

Client: Windows, `-nosteam`, `-Dappearanceoverride.debug=true`, mods copied into `~/Zomboid/mods`, driven by the
takeover's archived harness (`archive/2026-10/10-27-sctd-render-takeover/research/harness/`, `run-remote.ps1`) with the
new driver tests `sctdMigMake`, `sctdMigReport`, `sctdMigPickup`, `sctdMigDisabled`, `sctdMigPickup2` and the server
command `sctdAll` (harness README, "Added for task 10-27-sctd-legacy-migration"). A new female character at Rosewood
prison (body form `Base.FemaleBodyT512`).

The Lines column quotes the driver's client and server lines (every item of the player on both sides, by id, and the
comparison), All-in-One's client console lines (the appearance core's `model of ...` debug lines among them) and the
server console; the raw logs were not kept.

Comparison lines: `test mig <tag> compare: client=n server=n same=n differ=n clientOnly=[...] serverOnly=[...]`.
`name` differs on every item (client Chinese, server English display names) and is left out below.

## Results

### Step 1: old-style items through SCTD's own functions (takeover disabled) — `st1-make`

| Check | Result | Lines |
|---|---|---|
| Takeover disabled | yes | server `sctd-render-takeover: disabled after an error while checking SCTD's code: HideItem_Server at media/lua/shared/LingLi_SCTD_Skins.lua:2796 is not the reviewed version (found length 4444, hash 939850532, 2047066782)`; driver `sctdState HideItem_Server=…/LingLi_SCTD_Skins.lua:2797` (SCTD's own) |
| Hidden knapsack sprayer with 2 L water, worn | `Base.SCTD_Invis_back_f684b5d5#372330415` worn `base:back`, no fluid container (the water is gone at the hide, SCTD's shipped behavior) | `fill Base.KnapsackSprayer#2091772814 … fluid=2.000/16.000`; `sctd hide Base.KnapsackSprayer#2091772814 -> true Base.SCTD_Invis_back_f684b5d5#372330415` |
| Hidden bag with items inside, worn | `Base.SCTD_Invis_satchel_bag_46c3569b#90141555` worn `base:satchel`, `n=2`: `Pencil#1502735711`, `Battery#1061264908` inside | `sctd hide Base.Bag_Satchel#842850099 -> true …#90141555` |
| Type transmog, worn | `Jacket_Black#1452241404` → `Base.Jacket_Leather_Punk#1336517299` worn `base:jacket`, record `baseFullType=Base.Jacket_Black, srcFullType=Base.Jacket_Leather_Punk`, A's stats (bite 20 / scratch 40) | `sctd apply Base.Jacket_Black#1452241404 -> true Base.Jacket_Leather_Punk#1336517299` |
| Cross-slot transmog, worn | `Hat_BaseballCap#1841070716` → `Base.Shirt_Lumberjack#1590380137` worn `base:hat`, record `baseBodyLocation=base:hat` | `sctd apply Base.Hat_BaseballCap#1841070716 -> true Base.Shirt_Lumberjack#1590380137` |
| Inverted transmog, worn | `Shoes_Black#1693778039` stays, record `inverted=true, srcFullType=Base.Shoes_ArmyBoots`, boots' stats on it (bite 100, cond 30/30); boots consumed | `sctd apply Base.Shoes_Black#1693778039 -> true Base.Shoes_Black#1693778039` |
| Hidden item taken off into a bag | `Base.SCTD_Invis_hands_acb2bb8d#623223693` in `Bag_Schoolbag#356137276` (bag in the main inventory, not worn) | `… in:Base.Bag_Schoolbag#356137276 … baseFullType=Base.Gloves_LeatherGloves …` |
| Auto-hidden items (body form worn) | 5: belt, T-shirt (custom color, `baseCustomColor=true`), lumberjack shirt (`baseBaseTexture=3`), socks (tinted), jeans → `SCTD_Invis_*` with `autoHidden=true` | 5 `notice SCTD_Transmog.applied … autoHidden=true` |
| Old-style item dropped on the floor | `Base.SCTD_Invis_scarf_5625f2f8#290650797` at 7560,11815,0 | `made server: … ground:7560,11815,0 …` |
| Client equals server | yes, except the floor scarf's stats (server has SCTD's `baseStats` applied, the client's floor copy script values: SCTD's own behavior for a world item) | `made compare: client=19 server=19 … clientOnly=[] serverOnly=[]`; `beforeSwap`/`swapped` comparisons differ in names only |

A real backpack (`base:back`) could not be worn with the sprayer, which takes `base:back` too; the hidden bag is a
satchel (`base:satchel`, SCTD's `satchel_bag` variant), the closest worn bag.

### Step 2: correct hash, restart, first join — `st2-first-join`

| Check | Result | Lines |
|---|---|---|
| Takeover active | yes | `sctd-render-takeover: ready (server)` |
| One `converted` line, right counts, no error | yes: 8 hidden (sprayer, satchel, gloves, 5 auto-hidden), 2 transmogs, 1 invert; no error line from All-in-One | `sctd-render-takeover: converted admin: 8 hidden, 2 transmogs, 1 inverts (0 left as themselves), 0 left (see errors)` |
| Real types, worn where they were | `KnapsackSprayer#1236750129@base:back`, `Bag_Satchel#1278274416@base:satchel`, `Jacket_Black#1695406694@base:jacket`, `Hat_BaseballCap#131340879@base:hat`, `Shoes_ArmyBoots#1779522121@base:shoes`, belt/T-shirt/shirt/socks/jeans in their slots; gloves `Gloves_LeatherGloves#902976956` in the school bag | `join server: worn …` |
| Record and override | hidden ones `{Dont39AllInOne=1,hidden=true}` (+`autoHidden=true` for the five) and `{hidden=true}`; jacket `{Dont39AllInOne=1,srcDisplayName=Leather Jacket - Punk}` + look `type=Base.Jacket_Leather_Punk, textureChoice=3` (B''s visual was `3,-1`); hat look `type=Base.Shirt_Lumberjack, baseTexture=2` (B' `-1,2`); boots `{Dont39AllInOne=1,srcDisplayName=Shoes}` + look `type=Base.Shoes_Black` | `join server: …` lines |
| Kept state | T-shirt tint 0.32/0.41/0.38 as the record's `baseTint`, shirt `baseTexture 3`, socks tint 0.20/0.20/0.16, conditions as before; no `SCTD_Invis` icons left (`icon=nil`) | same |
| Contents | satchel `n=2`, `Pencil#1502735711` and `Battery#1061264908` (same ids) inside the new satchel | `… in:Base.Bag_Satchel#1278274416 …` |
| Water | lost before the migration (the placeholder had no fluid); the new sprayer is empty `0.000/16.000` (design R2) | `KnapsackSprayer#1236750129 … fluid=0.000/16.000` |
| Fresh source in the inventory | `Jacket_Leather_Punk#794416033` (jacket), `Shirt_Lumberjack#98630847` (hat); invert: fresh `Shoes_ArmyBoots#1779522121` worn, cond 30/30 = `srcCondition` | `join server: … inv …` |
| Invert as B worn drawn as A | boots worn in `base:shoes` drawn as `Base.Shoes_Black`; `Shoes_Black#1693778039` (same id) back in the inventory with its own script stats (bite 10, scratch 20, cond 24/24 = its `origStats`) | client `notice SCTD_Transmog.restored item=1693778039`, `notice SCTD_Transmog.applied item=1779522121 … record={Dont39AllInOne=1,srcDisplayName=Shoes}` |
| Same look on the own client | yes | `appearance-override: debug: model of admin: Base.KnapsackSprayer hidden, Base.Bag_Satchel hidden, Base.Jacket_Black as Base.Jacket_Leather_Punk, Base.Trousers_JeanBaggy hidden, Base.Shoes_ArmyBoots as Base.Shoes_Black, Base.Socks_Long hidden, Base.Shirt_Lumberjack hidden, Base.Tshirt_McCoys hidden, Base.Hat_BaseballCap as Base.Shirt_Lumberjack, Base.Belt2 hidden` |
| Stats equal client and server | yes for every item both sides have (all fields but `name`) | `join compare: client=22 server=22 … clientOnly=[Base.SCTD_Invis_hands_acb2bb8d#623223693] serverOnly=[Base.Gloves_LeatherGloves#902976956]`, the 18 `differs` lines all `name` only |
| Client's ids equal the server's after the first join | **all but one**: the gloves inside the school bag stayed the old placeholder `#623223693` on the client while the server has `Gloves_LeatherGloves#902976956`, still so 30 s later (`join30s compare`, same) | see failure 1 |

Timing: the conversion ran on the server tick in which the player first showed in the online list (driver
`online admin at 1793158314395 items=21 worn …` already lists the converted items, logged right after the `converted`
line); the client's driver marked `ingame` at 1793158315652 and had received SCTD's notices before that. The server's
clock runs about 0.93 s behind the client's (step 5: SCTD's two hides logged by the server's clothing-event line at
…131585/…131628, their replies on the client at …132519/…132570), so the conversion came about 0.3 s before the
client was in the world. The packet exception below shows independently that the server player had no square yet. Everything sent to the main inventory or the worn list
arrived; only the item inside a bag did not.

### Step 3: second join, no restart — `st3-second-join`

| Check | Result | Lines |
|---|---|---|
| Nothing converted, no line | yes: one `converted` line in that server run (step 2's), none at this join, no SCTD notices | server console of that run |
| Client equals server | yes, the gloves now match (`Gloves_LeatherGloves#902976956` on both: the join loads the server's inventory) | `join compare: client=22 server=22 same=3 differ=19 clientOnly=[] serverOnly=[]`; differences: names, and the T-shirt's drawn tint (observation 2) |

### Step 4: dropped item picked up and worn — `st4-pickup` (server restarted first, for a driver change)

| Check | Result | Lines |
|---|---|---|
| Not converted while carried | yes | `picked up: Base.SCTD_Invis_scarf_5625f2f8#290650797 inv …` |
| Converted when worn | **no**: 10 s after the wear the scarf is still `SCTD_Invis_scarf_5625f2f8#290650797` worn `base:scarf` on both sides; no `converted` line in that server run | `pickedUpWorn server: Base.SCTD_Invis_scarf_5625f2f8#290650797 worn:base:scarf … record={…hidden=true}`; see failure 2 |

Repeated as step 6 with a server log of every Lua clothing event, below.

### Step 5: takeover disabled again — `st5-disabled`, `st5b-disabled-rejoin`

| Check | Result | Lines |
|---|---|---|
| Disabled | yes; `SCTD_Transmog_HideItem_Server` SCTD's own, `SCTD_Transmog_Restore` the takeover's wrapper | server `… disabled after an error while checking SCTD's code: HideItem_Server …`; `sctdState HideItem_Server=…LingLi_SCTD_Skins.lua:2797 Restore=…Dont39AllInOne_SctdRenderTakeover.lua:230` |
| Nothing converted | yes: the worn old-style scarf stays `SCTD_Invis_scarf` through six server clothing events (body form off, SCTD's two hides, the necklace drop, SCTD's show and restore; none from the two wears, as in failure 2) and through a rejoin; no `converted` line in that server run | server console of that run; rejoin `join server: Base.SCTD_Invis_scarf_5625f2f8#290650797 worn:base:scarf …` |
| SCTD's own items keep working | hide: `sctd hide Base.Glasses_Aviators#1234938507 -> true Base.SCTD_Invis_eyes_0674654c#1844893105`, `sctd hide Base.Necklace_Gold#380524727 -> true Base.SCTD_Invis_necklace#1173409310`; show: `sctd show Base.SCTD_Invis_eyes_0674654c#1844893105 -> true Base.Glasses_Aviators#1188282084` (record nil) | driver |
| A converted item through SCTD's Restore | `sctd restore Base.Jacket_Black#1695406694 -> true Base.Jacket_Black#1695406694`: record and look cleared (wrapper R7a), same item | `sctdShownRestored server: Base.Jacket_Black#1695406694 worn:base:jacket … record=nil override=nil` |
| Client equals server | yes but names, the T-shirt's drawn tint and the unconverted placeholders' stats (observation 3) | `compare` lines |
| Correct copy back | `sctd-render-takeover: ready (server)` | server console |

### Step 6 (added): pick-up with the clothing events logged — `st6-pickup-event`

Correct hash. At the join the worn old-style scarf from step 4 was converted by the join scan
(`converted admin: 1 hidden, 0 transmogs, 0 inverts (0 left as themselves), 0 left (see errors)`, then
`Scarf_StripeBlackWhite#504042403` with our record), and the takeover's auto-hide gave back the five auto-hidden items,
since the body form came off in step 5 (5 `notice SCTD_Transmog.restored`). Then the necklace placeholder SCTD made
in step 5 and dropped:

| Check | Result | Lines |
|---|---|---|
| Picked up, carried | still old-style, not converted | `carried server: Base.SCTD_Invis_necklace#1173409310 inv …` |
| Worn | still old-style 10 s later; the server saw **no** Lua clothing event between the join and the next step | `wornNoEvent server: Base.SCTD_Invis_necklace#1173409310 worn:base:necklace …`; no `OnClothingUpdated` server line between 1793159398025 (join) and 1793159431893 |
| Next server clothing event | converted at once | server `OnClothingUpdated admin at 1793159431893 …`, `converted admin: 1 hidden, 0 transmogs, 0 inverts (0 left as themselves), 0 left (see errors)`; `afterEvent server: Base.Necklace_Gold#518385588 worn:base:necklace … record={Dont39AllInOne=1,hidden=true…}`, client the same id |

The event at 1793159431893 (server clock; about 1.2 s after the `wornNoEvent` report on the client's) came while the
driver was giving and equipping a pencil (the test's next step); the following move of the held pencil into the bag
gave another (1793159435092). The test's synthetic `clothingEvent`
then had nothing left to convert.

## Failures and their causes

1. **First join: an item inside a bag keeps its old copy on the client until the next join.** Server lines at the
   conversion: `ERROR: Multiplayer … INetworkPacket.send> Exception thrown` /
   `java.lang.NullPointerException: Cannot invoke "zombie.iso.IsoGridSquare.getX()" because "player.square" is null at
   ContainerID.setInventoryContainer(ContainerID.java:212). Message: Packet "AddInventoryItemToContainer" send failed`
   from `swap (Dont39AllInOne_SctdRenderTakeoverMigration.lua:207)` ← `convertSwapped (…:250)` ← `scanOnline (…:387)`,
   and the same for `RemoveInventoryItemFromContainer`. Cause: `scanOnline` converts in the first tick the player is in
   `getOnlinePlayers()`, before the server has placed the player on a square. `sendAddItemToContainer` /
   `sendRemoveItemFromContainer` for a container inside an inventory item build the container id with
   `ContainerID.setInventoryContainer`, which reads `player.square` (javap: `ContainerID.set` takes that branch when the
   container's outermost container belongs to an `IsoPlayer`; the main inventory takes the `IsoPlayer` parent branch,
   which needs no square). The engine catches and logs the exception inside `INetworkPacket.send`, so the migration sees
   no error and logs none; the server's state is right, the client shows the old placeholder in the bag until it
   rejoins (step 3). Affects any old-style item in a bag (worn or carried) at the join scan; worn items, items in the
   main inventory and a bag's contents moved with a rebuilt bag are not affected. Design.md's "the inventory is loaded
   before the player shows in the online list" holds for the inventory, not for the square the packets need.
2. **A picked-up old-style item worn later is not converted on the wear itself on a dedicated server.** Steps 4 and 6:
   no Lua `OnClothingUpdated` reaches the server for the wear. Vanilla `ISWearClothing:perform` raises the event
   (`shared/TimedActions/ISWearClothing.lua:92`) but runs on the client; `ISWearClothing:complete` (`:101-125`), which
   the server runs, only calls `setWornItem`. In Java the server raises the Lua event from `Transaction.updateItem`
   (an equipped item moved out), `IsoGameCharacter.setAttachedItem`, `helmetFall`, `addBlood`/`addDirt`/`addHole`/
   `addBasicPatch`, `onTrigger_setClothingToXmlTriggerFile`, `updateBeardAndHair` (string search over the game jar,
   javap), and SCTD's own swaps raise it in Lua; `setWornItem` → `onWornItemsChanged` calls the Java method only. So
   design.md's "`OnClothingUpdated(player)` … for an old-style item worn after the scan" converts it only when one of
   those happens later (step 6: within a few seconds, from the next hand/transfer action), else at the next join
   (step 6's scarf). The migration's handler itself works once the event comes.

## Observations (not the migration's failures)

1. Item names differ on every item (`getName()` is the side's language for an item without a custom name).
2. The custom-colored T-shirt: after the server reloads the player (step 3 on), the server's
   `getVisual():getTint(clothingItem)` reads 1.00/1.00/1.00 while the client reads the recorded 0.32/0.41/0.38; the item
   data is equal on both sides (`cc=true:0.32/0.41/0.38`, added to the item line in step 4). `ItemVisual.getTint`
   returns the item's color only through the visual's link to its item (javap), so the server's reloaded visual reads
   white there. The T-shirt is hidden (auto-hide), so nothing draws it; not traced further, and the same item fields come
   from SCTD's `applyAuthoritativeTint` when SCTD restores such an item.
3. An unconverted old-style item has different stats on client and server once the server restarted: SCTD's client
   resnap puts the record's `baseStats` on the client copy, the server's copy has script values (instance stats are not
   saved). Seen on the floor scarf and the worn scarf/necklace placeholders; SCTD's behavior, gone after conversion.
4. The first join attempt (`st0-failed-login`) was refused (`access denied: user "admin" reason
   "InvalidUsernamePassword"`): `run-remote.ps1` read the password with `(Get-Content $PasswordFile)[0]`, the first
   character of a one-line file. Fixed in the harness (`@(Get-Content …)[0]`).

## Not covered

- A real back-slot backpack (the sprayer needs `base:back`; a satchel instead).
- A type transmog whose B has a fluid container (`copyFluidsFrom`): none of the pairs had one.
- An inverted record whose A is not worn at the conversion, an invert `Apply` refuses (`left as themselves`), an old
  item held in a hand, a worn item in a multi-item slot (as in the takeover: the game has no suitable pair), and the
  per-item error paths (type gone, look not drawable).
- Single player; a second client watching (the takeover's st7c covers the drawing path).
- "No Server Reborn diff": Server Reborn was not on the list; the driver's per-field comparison of every item stands
  in for it.

## Re-run after fixes, 2026-10-28

The working tree's fixes (design.md "When" and Deviations): the server's scans wait for `player:getCurrentSquare()`;
a scanned online player gets a worn scan every second on the server; `Takeover.lookOf` takes a custom-colored item's
tint from the item (`isCustomColor` → `getR/G/B`). Rebuilt (`build assembleMod`, JDK 25; the build's three takeover
Lua files equal the sources). Same setup as above: fresh save on myserver2 (`migration-start.sh`, tmux `pzmig`, log
`migration-rerun-console.log`), SCTD and SCTG copied into `myserver2_Data/mods`, same jars (`Zabric.jar` `2ab64514…`,
`ZabricKotlin.jar` `1f134bcf…`, `Dont39AllInOne.jar` `8f899f54…`), whole mod folders equal on both sides (Zabric,
ZabricKotlin, Dont39AllInOne `8169cf3b…`, SGTestDriver, SCTD, SCTG); wrong hash only in the server's copy for step 1.
New fresh character. Server starts: R1 wrong hash, R2 correct hash for steps 2-4, R3 restart for step 5.

Driver additions for the re-run: `sctdMigMake` recolors the transmogged jacket through SCTD's own `SCTD_ChangeColor`
(SCTD's `changeColor` command → `applyAuthoritativeTint` on the server: a custom color); `sctdMigPickup` logs how long
after the wear the old item leaves the inventory.

### R1: old-style items, takeover disabled — `rerun-st1-make`

`sctd-render-takeover: disabled after an error while checking SCTD's code: HideItem_Server …`. Same set as the first
run, new ids: hidden sprayer `SCTD_Invis_back_f684b5d5#1949827215`, hidden satchel `#400211702` with pencil and battery,
jacket transmog `Jacket_Leather_Punk#1318912934`, cross-slot hat → `Shirt_Lumberjack#727140496`, inverted
`Shoes_Black#886300118`, **gloves placeholder `SCTD_Invis_hands_acb2bb8d#64103067` in the carried school bag
`#1162284521`**, **scarf placeholder `#1676558902` on the floor**, 5 auto-hidden (belt, T-shirt with custom color, denim
shirt, socks, jeans). **Custom-colored transmog**: `test mig ChangeColor transmogged jacket returned true`, then on both
sides `Base.Jacket_Leather_Punk#1318912934 worn:base:jacket … vis=0,-1,0.20/0.60/0.90 cc=true:0.20/0.60/0.90`.
SCTD's type transmog itself copies A's tint and color onto B' (`CopyState`), so B's own custom color never reaches a
transmog; a custom color on the transmogged item (SCTD's skin panel) is the case SCTD allows. `made compare:
client=20 server=20 … clientOnly=[] serverOnly=[]`, differences names only and the floor scarf's stats (observation 3).

### R2 step 2: correct hash, restart, first join — `rerun-st2-first-join`

| Check | Result | Lines |
|---|---|---|
| One `converted` line | yes | `sctd-render-takeover: converted admin: 8 hidden, 2 transmogs, 1 inverts (0 left as themselves), 0 left (see errors)` |
| Waits for the square | the conversion came about 1 s after the player showed in the online list | `[SGTEST] server: online admin at 1793160673980 items=19 worn Base.SCTD_Invis_belt…` (old items), then the `converted` line in the frame of `OnClothingUpdated admin at 1793160675032 … Base.Belt2#73919294 …` (converted items; that event is the migration's own) |
| No packet failures | none in the whole re-run console | `send failed` / `NullPointerException`: 0 lines; the only All-in-One error line is R1's intended `disabled` |
| Ids match, the bag item included, right after the join and 30 s later | yes | `join compare: client=23 server=23 same=3 differ=20 clientOnly=[] serverOnly=[]`, `join30s compare: client=23 server=23 same=3 differ=20 clientOnly=[] serverOnly=[]`; every `differs` is the display name only; gloves `Base.Gloves_LeatherGloves#2128170785 in:Base.Bag_Schoolbag#1162284521 … record={Dont39AllInOne=1,hidden=true} override={hidden=true}` on both sides |
| Custom-colored transmog's look | the look's tint is the transmogged item's custom color | `Base.Jacket_Black#423704599 worn:base:jacket … override={baseTexture=-1,hue=0,textureChoice=0,tint={1:0.20000000298023224,2:0.6000000238418579,3:0.8980392217636108,4:1},type=Base.Jacket_Leather_Punk}` (0.898 is 0.9 as the item's color stores it) |
| The rest as in the first run | sprayer, satchel (contents, same ids), auto-hidden five with our `autoHidden` record, hat as the shirt, boots worn drawn as `Shoes_Black` with A in the inventory, fresh `Jacket_Leather_Punk#575127419` and `Shirt_Lumberjack#1125687951` | client `model of admin: Base.KnapsackSprayer hidden, Base.Bag_Satchel hidden, Base.Jacket_Black as Base.Jacket_Leather_Punk, Base.Trousers_JeanBaggy hidden, Base.Shoes_ArmyBoots as Base.Shoes_Black, Base.Socks_Long hidden, Base.Shirt_Denim hidden, Base.Tshirt_McCoys hidden, Base.Hat_BaseballCap as Base.Shirt_Lumberjack, Base.Belt2 hidden` |

The SCTD notices reached the client after its `ingame` mark this time (`ingame … 1793160675268`, then
`notice SCTD_Transmog.restored item=886300118 …` at …675963).

### R2 step 3: second join — `rerun-st3-second-join`

Nothing converted: no `converted` line after R2's one until step 4, no notices. `join compare: client=23 server=23
same=3 differ=20 clientOnly=[] serverOnly=[]` (and `join30s` the same); differences names and the hidden T-shirt's
drawn tint on the server (observation 2, the server read only; the T-shirt's look is `hidden`, so `lookOf` is not
involved).

### R2 step 4: floor item picked up and worn — `rerun-st4-pickup`

| Check | Result | Lines |
|---|---|---|
| Not converted while carried | yes | `carried server: Base.SCTD_Invis_scarf_5625f2f8#1676558902 inv …` |
| Converted within about 2 s of the wear | yes, 397 ms | `test mig worn at 1793160921293`, `test mig converted 397 ms after the wear`; server `converted admin: 1 hidden, 0 transmogs, 0 inverts (0 left as themselves), 0 left (see errors)`; `pickedUpWorn server: Base.Scarf_StripeBlackWhite#1394616041 worn:base:scarf …`, client the same (`compare: client=23 server=23 … clientOnly=[] serverOnly=[]`) |
| No other clothing event | yes: the only server `OnClothingUpdated` between the join (`online admin at 1793160897608`) and the conversion is in the conversion's own frame and already lists the converted scarf (the migration's own event after the swap) | `OnClothingUpdated admin at 1793160920708 worn … Base.Scarf_StripeBlackWhite#1394616041@base:scarf …`, same `st:1,813,932,903` as the `converted` line |

### R3 step 5: restart, rejoin, custom color on both sides — `rerun-st5-restart-rejoin`

No `converted` line in R3. The jacket's look on both sides after the server's reload:
`override={baseTexture=-1,hue=0,textureChoice=0,tint={1:0.20000000298023224,2:0.6000000238418579,3:0.8980392217636108,4:1},type=Base.Jacket_Leather_Punk}`
(client and server lines equal); the client's set: `Base.Jacket_Black=Look(type=Base.Jacket_Leather_Punk,
textureChoice=0, baseTexture=-1, tint=Tint(r=0.2, g=0.6, b=0.8980392, a=1.0), …)`. `join compare: client=23
server=23 same=2 differ=21 clientOnly=[] serverOnly=[]`, `join30s` the same; differences names and the T-shirt's drawn
tint on the server.

### Re-run restore

- myserver2 backed up again before the re-run (`backups-2026-10-28-sctd-migration-rerun`, `files.sha256` identical to
  the first run's backup) and restored after it: 7172 file hashes and the name list identical, `RCONPort=47015`, no
  `Mods=` line, `mods` back to ZombieBuddy, Zabric, ZabricKotlin, Dont39AllInOne; start script, password file and
  correct-copy file removed; `migration-rerun-console.log` (no password in it) and new `Logs/` folders left. The live
  `myserver` still pid 1812831 (started Oct 27 06:28).
- Client: `cleanup.ps1` (7 copies removed, 0 differences, `sgtest_ids.txt` and the multiplayer save moved to
  `%TEMP%\sgtest\cleanup\20261028-121935`); against `~/Zomboid/test-backups/2026-10-28-migration-rerun`: Loading
  Progress reports (24 files), `default.txt` (no SGTestDriver), ZombieBuddy `config.json` and `mod_approvals.json`,
  `options.ini`, `latestSave.ini` hash-equal, `mods`/`Lua` names and multiplayer saves unchanged.

## Restore (first run)

- myserver2: `Server`, `mods`, `zombie_buddy`, `Saves`, `db`, `Lua`, `options.ini` backed up first
  (`myserver2_Data/backups-2026-10-28-sctd-migration/state.tgz`, 7429 entries, `files.sha256` of 7172 files,
  `names.sha`) and restored after the server was stopped: file hashes and name list identical to the backup,
  `RCONPort=47015` and no `Mods=` line again, `mods` back to ZombieBuddy, Zabric, ZabricKotlin, Dont39AllInOne (their
  earlier copies); `migration-start.sh`, the admin password file and the correct-copy file removed. Left there, as after
  the takeover's run: `migration-console.log` (no password in it), new `Logs/` folders, `ItemTracker.log` and
  `server-console.txt` (rewritten by each start).
- Client: `cleanup.ps1` (7 mod copies removed, 0 differences, `sgtest_ids.txt` and the multiplayer save
  `192.168.50.249_48261_21232f…` moved to `%TEMP%\sgtest\cleanup\20261028-115242`); against the dated backup
  `~/Zomboid/test-backups/2026-10-28-migration-ingame`: Loading Progress reports (24 files), `default.txt` (no
  SGTestDriver), ZombieBuddy `config.json` and `mod_approvals.json`, `options.ini`, `latestSave.ini` hash-equal; the
  `mods` folder list, `Lua` names and multiplayer saves unchanged.
