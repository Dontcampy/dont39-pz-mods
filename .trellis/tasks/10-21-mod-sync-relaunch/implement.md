# Implement: relaunch the game for a mod-sync restart

Design: [design.md](./design.md). Gradle needs `JAVA_HOME=~/.jdks/graalvm-ce-25.0.2`.

## Order

1. [x] Merge `claude/cranky-pike-68a148` and port `-cachedir` into `GameFolders` (a3727aa, D3); `:zabric:test` green.
2. [ ] Zabric `relaunch.impl.LaunchSpec`: fields, wire form, environment block builder. Tests: round trip, version
       mismatch, block sorted and terminated, the JVM-option variables dropped from the waiter's environment only.
3. [ ] Zabric `ffm` `WindowsProcess`: `CreateProcessW`, `MessageBoxW`, `GetLastError` capture. Test on Windows only
       (`@EnabledOnOs(WINDOWS)`): start `cmd /c exit 7` with a raw line and a custom variable, check exit code and
       that the variable arrived (written to a temp file).
4. [ ] Zabric `relaunch.impl.Waiter` and `RelaunchFailure`, effects behind an interface. Tests: already exited →
       start; exits within the wait → start; timeout → record; start fails → record; record writes log and marker
       even when the box fails.
5. [ ] Zabric `relaunch.Relaunch`: `unavailable()` reasons, `arguments()`, span split and cut (tests: quoted
       arguments, escaped quotes, first/middle/last argument, two omitted, bad index), `restart(omitted)` handshake. Tests through a seam for the
       process start: `ready` → quit called; wrong line, timeout, early exit → waiter destroyed, throws, no quit;
       a real waiter JVM started from the built classes on Windows answering `ready` for a pid that never exits
       within the test (then destroyed).
6. [ ] Zabric `RestartNotice.relaunchFailure()` and `Zabric_RestartNotice.lua` dialog; package-info, `zabric/mod`
       docs if they list the API.
7. [ ] mod-sync: `Launch` + `GameLaunch`, `JoinArgs` (tests: no `+connect`; to this server by string and by
       resolved address; other host, other port, Steam ID; `+password` alone; repeated `+connect`, last wins; value
       missing), `JoinStatus.restart` `relaunch`, `Dont39AllInOne_restartModSyncGame`, the Rejoin check, Lua buttons,
       EN/CN keys. Tests with the fake `Launch`.
8. [ ] Gate: `./gradlew :zabric:test :mod-sync:test build`; smokeTest (take the `Dont39LoadingProgress` preload entry
       out of `~/.zombie_buddy/config.json` with a dated backup, restore after).
9. [ ] In game (Windows client, Linux test server, item 3823294577 on an older version on the server; back up the
       server's ini/sandbox/mods and restore): restart stop → "Restart game" → relaunch → override applied (log) →
       Rejoin with the server's jar. Compare the old and new process command lines (`Win32_Process.CommandLine`).
       Second run with `-agentlib:zbNative ...` on the command line instead of the json (json backed up and
       restored). Failure runs: a waiter given a short wait against a process kept alive (test hook or a test-only
       property, removed after), and a start failure (exe renamed copy) → box, log, main-menu notice once. Record
       Steam's `Running` value. D5: a launch with `+connect <test server>` → relaunch keeps it, vanilla popup, no
       Rejoin; a launch with `+connect` to another address → relaunched command line without it.
10. [ ] Spec update (`trellis-update-spec`): access-widening/zabric API notes for `relaunch`, error-handling if a new
        rule shows; parent prd D9 replaced, parent task map row for this child.
11. [ ] Commit on this branch; archive the child.

## Review gates

- After 5: the waiter and the handshake are the risky part; run `trellis-check` on Zabric before mod-sync.
- After 9: report the Steam observation and any in-game deviation before the spec update.

## Rollback points

- The merge (a3727aa) stands alone; every later step is its own commit-able unit. mod-sync's change is additive.
