# Relaunch the game for a mod-sync restart

## Goal

After a join stops for a restart (mod-sync-java J1), the game starts itself again with this launch's command line,
working folder and environment, so nothing between the two launches (a launcher rewriting `default.txt`, another
`-cachedir`, other launch options) changes what the override launch runs. Launchers that do setup work on each start
are skipped on purpose: that is what keeps the override launch the same. Parent:
[10-20-server-mod-sync](../10-20-server-mod-sync/prd.md); it replaces the parent's D9 "the player starts the game
again themself" (user, 2026-10-21).

## Background

- The restart path, the one-launch override and the Rejoin offer are done
  ([mod-sync-java](../archive/2026-10/10-20-mod-sync-java/prd.md),
  [zabric-launch-override](../archive/2026-10/10-20-zabric-launch-override/prd.md)). Today the Connect screen shows
  "Quit game" (`getCore():quitToDesktop()`, `mod-sync/src/main/lua/client/Dont39AllInOne_ModSyncJoin.lua:181-207`),
  and the player starts the game again.
- A client launch goes through `ProjectZomboid64.exe` (JNI): `sun.java.command` is empty at premain, and the JVM
  options come from `ProjectZomboid64.json` and the exe's own command line. The process's command line (Windows
  `GetCommandLineW`) holds the exe and its arguments, Steam launch options included.
- `ProjectZomboid64.exe` imports `SteamAPI_RestartAppIfNecessary`, `SteamAPI_Init`, `SteamAPI_IsSteamRunning`; the
  install ships `steam_appid.txt` (108600, same date as the exe), and a process Steam started carries `SteamAppId` in
  its environment, so a direct start with this environment does not bounce through Steam (mod-sync-java's in-game runs
  all started the exe directly). No mutex strings: nothing in the exe refuses a second instance by itself.
- This install's `ProjectZomboid64.json` asks `-Xmx23072m`: two game processes at once would each reserve that.
- ZombieBuddy installs three ways (main checkout `external-repos/ZombieBuddy/doc/Installation.md:135-184`): the json's
  `vmArgs`; Steam launch options `-agentlib:zbNative --` (JVM options before `--` on the exe's command line); the
  Alternate Launch `ProjectZomboid64.bat`, which runs `java.exe` with `SET _JAVA_OPTIONS=-agentlib:zbNative`. Keeping
  the command line and the environment keeps all three.
- On Linux, Steam runs games in its runtime container, which ends the container's other processes when the game exits;
  there is no Linux or macOS client to test on.

## Decisions

- D1. A waiter process does the relaunch (user, 2026-10-21): the game starts it on its own Java runtime with this
  launch's command line, exe, working folder and environment, waits for its acknowledgement, then quits; the waiter
  waits for the old process to exit and starts the game with exactly those (Windows: `CreateProcessW` with the raw
  `GetCommandLineW` string). The waiter's own JVM runs without `_JAVA_OPTIONS`/`JAVA_TOOL_OPTIONS`/`JDK_JAVA_OPTIONS`;
  the relaunched game gets them. Rejected: starting the new game at once and waiting at premain (two games' heaps and
  two Steam inits overlap).
- D2. A failure after the game quit is never silent (user, 2026-10-21): the waiter waits at most 2 minutes for the
  old process; on any failure it writes `<cachedir>/Zabric/relaunch.log`, shows a native message box on Windows, and
  leaves a marker that the next launch's main menu shows through Zabric's restart notice, then clears.
- D3. All of the parent's children and the `-cachedir` fix go on one branch (user, 2026-10-21): this branch carries
  mod-sync-core, zabric-launch-override and mod-sync-java (from `claude/trellis-main-update-subtasks-6c01f8`), and
  merged `claude/cranky-pike-68a148` in a3727aa with the port into `GameFolders`; the relaunch reuses `GameArgs` and
  `WindowsCommandLine`.
- D4. Windows clients only (user, 2026-10-21). On Linux and macOS the restart stop shows "Quit game" only, as today,
  and the log says why there is no "Restart game".

## Requirements

- R1. A Zabric API that restarts the game (client, Windows): it starts the waiter (D1), and quits the game only once
  the waiter acknowledged; anything that fails before that throws with the reason and leaves the game running. It also
  answers whether a relaunch is possible here, with the reason when not (D4).
- R2. The waiter starts the game with the old process's exe, raw command line, working folder and environment once the
  old process exited, and reports every failure after the quit as D2 says.
- R3. mod-sync's restart stop shows "Restart game" next to "Quit game" where R1 says a relaunch is possible. A refused
  relaunch shows its reason on the Connect screen; "Quit game" keeps working and the override request stays as it was.
  The Rejoin offer after the relaunch is unchanged.
- R4. The next launch's main menu shows a failure the waiter recorded (D2), once.

## Acceptance Criteria

- [ ] On a Windows client against the Linux test server: a join that stops for a restart offers "Restart game"; it
      brings the game back with the same command line (compared through the process list), the override applies,
      and Rejoin joins with the server's jar.
- [ ] ZombieBuddy given on the command line (as Steam launch options give it, `-agentlib:zbNative --`) is still
      loaded after the relaunch.
- [ ] A relaunch refused before the quit shows its reason on the Connect screen, and "Quit game" then works.
- [ ] A failure after the quit (old process kept alive past the wait, or the start failing) shows a message box,
      writes `relaunch.log`, and the next launch's main menu shows it once.
- [ ] On Linux/macOS (unit-tested), the restart stop offers no "Restart game", and the log says why.
- [ ] Unit tests for Zabric's relaunch and mod-sync's use of it; `:zabric:test :mod-sync:test build` and smokeTest
      green.
- [ ] Release with the parent's integration review (no version bump here).

## Out of Scope

- Relaunching Linux and macOS clients, and dedicated servers (D4).
- Going through a third-party launcher or Steam for the relaunch (the goal skips them).
- Steam's view of the relaunched process (playtime, "in game") is observed and reported in the in-game check, not
  required.
