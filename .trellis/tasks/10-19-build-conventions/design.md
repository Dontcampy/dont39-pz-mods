# Design: build conventions

## Convention plugins (buildSrc)

| Plugin / file | Owns | Applied by |
|---------------|------|------------|
| `pz-jvm` (new) | toolchain and release levels, one constant each; no zb-gradle-plugin | `pz-java`, `zabric-processor`, `zb-kt-utils-processor` |
| `pz-java` | as today minus the levels; exposes the game jar through a typed extension (`PzGame.gameJar`) | as today |
| `pz-junit` (new) | junit BOM + jupiter + launcher, `useJUnitPlatform()` | `zabric`, `zabric-processor`, `zb-skip-online-checks`, `zb-kt-utils-processor`, `tunnel` |
| `pz-bootstrap` (new) | `checkBootstrapClasses` register + `check`/`jar` wiring; ZombieBuddy on test classpaths + self-attach `jvmArgs` | `zabric`, `zb-skip-online-checks`, `zb-kt` (bootstrap check only; it has no tests) |
| `pz-mod` | mod.info parsed once into a typed `PzMod` extension (`modId`, `javaPkgName`, `pzUserDir`); jar into `assembleMod` `42/media/java`; consumable variant with the assembled mod folder | the four shipped mods |
| `pz-feature` | as today; glue values from the bundling mod's `mod.info` (see below); consumable variants for feature data | feature modules |
| `PzSmoke.kt` | shared smoke helpers (`kotlinVersions`, order check, `lines(dir)`) | `zb-kt`, `dont39-all-in-one` |
| `CheckGlue` (new task class) | today's `checkGlue` logic with `@InputFiles` listing dirs | `dont39-all-in-one` |

`pz-bootstrap` may be split if a module needs only part of it; the implementer keeps one place per concern.

## Cross-project data without model access (R7)

| Today | After |
|-------|-------|
| all-in-one sets each feature's `PzGlue` | `pz-feature` reads `id=` / `javaPkgName=` from the bundling mod's `mod/42/mod.info` by path (single bundler: `dont39-all-in-one`; path in one constant in `pz-feature`, overridable by a gradle property). `PzGlue` stays the typed holder. |
| all-in-one reads features' `SourceSetContainer` output for the jar and listing dirs | resolvable configuration over the features' `classes` + `resources` secondary variants of `runtimeElements` (java plugin provides them) |
| all-in-one reads features' `src/main/lua`, `src/main/media` | each feature (pz-feature and Lua-only modules) publishes a `pzModFiles` variant (lua + media dirs) |
| all-in-one reads features' `WidenJars.expanded` | `pz-feature` publishes a `pzClassTweaker` variant when the feature has a `.classtweaker` |
| `project(zabric).extra["modId"]` (all-in-one, zb-kt) | read from zabric's `mod.info` by path, or from the `pzModFolder` variant's folder name |
| `zb-kt` reads `project(utils)` source sets | `classes`/`resources` variants of `zb-kt-utils` |
| `zb-kt` uses `project(zabric).tasks.named("assembleMod")` | `pzModFolder` variant of `zabric` |

Attributes: one custom `Usage`-like attribute (`pz.artifact`: `mod-files`, `classtweaker`, `mod-folder`,
`feature-init`) declared in a buildSrc Kotlin file.

## Feature registration (R6)

- Each `pz-feature` module publishes a `feature-init` variant: a one-line text file with the facade's FQN. The facade
  name follows the existing convention (package `com.dont39.pz.<name without dashes>`, object `<PascalName>`, see
  `featureName` in `pz-feature.gradle.kts`); the KSP processor already receives `dont39.feature`, so it can emit the
  file and fail when the facade object is missing.
- `dont39-all-in-one` resolves those files and generates `GeneratedFeatures.kt` (`fun initFeatures()` calling each
  facade's `init()` in the order of the `features` list), added to its main source set. `AllInOneMain` calls
  `initFeatures()`. Typed calls, no reflection or ServiceLoader (spec forbids reflection in feature code).
- Lua-only modules declare their smoke status line in their own build (`pzLuaOnly { readyLine = ... }` or the
  `mod-files` variant metadata); `smokeTest` collects them. `luaTargeted` remains for Lua-only features with a target.
- Remaining manual steps: module dir, `settings.gradle.kts`, the all-in-one `features` list, `workshop.txt`,
  `mod.info` target mods, version bump on release.

## Configuration-time evaluation (R8)

- `pz-feature`'s `PzTargets` takes a `Provider<Directory>` for the Workshop root instead of `file(pzDir.get())`.
- `deployWorkshop`, `serverSmokeTest`, `smokeTest` map providers instead of calling `.get()` at configuration.
- `pz-kotlin` reorder: use `afterEvaluate`-free ordering (e.g. `compileOnly` priority through a dedicated configuration
  placed first) or document the constraint at the call site; the implementer picks the variant that keeps the game
  jar first and fails loudly if something is added after.

## Compatibility

- Shipped artifacts unchanged (AC7 compares staged folders; jars compared entry by entry, not by bytes).
- `release-zabric.yml` uses only `-PpzDir`; unaffected by R2.

## Risks / rollback

- KGP/KSP may not be Isolated-Projects clean; AC3 accepts third-party-only violations.
- Variant-aware resolution can pull transitive features' classes twice; `duplicatesStrategy = FAIL` on the jar
  catches it.
- Each requirement is a separate commit; revert per commit.
