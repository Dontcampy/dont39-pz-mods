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
  (`hooks/JavaMods.java`), gives the "Java mod i / n" item and a frame per jar: two at once, then one per 50 ms.
- Patching one jar can hold the game thread for seconds. Meanwhile the render thread shows interim frames
  (`draw/Interim.java`, hooks on `Display` in `hooks/DisplayHooks.java`): the jar's own frame, copied before its swap,
  with the sub-bar moved further into the jar's share every 250 ms while Zabric's heartbeat moves, in shrinking steps
  that stop short of the jar's end. Bind-based GL with every binding and state restored, also for a multisampled
  window; a GL error of its own calls stops the mod, one the game left pending skips the frame.
- `draw/Overlay.java` draws at the head of `Core.EndFrameUI` in every frame the game thread hands over: the step line,
  the bar by step, the item line and a sub-bar by the item's count, below ZombieBuddy's watermark (its wrapping is
  replayed from the Java mods the loader progress named); `draw/FramePump.java` hands over its own frames from the
  hooks inside the loading code, but none while the watermark's font loads and the render thread pumps on its own.
- Calls that hold the game thread with no hookable point inside (comment stripping of script files, Steam's installed
  items query) are bracketed as known calls (`progress/Known.java`).
- One `console.txt` line per flow: outcome, duration, own and interim frames (and interim frames skipped for the
  game's pending OpenGL errors); the largest gap between frames the game
  thread handed over, the largest counting interim frames, the largest while the Java mod jars showed, and the one
  with the most time outside known calls, each with its step, item and known-call share.

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
