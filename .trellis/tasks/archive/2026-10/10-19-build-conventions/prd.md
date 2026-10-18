# Build logic cleanup and conventions

Parent: [10-19-codebase-structure-review](../10-19-codebase-structure-review/prd.md). Evidence:
[research/build.md](../10-19-codebase-structure-review/research/build.md) (B1–B5). Runs first: children 2 and 4 use
its test convention.

## Goal

Module build scripts hold only what is specific to the module; shared wiring lives in typed buildSrc conventions;
adding a feature touches fewer places; no project reads or writes another project's model.

## Requirements

- R1 (B4). Remove Gradle-init boilerplate from `settings.gradle.kts`, `README.md`, buildSrc scripts,
  `libs.versions.toml`, `gradle.properties`; remove unused catalog entries `kotlinxDatetime`, bundle
  `kotlinxEcosystem`, plugin `kotlinPluginSerialization`. README gets a short module map and the local setup.
- R2 (D2). `pzDir` and `pzServerDir` leave the tracked `gradle.properties`; the user's
  `~/.gradle/gradle.properties` gets the current values; README and `quality-guidelines.md#build-gate` say where they
  go. CI (`release-zabric.yml`) keeps working.
- R3 (B1). One place each for: jar into `assembleMod`; `checkBootstrapClasses` wiring; JUnit setup; the ZombieBuddy
  test classpath and self-attach; Java toolchain/release levels (a plugin-free JVM base the processors can apply).
- R4 (B1). Typed extensions replace `extra["pzGameJar"]`, `extra["modId"]`, `extra["pzUserDir"]`; `mod.info` is
  parsed once (id and javaPkgName); the Workshop content path is defined once.
- R5 (B2). Smoke-test helpers shared by `zb-kt` and `dont39-all-in-one` live in `PzSmoke.kt`; `checkGlue` is a typed
  task with declared inputs. `luaTargeted` stays as the documented slot.
- R6 (B3). Adding a feature needs no edit to `AllInOneMain.kt` and no hand-written `smokeTest` line for Lua-only
  features.
- R7 (B5). No build script or convention reads or configures another project's model (`evaluationDependsOn`,
  `project(x).extra`, `.tasks`, `.the<...>()`, `extensions.findByType`); cross-project data flows through
  dependencies/variants.
- R8 (B5). No eager `Provider.get()` at configuration time for `pzDir`, `pzServerDir`, `workshopDir`; a feature
  project configures without `pzDir`. `pz-kotlin`'s classpath reorder no longer drops later additions silently.

## Acceptance Criteria

- [ ] AC1. Parent AC1 and AC2 hold.
- [ ] AC2. `./gradlew help --configuration-cache` passes; `git grep` finds none of the R7 patterns in
  `*.gradle.kts` / buildSrc outside `dependencies {}` project references.
- [ ] AC3. A build with `-Dorg.gradle.unsafe.isolated-projects=true` was attempted; it passes, or the remaining
  violations are only in third-party plugins (KGP/KSP) and are listed in the task report.
- [ ] AC4. With the two lines removed from the repo's `gradle.properties` and set in `~/.gradle/gradle.properties`,
  the build gate passes; `./gradlew help -PpzDir=` (empty) configures every feature project.
- [ ] AC5. `zb-kt/build.gradle.kts` and `dont39-all-in-one/build.gradle.kts` are each under ~140 lines.
- [ ] AC6. `directory-structure.md#adding-a-feature` lists the reduced steps; `quality-guidelines.md` build gate
  section matches R2.
- [ ] AC7. `deployWorkshop` output for the three mods is identical before and after (diff of the staged folders).
