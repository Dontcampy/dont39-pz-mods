# Sync Guard test harness (not shipped)

Local dedicated server `sgtest` built from the user's `myserver1` config, one `-nosteam` client driven by the
`SGTestDriver` Lua mod, a sampler and the comparison scripts for AC10 (compatibility) and AC7 (load): step
12 of `../../implement.md`; all in-game tests run on this machine (`prd.md`).

PowerShell 7 on Windows. Run every script from the repo root as `& "$h\<script>.ps1"` after
`$h = '.trellis\tasks\10-24-mp-sync-loss-recovery\research\harness'`. `Get-Help "$h\<script>.ps1" -Full` shows each
script's parameters.

## Files

| File | What it does |
|---|---|
| `common.ps1` | Paths, ports, test spots, RCON client, helpers; dot-sourced by the others |
| `setup-server.ps1` | Server `sgtest` in the cache folder from `myserver1`, mod copies for server and client; `-WithSyncGuard`, `-DryRun` |
| `start-server.ps1` / `stop-server.ps1` | Start (`-Wait` for `SERVER STARTED`) and stop (RCON `quit`) the server; console and logs go to the run folder |
| `backup-client.ps1` | Backs up the user's client files before a series |
| `start-client.ps1` / `stop-client.ps1` | Launch the client; the driver joins and runs the scenario. Stop and collect its console |
| `SGTestDriver/` | Lua-only client mod: join, god mode, teleport, loot/move/eat loop, progress lines `[SGTEST]` |
| `spawn-zombies.ps1` | `createhorde2` at the test spot over RCON, repeatable |
| `rcon.ps1` | Any server console command over RCON, e.g. `& "$h\rcon.ps1" players` |
| `sampler.ps1` | One CSV row per 5 s: CPU, memory, server network and heap statistics, newest Sync Guard summary line |
| `summarize.ps1` | Off/on table of two sampler CSVs, for `research/load-test.md` |
| `diff-errors.ps1` | Error lines only the run with Sync Guard has (AC10) |
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

- Sync Guard built: `JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew :sync-guard:build :sync-guard:assembleMod`.
- Zabric, ZabricKotlin and Dont39AllInOne are taken from their Workshop staging (`~/Zomboid/Workshop/<id>/Contents/mods/<id>`),
  the copies the client's preload config (`~/.zombie_buddy/config.json`) already uses. To test current repo builds of
  those, run their `deployWorkshop` first. If `~/Zomboid/Workshop/Dont39SyncGuard` exists, its jar must equal the
  build's (`setup-server.ps1` stops otherwise).
- Every Workshop item of `myserver1` subscribed and downloaded (`setup-server.ps1 -DryRun` warns about missing ones).
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

## Sequence: smoke and compatibility (AC10)

The first start of a fresh server registers Zabric's preload in the server's own ZombieBuddy config and generates the
world, and the first join creates the character; a warm-up run takes those, so the two compared runs both load an
existing world with an existing character.

```powershell
$h = '.trellis\tasks\10-24-mp-sync-loss-recovery\research\harness'
& "$h\backup-client.ps1"
& "$h\setup-server.ps1" -DryRun             # check sources and size first
& "$h\setup-server.ps1"                     # Sync Guard off

# warm-up (restart once if the console asks for it after the preload registration)
& "$h\start-server.ps1" -Label warmup -Wait
& "$h\start-client.ps1" -Label warmup -NoLoop -DurationSec 60 -Quit
& "$h\stop-client.ps1"; & "$h\stop-server.ps1"

# without Sync Guard
& "$h\start-server.ps1" -Label compat-off -Wait
& "$h\start-client.ps1" -Label compat-off -DurationSec 300 -Quit
& "$h\stop-client.ps1"; & "$h\stop-server.ps1"

# with Sync Guard (only Mods= changes)
& "$h\setup-server.ps1" -WithSyncGuard
& "$h\start-server.ps1" -Label compat-on -Wait
& "$h\start-client.ps1" -Label compat-on -DurationSec 300 -Quit
& "$h\stop-client.ps1"; & "$h\stop-server.ps1"

$r = "$env:TEMP\sgtest\runs"
& "$h\diff-errors.ps1" -Without "$r\compat-off\server-console.txt" -With "$r\compat-on\server-console.txt"
& "$h\diff-errors.ps1" -Without "$r\compat-off\client-console.txt" -With "$r\compat-on\client-console.txt"
Select-String -Path "$r\compat-on\*-console.txt" -Pattern 'Dont39SyncGuard'   # ready and active on both sides
```

`stop-client.ps1` waits for the driver's own quit (`-Quit`), otherwise asks it to quit through `sgtest_stop.txt` and
kills the client after 90 s. `diff-errors.ps1` exits with 1 when the run with Sync Guard has an error signature the
other has not; check the "more often" list too.

## Sequence: load scenario (AC7)

Same script and duration with the mod off, then on; one client in god mode at the prison, many zombies, looting and
moving items. Run the warm-up above first if the server is fresh.

```powershell
$h = '.trellis\tasks\10-24-mp-sync-loss-recovery\research\harness'
foreach ($mode in 'off', 'on') {
    if ($mode -eq 'on') { & "$h\setup-server.ps1" -WithSyncGuard } else { & "$h\setup-server.ps1" }
    & "$h\start-server.ps1" -Label "load-$mode" -Wait
    & "$h\start-client.ps1" -Label "load-$mode" -Spot prison -DurationSec 1800 -Quit -WaitArrived
    & "$h\spawn-zombies.ps1" -Spot prison -Count 300
    & "$h\sampler.ps1" -Label "load-$mode" -DurationSec 1800
    & "$h\stop-client.ps1"; & "$h\stop-server.ps1"
}
$r = "$env:TEMP\sgtest\runs"
& "$h\summarize.ps1" -Off "$r\load-off\samples.csv" -On "$r\load-on\samples.csv" -SkipFirstSec 60 `
    -Out '.trellis\tasks\10-24-mp-sync-loss-recovery\research\load-test-table.md'
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
  (`total-actual-bytes-sent/-received`): everything on the wire, Sync Guard's packets and resends included.
- `net_game_sent` / `net_game_recv`: bytes of packets with a known `PacketType` in the last 1 s period. Sync Guard's own
  packets (id outside `PacketTypes`) are not in them: `NetworkStatistic.addOutcomePacket` skips unknown ids.
- `srv_heap_used_mb`: `memory-used` of `stats performance`; `srv_update_*_ms`: the main loop's update period.
- `sg_line`: the newest server console line with `[Dont39SyncGuard]` and `key=value` pairs. `summarize.ps1` tabulates
  every key of the on run (first, last, max, rate) and computes the mod's share of the wire output from the key named
  by `-OwnBytesKey` (default `bytesOut`, a running byte counter). So the mod's summary line must carry its counters,
  state size, own byte counters and end-of-tick percentiles as `key=value` with plain numbers, and be logged often
  enough during a load run to give several samples (see Gaps).
- Optional: `start-server.ps1 -PrometheusPort 9108` plus `sampler.ps1 -PrometheusPort 9108` adds the game's own
  Prometheus exporter (`StatisticManager.init`, `-DprometheusPort`): JVM heap and cumulative `packet_send` /
  `packet_receive` byte sums over all packet types. Not tried in game.

## Other acceptance runs

The same scripts serve AC1-AC6, AC8 and AC9: `start-client.ps1 -NoLoop` puts the client at the spot without the
loop (play by hand, or with Sync Guard's debug switch); `rcon.ps1` runs any console command. AC8's vanilla client on
a modded server: remove `~/Zomboid/mods/Dont39SyncGuard` (setup copies it back); its modded client on a vanilla
server: `setup-server.ps1` without `-WithSyncGuard`. AC11 drops the mod's transfer broadcasts on the client with
`-ClientJvmArgs '-Dsyncguard.debugDropModule=VacMod13.AddItem'` (a module, or `module.command`; every one, or every
n-th with `-Dsyncguard.debugDropDelta=n`, which also drops every n-th vanilla in-scope update); the driver shows each
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
| `equipRemove` (AC5) | Equips a carried item in the primary hand (`ISEquipWeaponAction`; takes one from a container first if nothing is carried) and picks a worn clothing item; the server removes both from the inventory (the worn one also from its worn list; its hands stay, then `clearHands`). The driver logs every change of `inInventory` / `inHand` / `worn` for 20 s | Sync Guard's snapshot removes both: worn and in hand until the `repaired ... removed [...]` line, then neither |
| `empty` (AC4) | Container A emptied with `removeAllItems` (as fire empties a burning object; Sync Guard's content hooks see it), container B (at least 4 squares away) by clearing its item list directly (nothing sees it); counts after 8 s, then opens B in the loot window and counts again | A empty on the client after the frame's grace; B unchanged until opened, empty after its open check |
| `burst` (AC9) | One item removed from one container, then 4 s later one from each of ten more | One notice for the first repair, then one notice for the ten (`repair notice shown for N repairs`) |

`-LogTransfers` logs each Multiplayer Weight Limit Breaker `AddItem` whose target container holds another instance of
its item (the game's `Error, container already has id`), with where the client's own copy is (`transfer: ...` lines).
`-NoZombieBuddy` launches the client without ZombieBuddy's agent: every mod's Lua loads, no Java mod runs (AC8's
vanilla client).

`-VanillaTransfers` puts back the game's transaction functions that Multiplayer Weight Limit Breaker replaces (it
keeps them in `VacMod13.raw`), so the loop's transfers go through the game's own container packets, which
`-Dsyncguard.debugDropDelta=n` can drop (AC1). Wrappers other mods put around those functions after it are dropped too.

## Cleanup

```powershell
& "$h\stop-client.ps1"; & "$h\stop-server.ps1"     # if still running
# copy what the task keeps (samples, consoles) from $env:TEMP\sgtest\runs into research/ first
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
