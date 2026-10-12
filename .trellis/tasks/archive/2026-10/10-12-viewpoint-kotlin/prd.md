# Rewrite viewpoint-blood-fx-fix in Kotlin

Parent: `10-12-allinone-kotlin-sidebar`. Depends on `10-12-zombiebuddy-kotlin-utils` and `10-12-ksp-glue` (both are
built for and proven by this port; their code lands first).

## Goal

The `viewpoint-blood-fx-fix` feature is written in Kotlin like all new mod code (spec `mod/index.md`), with the same
in-game behavior and log output as the Java version.

## Background (repository evidence)

- Feature package (Java, 456 lines): `viewpoint-blood-fx-fix/src/main/java/com/dont39/pz/viewpointbloodfxfix/`
  `ViewpointBloodFxFix.java`, `LiftOffsets.java`, `Relief.java`, `AimShift.java`.
- Glue in `com.dont39.pz.allinone`: `ViewpointBloodFxFixPatches.java`, `ViewpointBloodFxFixLua.java`.
- Module applies `buildsrc.convention.pz-java` (`viewpoint-blood-fx-fix/build.gradle.kts`); Kotlin modules apply
  `pz-kotlin`.
- The current spec layout keeps glue in Java (`src/main/java/com/dont39/pz/allinone/<Feature>Patches.java`,
  `<Feature>Lua.java`, `.trellis/spec/mod/directory-structure.md`); this task changes that rule.
- ZombieBuddy accepts Kotlin glue: `PatchTransformer` rewrites `Patch.*` annotations in any class's bytecode and only
  needs annotated static methods; `Exposer.newInstanceForGlobalLuaMethods` (`Exposer.java:285`) instantiates the Lua
  bridge through its private no-arg constructor, which a Kotlin `object` has.
- ZombieBuddy applies `@Patch` advice with ByteBuddy `Advice.to(...)` (`PatchEngine.java:551`), which **inlines** the
  advice bytecode into the target method (`bloodfx.Gpu.render` etc.). Anything the advice references is resolved from
  the target mod's class.
- `Main.kt` currently calls `ViewpointBloodFxFix.init()` even when ZombieBuddyKotlin is not ready, because the
  feature is Java (`dont39-all-in-one/src/main/kotlin/com/dont39/pz/allinone/Main.kt`). The archived kotlin-lib task
  (D5) kept the feature Java and listed this rewrite as out of scope.
- `smokeTest` checks the `viewpoint-blood-fx-fix: ` status line (`dont39-all-in-one/build.gradle.kts`); after
  `10-12-ksp-glue` its patch/Lua expectations come from the generated resource.

## Requirements

- R1 Feature package in Kotlin under `src/main/kotlin/com/dont39/pz/viewpointbloodfxfix/`; module applies `pz-kotlin`.
- R2 Behavior unchanged: same gating (`problem()`/`active()`/`setForce()`/`disable()`), version allowlist, enter/exit
  restore, per-frame cost (no new per-frame allocation), same log lines.
- R3 Pure Kotlin, no hand-written glue (user decisions 2026-10-12): the module applies the feature convention
  (`pz-feature`); hooks and the Lua function are annotations on the feature's own functions, and the patches / Lua
  bridge classes in `com.dont39.pz.allinone` are generated (`10-12-ksp-glue`). Generated patch class names replace
  today's (`$BloodRender` -> generated names); `smokeTest` reads them from the generated resource. The module has no
  `src/main/java`. Inlined advice may contain ordinary Kotlin bytecode (no build check; personal mod). The
  mod-options Lua file stays Lua (the game's options UI is Lua-only).
  If the KSP spike fails, glue is hand-written Kotlin objects with `@JvmStatic` advice methods instead.
- R3a The port uses `zombiebuddy-kotlin-utils` for reflection resolution, gating, and logging; the feature keeps only
  its target checks, internals, and patch logic.
- R4 Kotlin-not-ready path: `Main.kt` initializes the feature only after the readiness check; when Kotlin is not
  ready the feature stays inactive and the patched render/aim calls never fail because of it.
- R5 Specs updated: the reference implementation is now Kotlin, including the patches/Lua bridge layout; remove "the
  existing feature is Java" notes.

## Acceptance Criteria

- [ ] `viewpoint-blood-fx-fix/src/main/java` no longer exists, and the module has no hand-written source in
      `com.dont39.pz.allinone`. (R1, R3)
- [ ] All-in-One jar has no `kotlin/` or `kotlinx/` classes. (R1)
- [ ] `smokeTest` passes; console shows the same `viewpoint-blood-fx-fix: ready (BloodFX …)` / `idle: …` line and the
      `Transformed:` lines when BloodFX is present. (R2, R3)
- [ ] In-game with Viewpoint + BloodFX: blood/gore sit on relief and aimed cuts hit, as before (or reported as not
      done). (R2)
- [ ] With the ZombieBuddyKotlin stdlib corrupted: game reaches init, All-in-One logs the not-ready line, the feature
      logs nothing that throws, and no render-thread exception appears. (R4)
- [ ] Specs updated. (R5)

## Out of Scope

- Behavior changes, new options, supporting new BloodFX versions.
- KSP (parent decision).
