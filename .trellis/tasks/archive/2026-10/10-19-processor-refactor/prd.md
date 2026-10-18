# Hook processor refactor and KSP tests

Parent: [10-19-codebase-structure-review](../10-19-codebase-structure-review/prd.md). Evidence:
[research/zabric-processors.md](../10-19-codebase-structure-review/research/zabric-processors.md) (P1–P5, dead code).
Runs after 10-19-build-conventions (uses `pz-junit`).

## Goal

The KSP front end is tested like the javac one, and each processor class has one job, with generated output
unchanged.

## Requirements

- R1 (P1). JUnit tests for `zb-kt-utils-processor` covering: nullability rules (`HookReader.kt:180-185`), facade gate
  (`GlueProcessor.kt:153-170`), object/visibility rules, `@LuaGlobal` bridge output, JVM descriptor parsing
  (`KspTypes.kt:213`), and one golden glue + listing for a representative hook set (mirroring the javac `GoldenTest`).
- R2 (P2). Reading javac elements into `HookDecls` moves out of `ZabricProcessor` into `JavacHookReader`, mirroring
  `HookReader.kt`; `ZabricProcessor` keeps round handling, `@Patch`/stray policing and file generation.
- R3 (P3). The `@LuaGlobal` bridge moves out of `GlueProcessor.kt` into its own file.
- R4 (P4). Messages repeated word for word by both front ends live in `processor/glue/Messages` (the three listed in
  the research, plus any other found while moving code).
- R5 (P5). `HookAnalyzer` splits into handler binding (shape, member resolution, parameter binding, modifiedArg) and
  patch building (patch assembly, share/always and access checks, matcher simulation, naming); `bind` is split into
  its INJECT and MODIFY paths. `HookAnalyzer` remains the entry point the front ends call.
- R6. Dead code removed: `Names.isPrimitive`, `Reporter.warning` (interface method and both implementations);
  `GlueWriter.listingPath` narrowed if nothing outside the package uses it.

## Acceptance Criteria

- [ ] AC1. `./gradlew :zabric-processor:check :zb-kt-utils-processor:check` pass; R1 tests exist and fail when the
  rule they cover is removed (checked once by temporarily breaking one rule per area).
- [ ] AC2. Existing `GoldenTest`, `HookRulesTest`, `LoaderPatchTest` pass unchanged (no expected-output edits).
- [ ] AC3. Parent AC2: generated glue and listings of all features byte-identical to the baseline;
  `samples/zabric-java-hooks` builds and its generated sources are identical.
- [ ] AC4. No file in `zabric-processor/src/main` or `zb-kt-utils-processor/src/main` exceeds ~400 lines.
- [ ] AC5. `zabric-hooks.md` "where it lives" section points at the new files.
