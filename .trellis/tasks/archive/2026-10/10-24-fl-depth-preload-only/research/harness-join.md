# Harness join check, run fl-warmup (2026-10-26)

Client -nosteam, joining the local sgtest server (myserver1's 244 mods, no Sync Guard); Faster Loading 0.5.0 at 6f7b4b9, preloaded from its Workshop staging, not in the session's mod list (no 'main: Dont39FasterLoading').

```
Mod          f:0> [Dont39FasterLoading] active: comment stripping on, texture compression on, texture bc7 on, depth map order on, lua folder walk on (C:\Users\dontc\Zomboid\Lua\Dont39FasterLoading.ini)
General      f:0> [ZB] ZomboidFileSystem.loadMods(244 mods) ...
Mod          f:0> [Zabric] preLaunch: Dont39FasterLoading
General      f:0> [ZB] patching zombie.ZomboidFileSystem.loadMods with 2 advice(s)
General      f:0> [ZB] patching zombie.ZomboidFileSystem.loadMods with 1 advice(s)
General      f:0> [ZB] patching zombie.ZomboidFileSystem.loadMods with 2 advice(s)
General      f:0> [ZB] patching zombie.ZomboidFileSystem.loadMods with 1 advice(s)
General      f:0> [ZB] patching zombie.ZomboidFileSystem.loadMods with 2 advice(s)
General      f:0> [ZB] patching zombie.ZomboidFileSystem.loadMods with 1 advice(s)
Mod          f:0> [Zabric] Dont39FasterLoading: hooks ready (4 hook classes)
Mod          f:0> [Zabric] main: Dont39AllInOne
General      f:0> [ZB] ZomboidFileSystem.loadMods(244 mods) took 6832 ms
Mod          f:0> [Dont39FasterLoading] access widener: ok (16 directives)
Mod          f:0> [Dont39FasterLoading] depth map order: 900 tasks of 26 depthmaps folders on 4 workers, most work in game (216 tasks)
General      f:0 st:0> [ZB] ZomboidFileSystem.loadMods(245 mods) ...
General      f:0 st:0> [ZB] ZomboidFileSystem.loadMods(245 mods) took 7291 ms
Mod          f:0 st:0> [Dont39FasterLoading] depth map order: 0 tasks of 0 depthmaps folders, left as queued
Lua          f:188 st:260,509,715> :	[SGTEST] 1792994859984 arrived ok=true at 7560,11815,0
```
