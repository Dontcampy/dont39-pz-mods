# Zabric runtime refactor

Parent: [10-19-codebase-structure-review](../10-19-codebase-structure-review/prd.md). Evidence:
[research/zabric-processors.md](../10-19-codebase-structure-review/research/zabric-processors.md) (P6–P8). Runs after
10-19-processor-refactor (P7 touches the processor's `glue/Names`).

## Goal

Zabric's large runtime classes each do one job, small helpers exist once, and the hook listing format has one source,
with no change to Zabric's public API or behavior.

## Requirements

- R1 (P6). One definition of the `[Zabric] ` log prefix (Lifecycle, Preload, AccessWidener, HookRuntime,
  RestartList); one helper each for "jar of a class's code source" and "zip entry as UTF-8 lines" (AccessWidener,
  HookListing), in a non-API package.
- R2 (P8). `AccessWidener` keeps its public surface and delegates scanning (premain scan + mod folders) and
  transforming (ByteBuddy adjustments, agent install) to separate classes; diagnosis/verification/reporting stay
  with the facade or move to one of them by cohesion. `problem()` no longer adds to `RestartList` as a side effect;
  the caller does, with the same observable result.
- R3 (P8). `HookListing` keeps record + parsing; the runtime target check (`problem` / `gateProblem` / `missing`)
  moves to `hook.impl.TargetCheck`.
- R4 (P8). `Lifecycle` manifest reading and entrypoint instantiation move to an `Entrypoints` helper.
- R5 (P7). Listing format constants (dir, suffix, header, default gate) defined once in a small class compiled into
  both `zabric` and `zabric-processor` through a shared srcDir, like `zabric/src/classtweaker`; the processor still
  does not link against zabric; `listing.ftl` takes the header from the model.

## Acceptance Criteria

- [ ] AC1. `./gradlew :zabric:check` passes with existing tests unchanged; new unit tests cover `TargetCheck` and the
  moved `problem()` / RestartList behavior if not already covered.
- [ ] AC2. Parent AC3: `javap -public` over the zabric jar's API packages identical to the baseline.
- [ ] AC3. Parent AC2: listings and glue identical (R5 must not change listing bytes).
- [ ] AC4. `:dont39-all-in-one:smokeTest` and `:zb-kt:serverSmokeTest` pass; console shows the same Zabric lifecycle
  and widening lines as the baseline log (order and text).
- [ ] AC5. `access-widening.md`, `zabric-hooks.md` file pointers updated.
