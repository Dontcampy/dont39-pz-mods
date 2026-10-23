# Implementation plan: Sync Guard

Order matters: spikes first, each recorded in `research/` before dependent code. Design: `design.md`.

## Checklist

1. Spikes S1-S5 (`design.md#spikes`), with `javap` on `projectzomboid.jar`; results in `research/spike-s1-s5.md` and `research/spike-s2-s4.md` (done 2026-10-24).
   Stop and return to planning if S1 finds no safe raw-packet path or S5 finds reliability/channel cannot change at
   runtime.
2. Module skeleton: `sync-guard/` with `build.gradle.kts` modeled on `loading-progress/build.gradle.kts` (differences
   in `design.md#module-and-packaging-d4`), `mod/42/mod.info`, `mod/workshop/`, translations EN/CN, entrypoint that
   logs `loaded` / `ready` / `not ready: <why>`; register in `settings.gradle.kts`. Build passes, `smokeTest` sees the
   ready line.
   Done 2026-10-24. Carry-overs for the hook steps: drop `checkJdkGlue` and the "JDK types only" note in `Mod.java`
   (only preloaded mods' glue must be JDK-only, `.trellis/spec/mod/zabric-hooks.md:44`); with the first widening list
   add `AccessWidener.report(MOD_ID, MainEntry.class)` to `MainEntry`; set `smokeTest` `hooks = true` with the first
   hook class; add `pz-switches` only if a switch is needed; Workshop preview image before upload.
3. `protocol`: keys, digests, message codecs, trailer; JUnit tests for codec round trips and digest stability
   (order independence of the container digest, field coverage).
4. Activation: trailer write/read, `Hello`, per-connection active set, inactive log lines (R8).
5. Channel change for the four packet types (R2).
6. Server: touch recording, per-connection state and sweep, end-of-tick `SyncFrame` (R1, R3, R4).
7. Client: `SyncFrame` handling, pending unresolved keys, `SnapshotRequest` (R5). Done 2026-10-24 (not run in game).
8. Server snapshots with budget; client in-place reconcile including unequip/unwear (R6). Client half done
   2026-10-24 (not run in game).
9. Open check (R7). Client half done 2026-10-24, with a Lua trigger (`design.md`, phase B deviations).
10. Notice, log lines, counters and summaries (R9). Summary lines are one `[Dont39SyncGuard]` line of plain
    `key=value` numbers (the harness's sampler parses them; server keys include `bytesOut`, `stateEntries`,
    `tickNsP99`), period set by `-Dsyncguard.summarySeconds` (default 600, load runs 5-60). Client side done
    2026-10-24 (`scope=client`).
11. Debug switch for AC1: drop the Nth in-scope delta on the client (sandbox option or system property; off by
    default, logged when on). Done 2026-10-24: `-Dsyncguard.debugDropDelta=<n>`, and `-Dsyncguard.debugFieldDiff=true`
    for the S2 field run.
12. Test harness, not shipped (kept under the task's `research/` with its scripts). Done 2026-10-24 in
    `research/harness/` (see its README; not yet run in game). Open: AC6's second client needs its own user folder
    (try the client's `-cachedir`), the driver does not drink, and the first server start may need a warm-up restart.
    - local modded server setup: a copy of `myserver1` (`~/Zomboid/Server/myserver1.ini`, its sandbox and spawn
      files) under a test server name, its mod list plus Sync Guard, run from `pzServerDir` with `-nosteam` and a short
      `-cachedir`; the mods it needs copied (real copies, not junctions, `robocopy /MT:16` from the Workshop content
      folder) and removed afterwards;
    - a test-driver client mod that, after join, turns on god mode, teleports to the test spot (Rosewood prison or a
      military base on the server's map), and loops looting and moving items for a fixed time; zombies spawned with
      the server's admin commands;
    - a sampler that logs every 5 s: server process CPU and memory (Windows process counters), JVM heap used and the
      mod's state size (from the mod's summary line), server bytes in/out (vanilla network statistics), and the mod's
      own bytes.
13. Smoke and compatibility (AC10): the modded local server with and without Sync Guard, one client joining; diff the
    error lines of both consoles.
14. In-game verification AC1-AC9 on the modded local server; the load scenario (AC7) runs mod off, then mod on, same
    script and duration; results and the off/on table in `research/load-test.md`. Then Workshop staging for the user
    to upload.

## Validation

- Build gate (Gradle needs JDK 25):
  `JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew :sync-guard:build :sync-guard:assembleMod`
- `:sync-guard:smokeTest` (client launch, mod reaches ready) as a quick check during development; check the game's
  mod list first, the launcher rewrites `default.txt`.
- In game: this machine's local dedicated server only (user, 2026-10-24). Client and server run with `-nosteam`
  (Steam joins to a local server hang); back up Loading Progress reports before test runs.
- AC6 and AC7 read the counters summary, the end-of-tick timing and the sampler log.

## Risky points and rollback

- Raw packet path (S1): a wrong id or missed anticheat bypass kicks clients. Test against a vanilla client (AC8)
  before anything else uses the path.
- Channel change: global to the four packet types; verify with a vanilla client on a modded server (AC8) and under
  loss (AC2).
- In-place reconcile of the player inventory: test equipped/worn removal (AC5) before enabling snapshots for the
  player inventory.
- Each step is its own commit on the task branch; the mod is new, so rollback is reverting the branch or leaving the
  mod off the server list.

## Before `task.py start`

- User approved the final planning summary.
- `implement.jsonl` and `check.jsonl` list the spec and research files.
- Branch created from `main`.
