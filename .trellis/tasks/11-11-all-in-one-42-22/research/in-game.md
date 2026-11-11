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

## Not verified in game

- `tunnel` (a TCP or KCP join), `mod-sync` (a join that takes a Workshop item from the server) and
  `anim-extends-case` on a Linux server: they need the Linux test server, which is still on 42.21 and whose install is
  shared with the running live server `myserver`. It was not touched. On 42.22 these three were only seen `ready`
  and `active` on the Windows client and server; the tunnel refuses a server on the same machine by design.
- `ime-keyboard-fix`: `active` with its hooks woven; typing with an IME needs a person.
- `mod-options-sidebar`: `active (2 sections)`; the options screen was not opened.
- `sctd-render-takeover`: idle without SCTD, disabled with the current SCTD (above). Nothing of it ran.
- The "unknown packet type" row, the `chunk corpses` consumer and an image of the drawn override (above).
- Another player's view of an override, single player, and the user's full mod list in a session.
