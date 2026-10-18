# Implement: build conventions

Gradle runs on JDK 25: `JAVA_HOME=~/.jdks/graalvm-ce-25.0.2`.

## Baseline (before any edit)

1. `./gradlew build assembleMod deployWorkshop` on the current branch; copy each mod's staged Workshop folder,
   `*/build/generated/ksp` and every `META-INF/zabric/hooks` dir to the task's scratch folder (outside the repo).

## Checklist (one commit each)

1. R1 boilerplate + unused catalog entries + README module map.
2. R2 move `pzDir`/`pzServerDir` to `~/.gradle/gradle.properties` (keep existing content of that file), drop them from
   the repo file, update README and `quality-guidelines.md#build-gate`.
3. R3 `pz-jvm`, `pz-junit`, `pz-bootstrap`; jar into `assembleMod` in `pz-mod`; strip the copies from module scripts.
4. R4 `PzMod` / `PzGame` extensions; replace `extra[...]`; single Workshop-root constant.
5. R5 `PzSmoke.kt` helpers; `CheckGlue` task class.
6. R7 variants and `pz.artifact` attribute; remove `evaluationDependsOn` and every cross-project model access.
7. R6 `feature-init` variant + `GeneratedFeatures.kt`; Lua-only ready lines from their modules; `AllInOneMain` uses
   `initFeatures()`.
8. R8 lazy providers; `pz-kotlin` reorder.
9. Specs: `directory-structure.md#adding-a-feature`, `quality-guidelines.md` (build gate, tests convention),
   `kotlin-runtime.md` if `pz-kotlin` changed.

## Validation

- After each step: `./gradlew build assembleMod`.
- End: `./gradlew check`, `:dont39-all-in-one:smokeTest`, `:zb-kt:serverSmokeTest` (run twice after a fresh preload
  registration), `deployWorkshop` diff against the baseline (jar entries + other files), ksp/listing diff,
  `./gradlew help --configuration-cache`, `./gradlew help -PpzDir=`, and one attempt with
  `-Dorg.gradle.unsafe.isolated-projects=true` (record result).

## Rollback points

Steps 6 and 7 are the risky ones (resolution and generated source); keep them as separate commits.
