# Dont39FasterLoading

A client-only Java mod that shortens Project Zomboid's loads (launch to the main menu, server join and single-player
start) by doing the same work faster, with the game's own results. One switch per fix. Made for game 42.21 revision
`4a0e9546ec`. Workshop text: `mod/workshop/workshop.txt`.

## Fixes

| Switch | Hook | What it does |
|---|---|---|
| `stripComments` | `ScriptParser.stripComments(String)`, `@ModifyVariable` on the argument at HEAD (`hooks/ScriptHooks.java`) | Hands the game's method `comments/Comments.java`'s result, linear where the game's method cuts each comment out of one buffer (about 2 s for the game's `tileGeometry.txt` alone, per launch and per join). Same output for every input, the game's quirks included (markers inside strings, two-level nesting, a stray `*/` that stops it, `/*/`, markers formed across a cut); the game's method returns it unchanged after one scan (about 10 ms for the hooked call on `tileGeometry.txt`) |

## How it works

- Zabric keeps the mod preloaded (its preLaunch entrypoint) and opens its hooks at game start; `Zabric-Hooks: launch`
  keeps them open on server joins, whose mod list lacks a client-only mod.
- `hooks/Session.java` decides at the first hook call whether the fixes run: not on a dedicated server, and not on a
  game build other than revision `4a0e9546ec`, where the first hook call throws and the mod's default gate stops all
  its hooks with that reason. A fix reproduces the game's code of that build, which the next build may change.
- With a switch off, its hook leaves the argument as it is and the game's own code runs on it.

## Switches

- One key per fix in `Dont39FasterLoading.ini` in the game cache's `Lua` folder (`settings/Settings.java`, keys in
  `settings/Fix.java`), all on by default. Read once per session at the first hook call; a missing file is written
  with the defaults, an invalid one is reported in one line and the defaults apply.
- The mod options page (`src/main/lua/client/Dont39FasterLoading_Options.lua`, text in `Translate/`) shows the same
  switches, set from the ini when the page is built, and rewrites the ini on apply; a change applies from the next
  launch. A new fix adds its key to `Fix.java` and to the page's `FIXES`.

## Constraints

- Only the generated glue lives in `com.dont39.pz.fasterloading`: ZombieBuddy loads every class there at premain.
- ZombieBuddy loads the types in the glue's signatures at premain, so only methods whose signatures name JDK types are
  hooked, never with `@Self`; `checkJdkGlue` fails the build otherwise.
- Hooks do not cancel the game's method: ZombieBuddy stacks the advice of several mods on one method in load order,
  and a cancel by the outer one would skip the inner one's, such as Loading Progress's known-call bracket on
  `stripComments`.

## Checks

`./gradlew :faster-loading:check` runs the unit tests and `checkJdkGlue`. The comment stripping tests run the game's
own method from `projectzomboid.jar` as the reference, and check that it returns the result unchanged: the quirk
cases, every string of `/`, `*` and `x` up to length 12, random text and nesting, and every script and tile-format
file under the game's `media` and the installed Workshop items (skipped when a folder is not there); one test logs the
time of the game's method and of the hooked call on the game's `tileGeometry.txt`. `:faster-loading:smokeTest`
launches the game until init and checks the log for the mod's lines.
