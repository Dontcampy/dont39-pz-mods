# In-game checks on 42.22, 2026-11-11 and 2026-11-12

Build: Server Reborn 0.3.0 at `e586fa3` (runs a-f), at `858be8c` (run g, which only adds a debug line) and with the two
fixes of PRD D5 (run h, the build that is staged), Zabric 0.7.3, ZabricKotlin 0.8.0, ZombieBuddy 2.3.4, game 42.22.2
(`3d39148846`). Driver: this task's `harness/` (`port4222`, added for these runs). All runs under the shared test
lock; the client's files were restored by `cleanup.ps1` (0 differences) and the Linux instance from its backup
(`myserver2_Data/backups-2026-11-11-sr-42.22`).

## Results

| # | Run | Result |
|---|---|---|
| a | `:server-reborn:smokeTest` (client, Steam) | pass: `0.3.0 loaded`, `access widener: ok (47 directives)`, `[Zabric] Dont39ServerReborn: hooks ready (17 hook classes)`, `ready on a client` |
| a | Local dedicated server `srmin`, `-nosteam` | pass: `hooks ready (17 hook classes)`, `active on a dedicated server`, `tick rate off: sandbox option ... is 10` |
| b | Local join, existing character, `port4222` | pass, see below; the player's online id was 0 |
| d | Linux `myserver2`, `-nosteam`, `debugFieldDiff` on both sides, `debugTouches` on the server, one fake client joined first; `port4222` | pass for bags and the pistol; the map step found the open-window problem below |
| e | Linux, tick rate 20, client `-Dserverreborn.debugDropDelta=5`, loot loop 240 s | pass: 12 dropped updates, 12 repairs |
| f | Linux, sandbox option 20 | pass: `tick rate on: 20 ticks per second (period 50 ms)`, `the server ticked 20.00 times per second in its first 10 s` |
| g | Linux, as d, a fresh copy of the save, `port4222more`, twice | pass for melee wear and for a map annotated in a bag; one kind of item stays different after a snapshot, see "Items that stay different" |
| h | With R8 and R9: client `smokeTest`; Linux as d: `port4222map`, `port4222`, the loot loop with `debugDropDelta=5` | pass: `hooks ready (18 hook classes)`, `access widener: ok (50 directives)` on both sides; see "With the two fixes" |

No `hooks stopped`, `disabled`, weave error or exception naming the mod, Zabric or ZombieBuddy in any console. No
`wrong player connection index` or `remote connection player` line of the game on either side.

The first join of each server created the character and was inactive (`inactive, as the game kept no
connection-details`), the first-join gap known since 0.1.0.

## F1: a player's containers by online id (run d, online id 4)

- Driver: `test port4222 onlineId=4 index=0`. Server: `admin (connection 1): checking its sync (Server Reborn 0.3.0
  on the client)`, touches `touch PlayerInventory inventory of player 4: recorded`, `touch InventoryContainer bag
  1782587366 of player 4: recorded`.
- The server took one pencil out of the outer bag and one out of the bag inside it, with no packet. Client:
  `repaired bag 1340372877 of player 4 from the server's state: removed [84653036]` (the bag in the bag) and
  `repaired bag 1782587366 of player 4 from the server's state: removed [712300710]`. Driver, 20 s later:
  `pencil in outer=false pencil in inner=false`.
- Run e: `repaired inventory of player 4 from the server's state: added [...]` five times, and seven repairs of world
  containers; no `could not repair`. Server: `checks=65 mismatches=0 ... requested=12 refused=0 snapshotsSent=12`.

## Weapon state (runs b and d)

A pistol loaded through the game's timed actions, three shots (`DoAttack` while aiming), the magazine ejected. The
driver compared both sides after each step: `gun loaded ... same: client cond=10 ammo=4 chambered=true clip=true
... server cond=10 ammo=4 chambered=true clip=true`, `gun fired ... same: ... ammo=1 ...`, `gun unloaded ... same:
... ammo=0 chambered=true clip=false`. No repair of the pistol or the magazine. `AMMO` stays in the digest.

Melee (run g): a hand axe swung at zombies, 40 swings in the first run. The client's values moved with each hit
(`melee after swing 37: cond=9 ... blood=0.580 sharp=1.000`, `after swing 39: cond=8 ... blood=0.660 sharp=0.900`),
and both sides agreed at the end: `melee ... same: client cond=8 ... blood=0.660 sharp=0.900 server cond=8 ...
blood=0.660 sharp=0.900`. No field diff line names the axe. `CONDITION`, `BLOOD` and `SHARPNESS` stay in the digest.

## Snapshots that changed nothing

Four times on the Linux server (none locally) the client's digest of its inventory or of a bag differed from a
frame's and the snapshot found nothing to change: `field diff: inventory of player 4 at the mismatch, against the
server's copy: no item differs`, no `repaired` line. Each came right after the client changed an item itself
(reloading; two map edits 3 ms apart; a map edit within the grace period of a container the server had just added
items to): the frame's digest was older than the client's own change. The mod's request and the field packets share
one ordered channel, so the snapshot always has the client's change.

## Map annotations (run d)

- After a symbol and a note, each sent with `syncItemFields`, the next comparison of the inventory matched: the
  server has the client's annotations and both sides hash the same bytes. `MAP` stays in the digest.
- The control, a symbol added without a sync: `field diff: inventory of player 4 at the mismatch, against the
  server's copy: 232768661 (Base.MuldraughMap): map{saved c=157 bytes #69410941d1fa7e44 s=115 bytes
  #f87ae147bfd46b0a}`, then `repaired inventory of player 4 from the server's state: changed [232768661]` and
  `after the snapshot, against the server's copy: no item differs`.
- Problem, fixed since (PRD D5, "With the two fixes"): a map window that is open while a snapshot of its container
  is applied stops working. The game's
  `SyncItemFieldsPacket.parse` clears the item's user annotations and loads the packet's without telling the window's
  symbols API, whose own list then stays empty: the driver read `symbols in the window=0`, and its next
  `addTexture` returned nothing (`attempted index: setRGBA of non-table: null`, the same call the game's own map tool
  makes). The item's data is right, and a reopened window shows it. It happens for every snapshot of the container
  that holds the map, also one that changes nothing (the map edits of the section above), and whatever the digest
  covers. It is the game's behaviour for any `SyncItemFields` of an open map; the mod makes it reachable.

- In a bag (run g): a map annotated in the inventory, moved into a schoolbag and annotated there, its window closed
  before each comparison. `map in a bag, in the inventory: symbols in the window=1 added=true`, `in the bag=true`,
  `in the bag: symbols in the window=2 added=true`, `opened again at the end: symbols in the window=2`; no repair of
  the map.

## With the two fixes (run h)

The map scenario with its window left open the whole time, online id 4:

- A snapshot of the inventory for another item's sake (a client-only mod data key on a pencil): `map after the
  pencil's repair: ... shirt wetness=42.0 (42 set on the client only)`, `window still open: symbols in the window=2`,
  `one more symbol, synced (added=true): symbols in the window=3`. The matching shirt kept a value `SyncItemFields`
  carries and the digest leaves out, and the window took the next annotation (R8).
- The map's own repair (a symbol never synced): `repaired inventory of player 4 from the server's state: changed
  [1275464061]`, then `after the map's repair, window still open (3: the unsynced symbol is gone): symbols in the
  window=3`, `one more symbol, synced (added=true): symbols in the window=4`, and after closing and opening it again
  `symbols in the window=4` (R9). Before the fixes the same step read 0 and the next annotation failed.
- `port4222` again: both bags repaired, the pistol's values equal at each step, no repair of it.
- The loot loop with every fifth update dropped: 15 dropped; the 12 for the player's inventory and the containers
  around it were all repaired (`requested=12 refused=1 snapshotsSent=11`, the refused one a bag that had left its
  container); the other 3 were additions to containers about 90 squares away at the join, on squares the client had
  not loaded, which the mod does not check (`objects the server checked were not found on this client within 10 s`).

The pencil's client-only key stayed (`still differ ... in fields a snapshot cannot set`): the server's item has no
mod data, so no payload carries a table to replace the client's. As in 0.2.0.

## Items that stay different (run g)

A zombie the driver killed left a corpse whose shirt the client reported once: `items [1511957258] in corpse 0 at
7599,11832,0 still differ from the server's after its snapshot, in fields a snapshot cannot set`. With the debug line
added at `858be8c` the two logs name the field: the client has `customColor.custom=true, customColor.abgr=-12961222`,
the server `customColor.custom=false`, every other value equal. The game's item save writes an item's colour when it
is not white, custom or not, and its load then marks it custom; a field packet only ever turns the flag on. So a
tinted item made on the server and never saved there differs from every client's copy until the server reloads it.
`InventoryItem`'s and `Clothing`'s colour code is the same in 42.21 and 42.22: not caused by the game update or by
this task. The mod handles it as before: one snapshot, one warning per item, then the item is compared as the server
has it. In the first run of g four more items in containers the client saw for the first time were reported the same
way (dish gloves, a pop bottle, two pens), before the debug line existed; their field was not identified.

## Not covered

- Default map annotations.
- The server at the live server's mod list; only the minimal list ran.
