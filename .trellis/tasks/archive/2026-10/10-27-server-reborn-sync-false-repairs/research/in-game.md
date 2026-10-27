# In-game check, 2026-10-27

Test instance `myserver2` on the Linux test server (`-nosteam`, own RCON port 48015, `-Dserverreborn.debugFieldDiff=true`;
mods ZombieBuddy, Zabric 0.7.2, ZabricKotlin 0.8.0, Server Reborn 0.2.0 (this branch, uncommitted), SGTestDriver,
Escape from Kentucky `ModernFirearmsSystem`, same Workshop copy as the client's). The Windows client `-nosteam`, driven by
`harness/` (a copy of the archived Server Reborn harness with the new driver test `falseRepairs`), at Rosewood prison.
myserver2's ini, mods, ZombieBuddy config, save, db and Lua were backed up first (`backups-2026-10-27-falserepairs/`
there) and restored afterwards; the client's files were restored by `cleanup.ps1` (0 differences). Lines of each run in
`in-game-2026-10-27/`.

`falseRepairs`: the server gives an apple, a sugar packet and a canned chili, gives the apple endurance, thirst and
hunger, and syncs the three in that order through ItemStats; then a bandage on the right foot (god mode off) and 60 s of
samples of the model items; then an M7_cat equipped, and a server change to the inventory so its digest is compared;
last, a client-only mod data key on a pencil and another server change, as the control.

## Results: pass

| Run | Client | Seen |
|---|---|---|
| fr1 | 0.2.0, first join (new character) | Server Reborn inactive on this join (the known first-join gap); food values equal to the server's |
| fr2 | 0.2.0, second join, active | food: sugar packet and chili equal to the server's, no `food{...}` diff; bandage: `Base.Bandage_RightFoot` items kept the same ids for 60 s, no repair; EFK: the 8 `AWCWF_*` keys and `MagzineTypeNow=Base.68Clip` on the gun, no diff after the server change; control: `modData{SGTEST_clientOnly c=1 s=-}` found at the next comparison. Server: `checks=... mismatches=0 ... requested=1 snapshotsSent=1` (the control only) |
| fr3-vanilla | no Java mods (`-NoZombieBuddy`): the bug as shipped | sugar packet `end=0.1000 thirst=-0.2000` (the apple's), chili `end=0.1 hung=-0.01 thirst=-0.2 base=-0.01` (the sugar packet's) against the server's 0 |
| fr4-drop | 0.2.0, `-Dserverreborn.debugDropDelta=3`, loot loop 4 min | every dropped update repaired (19 `repaired` lines, containers and the inventory); no repair removed a bandage model. Server: `requested=19 snapshotsSent=19` |

No error, `disabled` or `hooks stopped` line from Server Reborn, Zabric or ZombieBuddy on either side.

Not covered in game: wound models (same filter as the bandage model, made by the same update), and the server applying
a client's ItemStats packet (`processServer`).

Seen in passing: the bandage update shows two `Base.Bandage_RightFoot` items with different ids, both stable.
