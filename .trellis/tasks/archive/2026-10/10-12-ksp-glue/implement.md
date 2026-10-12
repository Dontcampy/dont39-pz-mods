# Implement: KSP glue generation

## Checklist

1. Spike (design.md "Spike"); write `research/ksp-spike.md`. Stop here on failure (prd D2) and tell the user.
2. Version catalog + `buildSrc/build.gradle.kts` KSP plugin dependency.
3. Annotations in `zombiebuddy-kotlin-utils` (`glue/` subpackage).
4. `zombiebuddy-kotlin-utils-processor` module: provider, processor (validation R4, codegen R2/R3, resource R5);
   register in `settings.gradle.kts`.
5. `buildSrc/src/main/kotlin/pz-feature.gradle.kts`.
6. `dont39-all-in-one/build.gradle.kts`: smokeTest reads the glue resources; remove hard-coded patch/Lua lines.
7. Hand off to `10-12-viewpoint-kotlin` (switch the module to `pz-feature`, annotate, delete hand-written glue).
8. Specs (prd R7).

## Validation

```
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew build assembleMod --configuration-cache
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew build --configuration-cache   # second run: cache reused
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew :dont39-all-in-one:smokeTest
```

- Inspect `viewpoint-blood-fx-fix/build/generated/ksp/main/kotlin/com/dont39/pz/allinone/`.
- `javap -v` generated patch classes (prd AC).
- Temporarily trigger each R4 error; revert.

## Risky Points

- KSP2 vs Kotlin 2.4.21 / Gradle config cache (spike).
- `pz-kotlin`'s reordering of `KotlinCompile.libraries` also applies to KSP's compile tasks if they are
  `KotlinCompile` subtypes — check it does not drop or misorder KSP's classpath.
- Generated advice must still apply: smokeTest `Transformed:` lines are the proof.
