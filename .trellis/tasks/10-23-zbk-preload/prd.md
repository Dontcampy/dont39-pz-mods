# ZabricKotlin as a preload mod

## Goal

ZabricKotlin (`zb-kt`) becomes a preload mod and installs the current Kotlin at premain, so code that runs at game
start (preLaunch entrypoints, hooks of preloaded mods) can use it. On a launch where it is not preloaded it still
installs Kotlin from its ZombieBuddy `Main` as today, so Kotlin mods keep working on every launch, and the player is
asked to restart once. No mod is converted in this task.

## Background

- Today ZabricKotlin is not a preload mod (`zb-kt/mod/42/mod.info`). Its ZombieBuddy `Main` swaps the stdlib
  (`zb-kt/src/main/java/com/dont39/pz/zbkt/Main.java`, `KotlinSwap.java`) in the MAIN pass, before any dependent.
  `KotlinSwap.install` refuses once any `kotlin.*` class is loaded and needs Zabric's `Loader.instrumentation()`.
- History: `10-13-zb-kt-on-zabric` D6 swapped at premain only, with an `isReady()` gate in dependents;
  `10-13-zabric-load-order` moved to a MAIN-only swap with no preload and no readiness checks. Standing rules kept here:
  no readiness checks or degraded mode in dependents; ZabricKotlin handles its own not-preloaded launch
  (`.trellis/spec/mod/kotlin-runtime.md`).
- Because of this, code that runs at game start must be Java: `PreLaunchEntrypoint.java` javadoc,
  `zabric/README.md:17`, `zb-kt/README.md:11`, and the comment at the top of `loading-progress/build.gradle.kts` and
  `faster-loading/build.gradle.kts`.
- ZombieBuddy preload (`external-repos/ZombieBuddy/.../Loader.java` `preloadMods`, ~401-465): entries run in
  `preload_mods` order; each jar is added, its `PreMain` called and its `javaPkgName` classes loaded. A preloaded jar's
  `Main` still runs in the MAIN pass. Entries whose jar is missing or fails its checks are dropped.
- Zabric moves only itself to the front of `preload_mods` (`zabric/.../loader/PreloadConfig.java`
  `withZabricFirst`). It registers mods that declare `Zabric-PreLaunch` (`zabric/.../entrypoint/Lifecycle.java`
  `load`, `onZabricMain`; `Preload.java`); a new registration goes on `RestartList`: a dialog on the client's main
  menu, a "restart the server once" line on a dedicated server.
- Zabric (3820726385) and ZabricKotlin (3820734224) are published, so player compatibility matters.

## Decisions

- D1 Scope (user, 2026-10-23): ZabricKotlin and the Zabric support it needs only. Loading Progress and Faster Loading
  stay Java. (Replaces an earlier choice to rewrite them.)
- D2 Two swap points (user): premain when ZabricKotlin is preloaded; its ZombieBuddy `Main` when it is not, as today.
  (Replaces an earlier premain-only choice.)
- D3 Restart (user): the existing restart prompt (`RestartList` dialog / server log line). No automatic restart.
  (Replaces an earlier automatic-restart choice.)
- D4 (planning): Zabric orders `preload_mods` by `require`, Zabric first, so ZabricKotlin precedes any preload mod
  that requires it. Zabric never names ZabricKotlin.

Design: `design.md`. Plan: `implement.md`.

## Requirements

- R1 (D2) ZabricKotlin is a preload mod: `javaPreload=true`, `ZB-Preload: true`, and a preLaunch entrypoint so Zabric
  registers it. Its ZombieBuddy `PreMain` installs Kotlin.
- R2 (D2) In the MAIN pass its `Main` installs Kotlin only when premain did not, then runs the self-check and logs the
  ready line with versions, or an error naming the cause. The ready line tells whether Kotlin was installed at game
  start.
- R3 (D3) On a launch where ZabricKotlin was not preloaded, Zabric registers it and the existing restart prompt lists
  it; Kotlin mods still work that launch.
- R4 (D4) Zabric keeps `preload_mods` ordered by `require`, Zabric first, and rewrites the file when the stored order
  is wrong.
- R5 Contract texts and specs state the new rules: `PreLaunchEntrypoint` javadoc, `zabric/README.md`,
  `zb-kt/README.md`, Zabric's and ZabricKotlin's Workshop texts, All-in-One's load-order line, and the spec files
  named in `implement.md`.

## Acceptance criteria

- AC1 (R1-R4) `zb-kt` `serverSmokeTest`: a first run without a ZabricKotlin entry installs Kotlin from `Main`, logs the
  ready line, registers ZabricKotlin right after Zabric in `preload_mods` and logs "restart the server once"; a second
  run installs Kotlin at premain, logs the ready line, and no registration or restart line.
- AC2 (R2) Client `smokeTest` of All-in-One with entries current: ZabricKotlin ready from game start, All-in-One
  features ready, restart notice "not needed".
- AC3 (R3) In game on this PC with the ZabricKotlin entry removed: All-in-One works, the main menu asks for a restart
  naming ZabricKotlin; after the restart ZabricKotlin is ready from game start and no dialog shows.
- AC4 (R4) Unit tests for the order: require order, Zabric first, stable otherwise, cycle kept with an error, rewrite
  when the stored order is wrong.
- AC5 `./gradlew build` passes, including `checkBootstrapClasses` and `checkLoaderRefs`.
- AC6 (R5) No text in the repo outside task archives still says code at game start must be plain Java because
  ZabricKotlin is not preloaded, except the two build-script comments in Loading Progress and Faster Loading, which
  are reworded to their real reason (their hooks are written for the javac glue processor).

## Out of scope

- Rewriting Loading Progress or Faster Loading in Kotlin; a KSP glue mode for standalone preload mods.
- Automatic restart.
- Converting other Java modules; changing ZombieBuddy.
- Workshop uploads (the user uploads; Zabric before ZabricKotlin).
