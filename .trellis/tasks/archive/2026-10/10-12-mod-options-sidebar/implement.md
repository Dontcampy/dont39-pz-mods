# Implement: Mod Options sidebar

## Checklist

1. Module: `mod-options-sidebar/build.gradle.kts` (pz-kotlin), register in `settings.gradle.kts`, add to `features`
   in `dont39-all-in-one/build.gradle.kts`.
2. `mod-options-sidebar/src/main/lua/client/Dont39AllInOne_ModOptionsSidebar.lua`: wrapper, section recording, width
   trick, sidebar, highlight, persistence, R6 fallback (design.md).
3. Check whether ZombieBuddy's `exit_after_game_init` happens after client Lua is loaded (does the existing
   `Dont39AllInOne_ViewpointBloodFxFix.lua` run before exit? add a temporary print or check `console.txt` from a
   smokeTest run). If yes, add the feature's load line to `smokeTest` expectations; if no, note that the feature is
   verified in game only.
4. `dont39-all-in-one/mod/workshop/workshop.txt`: section for the feature, following the existing one.
5. Specs: `directory-structure.md` (a feature may be Lua-only), `zombiebuddy-patching.md` or a new note on wrapping
   vanilla Lua UI functions (wrap, call vanilla outside pcall, fall back to vanilla) — link the Lua file, don't
   restate it.
6. Version bump in `dont39-all-in-one/build.gradle.kts` (shared with the sibling child; bump once).

## Validation

```
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew build assembleMod
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew :dont39-all-in-one:smokeTest
```

In game (main menu and pause menu), with 3+ mods that register options: every acceptance criterion in `prd.md`.

## Risky Points

- Width trick relies on vanilla reading `self.width` vs `self:getWidth()` as it does in the current build; a game
  update can change that → R6 fallback must still leave a usable page.
- Sidebar visibility across tab switches and screen close/reopen.
