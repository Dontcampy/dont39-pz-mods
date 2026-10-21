# Relaunch the game for a mod-sync restart

## Goal

After a join stops for a restart (mod-sync-java J1), the game starts itself again with this launch's command line,
so nothing between the two launches (a launcher rewriting `default.txt`, another `-cachedir`, other launch options)
changes what the override launch runs. Parent: [10-20-server-mod-sync](../10-20-server-mod-sync/prd.md); it
replaces the parent's D9 "the player starts the game again themself" (user, 2026-10-21).

## Background

- The restart path, the one-launch override and the Rejoin offer are done
  ([mod-sync-java](../archive/2026-10/10-20-mod-sync-java/prd.md),
  [zabric-launch-override](../archive/2026-10/10-20-zabric-launch-override/prd.md)). Today the Connect screen shows
  "Quit game" (`getCore():quitToDesktop()`), and the player starts the game again.
- A client launch goes through `ProjectZomboid64.exe` (JNI): `sun.java.command` is empty at premain, and the JVM
  options come from `ProjectZomboid64.json`, not the command line. The process's own command line (Windows
  `GetCommandLineW`, Linux `/proc/self/cmdline`) holds the exe and its arguments, Steam launch options included.
- Branch `claude/cranky-pike-68a148` (commit d9810a0, not merged) reads it already (`GameArgs`, `WindowsCommandLine`)
  for Zabric's `-cachedir`; reuse it rather than writing it twice.
- Steam works when the exe is started directly (all of mod-sync-java's in-game runs did that).

## Requirements (draft, to refine in planning)

- R1. A Zabric capability: restart the game with this launch's command line, working folder and environment, once the
  current process has exited. Client side only to start with.
- R2. mod-sync's restart stop offers "Restart game" (and keeps a way to quit only); the Rejoin offer after the
  relaunch is unchanged.
- R3. A relaunch that cannot be done (command line unreadable, start failed) is reported with a reason, and the
  player can still quit and start the game by hand; nothing is left half-done (the override request stays valid).

## Open Questions (for planning)

- How the new process waits for the old one (a waiter process vs. the new game waiting at premain), and whether
  Steam accepts a second instance while the first shuts down.
- Steam's view of the relaunched process (playtime, "in game" status).
- Players whose launcher does setup work each start: is skipping it acceptable, or must the relaunch go through it?
- Whether to merge `claude/cranky-pike-68a148` first.
- Linux and macOS clients.

## Acceptance Criteria (draft)

- [ ] After a restart stop, "Restart game" brings the game back with the same command line; the override applies;
      the Rejoin offer works.
- [ ] A Steam-launch-options install of ZombieBuddy is still loaded after the relaunch.
- [ ] A failed relaunch says why and leaves the manual restart working.
- [ ] Release with the parent's integration review.
