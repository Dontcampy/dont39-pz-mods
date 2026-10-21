# Dont39LoadingProgress

A client-only Java mod that shows a progress bar and a log of what loads while Project Zomboid loads (launch to the
main menu; server join or single-player start to the first frame in the world) and hands over frames of its own where
the game loads without drawing, so the window keeps pumping its messages. It also writes a timing report per load.
Workshop text: `mod/workshop/workshop.txt`.

## How it works

- Zabric keeps the mod preloaded (its preLaunch entrypoint) and opens its hooks at game start; `Zabric-Hooks: launch`
  keeps them open on server joins whose mod list lacks the mod.
- The flows and their steps are in `progress/Flow.java`; `progress/Progress.java` is the game-free state the hooks
  feed, tested in `src/test/`.
- ZombieBuddy's Java mod pass runs no game method between its jars: Zabric's loader progress, registered at preLaunch
  (`hooks/JavaMods.java`), gives the "Java mod i / n" line and a frame per jar: two at once, then one per 50 ms.
- Patching one jar can hold the game thread for seconds. Meanwhile the render thread shows interim frames
  (`draw/Interim.java`, hooks on `Display` in `hooks/DisplayHooks.java`): the jar's own frame, copied before its swap,
  with up to three trailing dots after the log's newest line, one more (or none again after three) every 250 ms while
  Zabric's heartbeat moves. Bind-based GL with every binding and state restored, also for a multisampled window; a GL
  error of its own calls stops the mod, one the game left pending skips the frame.
- `draw/Overlay.java` draws at the head of `Core.EndFrameUI` in every frame the game thread hands over, in a column at
  the right edge (`draw/Column.java`) below ZombieBuddy's watermark (its wrapping is replayed from the Java mods the
  loader progress named): the bar by step, and under it the flow's log (`progress/Log.java`): a line per step and per
  item, newest at the bottom, older ones fading, long ones cut with an ellipsis, down to the highest of the screen's
  own content that reaches into the column (`Overlay.blocks`); `draw/FramePump.java` hands over its own frames
  from the hooks inside the loading code, but none while the watermark's font loads and the render thread pumps on its
  own.
- Calls that hold the game thread with no hookable point inside (comment stripping of script files, Steam's installed
  items query) are bracketed as known calls (`progress/Known.java`).
- One `console.txt` line per flow: outcome, duration, own and interim frames (and interim frames skipped for the
  game's pending OpenGL errors); the largest gap between frames the game
  thread handed over, the largest counting interim frames, the largest while the Java mod jars showed, and the one
  with the most time outside known calls, each with its step, item and known-call share.

## Switches and timing report

- Two switches in `Dont39LoadingProgress.ini` in the game cache's `Lua` folder (`progress/Settings.java`): `display`
  (bar, log, own and interim frames) and `report` (timing report), both on by default. Read once per session at the
  first flow's begin; a missing file is written with the defaults, an invalid one is reported in one line and the
  defaults apply. With `display` off flows still run for the report and the frame-gap line; with both off no flow
  begins.
- The mod options page (`src/main/lua/client/Dont39LoadingProgress_Options.lua`, text in `Translate/`) shows the same
  two switches, set from the ini when the page is built, and rewrites the ini on apply; a change applies from the next
  launch.
- Timing: each new log line also gets a time (`progress/Timed.java`), until the next line, the return of the game's
  loop over its kind (mod list, script files, a Lua folder, a Java mod jar), a wait in the menu or the flow's end. No
  hook separates the last script file from the game's processing of all script files after it, so the summary names
  that row and credits it to no mod. At the flow's end `report/Report.java` builds a summary (steps, per item kind the slowest, time per mod)
  and a CSV with a row per line, and `report/Reports.java` writes them on a background thread to
  `Lua/Dont39LoadingProgress/` in the cache folder, keeping the last 10 loads; `console.txt` gets the summary's path.
  A write failure turns the report off for the session.

## Constraints

- Only the generated glue lives in `com.dont39.pz.loadingprogress`: ZombieBuddy loads every class there at premain.
- ZombieBuddy loads the types in the glue's signatures at premain. The glue binds every argument of a hooked method
  (and its return value for RETURN and FINALLY handlers), so only methods whose signatures name JDK types are hooked,
  never with `@Self`. `checkJdkGlue` fails the build otherwise; where a per-item method takes a game type, a JDK-only
  callee or caller is hooked instead (`Core.DoFrameReady` for script files, `KahluaThread.pcall(int)` for Lua calls).
- Hooks use the mod's default gate, so Zabric checks their targets from class bytes as each loads. Client only: flows
  start only where `GameServer.server` is false, and every handler and the drawing act only while a flow runs. A
  dedicated server logs one "not active" line, from the main entrypoint or, when it only preloads the mod, from
  `hooks/ServerHooks.java`.

## Checks

`./gradlew :loading-progress:check` runs the unit tests and `checkJdkGlue`; `:loading-progress:smokeTest` launches the
game until init and checks the log for the mod's lines.
