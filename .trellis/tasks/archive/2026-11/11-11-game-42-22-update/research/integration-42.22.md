# Integration checks on 42.22, 2026-11-12

Branch `feat/game-42-22-update` at `835824c`: `main` (`d4ebb4a`) plus the four children's branches, merged without
conflict. One fix after the merges: the two branches each added an item 13 to the in-game checklist of
`quality-guidelines.md`; they are 13 to 15 now.

Game 42.22.2 `3d39148846` on the client, the local dedicated server and the Linux test server; ZombieBuddy 2.3.4.
Everything ran under the sessions' game-test lock. The user's `default.txt`, ZombieBuddy config, `options.ini` and
Loading Progress reports were backed up first and are identical to the backup afterwards; the Linux instance
`myserver2` was restored from `myserver2_Data/backups-2026-11-12-integration-42.22/state.tgz` (0 files missing, 0
extra).

## Results

| Check | Result |
|---|---|
| `./gradlew build assembleMod` | pass |
| `:faster-loading:smokeTest`, `:server-reborn:smokeTest` | pass with the user's mod list |
| `:dont39-all-in-one:smokeTest` | fails with the user's list on Shadow Cat Tactics (below); pass with its three mods out of the list |
| `:loading-progress:smokeTest` | fails with the user's list, which does not enable the mod (below); pass with it enabled |
| `:zb-kt:serverSmokeTest` | pass |
| All mods, client joins the Linux test server | pass, twice; one finding in the game itself (below) |
| `versionMin` | `42.22.0` in the four updated mods; `42.21.0` in Zabric, ZabricKotlin and ZB Skip Online Checks |

Every client launch, with all of the repository's mods loaded:

```
version=42.22.2 3d39148846 classpath=1,agents=1 demo=false
[Zabric] access widener: ok (4 mods, 97 directives)
[ZabricKotlin] ready: Kotlin 2.4.21, kotlinx-coroutines 1.11.0, kotlinx-serialization 1.11.0; installed at game start
[ZBSkipOnlineChecks] skipping ZombieBuddy 2.3.4's online checks, installed by agent start ...
[Zabric] Dont39LoadingProgress: hooks ready (53 hook classes)
[Zabric] Dont39FasterLoading: hooks ready (4 hook classes)
[Zabric] Dont39AllInOne: hooks ready (25 hook classes)
[Zabric] Dont39ServerReborn: hooks ready (18 hook classes)
[Dont39AllInOne] 0.14.1 loaded / [Dont39FasterLoading] 0.5.1 loaded / [Dont39LoadingProgress] 0.2.1 loaded /
[Dont39ServerReborn] 0.3.0 loaded
[Dont39FasterLoading] active: comment stripping on, texture compression on, texture bc7 on, depth map order on, lua folder walk on
```

No `hooks stopped`, `NoSuchMethodError`, `NoSuchFieldError`, `VerifyError` or `LinkageError` in any console. This is
also the in-game check of Zabric 0.7.3, ZabricKotlin 0.8.0 and ZB Skip Online Checks 0.1.0 (parent R3): nothing to
release for them.

## The two smoke tests that need a changed mod list

- All-in-One: `sctd-render-takeover: disabled after an error while checking SCTD's code: HideItem_Server at
  media/lua/shared/LingLi_SCTD_Skins.lua:2695 is not the reviewed version`. Shadow Cat Tactics: Dispatch changed on the
  Workshop on 2026-10-29; the feature turns itself off as designed and `smokeTest` fails on a disabled feature. Not
  caused by the game update; found by the All-in-One child too. With the three Shadow Cat Tactics mods out of the
  list: `sctd-render-takeover: idle: ShadowCatTacticsDispatch is not enabled`, pass.
- Loading Progress: `the access widener is not in effect: no access widener line`. The user's list does not name the
  mod; it is preloaded, so its hooks are ready, but its main entrypoint, which logs that line, runs only for a mod on
  the list. With `mod = Dont39LoadingProgress,` in the list: pass.

## Join on the Linux test server

Server `myserver2`, `-nosteam`, mods ZombieBuddy, Zabric, ZabricKotlin, Dont39ServerReborn, Dont39AllInOne and the
All-in-One child's `SGTestDriver`, `-Dserverreborn.debugFieldDiff=true`. Client `-nosteam` with real copies of all
seven mods of the repository in `~/Zomboid/mods`, driven by the same harness: a first join that created the character,
then a join with it and the loot loop at Rosewood prison for 240 s.

Server:

```
[Zabric] access widener: ok (2 mods, 60 directives)
[Zabric] Dont39AllInOne: hooks ready (25 hook classes)
[Zabric] Dont39ServerReborn: hooks ready (18 hook classes)
[Dont39ServerReborn] active on a dedicated server: joining clients that run it are checked
[Dont39AllInOne] tunnel: listening on TCP port 48300, relaying to UDP ports 48261 and 48262
[Dont39ServerReborn] admin (connection 0): checking its sync (Server Reborn 0.3.0 on the client)
[Dont39ServerReborn] scope=connection connection=0 stateEntries=17 checks=57 mismatches=0 ... requested=0 refused=0 snapshotsSent=0
```

Client, second join: every All-in-One feature `ready` then `active` (`sctd-render-takeover` idle, `mod-options-sidebar:
active (4 sections)`), `[Dont39ServerReborn] active: the server checks this client's containers and items`,
`[Dont39LoadingProgress] launch: done after 8.4 s at step 10 / 10`, `game start: done after 13.6 s at step 13 / 13
(Entering the world)`. Driver: `done cycles=16 taken=30 ... errors=0`. The first join was inactive for Server Reborn
(`inactive, as the game kept no connection-details`), the known first-join gap.

## Finding: the game sends an Equip packet for a zombie

The server logged `wrong player connection index (-1)` at `ConnectionPlayer.parse`, each followed by `The packet Equip
is not consistent: ... "Player": { "id" : -1 }`; the client logged `EquipPacket.set get invalid arguments` at the same
moments. 18 times in the 240 s with all mods.

- Cause, from the bytecode: `IsoGameCharacter.tryPerformDropHeldItem`, new in 42.22, sends `PacketType.Equip` with
  `this` when the character is local on a client. `EquipPacket.setData` accepts an `IsoPlayer` only. The callers
  (`dropHandItems` from `fallenOnKnees`, `handleLandingImpact` and `ClimbOverFenceState`) also run for zombies the
  client owns, and the spot had 700 to 750 zombies at the prison's fences.
- Control: the same join and loop with the client's Java mods off (`start-client.ps1 -NoZombieBuddy`): 4 more on both
  sides in 240 s. So it is the game, not a mod of this repository. The count differs between the runs (18 and 4);
  what the zombies did was not controlled.
- Effect seen: none besides the log lines. The server drops the packet.

The children's runs did not show it: their Linux consoles have no such line.

## Not covered

- The join was a direct connection: no line of the client shows it going through the tunnel, although the server
  listened on the tunnel's TCP port.
- The player's online id was 0 (no fake client first); the Server Reborn child covered a non-zero id.
- Steam-mode join, the user's full mod list on the server, mod sync (the server sent no location).
- The repository's mods together on the local dedicated server: only `:zb-kt:serverSmokeTest` ran there.

## Staged Workshop folders

Deployed from this branch by the smoke tests; each equals `<module>/build/mod/<id>` and has a real `preview.png`.

| Folder under `~/Zomboid/Workshop` | Version | `versionMin` | Upload |
|---|---|---|---|
| `Dont39AllInOne` | 0.14.1 | 42.22.0 | yes |
| `Dont39FasterLoading` | 0.5.1 | 42.22.0 | yes |
| `Dont39LoadingProgress` | 0.2.1 | 42.22.0 | yes |
| `Dont39ServerReborn` | 0.3.0 | 42.22.0 | yes |
| `Zabric`, `ZabricKotlin`, `ZBSkipOnlineChecks` | 0.7.3, 0.8.0, 0.1.0 | 42.21.0 | no, unchanged |
