# Implementation plan

Build with `JAVA_HOME=~/.jdks/graalvm-ce-25.0.2`. Back up Loading Progress reports before any game run
(`backup-lp-reports-before-tests`) and check the game's mod list before smoke/in-game runs. Workshop uploads are the
user's; stage content only.

1. Zabric `PreloadConfig`: order by `require` (design 3), with unit tests (AC4). Version bump in
   `gradle/libs.versions.toml`.
2. ZabricKotlin: `PreMain`, `Main` with two paths, preLaunch entrypoint, `mod.info` and manifest (design 2). Version
   bump. `checkBootstrapClasses` passes.
3. Texts: `PreLaunchEntrypoint` javadoc, `zabric/README.md`, `zb-kt/README.md`, Zabric's and ZabricKotlin's Workshop
   texts, All-in-One's load-order line, the build-script comments in `loading-progress` and `faster-loading` (AC6).
4. `ServerSmokeTest`: replace the leftover-entry case with the two-run flow of AC1. Update `GameSmokeTest`'s
   ZabricKotlin lines if the ready line changes (`buildSrc/src/main/kotlin/smoke/GameSmokeTest.kt:87,116`).
5. Gate: `./gradlew build`, `:zb-kt:serverSmokeTest`, `:dont39-all-in-one:smokeTest`.
6. In game (AC3): remove the ZabricKotlin entry from `~/.zombie_buddy/config.json`, launch, check All-in-One and the
   dialog, restart, check the ready line and no dialog.
7. Spec update: `kotlin-runtime.md` (Library, Dependent modules), `access-widening.md` (Preload Registration),
   `zombiebuddy-patching.md` (Loading) where they state the old rules.

Rollback point: each step is a separate commit; steps 1 and 2 can ship apart (1 alone changes nothing visible).
