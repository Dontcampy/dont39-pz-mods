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

Not covered: a real client at 20 Hz with Server Reborn (the tick rate's own AC6 was accepted on 2026-10-26 under
All-in-One), and the full `myserver` mod list.
