# In-game check on 42.22, 2026-11-11

Game 42.22.2 `3d39148846` (client and the local dedicated server), ZombieBuddy 2.3.4, Zabric 0.7.3, ZabricKotlin 0.8.0
and Dont39 All-in-One 0.14.1 built from this branch. Everything ran under the sessions' game-test lock; the user's
`default.txt`, ZombieBuddy config and approvals, `options.ini`, `latestSave.ini` and Loading Progress reports were
backed up first (`~/Zomboid/test-backups/2026-11-11-aio-42-22`) and are byte-identical to the backup afterwards.

## smokeTest

`./gradlew :dont39-all-in-one:smokeTest`, three runs:

| Run | Mod list | Result |
|---|---|---|
| 1 | the user's list | failed, two causes outside this task (below) |
| 2 | Faster Loading out of `default.txt` | the same: it is loaded from ZombieBuddy's `preload_mods`, not from the list |
| 3 | Faster Loading out of the list and of `preload_mods`, the three Shadow Cat Tactics mods out of the list | passed |

Run 3:

```
version=42.22.2 3d39148846 classpath=1,agents=1 demo=false
[Zabric] access widener: ok (4 mods, 71 directives)
[ZabricKotlin] ready: Kotlin 2.4.21, kotlinx-coroutines 1.11.0, kotlinx-serialization 1.11.0; installed at game start
[Zabric] Dont39AllInOne: hooks ready (25 hook classes)
[Dont39AllInOne] 0.14.1 loaded
[Dont39AllInOne] access widener: ok (11 directives)
ime-keyboard-fix: ready / missing-anim-folders: ready / anim-extends-case: ready / tunnel: ready / mod-sync: ready /
network-status: ready / appearance-override: active / sctd-render-takeover: idle: ShadowCatTacticsDispatch is not enabled
```

No `hooks stopped`, no `disabled`, no `NoSuchMethodError` / `NoSuchFieldError` / `VerifyError` in the console.

The two causes of runs 1 and 2:

- The staged 42.21 build of Faster Loading: `[Zabric] Dont39FasterLoading: hooks stopped after an error while running
  ScriptHooks.stripComments ... game 42.22.2 3d39148846 ... is not 42.21 revision 4a0e9546ec`. `GameSmokeTest` fails on
  any mod's stopped hooks. The Faster Loading child fixes it.
- Shadow Cat Tactics: Dispatch changed on the Workshop on 2026-10-29: `sctd-render-takeover: disabled after an error
  while checking SCTD's code: HideItem_Server at media/lua/shared/LingLi_SCTD_Skins.lua:2695 is not the reviewed version
  (found length 4330, hash 1304344994, 1222336016)`. The feature turns itself off as designed. Not caused by the game
  update; the module is out of this task's scope (branch `feat/sctd-transmog-server-resnap-015452`).

So with the user's own mod list `smokeTest` does not pass yet: it needs the updated Faster Loading and a
`sctd-render-takeover` reviewed against the current SCTD.

## Multiplayer session

Local dedicated server (`pzServerDir`, 42.22.2, `-nosteam`, fresh save, `Map=Muldraugh, KY`, mods ZombieBuddy, Zabric,
ZabricKotlin, Dont39AllInOne, SGTestDriver) and one `-nosteam` client with `-Dappearanceoverride.debug=true`, driven
by [harness/](harness/README.md) at Rosewood prison. The server's first start registered Zabric's preload and was
restarted once.

Server: `[Dont39AllInOne] 0.14.1 loaded`, every feature `ready` or idle with its reason (`ime-keyboard-fix`,
`network-status`: a dedicated server), `missing-anim-folders: active`, `appearance-override sync: server part ready
(server)`. Client: every Java feature `ready` then `active` at the join, `mod-options-sidebar: active (2 sections)`,
`mod-sync: join to 127.0.0.1:16361: no mod sync, as the server sent no mod sync location`. No `disabled`, `hooks
stopped` or error line of All-in-One, Zabric or ZombieBuddy on either side in any run.

### Network Status (R1), run `aio1`

The driver switched the report on through `Dont39AllInOne_setNetworkTrafficReport(true)`, as the mod option does. At
the disconnect:

```
[Dont39AllInOne] network-status: received 1.62 MB of game packets in 1.7 min, by packet type:
  SentChunk                                      0.77 MB  47.3%      1040 packets
  AddInventoryItemToContainer                    0.27 MB  16.7%       932 packets
  RequestData                                    0.15 MB   9.2%       155 packets
  PlayerDamage                                   0.08 MB   4.8%        35 packets
  VehicleFullUpdate                              0.07 MB   4.6%        15 packets
  ...
  PlayerConnectedToChat                          0.00 MB   0.0%         1 packets
```

65 rows, every one a game packet type name. The tooltip's table (`Dont39AllInOne_getNetworkStatus().traffic`) during
the session: `seconds=80 bytes=1644116 types=63 top: SentChunk=805239B/1040 AddInventoryItemToContainer=283723B/932
RequestData=157538B/155 ...`.

Not seen: the "unknown packet type" row. No packet with an id outside the game's types arrived (Server Reborn, whose
packets are such, was not on the server); that case rests on `getById` returning null
([hook-review.md](hook-review.md)).

### Appearance override on a player, a mannequin and a corpse, runs `aio1`, `aio2`

Driver test `aio4222`: the server gives a `KnapsackSprayer` and a `Jacket_Padded`, the client wears both, the server
sets the sprayer hidden and the jacket's look to `Jacket_Black`; a second padded jacket with the same look goes onto
a mannequin the server places next to the player (the game's own transfer action); then the server kills the player.

| What | Seen on the client |
|---|---|
| Player | `set of admin, revision 2: {Base.Jacket_Padded=Look(type=Base.Jacket_Black, ...), Base.KnapsackSprayer=Hidden}`; `debug: model of admin: Base.KnapsackSprayer hidden, Base.Jacket_Padded as Base.Jacket_Black`, the same for `attached items of admin` and `textures of admin`. Gameplay values unchanged on both sides (`fluid=2.000/16.000`, `bite=10.0 scratch=20.0 ins=0.95`) |
| Mannequin | the jacket worn with `override=Base.Jacket_Black`; `debug: mannequin of items: nothing`, then `debug: mannequin of items: Base.Jacket_Padded as Base.Jacket_Black`. After a server restart and a rejoin (`aio2`) the same line again when the spot loaded, before that run dressed its own mannequin |
| Corpse | `test aio corpse client dead=true`; the body wears `Base.Jacket_Padded ... override=Base.Jacket_Black`; `debug: body atlas of items: Base.Jacket_Padded as Base.Jacket_Black`. After a server restart and a rejoin (`aio2`) the same line when the spot loaded |

The evidence is the debug line of each render consumer (what it was handed in the worn items' place), as in the
42.21 runs of `10-27-appearance-override`. No image was taken: `Core.TakeScreenshot()` called from Lua hung the client
(run `aio3`, killed).

A debug line names the consumer, not the object: it says that consumer was handed an overridden item. The only
dressed mannequins and the only body in view were the tests' own.

Not seen: the `chunk corpses` consumer, whose mannequin entry is one of the two changed hook targets. No `chunk
corpses of items` line came in any run, `aio2`'s `aioMannequinFar` included (the dressed mannequin seen from 9 and 18
squares for 12 s each).

- Corpses: the game sends a body through `FBORenderCorpses` only with its debug option `corpsesInChunkTexture`
  (`FBORenderDebugOptions`, read in `FBORenderCell`), which is off by default; otherwise the body atlas draws it, as
  seen.
- Mannequins: `IsoMannequin.render` calls `FBORenderCorpses` only while `FBORenderChunkManager.isCaching()`. Why no
  such call reached the hook in these runs is not known.

The class was woven (`[ZB] Transformed: zombie.iso.fboRenderChunk.FBORenderCorpses$BodyParams`), and the target was
read against the hook ([hook-review.md](hook-review.md)): static evidence only. There is no 42.21 run of this consumer
to compare with either: the earlier tasks did not cover corpses and mannequins in game.

Observed, the game's own behavior: right after the transfer the server's mannequin did not list the jacket as worn
(`mannequin wears 4` against the client's 5); after the server restart the rejoined client drew it worn.

## Linux test server on 42.22

The shared install `~/pz-only/servers` was updated in place to 42.22.2 (Steam build 26486687; the jar's sha256 equals
the Windows dedicated server's), on the user's word, with no server running there. Anonymous SteamCMD was refused the
installed build's manifests (`Failed to get manifest request code, 'Access Denied'`, app state 0x6), so the update ran
as an install over the existing folder: `appmanifest_380870.acf` set aside, then `app_update 380870 validate`. That
put the stock `ProjectZomboid64.json` back; the user's copy (ZombieBuddy's `-javaagent` line) was restored from
`~/pz-only/backups-2026-11-11-before-42.22`, which also holds the other launch files and the SteamCMD logs.

Test instance `myserver2` (`-nosteam`, game port 48261, UDP 48262, own RCON port 48015, fresh save, `Map=Muldraugh,
KY`; mods ZombieBuddy, Zabric, ZabricKotlin, Dont39AllInOne 0.14.1, SGTestDriver; sandbox: TCP tunnel 48310, KCP
tunnel 48311, mod sync 48320), started from `aio-start.sh` in tmux `pzaio`. Backed up first
(`backups-2026-11-11-aio-42.22/state.tgz`, 7429 entries) and restored after (7429 entries, `RCONPort=47015`, no
`Mods=` line, the start script and password file removed). The Windows client `-nosteam` through `harness/`
(`start-client.ps1 -ServerHost`), 90 s at Rosewood prison per run; its tunnel store, `default.txt`, ZombieBuddy
files, `options.ini` and Loading Progress reports equal their backup afterwards.

Server start: `[Dont39AllInOne] 0.14.1 loaded`, `access widener: ok (11 directives)`, `anim-extends-case: active`,
`missing-anim-folders: active`, then

```
tunnel: listening on TCP port 48310, relaying to UDP ports 48261 and 48262
tunnel: listening on KCP port 48311, relaying to UDP ports 48261 and 48262, interval 10 ms, fast resend after 2, congestion window off, window 1024, MTU 1350, FEC 10+3, flush 20 ms
mod-sync: manifest: 0 Workshop items, 0 files, 0.0 MB, built in 5 ms
mod-sync: serving the Workshop items on TCP port 48320
```

| Run | Client | Server |
|---|---|---|
| `lin-tcp`, store `tcp,48310,48311` | `tunnel: connecting to 192.168.50.249:48261 through the TCP tunnel on port 48310`; in game after 53 s; at the quit `tunnel to 192.168.50.249:48310 closed: the game closed the connection` | `tunnel from 192.168.50.192:6031 open as 127.0.0.1:41529`, `Connected new client`, 135 s later `closed: closed by the other end` |
| `lin-kcp`, store `kcp,48310,48311` | `tunnel: connecting to 192.168.50.249:48261 through the KCP tunnel on port 48311 (... FEC 10+3, flush 20 ms)`; in game after 42 s; `KCP tunnel ... closed: the game closed the connection (FEC rebuilt 0, could not rebuild 0; KCP resent 1 on timeout, 0 fast; dropped 0 invalid)` | `KCP tunnel from 192.168.50.192:57924 open as 127.0.0.1:50059`, `Connected new client`, 124 s later `closed: closed by the other end (FEC rebuilt 0, ...)` |

Mod sync, both runs: the client logged `mod-sync: join to 192.168.50.249:48261: the server offers its Workshop items
at http://192.168.50.249:48320/dont39-modsync/v1/`, so the location trailer is written and read on 42.22;
`manifest.json` there answered HTTP 200. The server has no Workshop items under `-nosteam`, so nothing was taken.

No `disabled`, `hooks stopped` or error line of All-in-One, Zabric or ZombieBuddy on either side; the server's errors
are the game's own (`IsoPropertyTypeNotFoundException: Property Name not found: WindowShape` and the like).

## Not verified in game

- `mod-sync` taking a Workshop item from the server: it needs a Steam-mode server and an item whose version differs
  from the player's, which means an upload by the user. Seen: the server part serving, the location reaching the
  client (above).
- `anim-extends-case`: `active` on the Linux server and on the client, that is, its hook ran without an error. No mod
  on the test list has an animation that extends a file under another case, so the fix itself was not seen.
- `tunnel` under loss, and the tunnel's dialog and list tag in the server list (the driver joins through
  `serverConnect`).
- `ime-keyboard-fix`: `active` with its hooks woven; typing with an IME needs a person.
- `mod-options-sidebar`: `active (2 sections)`; the options screen was not opened.
- `sctd-render-takeover`: idle without SCTD, disabled with the current SCTD (above). Nothing of it ran.
- The "unknown packet type" row, the `chunk corpses` consumer and an image of the drawn override (above).
- Another player's view of an override, single player, Steam mode, and the user's full mod list in a session.
