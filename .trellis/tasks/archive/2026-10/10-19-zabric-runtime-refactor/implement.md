# Implement: Zabric runtime refactor

## Baseline

`javap -public -classpath zabric/build/libs/*.jar` over every public class → saved list; a `smokeTest` console.txt
copy; listings from the processor-refactor baseline.

## Checklist (one commit each)

1. R1 `internal/Log`, `internal/Jars`.
2. R5 `HookListingFormat` shared srcDir (touches `zabric-processor/build.gradle.kts` and `glue/Names`).
3. R3 `TargetCheck` (+ tests if uncovered).
4. R4 `Entrypoints`.
5. R2 AccessWidener split, `problem()` side effect moved.
6. Spec pointers.

## Validation

- Each step: `./gradlew :zabric:check :zabric-processor:check`.
- End: `javap` diff, listing/glue diff, both smoke tests with console line comparison.
