# Verification: Faster Loading 0.4.0, Lua folder walk (`luaFolderWalk`)

Date: 2026-10-22. Build: this worktree (`feat/lua-folder-walk-143b68`, uncommitted). PC: Ryzen 7 9800X3D (16
threads), RTX 4090, game 42.21 `4a0e9546ec` on its own JRE (Zulu 25.0.1). Faster Loading's other switches on in every
run; only `luaFolderWalk` changes.

## Offline

- `./gradlew :faster-loading:check`: pass (63 tests, 1 skipped: a symbolic link needs a privilege this user lacks;
  the junction case covers reparse points).
- `FolderWalkTest` against the game's `LuaManager.searchFolders`: the game's `media/lua/{shared,client,server}` and
  1,149 Workshop Lua folders (4,298 files) give the same list in the same order, with one canonical lookup per root;
  generated trees (junction, names ending in a dot or space, upper-case extensions, empty folders, a file or missing
  root) the same.
- `:faster-loading:smokeTest`: pass after the registration run. `0.4.0 loaded`, `active: ... lua folder walk on`,
  `[ZB] patching zombie.Lua.LuaManager.searchFolders with 1 advice(s)` (only ours), `hooks ready (4 hook classes)`.

## Precompile decision

Not done. Compile of every Lua file of the live mod list measured offline at 0.94 s (cold JIT, 2,536 files; ≈ 1.15 s
for the in-game 3,138), at the 1 s threshold, but no preloadable hook point exists to hand a compiled prototype in
(`research/walk-measurement.md`).

## In game

Setup: R2's recipe (`archive/2026-10/10-21-asset-wait-speedup/implement.md` step 6). Local nosteam server `fulltest`
with the live server's mod list (245 mods + LPTestDriver, real copies), Loading Progress 0.2.0 and Faster Loading
0.4.0 from `deployWorkshop`. Each run is a fresh client launch plus a join (3 s in the menu); one warm-up discarded,
then off/on alternating. Walk times from Loading Progress's CSV (`research/walk_table.py`, table in
`research/in-game-walk-table.txt`): from the later of the step's start and the previous folder's last file to the
folder's first Lua file. Sample summaries: `research/{launch,start}-{off1,on1}.txt`.

| Run | Launch shared | Launch client | Launch total | Join shared | Join client | Join server | Join total |
|---|---|---|---|---|---|---|---|
| off1 | 3,050 | 878 | 34,877 | 2,875 | 859 | 533 | 68,477 |
| on1 | 608 | 315 | 32,252 | 595 | 327 | 285 | 64,753 |
| off2 | 3,149 | 861 | 34,426 | 2,889 | 871 | 536 | 67,379 |
| on2 | 600 | 315 | 31,168 | 584 | 304 | 264 | 64,749 |
| off3 | 2,949 | 860 | 34,596 | 2,898 | 877 | 538 | 69,281 |
| on3 | 610 | 318 | 31,386 | 586 | 309 | 278 | 65,957 |
| **median off** | **3,050** | **861** | **34,596** | **2,889** | **871** | **536** | **68,477** |
| **median on** | **608** | **315** | **31,386** | **586** | **309** | **278** | **64,753** |

All times in ms. Shared 1,126 files, client 2,014 (launch) / 2,012 (join), server 1,004.

- **AC: ≥ 50 % drop.** Met. The walks before the first file of each folder total 3,911 → 923 ms per launch (−76 %)
  and 4,296 → 1,173 ms per join (−73 %). The launch total drops by 3.2 s and the join total by 3.7 s (medians).
- **What is left** (~0.6 s for shared, ~0.3 s for client) is `LoadDirBase`'s per-mod work outside `searchFolders`
  (a canonical lookup and three folder listings per mod folder, `research/walk-measurement.md`), which no
  JDK-only hook reaches.
- **AC: same `loadList`.** Met. The Lua file rows (names in run order) hash the same in every run of a flow, on and
  off: launch `7759559cd4` (3,140 files), join `2ab85ae098` (4,142 files).
- **AC: no new errors, no checksum mismatch.** No new errors: met. `console.txt` has 954 `ERROR` lines and 15 lines
  with "exception" in every run, on and off (the live mod list's own). The checksum was **not exercised**: the test
  server copies the live server's `DoLuaChecksum=false`. The list identity above stands in for it, since the client
  adds the files to its checksum in `loadList` order (`LoadDirBase` L1228-1229), so identical lists give identical
  checksum input. Every join reached the world
  (`lptest_state.txt` "ingame"). `[ZB] patching zombie.Lua.LuaManager.searchFolders with 1 advice(s)` in every run
  (only ours). The mod logged `lua folder walk on|off` as set, and none of its hooks stopped.
- Not covered: Linux and macOS clients (the rule's link check is the same there; untested), and a symbolic link
  in game (none on the live mod list).
- The user's files were backed up before and restored after (mod list, ZombieBuddy config, `options.ini`, the Lua
  folder's inis, both mods' Workshop staging, Loading Progress's reports); the 494 mod copies were removed. Mods' own
  `.txt` state files in `Lua/` (alerts, shortcuts, map ink) were rewritten by the runs as by any launch and were not
  part of the backup.
