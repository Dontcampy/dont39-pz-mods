# Implement: processor refactor

## Baseline

Reuse the build-conventions baseline of `build/generated/ksp` and listings if no processor change landed since;
otherwise regenerate. Also save `samples/zabric-java-hooks` generated sources.

## Checklist (one commit each)

1. Spike the KSP test harness (design: kctfork vs programmatic KSP2); apply `pz-junit`.
2. R1 tests against the current code (they must pass before any refactor).
3. R6 dead code.
4. R4 shared messages.
5. R3 `LuaBridge.kt`.
6. R2 `JavacHookReader`.
7. R5 `HandlerBinder` / `PatchBuilder`, `bind` split.
8. `zabric-hooks.md` file pointers.

## Validation

- Each step: `./gradlew :zabric-processor:check :zb-kt-utils-processor:check`.
- End: parent AC1 (build gate + both smoke tests), golden diff of all features and the sample, mutation check of AC1.
