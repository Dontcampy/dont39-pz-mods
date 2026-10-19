# Dont39LoadingProgress

A client-only Java mod that shows a progress bar while Project Zomboid loads (launch to the main menu; server join or
single-player start to the first frame in the world) and hands over frames of its own where the game loads without
drawing, so the window keeps pumping its messages. Workshop text: `mod/workshop/workshop.txt`.

## How it works

- Zabric keeps the mod preloaded (its preLaunch entrypoint) and opens its hooks at game start; `Zabric-Hooks: launch`
  keeps them open on server joins whose mod list lacks the mod.
- The flows and their steps are in `progress/Flow.java`; `progress/Progress.java` is the game-free state the hooks
  feed, tested in `src/test/`.
- ZombieBuddy's Java mod pass runs no game method between its jars: Zabric's loader progress, registered at preLaunch
  (`hooks/JavaMods.java`), gives the "Java mod i / n" item and a frame per jar.
- `draw/Overlay.java` draws the bar at the head of `Core.EndFrameUI` in every frame the game thread hands over;
  `draw/FramePump.java` hands over its own frames from the hooks inside the loading code.
- One `console.txt` line per flow: outcome, duration, own frames and the largest gap between frames, with its step.

## Constraints

- Only the generated glue lives in `com.dont39.pz.loadingprogress`: ZombieBuddy loads every class there at premain.
- ZombieBuddy loads the types in the glue's signatures at premain. The glue binds every argument of a hooked method
  (and its return value for RETURN and FINALLY handlers), so only methods whose signatures name JDK types are hooked,
  never with `@Self`. `checkJdkGlue` fails the build otherwise; where a per-item method takes a game type, a JDK-only
  callee or caller is hooked instead (`Core.DoFrameReady` for script files, `KahluaThread.pcall(int)` for Lua calls).
- Hooks use the mod's default gate, so Zabric checks their targets from class bytes as each loads. Client only: flows
  start only where `GameServer.server` is false, and every handler and the drawing act only while a flow runs.

## Checks

`./gradlew :loading-progress:check` runs the unit tests and `checkJdkGlue`; `:loading-progress:smokeTest` launches the
game until init and checks the log for the mod's lines.
