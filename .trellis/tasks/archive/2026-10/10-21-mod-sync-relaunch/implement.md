# Implement: relaunch the game for a mod-sync restart

Design: [design.md](./design.md). Gradle needs `JAVA_HOME=~/.jdks/graalvm-ce-25.0.2`.

## Order

1. [x] Merge `claude/cranky-pike-68a148` and port `-cachedir` into `GameFolders` (a3727aa, D3); `:zabric:test` green.
2. [x] Zabric `relaunch.impl.LaunchSpec`: fields, wire form, environment block builder. Tests: round trip, version
       mismatch, block sorted and terminated, the JVM-option variables dropped from the waiter's environment only.
3. [x] Zabric `ffm` `WindowsProcess`: `CreateProcessW`, `MessageBoxW`, `GetLastError` capture. Test on Windows only
       (`@EnabledOnOs(WINDOWS)`): start `cmd /c exit 7` with a raw line and a custom variable, check exit code and
       that the variable arrived (written to a temp file).
4. [x] Zabric `relaunch.impl.Waiter` and `RelaunchFailure`, effects behind an interface. Tests: already exited →
       start; exits within the wait → start; timeout → record; start fails → record; record writes log and marker
       even when the box fails.
5. [x] Zabric `relaunch.Relaunch`: `unavailable()` reasons, `arguments()`, span split and cut (tests: quoted
       arguments, escaped quotes, first/middle/last argument, two omitted, bad index), `restart(omitted)` handshake. Tests through a seam for the
       process start: `ready` → quit called; wrong line, timeout, early exit → waiter destroyed, throws, no quit;
       a real waiter JVM started from the built classes on Windows answering `ready` for a pid that never exits
       within the test (then destroyed).
6. [x] Zabric `RestartNotice.relaunchFailure()` and `Zabric_RestartNotice.lua` dialog; package-info, `zabric/mod`
       docs if they list the API.
7. [x] mod-sync: `Launch` + `GameLaunch`, `JoinArgs` (tests: no `+connect`; to this server by string and by
       resolved address; other host, other port, Steam ID; `+password` alone; repeated `+connect`, last wins; value
       missing), `JoinStatus.restart` `relaunch`, `Dont39AllInOne_restartModSyncGame`, the Rejoin check, Lua buttons,
       EN/CN keys. Tests with the fake `Launch`.
8. [x] Gate: `./gradlew :zabric:test :mod-sync:test build`; smokeTest (take the `Dont39LoadingProgress` preload entry
       out of `~/.zombie_buddy/config.json` with a dated backup, restore after).
9. [x] In game (Windows client, Linux test server, item 3823294577 on an older version on the server; back up the
       server's ini/sandbox/mods and restore): restart stop → "Restart game" → relaunch → override applied (log) →
       Rejoin with the server's jar. Compare the old and new process command lines (`Win32_Process.CommandLine`).
       Second run with `-agentlib:zbNative ...` on the command line instead of the json (json backed up and
       restored). Failure runs: a waiter given a short wait against a process kept alive (test hook or a test-only
       property, removed after), and a start failure (exe renamed copy) → box, log, main-menu notice once. Record
       Steam's `Running` value. D5: a launch with `+connect <test server>` → relaunch keeps it, vanilla popup, no
       Rejoin; a launch with `+connect` to another address → relaunched command line without it.
10. [x] Spec update (`trellis-update-spec`): access-widening/zabric API notes for `relaunch`, error-handling if a new
        rule shows; parent prd D9 replaced, parent task map row for this child.
11. [ ] Commit on this branch; archive the child.

## In-Game Results (2026-10-21)

Windows client against myserver2 on the Linux test server (game port 48261, mod-sync port 48310); test item 3823294577:
server on version 5, client on 6 (uploaded by the user while the server ran). Driven by a temporary Lua driver acting
through the real buttons (removed after); process command lines and Steam's `HKCU\Software\Valve\Steam` `Running` /
`RunningAppID` sampled every 0.5 s.

- A, ZombieBuddy on the command line (`-agentlib:zbNative=... -Dd39.relaunchprobe="a b" --`, json without it): join →
  confirm → download → restart stop with `relaunch = true`, "Restart game" next to "Quit game" → click. The helper
  (`javaw ... -cp ~/Zomboid/Workshop/Zabric/.../Zabric.jar ...Waiter`, child of the game) started the game again
  within a second of the old process's exit, with the same command line byte for byte (quoted probe included);
  ZombieBuddy loaded; `launch override: Workshop item 3823294577 uses ...ModSync\items\192.168.50.249_48261\...`;
  Rejoin → the join ran the server's `java version 5` ("runs the server's Java code"). Steam stayed `Running=1`,
  `RunningAppID=108600` throughout. The world load then waited in the background, as in mod-sync-java's run.
- B, `+connect 192.168.50.249:48261`: the relaunched command line kept it; vanilla's connect popup opened for it and
  no Rejoin dialog showed (`no rejoin offer for tcptest: this launch joins it through +connect ...`).
- C, `+connect 192.168.50.249:1 +password secret`: the relaunched command line was the bare exe; Rejoin was offered.
- D, the helper run by hand from the deployed jar: a program that does not exist → message box, `relaunch.log`,
  marker (`CreateProcessW failed with Windows error 3`); a stand-in that kept running → after exactly 2 minutes the
  same with "still running 2 minutes after it quit to restart", nothing started. The next launch's main menu showed
  the reason once and deleted the marker.
- Found and fixed: the failure notice and the Rejoin offer both appear after a failed relaunch and were both centred,
  the offer covering the notice; the notice now sits above the middle (f9c5449), checked in game.
- Test scaffolding only: vanilla's +connect popup never fills a saved entry (it compares the saved number port with
  the argument's string port) and hashes a saved hashed password again, so round B's first launch joined through the
  saved entry.
- Not covered in game: a handshake failure on the Connect screen (unit tests), Linux/macOS (unit tests: no button).
- Seen, not this task's: the Rejoin dialog's CN text breaks before its closing "。".

## Review gates

- After 5: the waiter and the handshake are the risky part; run `trellis-check` on Zabric before mod-sync.
- After 9: report the Steam observation and any in-game deviation before the spec update.

## Rollback points

- The merge (a3727aa) stands alone; every later step is its own commit-able unit. mod-sync's change is additive.
