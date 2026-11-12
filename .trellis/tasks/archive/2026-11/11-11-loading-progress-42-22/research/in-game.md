# In-game check: Loading Progress 0.2.1 on game 42.22

2026-11-11, this machine, game 42.22.2 (`3d39148846`), ZombieBuddy 2.3.4, Zabric from this worktree (= main),
Loading Progress 0.2.1 from `~/Zomboid/Workshop/Dont39LoadingProgress` (staged by `deployWorkshop`).

## Method

- `:loading-progress:smokeTest`.
- Three client launches through `java` with the game's own `ProjectZomboid64.json` arguments, driven by a temporary
  Lua-only mod (`LPDriver`, not shipped) that acts through the game's own callbacks from the main menu:
  `MainScreen.continueLatestSave` for a save, `ConnectToServer:connect` for a join.
  - Launch and save load: Steam mode, the user's mod list (244 mods, 15 Java mods, Faster Loading 0.5.1 among them).
    The save was a copy of `Sandbox/2026-10-07_16-08-38` (world version 249, converted by the game on load).
  - Join: `-nosteam` client into a vanilla `-nosteam` dedicated server on this machine (42.22, `Mods=LPDriver`, no
    ZombieBuddy on the server). Without Steam the client does not see `~/Zomboid/Workshop`, so real copies of
    ZombieBuddy, Zabric and Loading Progress were in `~/Zomboid/mods` for these launches.
- Window shots (`PrintWindow`) every few seconds during the launches.
- The 42.21 baseline is the user's reports of 2026-10-26 to 2026-10-28 in `~/Zomboid/Lua/Dont39LoadingProgress`, backed
  up before the runs and put back after them, with `default.txt`, `options.ini` and `~/.zombie_buddy`.

## Results

| Check | Result |
|---|---|
| `smokeTest` | Passed |
| Hook targets and weaving | No `hooks stopped` line of any mod in the three runs' consoles |
| Launch to the main menu | Bar seen in the window shots, further along in each later one; ended at step 10 / 10; report complete |
| Load into a save | Ended at step 13 / 13; report complete. Bar not seen: no shot was taken during this world load |
| Join | Bar seen during the world load; ended at step 13 / 13; report complete |
| Report format | As on 42.21. The `Game` line is `Game 42.22.2 3d39148846; ...` |

Smoke test and every launch:

```
[Zabric] Dont39LoadingProgress: hooks ready (53 hook classes)
[Dont39LoadingProgress] 0.2.1 loaded
[Dont39LoadingProgress] access widener: ok (28 directives)
```

End lines of the three flows (cut after the frame counts):

```
[Dont39LoadingProgress] launch: done after 30.0 s at step 10 / 10 (Starting the game); 308 own frames, 14 interim frames
[Dont39LoadingProgress] game start: done after 412.8 s at step 13 / 13 (Entering the world); 167 own frames, 0 interim frames
[Dont39LoadingProgress] game start: done after 11.3 s at step 13 / 13 (Entering the world); 70 own frames, 0 interim frames
```

A step that begins after a later one, or a count past its total, throws in `Progress` and stops the mod's hooks, so
a `done` line at the last step with no `hooks stopped` line means the bar only moved forward.

### Launch against 42.21

| Step | 42.21 (2026-10-28, 246 mods) | 42.22 (244 mods) |
|---|---|---|
| 1 / 10 Loading mods | 16702 ms | 14260 ms |
| 2 / 10 Loading translations | 2042 ms | 1773 ms |
| 3 / 10 Loading UI textures | 155 ms | 82 ms |
| 4 / 10 Loading scripts | 3465 ms | 3010 ms |
| 5 / 10 Loading clothing | 357 ms | 339 ms |
| 6 / 10 Loading tiles and furniture | 910 ms | 581 ms |
| 7 / 10 Loading Lua | 3359 ms | 3691 ms |
| 8 / 10 Loading textures | 231 ms | 60 ms |
| 9 / 10 Loading models and animations | 4137 ms | 3996 ms |
| 10 / 10 Starting the game | 2143 ms | 2208 ms |

Texture pack lines are the same 15 in both reports (7 UI packs, 8 `2x` packs): the baseline was a 2x launch, so the
removed 1x packs were never in it.

### Join against 42.21

The baseline join went to the user's server with 245 mods; this one to an empty vanilla server with one Lua mod, so
only the presence of each step compares, not the times.

| Step | 42.21 (2026-10-27) | 42.22 |
|---|---|---|
| 1 / 13 Connecting to the server | 16046 ms | 315 ms |
| 2 / 13 Loading mods | 6187 ms | 1012 ms |
| 3 / 13 Loading translations | 796 ms | 120 ms |
| 4 / 13 Loading scripts | 3324 ms | 946 ms |
| 5 / 13 Loading models and animations | 230 ms | 9 ms |
| 6 / 13 Loading clothing, tiles and furniture | 2203 ms | 649 ms |
| 7 / 13 Loading Lua | 2531 ms | 645 ms |
| 8 / 13 Running mods' start-up code | 2639 ms | 108 ms |
| 9 / 13 Waiting in the login queue | 18 ms | 6 ms |
| 10 / 13 Preparing the world | 865 ms | 191 ms |
| 11 / 13 Loading the world | 16826 ms | 5884 ms |
| 12 / 13 Loaded | 9327 ms | 510 ms |
| 13 / 13 Entering the world | 5899 ms | 544 ms |

The world load's lines by kind are the same set in both line CSVs (animation, asset wait, clothing items, map cells,
tile definitions, world, world events), and the asset wait section is there with its file tasks. The tile definitions
keep their names although 42.22 asks for them under `tile_definitions/`:

```
start,loading_world,tile_definitions,0,0,newtiledefinitions.tiles,,4791.102,0.009
```

### Save load

```
Game 42.22.2 3d39148846; 244 mods in the mod list; no Java mod pass in this load
     839 ms  10 / 13  Preparing the world  (outside its items 300 ms)
   28500 ms  11 / 13  Loading the world  (outside its items 101 ms)
  374183 ms  12 / 13  Loaded
    9311 ms  13 / 13  Entering the world  (outside its items 48 ms)
```

A save whose mods need no Lua reset begins at step 10, as on 42.21 (`Progress.worldPrepares`). The 374 s of `Loaded`
are the test's: its script stopped before it clicked the game's "click to start" screen, and the click came by hand
six minutes later.

### The `Game` line

The staged 0.2.0, run on 42.22 by the Faster Loading session before this change, wrote
`Game 42.22.2 3d39148846 classpath=1,agents=1; ...`; 0.2.1 writes `Game 42.22.2 3d39148846; ...`.

## Not verified

- The bar on screen during a save's world load (the report and end line only).
- A save load or a mod list change that runs a Lua reset (the `mod reload` flow, and steps 2 to 8 of a single-player
  start); the join covers the same hooks.
- A join in Steam mode, to a server with a large mod list, with a wait in the login queue, or with a new character
  (the first join here created one, but ran before Zabric was in the client's mod folder and so without the mod).
- The mod options page in the game (its Lua and the game's `PZAPI/ModOptions.lua` are unchanged).
- The Linux test server: not used.

## Test artifacts, not findings

- With the copies in `~/Zomboid/mods`, ZombieBuddy's watermark listed Loading Progress twice (the preload entry and
  the copy). The copies were removed after the join.
- Without those copies the `-nosteam` client logged `[Zabric] entrypoints: not run this session: ZombieBuddy did not
  approve and load Zabric` and Zabric closed the mod's hooks: Zabric was not in the mod list. That is Zabric's rule,
  not a 42.22 change.
