# Implementation plan: Reduce game load time

Gradle runs on JDK 25: `JAVA_HOME=~/.jdks/graalvm-ce-25.0.2`.

## D1 — `ZBSkipOnlineChecks`

1. Create `zb-skip-online-checks/` modeled on `zabric/build.gradle.kts`: `pz-java` + `pz-mod`, `CheckBootstrapClasses`,
   jar manifest `ZB-Preload: true`, `Zabric-PreLaunch` and `Zabric-Main` attributes, a `checkLoaderRefs` exclusion for
   the module, `assembleMod` into `42/media/java`. Compile-only on Zabric (project dependency) and ZombieBuddy.jar.
   Add a task that copies the jar into `pzDir` for the agent-start way.
2. `mod/42/mod.info` (require/loadModAfter Zabric, `javaPreload=true`, `javaJarFile`, `javaPkgName`, `versionMin`,
   `ZBVersionMin=2.3.4`); `mod/workshop/workshop.txt` draft (not uploaded) and a module README: the Steam ban
   trade-off, `allow-all` only, and the optional `patches_jar` entry with its relative path.
3. Register the module in `settings.gradle.kts`.
4. `Installer` (once per JVM, three advices per design D1, skip counts, errors naming the method and ZombieBuddy's
   version); `PreMain.premain` (agent start, stdout line, error printed then rethrown); the preLaunch entrypoint
   (mod only); the main entrypoint (way, counts, `allow-all` note, stale-copy check). No class named `Main`.
5. Spec: record the loader-target exception (and the `PreMain` for `patches_jar`) in
   `.trellis/spec/mod/zombiebuddy-patching.md` (loader seam section) and list the module in `directory-structure.md`.

## D2 — `missing-anim-folders`

6. Create the feature module (`pz-feature`), register it in `settings.gradle.kts` and in the feature list of
   `dont39-all-in-one/build.gradle.kts`.
7. Facade on `Feature` plus the `AdvancedAnimator.searchFolders` hook object (design D2); call `init()` from
   `AllInOneMain.kt`.
8. Add the feature section to `dont39-all-in-one/mod/workshop/workshop.txt`; bump the All-in-One version.

## Validation

- Build gate: `./gradlew build assembleMod`; check both jars hold their glue/listings and manifest attributes.
- Mod only: `deployWorkshop` both mods, enable `ZBSkipOnlineChecks`, launch twice (the first registers the preload and
  asks for a restart), join `myserver1`; check AC1 and AC2 against the 2026-10-17 02:53 baseline.
- Agent start: copy the jar into the game folder, add the `patches_jar` entry (back up `ProjectZomboid64.json` first),
  launch and join again; check AC1b.
- Report menu, join and process-start-to-log times next to the baseline (~65 s, ~89 s, 28 s).

## Rollback points

- D1 and D2 are separate commits; either reverts alone.
