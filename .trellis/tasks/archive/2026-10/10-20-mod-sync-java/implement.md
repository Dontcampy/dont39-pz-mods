# Implement: mod sync Java mods

`mod-sync` (Kotlin, Lua, texts) on top of Zabric's `com.pzlauncher.zabric.mods`. All-in-One's build compiles against
the worktree's Zabric.

## Checklist

1. [x] `client/java/ModInfo.kt`: the Java mods of a mod folder (version folder, `javaJarFile`, `javaPkgName`, side,
       preload flags incl. the jar manifest). Unit tests with fixture folders.
2. [x] Hold: `Work.jarChanges`, preload refusal on the client copy, J4 confirm rule, the decision in `ready`
       (design "Java decision"), `Failed_Jar` removed. Extend the existing `JoinSession` tests: jar change → restart
       stop with a request written; item already overridden → Ready; served by another jar → `Failed_NotServed`;
       remembered server with a jar change → Confirm; preload → `Failed_Preload` before any download.
       The Zabric calls go through a small interface so the tests run without the game.
3. [x] Restart path: request merge with `pending()`, `rejoin.json` store (unit tests: write, read, format, atomic),
       the post-stop status for Lua.
4. [x] Post-pass log for a mod whose jar is not served after the join's pass (design "Accepted").
5. [x] Lua: Quit button on the Connect screen; Java line in the confirm dialog; Rejoin offer on the main menu;
       EN and CN texts.
6. [x] Build gate and `smokeTest`.
7. [x] In game, Linux test server (memory: linux-test-server) + Windows client: a server started on an older version of
       a Java mod item (enabled on the client's main menu, and once not enabled): confirm marks Java → download →
       restart stop with Quit → restart → Rejoin offer → join works with the server's jar and Lua (log:
       `launch override`, `servingJar`) → the launch after is back to the client's copy. A remembered server still asks.
       A preload item is refused. Update the parent's acceptance list.

## In-Game Results (2026-10-21)

Windows client against myserver2 on the Linux test server (game port 48261, mod-sync port 48310), test item
3823294577 with a small Java mod (`dont39modsynctest`, `Main` prints its version): server on version 4, client on 5.
Driven by a temporary Lua driver (real button callbacks, state written to a file; removed after), checked in the
driver log, console.txt and window captures.

- Bugs found and fixed on the way:
  - Zabric read the side from `sun.java.command`, empty at premain on a `ProjectZomboid64.exe` (JNI) launch, so no
    request was read. Now premain peeks both sides' files and preLaunch takes its own (Zabric design "Request file").
  - Core: vanilla `ServerWorkshopItemScreen` hides the Connect screen during a Workshop download and never restores
    it, so the confirm dialog floated over the main menu and a stop's reason was lost (blank screen). mod-sync now
    shows the Connect screen again while it holds the join (`restoreScreen`). Not re-run in game: it needs a vanilla
    Workshop download during the join, i.e. another upload.
  - CN line breaks: "Java 代码" broke at the space; a closing "。" could start a line.
- Round 1 (mod not on the main menu): join → Connect screen with status lines → confirm with the Java line,
  "don't ask again" ticked → 6 files downloaded → stop with the restart reason → Quit game (button next to Back,
  geometry logged) quits. Next launch: `launch override` applied at preLaunch, Rejoin offer, rejoin without a second
  confirm, the join pass loads the tree's jar: `java version 4` + Lua `version 4`, "runs the server's Java code".
  Next launch: no override; join to the remembered server still asks (J4, 0 bytes to download); decline shows its
  reason.
- Round 2 (mod on the main menu): startup loads the client's `java version 5`; join → confirm → restart stop →
  Quit. Override launch: the startup pass loads the tree's jar (`java version 4`, Lua `version 4` at the main menu),
  rejoin without confirm, served by the tree. Next launch: back to `java version 5` from the Steam folder.
- Negative case seen by accident: with the item's Steam folder deleted, Zabric reported the item as not installed and
  the rejoin took the restart path again (as designed).
- Not covered in game: `restoreScreen` (above), `Failed_NotServed`/`Failed_NotLoaded` (unit tests), a denied
  ZombieBuddy approval (this install runs `policy=allow-all`), the preload refusal (unit tests), entering the world
  after the rejoin (the world load stalls while the window is in the background; the session's jar and Lua were
  verified at the join's pass).

## Validation

```bash
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew :mod-sync:test build assembleMod
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew :dont39-all-in-one:smokeTest
```

Before `smokeTest`: the loading-progress session's `Dont39LoadingProgress` preload entry needs that branch's Zabric;
remove it from `~/.zombie_buddy/config.json` with a dated backup for the run and restore it (as in the Zabric child).

## Rollback Points

- After step 2: the hold changes are self-contained; reverting them restores the interim `Failed_Jar`.
- Nothing is released by this child: the release is the parent's integration review (Zabric first, then All-in-One).

## Review Gates

- `trellis-check` after step 5 and after step 7.
- Spec update (3.3): the restart-and-rejoin pattern, if it is reusable beyond mod-sync.
