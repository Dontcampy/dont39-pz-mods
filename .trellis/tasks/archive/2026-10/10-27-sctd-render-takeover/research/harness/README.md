# Server Reborn test harness (not shipped)

Copy of the archived task `10-26-server-reborn`'s harness for `10-27-server-reborn-sync-false-repairs`, with one more driver test,
`falseRepairs` (ItemStats food stats, bandage model, EFK gun data, plus a control); its runs are in `../in-game.md`.

Local dedicated server `sgtest` built from the user's `myserver1` config (or `srmin`, the same settings with a
minimal mod list), one `-nosteam` client driven by the `SGTestDriver` Lua mod, a sampler and the comparison scripts.
Written for Sync Guard's acceptance runs (archived task `10-24-mp-sync-loss-recovery`, whose AC numbers the sections
below keep); renamed for Server Reborn, whose smoke join is the first section. All in-game tests run on this
machine.

PowerShell 7 on Windows. Run every script from the repo root as `& "$h\<script>.ps1"` after
`$h = '.trellis\tasks\archive\2026-10\10-26-server-reborn\research\harness'`. `Get-Help "$h\<script>.ps1" -Full` shows each
script's parameters.

## This copy (task 10-27-sctd-render-takeover)

Copied from the archived `10-27-appearance-override` harness for the SCTD takeover's in-game run on the Linux test
instance `myserver2` (`../in-game.md` has the server setup and the run sequence). Added:

| File / test | What it does |
|---|---|
| `run-remote.ps1` | One driver test against `myserver2` (`-nosteam`, 192.168.50.249:48261): `start-client.ps1 -ServerHost`, wait for the test's end, `stop-client.ps1` |
| `start-viewer.ps1` / `stop-viewer.ps1` | A second client beside the first, with its own `-cachedir` (default `C:\pz2c`): own console, Lua folder, mods (real copies) and driver files; joins as `-User` |
| `sctdHide`, `sctdTransmog`, `sctdSkin`, `sctdAutoHide`, `sctdCheck`, `sctdDisabled`, `sctdView` | Driver tests calling SCTD's own functions: hide/show/apply/restore on the server (`SGTest` commands `sctd`, `sctdReport`, `sctdState`), skin panel and UI helpers on the client. Item ids carry over between runs in `Zomboid/Lua/sgtest_ids.txt` |

A fresh `-cachedir` needs `mods/reset-mods-42_00.txt` (copy the user's), or the game empties its `default.txt` at
the first launch and the driver never loads.

Added for task 10-28-pr34-review-fixes: each client UI line ends with `invisAfter=…`, where
`SCTD_Transmog_IsInvisVariant` is after each panel helper call (`sctd` = SCTD's own, `LingLi_SCTD_Skins.lua:2619`);
`sctdHide`, `sctdTransmog` and `sctdAutoHide` draw every row of SCTD's transmog panel through its row closure (panel
built with `SCTD_TransmogPanel:new/initialise/instantiate`, never shown; the list's draw calls captured: `test sctd
panel <tag> row n <item>: <texts with colors>`), and `sctdHide` shows A again with the panel's button
(`SCTD_Transmog_ClickHide`); every appearance `set` message the client gets is logged as
`appearance set <user> revision=n entries=n`.

## Added for task 10-27-sctd-legacy-migration

Its in-game run (`.trellis/tasks/10-27-sctd-legacy-migration/research/in-game.md`) used these on `myserver2`:

| Test / command | What it does |
|---|---|
| `sctdMigMake` | With the takeover disabled on the server (a wrong block hash in the server's copy), makes old-style items through SCTD's own server functions: gives sprayer, satchel, `Jacket_Black` + `Jacket_Leather_Punk`, `Hat_BaseballCap` + `Shirt_Lumberjack`, `Shoes_Black` + `Shoes_ArmyBoots`, gloves, school bag, scarf, pencil, battery; wears seven of them, moves pencil and battery into the satchel, fills the sprayer (2 L); then hide sprayer, hide satchel, apply jacket as the punk jacket, apply hat as the shirt (`ignoreBodyLocation`), invert the shoes with the boots, hide gloves, hide scarf; recolors the transmogged jacket with SCTD's own `SCTD_ChangeColor` (0.2, 0.6, 0.9; SCTD's `changeColor` command makes it a custom color); takes the gloves placeholder off into the school bag, drops the scarf placeholder on the floor, wears the body form (SCTD's auto-hide). The new ids go to `sgtest_ids.txt` (`old_<item>`, `form`, `bag`, ...) |
| `sctdMigReport` | After a join: every item on the client and on the server (`sctdAll`) and the comparison, at the start and 30 s later |
| `sctdMigPickup` | Picks up the dropped placeholder (`ISGrabItemAction`), reports it carried, wears it, then logs how many ms after the wear the old item left the inventory (`converted <n> ms after the wear`, or `not converted 15 s after the wear`) and reports again |
| `sctdMigDisabled` | With the takeover disabled again: takes the body form off, gives and wears glasses and a necklace, hides both through SCTD, drops the necklace placeholder on the floor, shows the glasses through SCTD, calls SCTD's `Restore` on the worn `Jacket_Black`; reports after each |
| `sctdMigPickup2` | After `sctdMigDisabled`, takeover running again: picks up the dropped necklace placeholder, wears it, reports after 10 s (`wornNoEvent`); then equips a pencil and moves it into the school bag (`afterEvent`); if the necklace is still old-style, asks the server for `clothingEvent` (`afterSyntheticEvent`) |
| `clothingEvent` (server command) | `triggerEvent("OnClothingUpdated", player)`, as SCTD's own swaps raise it |
| `OnClothingUpdated` (server log) | `[SGTEST] server: OnClothingUpdated <user> at <ms> worn ...` for each Lua clothing event the server sees for a player (the driver loads last, so after every other handler) |
| `sctdAll` (server command) | One line per item of the player (bags included) and per floor item near the given square that carries an SCTD record or is named in `ids`, keyed by id; prints `[SGTEST] server: sctdAll <tag> ...` |
| `online` (server log) | `[SGTEST] server: online <user> at <ms> items=<n> worn ...` when a player object first shows in the online list (the migration's own scan runs on its next once-a-second check) |

Item line (client and server alike): `<type>#<id> <where>` (`worn:<slot>`, `hand:*`, `inv`, `in:<bag type>#<bag id>`,
`ground:x,y,z`), weight, condition, fluid, bite/scratch/bullet/insulation/wind/water/run/combat, container capacity,
weight reduction and item count, the item's own visual, `cc=` (custom color flag and the color a custom-colored item is
drawn in), name (the side's language), favorite, `SCTD_Transmog` record,
`Dont39AllInOne_Appearance` override, `customInventoryIcon`. The client compares its lines with the server's by id and
field: `test mig <tag> compare: client=n server=n same=n differ=n clientOnly=[...] serverOnly=[...]`, then one
`differs` line per item with the differing fields.

## Files

| File | What it does |
|---|---|
| `common.ps1` | Paths, ports, test spots, RCON client, helpers; dot-sourced by the others |
| `setup-server.ps1` | Server `sgtest` in the cache folder from `myserver1`, mod copies for server and client; `-WithServerReborn`, `-Minimal` (server `srmin`), `-DryRun` |
| `start-server.ps1` / `stop-server.ps1` | Start (`-Wait` for `SERVER STARTED`) and stop (RCON `quit`) the server; console and logs go to the run folder |
| `backup-client.ps1` | Backs up the user's client files before a series |
| `start-client.ps1` / `stop-client.ps1` | Launch the client; the driver joins and runs the scenario. Stop and collect its console |
| `SGTestDriver/` | Lua-only client mod: join, god mode, teleport, loot/move/eat loop, progress lines `[SGTEST]` |
| `spawn-zombies.ps1` | `createhorde2` at the test spot over RCON, repeatable |
| `rcon.ps1` | Any server console command over RCON, e.g. `& "$h\rcon.ps1" players` |
| `sampler.ps1` | One CSV row per 5 s: CPU, memory, server network and heap statistics, newest Server Reborn summary line |
| `summarize.ps1` | Off/on table of two sampler CSVs, for `research/load-test.md` |
| `diff-errors.ps1` | Error lines only the run with Server Reborn has (AC10) |
| `cleanup.ps1` | Removes the copies, restores the client backup, deletes the cache folder |

## Where things go

| What | Where |
|---|---|
| Server `-cachedir` (ini, sandbox, spawn files, world, server `Logs`, `server-console.txt`, `mods`, the server's own ZombieBuddy `config_dir`, admin password `harness-adminpw.txt`) | `%TEMP%\sgsrv` (short on purpose: mod paths under it hit the 260-character limit) |
| Copy manifest, client backup, pids, runs (`runs\<label>\`: server/client stdout, consoles, logs, `sgtest_state.txt`, `samples.csv`) | `%TEMP%\sgtest` |
| Client mod copies | `~/Zomboid/mods/<folder>` (only folders the client does not already see) |
| Driver config and state | `~/Zomboid/Lua/sgtest.txt`, `sgtest_state.txt`, `sgtest_stop.txt` |
| Ports | game 16361, UDP 16362, RCON 27115 (`common.ps1`), so `myserver1` (16261/27015) is untouched |

The admin password is generated once, stored in the cache folder, used as the admin account's and the RCON password,
and never printed. The driver's config holds it only until the join starts (the driver rewrites the file without it).

## Prerequisites

- Server Reborn built: `JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew :server-reborn:build :server-reborn:assembleMod`.
- Zabric, ZabricKotlin and Dont39AllInOne are taken from their Workshop staging (`~/Zomboid/Workshop/<id>/Contents/mods/<id>`),
  the copies the client's preload config (`~/.zombie_buddy/config.json`) already uses. To test current repo builds of
  those, run their `deployWorkshop` first. If `~/Zomboid/Workshop/Dont39ServerReborn` exists, its jar must equal the
  build's (`setup-server.ps1` stops otherwise).
- Without `-Minimal`: every Workshop item of `myserver1` subscribed and downloaded (`setup-server.ps1 -DryRun` warns about missing ones).
- About 29 GB free on C: (14.3 GB for the server's copies, 14.3 GB for the client's; dry run 2026-10-24).
- The game closed: one client at a time, it shares `~/Zomboid/console.txt`.

## Test spots

Both on the vanilla `Muldraugh, KY` map, last on `myserver1`'s `Map=` list. No mod map on that list has a cell at the
military base (cells 48-49 x 16-17); `New Hartburg, KY` replaces cell 28_45, the prison's north-west corner, so the
prison spot is in cell 29_46 (checked: `*.lotheader` names of every map folder of the server's Workshop items).

| Spot | x, y, z | Evidence |
|---|---|---|
| `prison` (Rosewood prison) | 7560, 11815, 0 | `media/maps/Muldraugh, KY/objects.lua`: `Prison` zombie zones x 7329-7800, y 11686-11943 (lines 31627-32489) |
| `military` (military base) | 12513, 4280, 0 | same file line 12689: `Army` zombie zone x 12449, y 4203, 129 x 154 (center), `army` parking stalls at 12539, 4208 |

Not checked in game: whether the exact square is walkable. Pass `-Spot x,y,z` to any script to use another one.

## Server commands used (from `zombie.commands.serverCommands.*` annotations, `javap -v`)

| Command | Form | Notes |
|---|---|---|
| `godmode` (alias `godmod`) | `/godmode -true` | self; capability `ToggleGodModHimself` |
| `invisible` | `/invisible -true` | zombies ignore the driver, so actions are not interrupted (`start-client.ps1 -Visible` keeps it off) |
| `teleportto` (alias `tpto`) | `/teleportto x,y,z` | self |
| `createhorde2` | `createhorde2 -x X -y Y -z Z -count N -radius R` | capped at 500 per call; `invalid location` unless the square is loaded on the server |
| `stats` | `stats network all`, `stats performance all` | `zombie.network.statistics`; values per `MultiplayerStatisticsPeriod` (set to 1 s) |
| `quit` | `quit` | saves and exits; used by `stop-server.ps1` |

RCON runs these through `GameServer.handleServerCommand` as the server console, without a leading `/`.

The client has no command-line auto-join: `+connect host:port` and `+password` only open the connect popup, which
still asks for the account (`MainScreen.lua` 1801-1817, `BootstrapConnectPopup:connect`). The driver therefore joins
from the main menu with `ConnectToServer:connect`, as the load-speed task's driver did.

## Sequence: Server Reborn smoke join (minimal list)

ZombieBuddy, Zabric, ZabricKotlin, Server Reborn and the driver only, on a save of its own, so it copies a few small
folders instead of `myserver1`'s list.
Back up the Loading Progress reports and check `default.txt` first (the launcher rewrites it).

```powershell
$h = '.trellis\tasks\archive\2026-10\10-26-server-reborn\research\harness'
& "$h\backup-client.ps1"
& "$h\setup-server.ps1" -Minimal -WithServerReborn
& "$h\start-server.ps1" -Label smoke -Wait -ZbAllowAll
& "$h\start-client.ps1" -Label smoke -NoLoop -DurationSec 60 -Quit -ZbAllowAll
& "$h\stop-client.ps1"; & "$h\stop-server.ps1"
Select-String -Path "$env:TEMP\sgtest\runs\smoke\*-console.txt" -Pattern 'Dont39ServerReborn|hooks stopped|ERROR'
& "$h\cleanup.ps1"
```

Pass: server `active on a dedicated server` and the tick rate `off` at 10; client `ready on a client` and active for
the server; no `hooks stopped`, `disabled` or error line from the mod, Zabric or ZombieBuddy. A fresh `srmin` needs
one server restart after the server registers Zabric's preload (see the next section), and the join that creates the
character is not checked (`../smoke-join.md`): join a second time. `-ZbAllowAll` loads new jars without ZombieBuddy's
approval dialog for that launch only; ZombieBuddy still records them in `mod_approvals.json`, which `cleanup.ps1`
restores.

## Sequence: smoke and compatibility (AC10)

The first start of a fresh server registers Zabric's preload in the server's own ZombieBuddy config and generates the
world, and the first join creates the character; a warm-up run takes those, so the two compared runs both load an
existing world with an existing character.

```powershell
$h = '.trellis\tasks\archive\2026-10\10-26-server-reborn\research\harness'
& "$h\backup-client.ps1"
& "$h\setup-server.ps1" -DryRun             # check sources and size first
& "$h\setup-server.ps1"                     # Server Reborn off

# warm-up (restart once if the console asks for it after the preload registration)
& "$h\start-server.ps1" -Label warmup -Wait
& "$h\start-client.ps1" -Label warmup -NoLoop -DurationSec 60 -Quit
& "$h\stop-client.ps1"; & "$h\stop-server.ps1"

# without Server Reborn
& "$h\start-server.ps1" -Label compat-off -Wait
& "$h\start-client.ps1" -Label compat-off -DurationSec 300 -Quit
& "$h\stop-client.ps1"; & "$h\stop-server.ps1"

# with Server Reborn (only Mods= changes)
& "$h\setup-server.ps1" -WithServerReborn
& "$h\start-server.ps1" -Label compat-on -Wait
& "$h\start-client.ps1" -Label compat-on -DurationSec 300 -Quit
& "$h\stop-client.ps1"; & "$h\stop-server.ps1"

$r = "$env:TEMP\sgtest\runs"
& "$h\diff-errors.ps1" -Without "$r\compat-off\server-console.txt" -With "$r\compat-on\server-console.txt"
& "$h\diff-errors.ps1" -Without "$r\compat-off\client-console.txt" -With "$r\compat-on\client-console.txt"
Select-String -Path "$r\compat-on\*-console.txt" -Pattern 'Dont39ServerReborn'   # ready and active on both sides
```

`stop-client.ps1` waits for the driver's own quit (`-Quit`), otherwise asks it to quit through `sgtest_stop.txt` and
kills the client after 90 s. `diff-errors.ps1` exits with 1 when the run with Server Reborn has an error signature the
other has not; check the "more often" list too.

## Sequence: load scenario (AC7)

Same script and duration with the mod off, then on; one client in god mode at the prison, many zombies, looting and
moving items. Run the warm-up above first if the server is fresh.

```powershell
$h = '.trellis\tasks\archive\2026-10\10-26-server-reborn\research\harness'
foreach ($mode in 'off', 'on') {
    if ($mode -eq 'on') { & "$h\setup-server.ps1" -WithServerReborn } else { & "$h\setup-server.ps1" }
    & "$h\start-server.ps1" -Label "load-$mode" -Wait
    & "$h\start-client.ps1" -Label "load-$mode" -Spot prison -DurationSec 1800 -Quit -WaitArrived
    & "$h\spawn-zombies.ps1" -Spot prison -Count 300
    & "$h\sampler.ps1" -Label "load-$mode" -DurationSec 1800
    & "$h\stop-client.ps1"; & "$h\stop-server.ps1"
}
$r = "$env:TEMP\sgtest\runs"
& "$h\summarize.ps1" -Off "$r\load-off\samples.csv" -On "$r\load-on\samples.csv" -SkipFirstSec 60 `
    -Out '.trellis\tasks\10-26-server-reborn\research\load-test-table.md'
```

Zombies of earlier runs stay in the save: before spawning, clear the spot with
`& "$h\rcon.ps1" 'removezombies -x 7560 -y 11815 -z 0 -radius 150'`, so both runs start alike. The AC7 runs of
2026-10-24 (`../load-test.md`) also read the server's heap with `jcmd <pid> GC.heap_info` every 5 s (see Gaps).

More zombies: `spawn-zombies.ps1 -Count 300 -Repeat 3 -IntervalSec 120` (from a second shell while the sampler runs).
The driver's progress (`progress cycles=.. taken=.. moved=.. eats=.. zombies=..`, every 60 s) is in
`~/Zomboid/Lua/sgtest_state.txt` during the run and in the run folder after `stop-client.ps1`; compare the two runs'
`done` lines to confirm they did the same work.

What the columns mean (details in `Get-Help "$h\sampler.ps1" -Full`):

- `net_out_Bps` / `net_in_Bps`: deltas of RakNet's running byte totals summed over connected peers
  (`total-actual-bytes-sent/-received`): everything on the wire, Server Reborn's packets and resends included.
- `net_game_sent` / `net_game_recv`: bytes of packets with a known `PacketType` in the last 1 s period. Server Reborn's own
  packets (id outside `PacketTypes`) are not in them: `NetworkStatistic.addOutcomePacket` skips unknown ids.
- `srv_heap_used_mb`: `memory-used` of `stats performance`; `srv_update_*_ms`: the main loop's update period.
- `sg_line`: the newest server console line with `[Dont39ServerReborn]` and `key=value` pairs. `summarize.ps1` tabulates
  every key of the on run (first, last, max, rate) and computes the mod's share of the wire output from the key named
  by `-OwnBytesKey` (default `bytesOut`, a running byte counter). So the mod's summary line must carry its counters,
  state size, own byte counters and end-of-tick percentiles as `key=value` with plain numbers, and be logged often
  enough during a load run to give several samples (see Gaps).
- Optional: `start-server.ps1 -PrometheusPort 9108` plus `sampler.ps1 -PrometheusPort 9108` adds the game's own
  Prometheus exporter (`StatisticManager.init`, `-DprometheusPort`): JVM heap and cumulative `packet_send` /
  `packet_receive` byte sums over all packet types. Not tried in game.

## Other acceptance runs

The same scripts serve AC1-AC6, AC8 and AC9: `start-client.ps1 -NoLoop` puts the client at the spot without the
loop (play by hand, or with Server Reborn's debug switch); `rcon.ps1` runs any console command. AC8's vanilla client on
a modded server: remove `~/Zomboid/mods/Dont39ServerReborn` (setup copies it back); its modded client on a vanilla
server: `setup-server.ps1` without `-WithServerReborn`. AC11 drops the mod's transfer broadcasts on the client with
`-ClientJvmArgs '-Dserverreborn.debugDropModule=VacMod13.AddItem'` (a module, or `module.command`; every one, or every
n-th with `-Dserverreborn.debugDropDelta=n`, which also drops every n-th vanilla in-scope update); the driver shows each
container in the loot window before it takes from it, so open checks run too (`shown=` / `notShown=` in its lines).
The window lists only containers next to the player, so where the walk there fails (the prison's fences), the driver
teleports to a free square next to the container (`/teleportto`, counted as `teleports=`).

## Driver tests

`start-client.ps1 -Test <name>` (or `run-scenario.ps1 -ClientArgs @{ Test = '<name>' }`) runs one scripted check at
the spot instead of the loot loop, then finishes (and quits with `-Quit`). The server half,
`SGTestDriver/42/media/lua/server/SGTestDriver_Server.lua`, changes a container on the server on the driver's client
command (`SGTest`) without any packet to the client, as a lost or never-sent update leaves it; it prints
`[SGTEST] server: ...` and answers with `SGTest.result`, which the driver writes as `server ...` lines.

| Test | What it does | Expect |
|---|---|---|
| `equipRemove` (AC5) | Equips a carried item in the primary hand (`ISEquipWeaponAction`; takes one from a container first if nothing is carried) and picks a worn clothing item; the server removes both from the inventory (the worn one also from its worn list; its hands stay, then `clearHands`). The driver logs every change of `inInventory` / `inHand` / `worn` for 20 s | Server Reborn's snapshot removes both: worn and in hand until the `repaired ... removed [...]` line, then neither |
| `empty` (AC4) | Container A emptied with `removeAllItems` (as fire empties a burning object; Server Reborn's content hooks see it), container B (at least 4 squares away) by clearing its item list directly (nothing sees it); counts after 8 s, then opens B in the loot window and counts again | A empty on the client after the frame's grace; B unchanged until opened, empty after its open check |
| `burst` (AC9) | One item removed from one container, then 4 s later one from each of ten more | One notice for the first repair, then one notice for the ten (`repair notice shown for N repairs`) |
| `nested` (AC12) | A bag inside a world container: one already lying in a container nearby, else a carried loose bag or one the server half gives (`giveBag`, `Base.Bag_Schoolbag`), put into the nearest container with items. Then until `-DurationSec` ends: up to `-Take` light loose items from the inventory into the bag and back with `ISInventoryTransferUtil.newInventoryTransferAction`, which RPGInventory turns into its `ISRPGNestedTransfer` (logged as `via ...`; `notNested=` counts others). Each move logs where the items are on the client right after and 4 s later (`test nested in/out +0s/+4s: id:bag|inventory|nowhere`); every third round the bag is shown in the loot window (`setNewContainer`, Server Reborn's open check). Ends with `test nested result ...` | With the drop: per move `dropped incoming server command RPGInventoryNestedTransfer.moved`, then `repaired bag <id> in container ... of object ...` and `repaired inventory of player ...` lines, and the items where they belong at `+4s` (`atBagAfter=` equals `into=`). Without it: no `repaired` line, client `mismatches=0` |

AC12 (RPGInventory, Workshop 3800660445, is on `myserver1`'s list; Server Reborn on): the dropped run, then the
normal one, each five minutes at the prison spot.

```powershell
$h = '.trellis\tasks\archive\2026-10\10-26-server-reborn\research\harness'
& "$h\setup-server.ps1" -WithServerReborn
& "$h\run-scenario.ps1" -Label ac12-drop -DurationSec 300 `
    -ClientJvmArgs '-Dserverreborn.debugDropModule=RPGInventoryNestedTransfer.moved' -ClientArgs @{ Test = 'nested' }
& "$h\run-scenario.ps1" -Label ac12-normal -DurationSec 300 -ClientArgs @{ Test = 'nested' }
$r = "$env:TEMP\sgtest\runs"
Select-String -Path "$r\ac12-*\sgtest_state.txt" -Pattern 'test nested (result|in \+4s)'
Select-String -Path "$r\ac12-*\*-console.txt" -Pattern 'Dont39ServerReborn.*(repaired|could not|scope=|dropped)'
```

`-LogTransfers` logs each Multiplayer Weight Limit Breaker `AddItem` whose target container holds another instance of
its item (the game's `Error, container already has id`), with where the client's own copy is (`transfer: ...` lines).
`-NoZombieBuddy` launches the client without ZombieBuddy's agent: every mod's Lua loads, no Java mod runs (AC8's
vanilla client).

`-VanillaTransfers` puts back the game's transaction functions that Multiplayer Weight Limit Breaker replaces (it
keeps them in `VacMod13.raw`), so the loop's transfers go through the game's own container packets, which
`-Dserverreborn.debugDropDelta=n` can drop (AC1). Wrappers other mods put around those functions after it are dropped too.

## Cleanup

```powershell
& "$h\stop-client.ps1"; & "$h\stop-server.ps1"     # if still running
# quote the lines that prove each result in research/in-game.md first; the raw logs are not committed
& "$h\cleanup.ps1"
Get-Content ~/Zomboid/mods/default.txt | Select-String SGTestDriver   # expect nothing
```

`cleanup.ps1` removes every folder in the copy manifest (only under the cache's and the client's `mods`), restores
`default.txt`, `~/.zombie_buddy/config.json` and `mod_approvals.json`, `options.ini`, `latestSave.ini` and Loading
Progress's reports and checks them against the backup's hashes, moves `~/Zomboid/Lua` files and multiplayer saves
the tests added to `%TEMP%\sgtest\cleanup\<time>`, and deletes `%TEMP%\sgsrv`. The runs stay in `%TEMP%\sgtest\runs`.
Afterwards check the launcher has not rewritten `default.txt` since (it keeps `default.txt.pzlauncher.bak`).

## Gaps

- Run in game on 2026-10-24 (`../acceptance.md`, `../load-test.md`): setup, start/stop, the driver's join, loop and
  tests, `spawn-zombies.ps1`, `sampler.ps1`, `summarize.ps1`, `diff-errors.ps1`.
- `stats performance all` gets no reply over RCON on this server, so `sampler.ps1`'s heap and update-max columns stay
  empty; the AC7 runs read the server's heap with `jcmd <pid> GC.heap_info` instead.
- Zombies spawned by `createhorde2` stay in the save: clear the spot before a load run with
  `removezombies -x X -y Y -z Z -radius R` (without `-remove true`, which only marks the calling connection, and RCON
  has none).
- The tests and the loop empty the containers next to the prison spot over time; the loop takes from containers that
  still hold light items and widens its scan when none does.
- Drinking is not implemented; the driver eats (a quarter of a carried food item every 10 cycles).
- AC6 needs two clients; this machine runs one through these scripts (both would share `~/Zomboid`). The second
  client is a person, or a second install with its own `-cachedir` and mod copies (not scripted).
- Per-process network counters: Windows has none without ETW; the server's own statistics above replace them.
