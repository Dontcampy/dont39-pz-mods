# Verification: Faster Loading 0.3.0, depth map order (`depthMapOrder`)

Date: 2026-10-22. Build: this worktree (`claude/dreamy-varahamihira-298ef8`, uncommitted). PC: Ryzen 7 9800X3D (16
threads), RTX 4090, game 42.21 `4a0e9546ec`, game option `textureCompression=true`. Faster Loading's comment stripping
and texture compression on in every run; only `depthMapOrder` changes.

## Offline

- `./gradlew :faster-loading:check :faster-loading:assembleMod`: pass (54 tests).
- `:faster-loading:smokeTest`: pass; `access widener: ok (16 directives)`. The main-menu launch logged
  `depth map order: 900 tasks of 26 depthmaps folders on 4 workers, most work in game (216 tasks)`.

## In game

Setup: R2's recipe (`archive/2026-10/10-21-asset-wait-speedup/implement.md` step 6). Local nosteam server with the live
server's mod list (245 mods + LPTestDriver, real copies), Loading Progress 0.2.0, Faster Loading 0.3.0 from
`deployWorkshop`. Each run is a fresh client launch plus a join. One registration launch and one warm-up join were
discarded. The ZombieBuddy approval for the new jar was already in place, so no dialog came up. Reports:
`research/join-dmo-<run>.txt|.csv`, `research/launch-dmo-<run>.txt`.

| Run | Launch total | Join total | Loading the world | Asset wait | Depth phase | Depth worker time | Largest frame gap | Depth tasks done / failed |
|---|---|---|---|---|---|---|---|---|
| off1 | 33,695 | 68,697 | 31,566 | 10,646 | 5.64 s | 21.7 s | 1,149 (Loading mods) | 900 / 0 |
| on1 | 33,343 | 67,575 | 30,413 | 10,551 | 2.91 s | 10.7 s | 1,146 (Loading mods) | 900 / 0 |
| off2 | 33,691 | 70,139 | 33,270 | 11,670 | 5.69 s | 21.9 s | 1,152 (Loading mods) | 900 / 0 |
| on2 | 34,015 | 84,387 | 29,983 | 7,295 | 3.03 s | 11.2 s | 1,339 (Entering the world) | 900 / 0 |
| off3 | 55,392 | 69,490 | 32,626 | 11,759 | 5.39 s | 20.6 s | 1,153 (Loading mods) | 900 / 0 |
| on3 | 33,699 | 66,893 | 29,777 | 10,208 | 2.90 s | 10.7 s | 1,105 (Loading mods) | 900 / 0 |
| **median off** | **33,695** | **69,490** | **32,626** | **11,670** | **5.64 s** | 21.7 s | 1,152 | |
| **median on** | **33,699** | **67,575** | **29,983** | **10,208** | **2.91 s** | 10.7 s | 1,146 | |
| offshot | 32,683 | 69,675 | 32,574 | 12,203 | 5.69 s | 21.9 s | 1,115 (Loading mods) | 900 / 0 |
| onshot | 33,350 | 67,925 | 30,574 | 9,925 | 2.87 s | 10.5 s | 1,131 (Loading mods) | 900 / 0 |

All times are in ms unless marked. "Depth phase" runs from the first depth task sent to the last one done. Depth
worker time is the workers' time on depth tasks, lock waits included. Both columns are from the CSV; `timeline.py`
prints the same phases rounded to 0.1 s.

- The two screenshot joins (`offshot`, `onshot`) ran with the inventory hidden, the survival guide off and 20 s in the
  world. They count as a fourth pair. With them, the medians of 4 are: asset wait 11,715 off vs 10,067 on, depth
  phase 5.67 s vs 2.89 s.
- No 4th pair was needed by the spread rule: the on medians (wait 10.2 s, join 67.6 s) are below the off spread
  (wait 10.6-11.8 s, join 68.7-70.1 s).
- Outliers outside the depth maps:
  - on2's join total: Loading Lua took 13.8 s (5.3 s in the others) and Entering the world 14.0 s (about 10 s in the
    others).
  - off3's launch: Loading mods took 30.3 s (17.2 s in the others), with the switch off.
  - The depth tasks run in "Loading the world", which is the lowest of the on runs in on2.

### Timeline of the asset wait (`timeline.py`, s from the wait's first file task)

| Run | Pack images (n) | Animations | Depth maps | Depth after the animations' end |
|---|---|---|---|---|
| off1 | 0.0-1.4 (104) | 1.5-5.4 | 5.4-11.0 | 5.6 |
| off2 | 0.0-2.5 (226) | 2.7-6.3 | 6.3-12.0 | 5.7 |
| off3 | 0.0-2.7 (250) | 2.8-6.7 | 6.7-12.0 | 5.3 |
| offshot | 0.0-3.1 (303) | 3.3-6.9 | 6.9-12.6 | 5.7 |
| on1 | 0.0-4.1 (428) | 4.2-8.0 | 7.9-10.9 | 2.9 |
| on2 | 0.0-1.3 (101) | 1.5-4.7 | 4.6-7.6 | 2.9 |
| on3 | 0.0-3.8 (401) | 4.0-7.6 | 7.6-10.5 | 2.9 |
| onshot | 0.0-3.2 (330) | 3.4-7.4 | 7.4-10.3 | 2.9 |

- **The depth maps are still the tail of every wait.** With the switch on they run after the animations as before,
  but 2.9 s instead of 5.3-5.7 s. Depth worker time halves (about 21 s to about 11 s) because the workers no longer
  wait on one mod's lock.
- **The order changed as designed:**
  - Off: the game sends the depth tasks mod after mod (89-97 changes of mod in 900 tasks), and `game`'s 216 maps come
    last (first sent at 3.6-3.9 s of the phase).
  - On: the mods alternate (805-817 changes of mod), and `game` is sent first.
- **The remaining phase is `game`'s chain in every on run.** It finishes last, at 2.87-3.03 s. Alone at the end of the
  off runs it takes 1.8 s (3.8 → 5.6 s). Running beside 3 other decodes stretches it to about 2.9 s. That is above
  the simulated 1.6-2.0 s and above the target.
- **The wait varies with its pack-image backlog, not with the switch.** The wait starts with 101-428 pack images still
  queued (1.3-4.1 s of the wait). From the last pack image to the end of the wait takes 9.3-9.6 s off and 6.3-7.1 s
  on, a gain of about 2.8 s. The whole wait's median gain is smaller (1.5 s) because of that backlog.
- **Every join includes the game's 216 maps.** `game` has 216-218 tasks in each CSV (attribution by empty mod), and
  the launch line names the largest chain `game (216 tasks)`.
- **Anomaly: the join's own `init` queues no depth tasks.** In every on run, the join logs
  `depth map order: 0 tasks of 0 depthmaps folders, left as queued` (at `Core.ResetLua`). The only batch that the fix
  reorders is the launch's: `900 tasks of 26 depthmaps folders on 4 workers, most work in game (216 tasks)`.
  - Even so, the 900 tasks of the join's wait run in the interleaved order with `game` first.
  - So they look like the launch's batch, queued at launch and run only in the join, not a re-queue by the join.
  - This contradicts `research/reprioritise.md` §1, which says `game`'s 216 tasks were queued by the join's own
    `init`. The fix works either way, but the cause is not confirmed. The registration launch, where Faster Loading
    loaded late, also showed the interleaved order in its join.

### Acceptance

- **AC2 not met.** Medians of 3 vs 3:
  - Depth phase: 2.91 s on vs 5.64 s off, above the 2.5 s target.
  - Asset wait: 10.21 s on vs 11.67 s off, 1.46 s shorter (target ≥ 3 s). With the 4th pair it is 1.65 s.
  - Join total: 67.6 s vs 69.5 s, 1.9 s shorter.
  - The depth phase itself is 2.7 s shorter. The asset wait shows less of that gain because the pack-image backlog
    at the start of the wait varies by up to 2.8 s between runs.
- **AC3 met:**
  - Every run completed all 900 depth tasks, with none failed.
  - `console.txt` has the same 954 `ERROR` lines in every run, on and off. Their kinds are the same: 807
    `buildingDef.ID ... expected`, the "bone index" errors, Map Mod Manager's Lua error and others. No error kind is
    new with the switch on, and there is no mod disable and no Faster Loading exception.
  - The `[Dont39FasterLoading]` lines at launch and join, on runs: `0.3.0 loaded`,
    `access widener: ok (16 directives)`, `active: comment stripping on, texture compression on, depth map order on`,
    `texture compression: dxt5 ... on 16 threads`, then the two `depth map order:` lines above. Off runs show
    `depth map order off` and no `depth map order:` line.
  - Frame gaps: Loading Progress reports only the largest gap of the whole load. On median 1,146 ms vs off median
    1,152 ms, within the +100 ms bound. on2's 1,339 ms falls in "Entering the world", outside the depth phase.
    Per-step gaps of "Loading the world" are not measured, as in R2.
  - The run script's "Not Responding" sampler saw 0 hung samples in every run.
- **AC4 met.**
  - Launch with the switch on: 33.3-34.0 s (median 33.7 s). That is within the off spread of 33.7-55.4 s (32.7 s with
    `offshot`), and equal to the off median of 33.7 s.
  - No new errors at launch. The launch batch logged its line in every on run.
- Screenshots: `offshot` and `onshot`, last frame in the world, about 18 s in. The view is the same spot (x 4342,
  y 9090) at night.
  - The difference is a mean of 0.08/255 over the world area (0.44 on the brick wall, 0.26 on the roofs), with no pixel
    off by more than 15 there.
  - Over the whole frame it is 0.05/255. Only HUD numbers differ (stats, clock).
  - The rendering is the same, within JPEG noise.

## Joins after 30 s in the menu

**Answer: no.** After 30 s in the main menu, the join's asset wait has no depth-map tasks and no animation tasks, with
the switch off and on. The launch's file queue drains in the menu about 12 s after the menu is up with the switch on,
and about 16 s with it off. So the series above (0.5 s in the menu) measures the worst case: a player who connects at
once. With a realistic stay in the menu, `depthMapOrder` does not change the join. It only drains the launch's
backlog about 4 s sooner while the player is still in the menu.

Setup:
- The same as above: fresh backup, staging, copies, a registration launch and a warm-up.
- The test driver connects 30 s after `OnMainMenuEnter`. The driver's own markers show menu → `start join` at 30.0 s
  in every run.
- Loading Progress's "waiting in the menu" stays at about 0.5 s. It counts only pauses inside the join flow, which
  starts at the connect click, so the 30 s fall before the flow.
- Reports: `research/join-dmm-<run>.txt|.csv`, `research/launch-dmm-<run>.txt`.

| Run | Join total (menu wait excluded) | Loading the world | Asset wait | Depth tasks | Animation tasks | Pack images | All file tasks in the wait |
|---|---|---|---|---|---|---|---|
| offm1 | 60,495 | 22,552 | 3,797 | 0 | 0 | 413 | 567 |
| onm1 | 57,995 | 20,965 | 921 | 0 | 0 | 79 | 233 |
| offm2 | 60,229 | 22,543 | 2,790 | 0 | 0 | 284 | 434 |
| onm2 | 60,775 | 22,678 | 2,276 | 0 | 0 | 196 | 354 |
| offm3 | 59,105 | 21,215 | 2,628 | 0 | 0 | 277 | 432 |
| onm3 | 58,652 | 20,992 | 1,588 | 0 | 0 | 152 | 305 |
| **median off** | **60,229** | 22,543 | **2,790** | 0 | 0 | | |
| **median on** | **58,652** | 20,992 | **1,588** | 0 | 0 | | |

Times are in ms.
- **What is left in the wait:** pack images and their image data (about 70), meshes (53) and physics shapes (30).
  None of the wait's tasks was already at the workers when the world load began.
- **The on/off difference in the wait (1.2 s) is not the switch's.** Neither wait has a depth task. The difference
  follows the pack-image backlog (79-196 on vs 277-413 off), which varies by run. In the 0.5 s series it ranged over
  101-428 with no pattern by switch.
- **Compared with connecting at once:** the join total is about 60 s instead of about 68-70 s, and the asset wait is
  1-4 s instead of 7-12 s. The difference is the 900 depth tasks and about 1,700 animations that a quick join waits
  for.
- **Drain time.** The run script sampled the game process's CPU every 0.5 s; `cpu.py` gives the cores busy per second
  after the menu is up.
  - From the menu's +4 s, 5-7 cores are busy. With the switch on, that drops to the idle level (1.1-1.3 cores) at
    +12 s in every on run and in the warm-up.
  - With the switch off, about 2.4-3.8 cores stay busy from +10 s to +15 s, and the idle level comes at +16 s. That
    plateau has the shape of one depth chain at a time under its mod's lock.
  - This is an inference from process CPU, not a reading of the queue. Loading Progress does not track file tasks
    outside a load.
- **The join's own `init`** logs `depth map order: 0 tasks of 0 depthmaps folders, left as queued` here as well. The
  join queues no depth maps; the only batch is the launch's (900 tasks, `game` 216). This confirms the anomaly above:
  the 900 tasks of a quick join are the launch's backlog, not a re-queue.
- **Errors:** the same `ERROR` and exception lines in every run, on and off (967), with no new kind. There is no mod
  disable, and 0 "Not Responding" samples.
- **Launch totals:** 33.4-34.2 s, unchanged from the first series.

## Restored after the runs

The test server was stopped (RCON `quit`) and `teardown.ps1` removed the 494 mod copies. Restored from the backups made
before the runs, with SHA-256 checks (46 files, 0 differences):

- `~/Zomboid/mods/default.txt`, without Faster Loading and LPTestDriver.
- `~/.zombie_buddy/config.json`, without Faster Loading's preload, and `mod_approvals.json`.
- `~/Zomboid/options.ini`.
- `Dont39FasterLoading.ini` and `Dont39LoadingProgress.ini`.
- The Workshop staging of Loading Progress, Faster Loading and Zabric, as they were before (0.1.0, 0.1.0 and 0.6.0).
- Loading Progress's reports: the user's 20 files.

The menu-delay series was handled the same way: a second backup before it (identical to the first), then teardown
(494 copies) and a restore with hash checks (46 files, 0 differences, the user's 20 reports).
`~/Zomboid/Lua/lptest_state.txt` was already there from R2; it now holds this series' last run.

## Not done

- **Lever 1 (prefetch decode, `STBImage.stbi_load`).** The depth maps are still the tail of the wait above 2 s:
  `game`'s chain takes about 2.9 s beside the other decodes. Per `prd.md`, that is the case for considering lever 1
  next.
- **Why the launch's depth maps were still queued at a quick join.** The menu-delay series shows they are the
  launch's backlog and drain in the menu after about 12-16 s. Why the join's own `init` queues none (no
  `previouslyLoadedModData` miss) was not looked into in the game code.
- **Per-step frame gaps** of "Loading the world": Loading Progress does not report them.
- **Machines with ≤ 4 cores** (2 file workers), and other GPUs and drivers: not run.
