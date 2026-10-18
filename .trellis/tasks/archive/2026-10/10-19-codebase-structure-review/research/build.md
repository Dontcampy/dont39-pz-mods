# Build logic and small modules

Overall: build is in good shape; five of six feature modules have a 3-line `build.gradle.kts` (`pz-feature`),
`mod-options-sidebar` a 3-line `pz-kotlin`. buildSrc task classes are typed with declared inputs/outputs. Do not merge
the tiny modules: each has its own `Feature` gate, status line checked by `smokeTest` and glue class.

## B1. Duplication across the four `pz-mod` modules (low effort)

- Jar into `assembleMod` (`from(tasks.jar) { into("42/media/java") }`) repeated in `zabric/build.gradle.kts:65-67`,
  `zb-skip-online-checks/build.gradle.kts:43-45`, `zb-kt/build.gradle.kts:95-97`,
  `dont39-all-in-one/build.gradle.kts:115-117` → belongs in `pz-mod.gradle.kts:27-37`.
- `checkBootstrapClasses` register + `check`/`jar` dependsOn identical in `zabric:28-33,60`,
  `zb-skip-online-checks:16-21,32`, `zb-kt:78-86`.
- Java test setup (ZombieBuddy `extendsFrom` on test classpaths, junit BOM/jupiter/launcher, `useJUnitPlatform()`,
  self-attach `jvmArgs`) copied between `zabric:36-52` and `zb-skip-online-checks:63-78`; junit part in
  `zabric-processor:33-35,44`.
- Toolchain/release copied: `zabric-processor:18-27` repeats `pz-java:13-22` because `pz-java` also applies
  zb-gradle-plugin. Literals 25/17 in `pz-java:15,20`, `pz-kotlin:16,19-20`, `zabric-processor:20,25`,
  `zb-kt-utils-processor:7`, `buildSrc/build.gradle.kts:9`. Fix: plugin-free `pz-jvm` base plugin.
- Stringly `extra["pzGameJar"]` (`pz-java:28`, `pz-feature:58`, `pz-kotlin:35`, `zabric:39`), `extra["modId"]` /
  `extra["pzUserDir"]` (`pz-mod:17-18`, unchecked casts `zb-kt:105-106`, `all-in-one:14-16,33`, `zb-skip:28`) → typed
  `PzModExtension` like `PzGlue`/`PzTargets`. `mod.info` parsed twice (`pz-mod:9-11` id, `all-in-one:41-42`
  javaPkgName).
- Workshop root `../../workshop/content/108600` duplicated: `pz-feature:45`, `zb-kt:114`.

## B2. Smoke tests in module scripts (medium effort)

- `zb-kt` lines 101-256 (`serverSmokeTest`) ≈ 60% of the file; `all-in-one` 123-227 (`smokeTest`) ≈ 45%. `PzSmoke.kt`
  exists. Duplicates: `kotlinVersions` (`zb-kt:142-145`, `all-in-one:141-144`), "lines out of order" check
  (`zb-kt:203-208`, `all-in-one:222-225`), `lines(dir)` twice in `all-in-one:87-88,170-171`.
- `checkGlue` (`all-in-one:77-113`) has no declared inputs/outputs, string task path `"$it:processResources"` (`:81`)
  → typed task class.

## B3. Feature registration fan-out (medium effort)

Adding a feature touches: `settings.gradle.kts`, all-in-one `features` list (`build.gradle.kts:17-24`),
`AllInOneMain.kt:20-24` init call, `workshop.txt`, `mod.info` (if target mods), Lua-only smokeTest line
(`all-in-one:201`). Spec checklist `.trellis/spec/mod/directory-structure.md:84-100` has 9 steps. Options: derive the
features list from projects applying `pz-feature`; generate the init registry (KSP / ServiceLoader).

## B4. Stale leftovers (trivial)

- `settings.gradle.kts:1-4,7,15,19-21` Gradle-init comments (mention `app`/`utils`); `README.md:3-23` init text.
- Init comments in `buildSrc/build.gradle.kts:2-4`, `buildSrc/settings.gradle.kts:3`, `libs.versions.toml:1-3,39`,
  `gradle.properties`.
- Unused catalog: `kotlinxDatetime` (`libs.versions.toml:7,31`), bundle `kotlinxEcosystem` (`:41`), plugin alias
  `kotlinPluginSerialization` (`:44`).
- Dead code `all-in-one:202-206` maps an empty list (leftover of removed Lua-only target-mod feature), concatenated at
  `:208`.
- `gradle.properties` tracked with machine paths (`pzDir`, `pzServerDir`); CI overrides via `-PpzDir`.

## B5. Architecture / configuration (optional)

- Cross-project configuration: all-in-one `evaluationDependsOn` (`:30-32`), writes features' `PzGlue` (`:43-49`), reads
  their source sets (`:53,57,69`) and `WidenJars` (`:63`), `project(zabric).extra` (`:33`); `zb-kt` reads
  `project(utils)` source sets (`:87`), `project(zabric).tasks.named("assembleMod")` (`:120`). Works with config cache;
  blocks Isolated Projects. Cleaner: consumable configurations/variants. Only worth it if Isolated Projects matters.
- Eager `.get()` at configuration: `pz-feature:45` `file(pzDir.get())` (every feature fails to configure without
  `pzDir`), `pz-mod:43`, `zb-kt:118,139-141`, `all-in-one:131-132`.
- `pz-kotlin:37-41` replaces `libraries` inside `configureEach`; later classpath additions silently dropped. Fragile.
