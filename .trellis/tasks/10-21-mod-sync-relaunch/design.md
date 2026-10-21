# Design: relaunch the game for a mod-sync restart

Requirements and decisions: [prd.md](./prd.md). Parent contracts: [../10-20-server-mod-sync/design.md](../10-20-server-mod-sync/design.md).

## Boundaries

| Layer | Owns |
|---|---|
| Zabric `relaunch` (API) | `Relaunch.unavailable()`, `Relaunch.restart()`: what this launch is, the waiter's start and handshake, the quit |
| Zabric `relaunch.impl` | `LaunchSpec` (what to start, its wire form), `Waiter` (the waiter's `main`), `RelaunchFailure` (log, marker) |
| Zabric `ffm` source set | `WindowsProcess` (`CreateProcessW`, `MessageBoxW`), next to `WindowsCommandLine` |
| Zabric `RestartNotice` + Lua | `Zabric_relaunchFailure()` and the main-menu dialog for D2's marker |
| mod-sync | `Launch.relaunchUnavailable()` / `relaunch()`, the status table's `relaunch` key, the Lua button |

The public API is a new package `com.pzlauncher.zabric.relaunch` (Zabric's API packages are by subject: `mods`,
`entrypoint`, `hook`); it needs a Zabric minor version at the parent's release.

## Zabric API

```java
public final class Relaunch {
    /** Why this launch cannot restart itself, or null when it can. */
    public static String unavailable();
    /** Starts the waiter, then quits the game. Throws with the reason, and leaves the game running, when it cannot. */
    public static void restart() throws IOException;
}
```

- Both require the running lifecycle (`Lifecycle.requireRunning`), the main thread, and a client.
- `unavailable()`: not Windows (D4); a dedicated server; the exe path (`ProcessHandle.current().info().command()`) or
  the command line (`WindowsCommandLine.read()`) unreadable; `java.home/bin/javaw.exe` missing; the Zabric classes not
  from a jar file. Each a distinct reason. Read each time (cheap), so a later call sees the same answer as `restart()`.
- `restart()`:
  1. builds the `LaunchSpec` of this launch: pid, exe, raw command line, working folder (`user.dir`), environment
     (`System.getenv()`, the JVM's snapshot of the launch's environment), the Zabric folder (`GameFolders.zabricDir()`).
  2. starts `javaw.exe --enable-native-access=ALL-UNNAMED -cp <Zabric.jar> com.pzlauncher.zabric.relaunch.impl.Waiter`
     with the environment minus `_JAVA_OPTIONS`, `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` (D1), working folder the
     game's, stderr appended to `relaunch.log`.
  3. writes the spec to the waiter's stdin and waits up to 10 s for its `ready` line on stdout. No line, another line,
     or an exited waiter: destroy the waiter, throw.
  4. `Core.getInstance().quitToDesktop()`. If that throws, destroy the waiter and rethrow.

## Wire form

`LaunchSpec.write(DataOutputStream)` / `read(DataInputStream)`: a format version int, then the fields; strings as
`writeUTF` (each well below 64 KB), the environment as a count and pairs. A version the waiter does not know fails the
handshake (the waiter and the game come from the same jar, so this only guards a mismatched jar).

## Waiter

`Waiter.main` (pure JDK, no game class), with its effects behind a small interface so tests drive it:

1. read the spec; load every class it will need later (`WindowsProcess`, `RelaunchFailure`) so a Workshop update of
   the jar after the handshake cannot break it; print `ready`, close stdout.
2. `ProcessHandle.of(pid)`: absent means already exited. Otherwise `onExit().get(2, MINUTES)`.
3. Timeout or any error: `RelaunchFailure.record(reason)`.
4. Windows: `WindowsProcess.start(exe, rawCommandLine, cwd, environmentBlock)`: `CreateProcessW` with
   `lpApplicationName` = exe (never searched), a mutable copy of the command line, no handle inheritance,
   `CREATE_UNICODE_ENVIRONMENT`, the block sorted case-insensitively by name and double-null terminated (as
   `CreateProcess` expects); `GetLastError` captured on failure. Close both handles. Failure: `record`.
5. Exit.

`RelaunchFailure.record(reason)`: append a timestamped line to `<zabricDir>/relaunch.log`, write
`<zabricDir>/relaunch-failed.txt` (the reason, `AtomicWrite`), then on Windows `MessageBoxW` (English, like Zabric's
other notices, `MB_ICONERROR | MB_SETFOREGROUND`). Each step's own failure goes to stderr (the log) and does not stop
the next.

## Main-menu notice (D2, R4)

`RestartNotice.relaunchFailure()` (`@LuaMethod Zabric_relaunchFailure`, global, client): reads and deletes
`relaunch-failed.txt`, returns its text or nil. `Zabric_RestartNotice.lua` shows it in an `ISModalDialog` on
`OnMainMenuEnter`, once. A read or delete error is returned as the text (the player still learns something failed).

## mod-sync

- `Launch` gains `relaunchUnavailable(): String?` and `relaunch()`; `GameLaunch` calls `Relaunch`. Tests use the
  existing fake pattern.
- `JoinStatus.restart(...)` gains `relaunch` (true when `relaunchUnavailable()` is null). The reason when false is
  logged once per stop.
- `@LuaGlobal("Dont39AllInOne_restartModSyncGame")`: calls `relaunch()`; returns nil once the game is quitting, the
  reason text otherwise (logged). The override request is untouched either way.
- Lua: the restart stop shows "Restart game" (accept colour) and "Quit game" side by side, next to Back. A click
  disables "Restart game"; a returned reason goes to the status line (`connectLabel`) and the button stays disabled
  for that stop. New keys `UI_Dont39AllInOne_ModSync_RestartGame`, `..._RestartFailed` (EN, CN).

## Compatibility and risks

- Exactness: the raw command line and exe go to `CreateProcessW` unchanged, so quoting cannot drift. A launch that
  came with `+connect`/`+password` (a Steam invite) repeats them, as the goal asks for the exact command line.
- Steam: the inherited `SteamAppId` keeps `SteamAPI_RestartAppIfNecessary` from bouncing; the waiter is a child of
  the game, the new game its child. Whether Steam keeps "in game" and playtime is observed in the in-game check
  (registry `HKCU\Software\Valve\Steam\Apps\108600\Running`), not required.
- The waiter's JVM: `javaw` with default heap, a few tens of MB, alive for the quit plus a moment.
- Rollback: mod-sync's button and Lua global are additive; without "Restart game" the stop is as today.

## Not in this design

Linux/macOS and server relaunch (D4): `unavailable()` names the OS; adding them later is another branch of step 4
and of `unavailable()`.
