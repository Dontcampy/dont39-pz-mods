# Implementation plan: Lua folder walk (R4)

Design: `design.md`. Gradle: `JAVA_HOME=~/.jdks/graalvm-ce-25.0.2`.

## Checklist

1. `settings/Fix.java`: `LUA_FOLDER_WALK("luaFolderWalk", "lua folder walk", ...)`; check `SettingsTest` still
   covers a missing new key (a 0.3.0 ini gets it at its default).
2. `luafiles/FolderWalk.java`: the walk per `design.md`, game-free except the relative-path function it is given.
3. `hooks/LuaHooks.java`: cancellable HEAD on `searchFolders(Ljava/net/URI;Ljava/io/File;)V`; walk, append, cancel.
4. `src/test/.../luafiles/FolderWalkTest.java`: the cases in `design.md`, the game's method as reference.
5. Options page `FIXES`, `Translate/EN/UI.json` and `CN/UI.json` (name + tooltip), `README.md` (fix row, cancel
   exception in Constraints, Checks), `mod/workshop/workshop.txt`, version 0.4.0 in `build.gradle.kts`.
6. `./gradlew :faster-loading:check`, then `:faster-loading:smokeTest` (twice after a fresh preload registration).
7. Back up the user's state (`~/Zomboid/mods/default.txt`, `~/.zombie_buddy/config.json`,
   `~/Zomboid/Workshop/Dont39LoadingProgress`, `~/Zomboid/Workshop/Dont39FasterLoading`,
   `~/Zomboid/Lua/Dont39LoadingProgress/`, `~/Zomboid/Lua/Dont39FasterLoading.ini`); copy the `fullsrv` tools into
   this session's scratchpad; run 3 launches + 3 joins with `luaFolderWalk` on and 3 + 3 off (other switches on);
   compare the Lua file rows and the `LoadDirBase` → first file times; restore everything; tear down the copies.
8. `verification.md`: medians on vs off, list identity, console checks, the precompile decision with its numbers.
9. `trellis-check`, spec update if something new was learned, commit when the user asks.

## Validation commands

- `./gradlew :faster-loading:check`
- `./gradlew :faster-loading:smokeTest`
- `git lfs pull` before any Workshop staging (worktree PNGs are pointers); uploads are the user's.

## Risky points / rollback

- The cancel: if a mod later hooks `searchFolders`, its advice may be skipped; the switch turns the fix off.
- A path shape the rule misses would change `loadList` (a join then fails its Lua checksum): the switch, and the
  tests' generated cases, are the guard.
- Rollback: revert the commit, or `luaFolderWalk=false`.
