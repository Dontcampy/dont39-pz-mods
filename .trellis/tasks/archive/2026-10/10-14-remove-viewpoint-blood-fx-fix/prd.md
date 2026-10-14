# Remove viewpoint-blood-fx-fix

## Goal

Drop the "Blood FX for Viewpoint: terrain relief fix" feature from Dont39 All-in-One (user decision, 2026-10-14).
Shared platform code it was the only user of stays.

## Background

- Module: `viewpoint-blood-fx-fix/` (`AimShift.kt`, `LiftOffsets.kt`, `Relief.kt`, `ViewpointBloodFxFix.kt`, Lua mod
  options `Dont39AllInOne_ViewpointBloodFxFix.lua`, `viewpoint-blood-fx-fix.classtweaker`, `build.gradle.kts` with
  `pzTargets` for Viewpoint and ViewpointBloodFX).
- Wiring: `settings.gradle.kts:22`; `dont39-all-in-one/build.gradle.kts:17` (`features`); `AllInOneMain.kt:4,17`;
  `dont39-all-in-one/mod/42/mod.info:8` (`loadModAfter=…,\Viewpoint,\ViewpointBloodFX`).
- Workshop text (`dont39-all-in-one/mod/workshop/workshop.txt`, published item 3818388200): Blood FX section (lines
  16–25), and wording that assumes parts work with other mods (line 5; line 13 "also goes after the mods below that
  you use"); the `mod.info` description says the same. All-in-One is 0.5.0 (`dont39-all-in-one/build.gradle.kts:12`).
- Remaining features: `ime-keyboard-fix` (vanilla game, Exit hooks only, no target mod, no Lua global; has
  `problem()`, `resolve()`, widening) and `mod-options-sidebar` (Lua only).
- Specs use the module as the reference implementation (`index.md:32`, `zb-kt-utils.md:5`) and cite its files in
  `access-widening.md` (:21, :36), `directory-structure.md` (:19, :48–57, :65, :75), `error-handling.md` (:13–16,
  :19), `logging-guidelines.md` (:18, :39, :64, :66, :68), `zombiebuddy-patching.md` (:93–94, :110–113, :149, :161,
  :166, :176), and `quality-guidelines.md` In-Game Verification steps 2–4 assume a target mod.

## Decisions

- **D1 — shared code stays (user, option A).** Without an in-repo user but kept as platform API: `TargetMod.kt`,
  `Feature.force` / `setForce`, `@LuaGlobal` and the Lua bridge generation, `checkGlue`, the paired Enter/Exit
  `@Patch.Local` gate, `skipOn`, the `Argument` / `Return` / `Thrown` / `AllArguments` bindings, `PzTargets`.
  `zb-kt-utils.md`'s "remove what no feature uses" gains: generic platform API is not removed because the feature that
  first used it is gone.
- **D2 — spec references.** Rules stay. Where `ime-keyboard-fix` shows the rule, it becomes the reference; where no
  feature does any more (target-mod version gating, Lua global, Enter/Exit restore, debug option, periodic summary),
  the spec links the shared code that implements it (`TargetMod.kt`, `Feature.kt`, `Glue.kt`) or states the rule
  without a reference. No spec cites a removed file.
- **D3 — version.** All-in-One 0.5.0 → 0.6.0 (a feature is removed from a published mod).

## Requirements

- R1: `viewpoint-blood-fx-fix/` deleted; no build, runtime or `mod.info` reference left (`settings.gradle.kts`,
  `features`, `AllInOneMain`, `loadModAfter` drops `\Viewpoint,\ViewpointBloodFX`).
- R2: Workshop text and `mod.info` description describe the remaining features only; no "works with other mods" or
  load-after-other-mods wording that no longer applies. Only local files change; uploading is the user's.
- R3: Specs updated per D1/D2; `index.md` and `zb-kt-utils.md` name `ime-keyboard-fix` as the reference.
- R4: All-in-One version 0.6.0 (D3).

## Acceptance Criteria

- AC1 (R1): `./gradlew build` passes; `grep -ri "viewpoint\|bloodfx\|blood fx\|AimShift\|LiftOffsets"` outside
  `external-repos/`, `build/`, `.trellis/tasks/archive/` and `.trellis/workspace/` finds nothing.
- AC2 (R1, R4): `smokeTest` passes; `console.txt` shows `[Dont39AllInOne] 0.6.0 loaded`, `ime-keyboard-fix` ready /
  active, and no line about `viewpoint-blood-fx-fix`.
- AC3 (R2, R3): every file a spec links exists; no spec names a removed file or module.
- AC4 (D1): `TargetMod.kt`, `Feature.setForce`, `@LuaGlobal`, the Lua bridge generation, `checkGlue` and `PzTargets`
  are still present and the build still compiles them.

## Out of Scope

- Uploading the Workshop item.
- Removing or changing shared platform code (D1).
- The silent catches in `ImeWindowProc` / `ImeContext` (separate suggested task).
