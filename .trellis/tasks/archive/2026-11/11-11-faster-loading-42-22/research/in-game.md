# In-game check on 42.22

2026-11-11, this machine (RTX 4090, driver 617.42), game 42.22.2 revision `3d39148846`, Steam on, the user's mod list
(243 mods, 15 Java mods), ZombieBuddy 2.3.4, Zabric 0.7.3 and Loading Progress 0.2.0 as staged. All switches of
`Dont39FasterLoading.ini` on. Raw consoles are not kept in the repo.

## Smoke test

`./gradlew :faster-loading:smokeTest`: pass, with Faster Loading 0.5.1 deployed from this branch. `console.txt`:

```
[Dont39FasterLoading] active: comment stripping on, texture compression on, texture bc7 on, depth map order on, lua folder walk on (C:\Users\dontc\Zomboid\Lua\Dont39FasterLoading.ini)
[Dont39FasterLoading] texture compression: bc7 (DirectXTex modes 4-7, alpha weight 4, nearest index when encoding) 2 on the GPU (textureBc7 on; NVIDIA GeForce RTX 4090/PCIe/SSE2, 4.6.0 NVIDIA 617.42; the driver's GL_COMPRESSED_RGBA: 0x83f3)
[Zabric] Dont39FasterLoading: hooks ready (4 hook classes)
[Dont39FasterLoading] 0.5.1 loaded
[Dont39FasterLoading] access widener: ok (16 directives)
```

## Two full runs: before and after

Each run: launch as `ProjectZomboid64.exe` does (its `ProjectZomboid64.json` arguments), main menu, 35 s in the menu,
a new sandbox world on `Muldraugh, KY`, 20 s in the world, quit to desktop. A temporary Lua mod in the mod list drove
it (the way `MainScreen.lua` starts its demo world); the click on the loading screen was posted to the window. Both
runs ended by the driver's own quit, 176 s and 138 s after the process started.

| | Before | After |
|---|---|---|
| Faster Loading | 0.5.0 as staged since 2026-10-26 | 0.5.1 of this branch |
| Its state | hooks stopped at the first hook call | all five switches on, hooks ready |
| Launch to the main menu (Loading Progress) | 44.8 s | 29.4 s |
| New world to in game (Loading Progress) | 68.4 s | 44.3 s |

Before, the 42.21 build turned itself off as designed:

```
java.lang.IllegalStateException: game 42.22.2 3d39148846 classpath=1,agents=1 is not 42.21 revision 4a0e9546ec, the build whose code this version of the mod reproduces; it needs an update for this game version at Session.on(Session.java:37). Message: [Zabric] Dont39FasterLoading: hooks stopped after an error while running ScriptHooks.stripComments on zombie.scripting.ScriptParser.stripComment...
```

After, `console.txt` has no `hooks stopped` line of any mod, no revision mismatch line, and no exception that names
`com.dont39.pz.fasterloading`. Besides the lines of the smoke test it has the depth map order of the launch:

```
[Dont39FasterLoading] depth map order: 894 tasks of 25 depthmaps folders on 4 workers, most work in game (215 tasks)
[Dont39LoadingProgress] launch: done after 29.4 s at step 10 / 10 (Starting the game); 300 own frames, 14 interim frames; ...
[Dont39LoadingProgress] game start: done after 44.3 s at step 13 / 13 (Entering the world); 165 own frames, 0 interim frames; largest frame gap 295 ms in Entering the world (Lua event: OnGameStart); ...
```

Each fix in the Loading Progress reports (before, after):

| Fix | Where it shows | Before | After |
|---|---|---|---|
| `stripComments` | launch, step 6 "Loading tiles and furniture" (`tileGeometry.txt`) | 4,784 ms | 564 ms |
| `luaFolderWalk` | launch, step 7 "Loading Lua", time outside its items | 4,938 ms | 735 ms |
| `depthMapOrder` | its log line above; the file tasks left for the world load | 2,008 queued at the asset wait's first frame | 1,141 |
| `textureCompression`, `textureBc7` | game start, "Texture decoders waited for the upload budget" | 86,743 ms | 166 ms |
| all | game start, asset wait | 42.4 s | 12.7 s |

These are one run each, the second right after the first (warm file cache), so the table shows that each fix works on
42.22 and not how much it gains. In the after run the step "Loaded" (the game waits for the click) took 7.2 s against
1.3 s: the world was loaded 28 s after the load began, against 58 s. Not looked into; a guess is the intro text of a
new world, which the click does not skip.

The errors that both runs have are other mods': All-in-One's `sctd-render-takeover` refusing the updated Shadow Cat
Tactics: Dispatch (the All-in-One child knows), and two translation format warnings of third-party mods.

## Loading Progress

The reports of Loading Progress 0.2.0 on 42.22 read as on 42.21, with one difference its child task already fixed for
0.2.1: the `Game` line carries the game's integrity summary (`Game 42.22.2 3d39148846 classpath=1,agents=1; ...`).

The five report files of each run are kept outside the repo, in the session group's notes folder under
`faster-loading-42-22-reports/` (`before-0.5.0-off`, `after-0.5.1-on`). The user's own reports, mod list,
`options.ini`, `Lua/*.ini` and ZombieBuddy config were backed up before the runs and put back after them (hashes
equal); the test world and the driver mod are deleted.

## Texture encoder against the driver

`./gradlew :faster-loading:qualityCheck` on the 42.22 install's `Tiles2x.pack` and three installed tile packs: pass
(DXT5 at or above the driver, BC7 above DXT5 on colour, the BC7 mip chains, the GL state around a level, the timing
limit). It runs the encoders on its own GL context, outside the game.

## Staged for upload

`~/Zomboid/Workshop/Dont39FasterLoading`: `workshop.txt` (id 3827655974, text names 42.22), `preview.png` (a real
PNG after `git lfs pull`), and the mod folder with `modversion=0.5.1`, `versionMin=42.22.0`; its jar is byte-equal to
this branch's build.

## Not verified

- A server join with the fixes on. The Linux test server was still on 42.21, and the PRD asks for the menu and a save.
- Loading an existing save: the world of both runs was new.
- DXT5 (`textureBc7` off, or a GL without 4.3), a switch turned off, and the mod options page.
- The new wording of the mismatch message in the game: there is no other build to start it on.
