# Implementation plan: Loading progress without a frozen window

Gradle runs on JDK 25: `JAVA_HOME=~/.jdks/graalvm-ce-25.0.2`. DI's Zabric and build line numbers predate the 0.4.1
refactor; go by the symbols in `design.md`.

## Z — Zabric

1. `Entrypoints`/`Lifecycle`: `Zabric-Hooks` constant and value check in `Entrypoints.declared` (`Declared` grows the
   flag); `Lifecycle.preLaunch` collects preloaded mods with a preLaunch entrypoint and calls `HookRuntime.preLaunch`;
   error for the attribute on a mod that is not preloaded; `start()` replays held hook lines after the existing
   reports.
2. `HookRuntime`: `preLaunch(...)`, buffering `Log` until `start()`, `passProcessed` keeps launch-wide mods and does
   not re-hold open checked mods, `closeAll` clears both new flags.
3. `ModState`: `preLaunchOpen`, `launchWide`; `open`/`live` per design.
4. `TargetCheck`: bytes overload with the reflective check's messages. `WeaveCheck`: a preLaunch registration
   (watched table as in `afterPass`) and the byte check on first load for mods opened at preLaunch, reported through
   the mod's gate in the same `transform` call.
5. Tests (`HookRuntimeTest`, `TargetCheckTest`, the weave tests under `hook/weave/`, a new `Entrypoints` manifest
   test): AC1 cases.
6. Docs: `ModHooks`/`Lifecycle` Javadoc, `zabric/mod/workshop/workshop.txt` (timing, attribute, client-UI-only note,
   handler signatures of preloaded mods), `.trellis/spec/mod/access-widening.md` (preLaunch rule: no game class, logs
   held), `zabric-hooks.md`. Version 0.4.1 → 0.5.0 in `gradle/libs.versions.toml`; the sample's `zabricVersion` follows in its own commit after the release.
7. Commit Z.

## P — `loading-progress`

8. Build:
   - a widening convention plugin taken out of `pz-feature.gradle.kts` (`widenCompileJars`, the compile-jar swap),
     applied by `pz-feature` and this module; `pz-feature` behavior unchanged (`dont39-all-in-one` builds the same
     jar);
   - `pz-mod`: jar folder property, default `media/java`;
   - `loading-progress/build.gradle.kts` after `zb-skip-online-checks`: `pz-java` + `pz-mod` + `pz-bootstrap`,
     `zabric-processor` as javac processor with the two `-A` options from `pzMod`, manifest attributes
     (`ZB-Preload`, `Zabric-PreLaunch`, `Zabric-Main`, `Zabric-Hooks: launch`), the `.classtweaker` into
     `META-INF/zabric`, `checkLoaderRefs` exclusion for the glue; `settings.gradle.kts`;
   - a check task on the module's glue classes: every advice parameter and return type is a JDK type (D9), wired
     into `check` and `jar`;
   - `smokeTest` registration; make the `GameSmokeTest` inputs only the All-in-One needs optional.
9. `mod/42/mod.info`, `poster.png`, `mod/workshop/preview.png`, `workshop.txt` (EN + CN), README, `.classtweaker`.
10. `progress` (flows, steps, state), `text` (EN/CN tables), `draw` (overlay at `EndFrameUI` HEAD, `FramePump`),
    `hooks` (boundaries and per-item triggers from design; javap descriptors from DI B5), `entrypoint`.
11. Frame-gap log line per flow end.
12. Spec: `directory-structure.md` layout entry and the widening plugin; `quality-guidelines.md` smoke-test lines for
    the new module; a note in `zombiebuddy-patching.md`/`zabric-hooks.md` if a new rule came out of P.
13. Commit P.

## Validation

- `./gradlew build assembleMod` (incl. `:zabric:test`, `:dont39-all-in-one:checkGlue`),
  `:loading-progress:smokeTest`, and `:dont39-all-in-one:smokeTest` (the widening and `GameSmokeTest` changes touch
  it).
- Check the jar: glue and listings in the root package, manifest attributes, jar under `media/java/client`, no game
  types in the glue's advice signatures (AC2, also enforced by the build check of step 8).
- In game (deploy Zabric and the mod locally, launch twice: the first registers the preload):
  - launch → menu with a screen recording (AC3), language CN and EN (AC6);
  - join `myserver1` with and without the mod in `Mods=`, and once with a client/server version mismatch (AC4),
    single player with and without a mod-list change (AC5);
  - with Dont39 All-in-One: join the `myserver2` test instance on the Linux server through TCP and KCP tunnels, and
    once with a wrong tunnel port (AC4b; recipe in the linux test server notes, restore its ini afterwards);
  - forced mismatch build (AC8);
  - timings with and without the mod, two runs each (AC7), from the flow-end log lines and DebugLog timestamps.
- Report each AC as passed, failed or not run.

## Rollback points

- After step 7 (Z alone is releasable and inert for existing mods).
- P as one commit; disabling the mod restores vanilla loading after a restart.

## Before `task.py start`

- `implement.jsonl` / `check.jsonl` curated (specs + research).
- Back up `~/.zombie_buddy/config.json` before the first in-game launch (preload registration edits it).
