# Acceptance runs (AC1-AC11)

- **Where**: this machine's local dedicated server `sgtest` (copy of `myserver1`: its 244 mods, Sync Guard right after
  Zabric when on, plus the test driver), one `-nosteam` client, driver at the Rosewood prison spot (harness
  `README.md`). Zabric 0.7.1 from its Workshop staging (`mod.info` `modversion=0.7.1`; the staged, server and client
  jars are byte-identical to `zabric/build/libs/Zabric.jar`, md5 `5e8881bc...`).
- **Runs**: `%TEMP%\sgtest\runs\<label>\` (both consoles, the driver's state file, server logs, sampler CSV). Client
  and server summaries every 30 s (`-Dsyncguard.summarySeconds=30`) unless noted.
- **Date**: 2026-10-24.
- **Sync Guard changes made during these runs**: see the last section.

## Results

| AC | Result | Run(s) |
|---|---|---|
| AC1 dropped vanilla delta detected and repaired | pass (world container and player inventory) | `ac1-vanilla2` |
| AC2 no field update lost after its add under loss | not done | - |
| AC3 rejected client change undone | not done; the AC's example is not rejected on B42 | - |
| AC4 container emptied on the server corrected on the client | pass (frame path and open-check path) | `ac4-empty1` |
| AC5 held and worn items removed by a snapshot | pass | `ac5-equip2` (`ac5-equip1`: worn only) |
| AC6 two clients, 30 min, no false mismatch | not done | - |
| AC7 load, off vs on | pass in steady play (end-of-tick p99 per 10-s window at most 512 us in 59 of 61, the bucket bound 1.024 ms in 2); the join's two windows exceed 1 ms; no measurable CPU, heap or wire difference; mod 0.16% of wire output; 1 snapshot (budget 8/tick) | `load-off4`, `load-on4` (`load-test.md`) |
| AC8 vanilla client / modded server, modded client / vanilla server | pass with one finding (no "inactive" line, see below) | `ac8-vanilla-on`, `ac8-vanilla-off`, `ac8-client1` |
| AC9 notice, log lines both sides, counters; ten losses one notice | pass (after adding the server's repair line) | `ac11-add1`, `ac9-burst2` |
| AC10 build, mod folder, smoke on the full list, no new errors | build and activation pass; not clean: one vanilla error signature only with Sync Guard active, open (see below) | `compat-off2`, `compat-on2` |
| AC11 dropped mod broadcast detected and repaired; no mismatch after grace otherwise | pass | `ac11-add1`, `ac11-remove1`, `compat-on2` |

## AC10: build and compatibility

- Build: `JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew :sync-guard:build :sync-guard:assembleMod` passes (unit tests
  included); `sync-guard/build/mod/Dont39SyncGuard/42/` holds `mod.info`, `media/java/Dont39SyncGuard.jar`, the
  open-check Lua and the EN/CN translations.
- `compat-off2` (Mods= without Sync Guard) and `compat-on2` (with), 300 s each, same driver loop, existing world and
  character.
- Server `compat-on2`: `[Zabric] access widener: ok (3 mods, 38 directives)`, `[Dont39AllInOne] access widener: ok (11
  directives)`, `[Dont39SyncGuard] access widener: ok (23 directives)`, `active on a dedicated server: joining clients
  that run it are checked`, `admin (connection 0): checking its sync (Sync Guard 0.1.0 on the client)`. Last summary:
  `checks=89 mismatches=2 frames=41 entries=83 changed=80 snapshotsSent=2 bytesOut=3734 tickNsP99=16000`; both
  snapshots answered open checks of containers with a transfer in flight (known, `digest-tuning.md`).
- Client `compat-on2`: `ready on a client`, `active: the server checks this client's containers and items`.
- `diff-errors.ps1`, server: 0 signatures only with Sync Guard, 0 more often. Client: 1 signature only with Sync Guard,
  `ERROR: General ... at ItemContainer.AddItem > Error, container already has id` (x1), see "Open: duplicate item id"
  below.
- The client error `[Zabric] Dont39FasterLoading: hooks stopped after an error while running DepthHooks.initBegins`
  (`IllegalStateException: a fix that needs the access widener ran before the mod's main entrypoint reported it`) is in
  both runs: pre-existing, not Sync Guard's.
- Every other run below was also diffed against `compat-off2` (both sides): no signature only with Sync Guard, except
  the duplicate-id line and the two world-load lines noted under "Other observations".

### Open: duplicate item id on the client

`Error, container already has id` is the game's guard in `ItemContainer.AddItem(InventoryItem)` (no stack trace). Counted
in every run's client console:

| Runs | Sync Guard on the client | Items the driver took | Lines |
|---|---|---|---|
| `compat-off2`, `load-off3`, `ac8-vanilla-on`, `ac8-vanilla-off` (current driver) | inactive | 142 | 0 |
| `compat-off`, `compat-on`, `func-on1`, `func-d7a` (older driver, no teleport) | inactive | 662 | 0 |
| `func-on2` ... `tune5` (server debug switches on) | active | 649 | 0 |
| `tune-final600`, `compat-on2`, `load-on3` (server without debug switches) | active | 195 | 4 (2, 1, 1) |

No Sync Guard line is near any of them (no repair, snapshot, drop or mismatch within 30 s), and the summaries after
them show `mismatches=0`, so no divergence stayed. The pattern points at Sync Guard being active (p about 3% against
the comparable inactive runs) but its mechanism is not found: the client side only reads state between the game's
packets, and the transfers on this list are the mod's Lua `AddItem` broadcasts (`modded-transfer-path.md`), whose
client handler adds the client's own instance, or a new one from the broadcast when its transaction is gone.
`-LogTransfers` (driver) logs the `VacMod13.AddItem` that meets another instance of its item; the diagnostic run
`diag-dupid1` (Sync Guard on, that switch) was stopped early at the user's request with no occurrence, so it gave
nothing.

**Status: open, not resolved.** Best hypothesis: a timing-dependent duplicate add in the mod's own transfer flow that
Sync Guard's presence makes more likely (it appeared only once the server ran without its debug switches, i.e. at
normal speed), e.g. a client-side add of an item the client already holds (a broadcast `AddItem` whose transaction
entry is gone, so the handler adds a new instance from the broadcast). Not confirmed: no stack trace, and no Sync Guard
call that adds items (only `Reconcile`, logged) ran near any occurrence. No lasting divergence followed (later
summaries `mismatches=0`). Next step: a longer run with `-LogTransfers` and Sync Guard on until one occurs.

A second vanilla line with the same pattern: `ERROR: Multiplayer ... at ContainerID.findObject > sendItemsToContainer:
can't find inventory container with id=...` (a bag the client does not have), once each in `func-on2`, `tune2` and
`load-on4` (all Sync Guard active), in no inactive run, with no Sync Guard activity near it. Also open.

## AC11 and AC9: the mod's transfer broadcasts dropped

`ac11-add1`: client `-Dsyncguard.debugDropModule=VacMod13.AddItem` (every one), 300 s loop (Sync Guard build before
the server repair line was added).

- Drop to repair: `dropped incoming server command VacMod13.AddItem (1)`, `(2)`, `(3)` at `st:93,004,375` -
  `93,004,567`, then `repaired inventory of player 0 from the server's state: added [1459836861, 2138780346,
  212177822]` at `st:93,006,383` (about 1.8 s: the 1.5-s grace of change-recorded entries, then one snapshot).
- Last client summary: `frames=32 entries=63 checks=33 mismatches=15 requested=15 snapshotsApplied=16 repaired=15
  unrepairable=0 pendingUnresolved=0 notices=15 droppedDeltas=30 deferred=60 stuckItems=0`. 30 dropped broadcasts, 15
  repairs (one per cycle: the items of a take arrive in one inventory snapshot), no unrepairable item. Each cycle took
  longer than the notice window, so each repair got its own notice.
- Server: `scope=connection ... checks=101 mismatches=1 frames=32 entries=63 changed=60 requested=15 refused=0
  snapshotsSent=16 bytesOut=174866`. **No per-repair line on the server** with this build: R9/AC9's "log line on both
  sides" was missing. Fixed (see the last section); later runs show it.
- Notice on screen: screenshot of the game window during the run,
  `C:\Users\dontc\AppData\Local\Temp\claude\E--Dev-Project-pz-dont39-pz-mods\72453824-9469-44c0-8173-78a1f8444250\scratchpad\shots-ac11\ac11-153611.484.png`:
  green halo text `已修复同步 (1)` above the player (`UI_Dont39SyncGuard_Repaired`, CN).
- No error signature only with Sync Guard, on either side, against `compat-off2`.

`ac11-remove1`: `-Dsyncguard.debugDropModule=VacMod13.RemoveItem`, 300 s: 34 dropped, client `mismatches=0
snapshotsApplied=0 repaired=0`. As expected on this list: for the player's own transfer the mod's client `AddItem`
handler adds the client's own instance to the target, and `ItemContainer.AddItem` takes it out of its old container
first (`javap`: `AddItem(InventoryItem)` calls `Remove` on `item.container`), so a lost `RemoveItem` leaves no
difference. It would for another player watching the source container (one client here). Together with
`compat-on2` (frame entries: no mismatch) this is "normal transfers cause no mismatch after the grace period".

`ac9-burst2` (`-Test burst`): the server half of the driver removed one item from one prison container, then 4 s later
one item from each of ten more, each with `ItemContainer.Remove` and no packet (Sync Guard's content hooks record
it).

- Client: `repaired container 0 of object 1 at 7570,11830,0 ...` and `repair notice shown for 1 repairs` at
  `st:96,201,641`; ten `repaired container ...` lines at `st:96,207,226` - `96,207,341`; `repair notice shown for 10
  repairs` at `st:96,211,645` (the end of the 10-s window). Summary `mismatches=11 requested=11 snapshotsApplied=11
  repaired=11 unrepairable=0 notices=1` (taken before the second notice).
- Server: eleven `admin: sent the server's state of container ... (present), as ...` lines, ten of them in one tick
  pair (8 per tick budget, then 2).
- AC9 asks for ten *injected losses*: here they are ten server-side changes the client never heard of, which is what
  a lost update leaves. With `debugDropModule` every lost broadcast of one take is repaired by a single inventory
  snapshot, so the count would stay at the number of containers, not of packets.

## AC1: dropped vanilla container updates

`ac1-vanilla2`: driver `-VanillaTransfers` (puts back the game's transaction functions that Multiplayer Weight Limit
Breaker replaces, so transfers use `ItemTransaction` and the game's container packets), client
`-Dsyncguard.debugDropDelta=3` (every third in-scope update), 300 s.

- World container: `dropped incoming RemoveInventoryItemFromContainer (update 3) for items [299983246] in container 0
  of object 2 at 7576,11835,0` (`st:97,142,468`), then `repaired container 0 of object 2 at 7576,11835,0 from the
  server's state: removed [299983246]` (`st:97,145,861`). Again for update 9 (`2000345493`).
- Player inventory: `dropped incoming AddInventoryItemToContainer (update 6) for inventory of player 0`
  (`st:97,143,570`), then `repaired inventory of player 0 from the server's state: added [1372066487]`
  (`st:97,145,864`). Again for update 12.
- Client `mismatches=4 requested=4 snapshotsApplied=4 repaired=4 unrepairable=0 droppedDeltas=4 stuckItems=0`;
  server `snapshotsSent=4 refused=0`. No new error signature.
- Limits: transfers through the restored vanilla path were slow on this list (6 items taken in 16 cycles); the
  driver ate nothing (no edible item carried), so no `ItemStats`/`SyncItemFields` delta was dropped. Item-field
  deltas were not covered in game.

## AC4: container emptied on the server

`ac4-empty1` (`-Test empty`): the server half emptied container A (`bin` at 7566,11830, 4 items) with
`removeAllItems`, the call fire uses on a burning object (`server-change-flow.md`; real fire also removes the
container itself, so it was not started), and container B (`freezer` at 7576,11833, 2 items) by clearing its item
list directly, which no `ItemContainer` method and so no content hook sees. Neither sends a packet.

- A, frame path: `repaired container 0 of object 2 at 7566,11830,0 from the server's state: removed [773473580,
  1868381991, 202429324, 837264215]`; server `sent the server's state of container 0 ... as a sync frame entry
  differed on the client`. Driver after 8 s: `A ... items=0`.
- B, open-check path: after 8 s still `B ... items=2` on the client (no frame covered it); the driver then opened B
  in the loot window: server `sent the server's state of container 1 of object 2 at 7576,11833,0 (present), as the
  container it opened differed on the client` (wording of that build), client `repaired container 1 of object 2 at
  7576,11833,0 ...: removed [751238775, 1901487630]`; 5 s after opening `B ... items=0`.
- No new error signature.

## AC5: held and worn items

`ac5-equip2` (`-Test equipRemove`): the driver equipped `Base.Crisps` (1439958483) in the primary hand
(`ISEquipWeaponAction`) and picked the worn `Base.Tshirt_McCoys` (732440150); the server half removed both from the
inventory (the shirt also from the server's worn list), no packet.

- The server's own hand check took the item out of the server's hand and sent `Equip` to the owner within 100 ms
  (`ac5-equip1`: `inHand=false` at +101 ms while still in the inventory). The driver puts it back into the hand on the
  client only, as if that `Equip` were lost: `+104ms: the game took the item out of the hand; back in the hand on this
  client only`.
- `+204ms: held 1439958483: inInventory=false inHand=false; worn 732440150: inInventory=false worn=false`, with the
  client's `repaired inventory of player 0 from the server's state: removed [732440150, 1439958483]` and the server's
  `sent the server's state of inventory of player 0 (present)` (asked through the client's check of the item entries
  the server reported gone). Both were held/worn until that snapshot: the shirt through Reconcile's worn-item path
  (`WornItems.remove`, `onWornItemsChanged`, `OnClothingUpdated`), the crisps through `removeItemWithID`'s hand
  clearing.
- `ac5-equip1` (before the re-hold): the worn `Base.Belt2` stayed worn until the repair 2.1 s later and was taken off
  by it; the held `Base.WaterBottle` had left the hand by the game's `Equip` first.
- No new error signature on either side in either run; the driver's `clearHands` afterwards resets the server's hands.

## AC8: activation across mixed installs

- **Vanilla client, modded server** (`ac8-vanilla-on`): a real vanilla client cannot join a server whose list has
  Sync Guard (the client must have the server's mods, and a Java mod runs only with ZombieBuddy), so the client was
  launched without ZombieBuddy's agent (`start-client.ps1 -NoZombieBuddy`): every mod's Lua loads, no Java mod runs.
  It joined and looted (8 cycles, 22 taken). Server: never `checking its sync`; every summary `connections=0 frames=0
  entries=0 snapshotsSent=0 bytesOut=0`: no Sync Guard packet went out. Against the same client on the server
  without Sync Guard (`ac8-vanilla-off`): client errors only with Sync Guard are four
  `NoSuchFileException ... Dont39SyncGuard\{42,common}\media\{AnimSets,actiongroups}` (the game's anim-set scan, which
  logs the same for each of the other mods without those folders, 922 vs 926 lines); server: world-load
  `map_meta.bin` lines (see "Other observations").
- **Modded client, vanilla server** (`ac8-client1`): Sync Guard enabled in the client's own `default.txt`, server
  without it. The client loaded the jar at the main menu (`[Dont39SyncGuard] ready on a client`), joined and looted;
  no Sync Guard line after that, server 0 Sync Guard lines, no error signature only in this run (against
  `compat-off2`). **No "inactive" line**: on a join the client plays with the server's mod list, and Zabric closes
  the hooks of a mod that a loader pass leaves out (`HookRuntime.passProcessed` -> `ModState.leavePass`, no log line),
  so Sync Guard's `sendPlayerConnect` hook, which logs `join to ...: inactive, as ...`, never runs. Its "inactive"
  line appears only when the server lists Sync Guard but offers no or another protocol. Making this visible needs a
  Zabric line when a mod's hooks close for a pass (not changed here).

## AC2, AC3, AC6: not done

- AC2 (field updates not lost after their add under loss): needs packet loss between client and server; no tool may be
  downloaded and none is on this machine. A user-space UDP proxy on 127.0.0.1 would have to cover both the game port
  and the UDPPort the server announces (`quality-guidelines.md`, in-game verification 6).
- AC3 (a rejected client change undone): the AC's example is not a rejected change on B42.
  `RemoveInventoryItemFromContainerPacket` requires only `Capability.LoginOnServer` (`@PacketSetting`, `javap -v`),
  and its `processServer` removes the item and relays it, so a non-admin's Lua `sendRemoveItemFromContainer` is
  accepted. The in-scope packet that needs more is `SyncItemDelete` (`Capability.EditItem`), which client Lua has no
  function to send. The server records a client's in-scope packet whether the game accepted it or not (`received` at
  `onServerPacket` FINALLY), but no rejected case was produced in game.
- AC6 (two clients, 30 min): the harness runs one client (`~/Zomboid` is shared); a second needs its own `-cachedir`
  with a second 14 GB mod copy, its own driver config, and a second account. Not done. Single-client normal play
  without injected losses: `compat-on2` and `load-on4` frames (see `load-test.md`) show no mismatch after the grace;
  the open checks' known in-flight case is in `digest-tuning.md`.

## Other observations

- World-load errors `invalid room metaID ... while reading map_meta.bin` are in every run, Sync Guard on or off (4
  lines). Two runs had more (`ac9-burst2` 10, the first start after a server this session had to kill during its
  start; `ac8-vanilla-on` 25), and the next run was back to 4. They are logged at `f:0`, before any connection.
- `load-off`, `load-off2`, `load-off3`, `load-on3`: discarded or superseded load runs (driver took nothing at the
  emptied first containers; `removezombies -remove true` removes nothing over RCON, so zombies piled up across runs);
  see `load-test.md`.

## Sync Guard changes made during these runs

Both found by AC9's "a log line on both sides" (`ac11-add1`: the server logged no repair):

- `server/Snapshots.java`: one line per snapshot sent, e.g. `admin: sent the server's state of container 0 of object
  2 at 7566,11843,0 (present), as it differed from a sync frame entry on the client` / `as the client's own check of
  it differed` (an `OpenCheck`: an opened container, or item entries the server reported gone). `server/Peer.java`:
  `Job` carries whether it came from an `OpenCheck`; `ServerSync` sets it.
- `client/ClientSync.java`: `repair notice shown for N repairs` when the notice shows, so the notice's count is in the
  log (AC9's "one notice with the count").

Rebuilt with `:sync-guard:build :sync-guard:assembleMod` (passes). Runs from `compat-on2` on use these lines
(`compat-on2` to `ac1-vanilla2` with the first wording "as <the container it opened | a sync frame entry> differed on
the client").
