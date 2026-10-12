# Kotlin library for future mods

## Goal

Make Kotlin the language for mod code in this repo. Ship a separate prerequisite **library mod**, "ZombieBuddyKotlin",
that replaces the game's bundled Kotlin stdlib with the current one at runtime and provides kotlinx-coroutines and
kotlinx-serialization. Dependent mods are then written in plain, unrelocated Kotlin. Dont39 All-in-One becomes the first
dependent mod.

User request (2026-10-12): "我想做一个kotlin lib，之后的mod都用kotlin写".

## Decisions (user, 2026-10-12)

- **D1 Distribution:** a separate Workshop library mod; dependent mods `require=` it.
- **D2 No relocation:** dependent mods use plain `kotlin.*` / `kotlinx.*`.
- **D3 Latest Kotlin:** the version pinned in `gradle/libs.versions.toml` (`2.4.21` after the implementation's version check; kotlinx-coroutines `1.11.0`, kotlinx-serialization `1.11.0`); replace the game's
  stdlib at runtime instead of compiling against it.
- **D4 Contents:** Kotlin runtime (stdlib swap + readiness API + Gradle convention for dependent mods) plus
  kotlinx-coroutines and kotlinx-serialization-json. No helper APIs; no kotlinx-datetime.
- **D5 All-in-One migrates in this task:** its `Main` becomes Kotlin and it requires the library mod. Feature module
  `viewpoint-blood-fx-fix` stays Java.
- **D6 Name:** the library mod is `ZombieBuddyKotlin` (mod id, `name=`, jar name, log prefix). Following the repo's
  naming rule, the module is `zombie-buddy-kotlin`, the package is `com.dont39.pz.zombiebuddykotlin`, and the public
  facade class is `ZombieBuddyKotlin`.

## Background (repository / game evidence)

- The old Kotlin is in the game, not in ZombieBuddy: `projectzomboid.jar` bundles kotlin-stdlib **1.4.10**
  (`kotlin.KotlinVersion.CURRENT`), pulled in with okhttp/okio. `ZombieBuddy.jar` has no `kotlin/` classes. No class
  in `projectzomboid.jar` outside okhttp/okio/kotlin references `okhttp3`, `okio` or `kotlin/jvm/internal/Intrinsics`,
  so the vanilla game never loads a `kotlin.*` class itself.
- Classpath: launcher classpath is `.` + `projectzomboid.jar` (`ProjectZomboid64.json`). ZombieBuddy appends each mod
  jar to the system classloader
  (`external-repos/ZombieBuddy/java/src/main/java/me/zed_0xff/zombie_buddy/Loader.java:512`). With parent-first
  delegation, a stdlib inside a mod jar always loses to `projectzomboid.jar`. A jar appended to the **bootstrap**
  search beats both.
- ZombieBuddy keeps the `Instrumentation` in the package-private `Loader.g_instrumentation` (`Loader.java:42`, set in
  `Agent.premain`). There is no public accessor.
- MAIN-phase loading goes one mod at a time, in mod-list order: append jar → call
  `<javaPkgName>.Main.main` → apply patches, which loads every class in exactly `javaPkgName` (not subpackages)
  (`Loader.java:936-940`, `977-990`, `996-1048`; `PatchEngine.java:718-728`). ZombieBuddy catches and logs a
  `Throwable` from `main`.
- The PREMAIN phase needs a signed jar plus a preload approval from each player (`external-repos/ZombieBuddy/doc/ModdingGuide.md`,
  "Preload Mods"). Signing is off in this repo (commit `a80ca29`), so this task does not use PREMAIN.
- ZombieBuddy allows one `javaJarFile` per mod (`ModdingGuide.md:38`). Patch classes are found by a ClassGraph scan of
  `javaPkgName` (`PatchEngine.java:671`), so Kotlin classes are eligible.
- The game JRE is Zulu 25.0.1 (`jre64/release`). Mod code compiles with `release = 17`
  (`buildSrc/src/main/kotlin/pz-java.gradle.kts`).
- Dont39 All-in-One is published (Workshop id `3818388200`). Its `mod.info` requires only ZombieBuddy today.
- The repo currently ships one mod per build (`.trellis/spec/mod/directory-structure.md`). This task makes it two.

## Requirements

- **R1 Library mod module.** A new shipped mod `ZombieBuddyKotlin` (module `zombie-buddy-kotlin`, package
  `com.dont39.pz.zombiebuddykotlin`), with `mod.info`, Workshop folder, `assembleMod` and `deployWorkshop`, built like
  All-in-One. Its jar loads on both client and dedicated server (jar path with neither `client/` nor `server/`).
- **R2 Stdlib swap.** In the library's MAIN-phase `Main`, which is Java and touches no `kotlin.*` type, run these
  steps in order. The first one that fails stops the swap, logs a reason a player can act on, and leaves the library
  "not ready":
  1. Obtain `Instrumentation` from ZombieBuddy.
  2. Check that no `kotlin.*` class is loaded yet.
  3. Check that the bundled kotlin-stdlib jar's SHA-256 matches the value compiled into the mod jar.
  4. Append the jar to the bootstrap search.
  5. Check that `KotlinVersion.CURRENT` equals the expected version.
- **R3 kotlinx.** kotlinx-coroutines-core and kotlinx-serialization-json (with their transitive kotlinx artifacts)
  are on the shared classpath through the library mod, unrelocated, at the versions pinned in the version catalog.
- **R4 Self-check.** Once the swap succeeds, the library runs a one-time self-check. It uses a stdlib API that 1.4.10
  lacks, runs a coroutine, and does a serialization round-trip. It then logs a single "ready" line naming the Kotlin
  version. Any failure logs the reason and leaves the library not ready.
- **R5 Readiness API.** A public Java API (`ZombieBuddyKotlin.isReady()` and the reason when not ready) that dependent mods
  can call before running Kotlin-dependent code.
- **R6 Gradle convention for dependent mods.** A `buildSrc` convention plugin for Kotlin mods: Kotlin JVM with
  bytecode target 17 on toolchain 25, the serialization compiler plugin, and `compileOnly` on the catalog's stdlib and
  kotlinx artifacts. A dependent mod's jar never contains `kotlin/` or `kotlinx/` classes.
- **R7 All-in-One migration.**
  - `dont39-all-in-one`'s `Main` becomes Kotlin.
  - `mod.info` requires and loads after `ZombieBuddyKotlin`.
  - When the library is not ready, `Main` logs one line saying so and why, and still initializes the Java features.
  - `workshop.txt` lists the library as a requirement and puts it in the load order.
  - The version is bumped.
- **R8 Smoke test.**
  - `smokeTest` deploys both mods.
  - It checks the library's loaded and ready lines and All-in-One's loaded line.
  - It keeps the existing feature expectations.
- **R9 Specs.** `.trellis/spec/mod/` describes the second shipped mod, the Kotlin convention, the Java-only bootstrap
  rule, and the ban on bundling `kotlin`/`kotlinx` in dependent mods.

## Acceptance Criteria

- [ ] `./gradlew build assembleMod` succeeds (JDK 25). (R1, R6)
- [ ] The library mod folder contains its jar and the kotlin-stdlib jar of the catalog version. The jar contains
      `kotlinx/coroutines` and `kotlinx/serialization` classes and no `kotlin/` classes. (R1, R3)
- [ ] The All-in-One jar contains `com/dont39/pz/allinone/Main.class` compiled from Kotlin and no `kotlin/` or
      `kotlinx/` classes. (R6, R7)
- [ ] All-in-One's `mod.info` has `\ZombieBuddyKotlin` in `require` and `loadModAfter`. `workshop.txt` names the
      library in requirements and load order. The version is bumped. (R7)
- [ ] With both mods enabled (library first), `smokeTest` passes. `console.txt` shows the library loaded, then ready
      with the catalog's Kotlin version (`2.4.21`) and the self-check passing, then All-in-One loaded, plus every existing feature expectation.
      (R2, R4, R8)
- [ ] Negative check with a corrupted stdlib jar in the deployed library folder: `console.txt` shows the library
      "not ready" with the checksum reason, and All-in-One's line saying the library is not ready. The game reaches
      init, and the Viewpoint feature's own status line still appears. (R2, R5, R7)
- [ ] If the game lets All-in-One load before the library despite `loadModAfter`, All-in-One logs that the library is
      unavailable and does not throw out of `main`. The library then reports "Kotlin already loaded". (R2, R7)
- [ ] Spec files are updated per R9.

## Out of Scope

- Helper APIs (reflection, gating, logging DSL) and kotlinx-datetime / kotlin-reflect.
- Rewriting `viewpoint-blood-fx-fix` in Kotlin.
- PREMAIN/preload loading and re-enabling jar signing.
- Uploading to the Workshop. The first upload of the library, recording its Workshop id, and adding its Workshop link
  to All-in-One's `workshop.txt` are manual release steps after this task.
- Making the library work when another mod loads its own Kotlin first. That case is detected and reported (R2), not
  worked around.
