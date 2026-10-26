# Linux server test, 2026-10-26

Test instance `myserver2` on the Linux server (`-nosteam`, ZombieBuddy `policy=allow-all`; mods ZombieBuddy, Zabric
0.7.1, ZabricKotlin 0.7.0, Server Reborn 0.1.0 at `fba4e6f`; `PauseEmpty=false`). The live `myserver` was not
touched. Consoles in `~/pz-only/myserver2_Data/srtest-results/`; the instance's ini, sandbox file, mods, ZombieBuddy
config, save and db were backed up first and restored afterwards (diff clean).

## Results: pass

| Run | Expected | Seen |
|---|---|---|
| Option 20, 2 fake clients (`FakeClientManager`, the tick-rate task's scenario) for 90 s | the mod active, the rate on and measured, vanilla clients play | `active on a dedicated server`, `tick rate on: 20 ticks per second (period 50 ms)`, `the server ticked 20.00 times per second in its first 10 s`; both clients connected and simulated zombies; no line for them (clients without the mod, as Sync Guard's AC8 found); no error from the mod, Zabric or ZombieBuddy |
| Option 10 | the rate off | `tick rate off: sandbox option Dont39ServerReborn_ServerTickRate.TicksPerSecond is 10` |
| Option 20, a build whose expected limiter period is 99 ms | a mismatch turns the whole mod off and undoes it, the server runs on at 10 | `IllegalStateException: GameServer.main set the lock fps without building a 99 ms limiter first` → `disabled for this session after an error while setting the server's tick rate`; the undo raised nothing; no `hooks stopped`; `SERVER STARTED`, server kept running |

Errors in the consoles were the game's own (fluid container names, animation folders, basement and map meta), as
without the mod.

## A real client at 20 Hz

The Windows client, `-nosteam` and driven by the harness (`start-client.ps1 -ServerHost 192.168.50.249 -ServerPort
48261`, ZombieBuddy `allow-all` for the launch), joined `myserver2` at option 20 (mods as above plus `SGTestDriver`).
Consoles in `srtest-results/console-client20.log` and `%TEMP%\sgtest\runs\linux1`-`linux4`.

- First join (new character): not checked, the known first-join gap (`smoke-join.md`).
- Second join, existing character, 3 min of the driver's loop at Rosewood prison (14 cycles, 33 items taken, 28
  containers shown, about 860 zombies): server `checking its sync (Server Reborn 0.1.0 on the client)`, connection
  summary `checks=49 mismatches=0 frames=37 entries=91 snapshotsSent=0 bytesOut=2695`; client `active`, last summary
  `frames=33 checks=29 mismatches=0 repaired=0 openChecks=45 stuckItems=0`. No error from the mod, Zabric or
  ZombieBuddy on either side.
- `pendingUnresolved=22`: 22 object containers about 90 squares away that the join's first frames named before the
  client had loaded those squares; dropped after 10 s by design, none after the join.

### Incident: the control panel quit the test server

myserver2's ini carried the live server's RCON port 47015. The live `myserver` was stopped, so myserver2 bound 47015
and Zomboid Control Panel took it for `myserver`: a Workshop update (Nekomata Ridge) triggered its auto-restart, which
saved and quit myserver2 during the client's second join (the client then failed with `GameClient.connection` null)
and started the live `myserver` at 08:28 (server time). The retry ran with `RCONPort=48015` and its own password.
The live server was left running for the user to decide on.

Not covered: the full `myserver` mod list.
