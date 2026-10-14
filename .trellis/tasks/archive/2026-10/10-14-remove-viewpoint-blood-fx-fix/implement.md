# Implementation Plan

Gradle needs JDK 25: `JAVA_HOME=~/.jdks/graalvm-ce-25.0.2`.

## Checklist

1. `git rm -r viewpoint-blood-fx-fix` (also delete its untracked `build/`).
2. `settings.gradle.kts`: drop the include. `dont39-all-in-one/build.gradle.kts`: drop it from `features`; version
   0.6.0. `AllInOneMain.kt`: drop the import and `init()`. `mod.info`: `loadModAfter=\ZombieBuddyKotlin`; description
   without the "only switches on when the mods it works with are enabled" claim.
3. `workshop.txt`: remove the Blood FX section; rewrite line 5 and the load-order sentence for features that only touch
   the game.
4. Specs (D2), one file at a time:
   - `index.md`, `zb-kt-utils.md`: reference implementation → `ime-keyboard-fix`; `zb-kt-utils.md` rule from D1.
   - `access-widening.md`: reference features → `ime-keyboard-fix`; the target-mod-types rule loses its example.
   - `directory-structure.md`: naming table and examples from `ime-keyboard-fix` (`ImeKeyboardFix`, …); Lua file /
     mod-options id / Lua global row points at `@LuaGlobal` in `Glue.kt` and the naming rule, as no feature has one.
   - `error-handling.md`: rows cite `Glue.kt` / `ImeWindow.kt` / `ImeContext.kt`; drop the `LiftOffsets` examples.
   - `logging-guidelines.md`: `problem()` → `ImeKeyboardFix.kt`; log example generic; debug-option and periodic
     summary rules without the `LiftOffsets` reference.
   - `zombiebuddy-patching.md`: reference hooks → `ImeCaret.kt`, `ImeWindow.kt`; Lua bridge → `Glue.kt` /
     `Feature.setForce`; Gating reference → `TargetMod.kt` + `ImeKeyboardFix.kt`; Enter/Exit section and hot-path
     note without the removed files.
   - `quality-guidelines.md` In-Game Verification: steps 2–4 phrased for a feature with or without target mods.
5. Grep for AC1/AC3; check every spec link resolves.
6. Build, then `smokeTest` (AC2).

## Validation commands

```bash
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew build
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew :dont39-all-in-one:smokeTest
```

## Risks

- `smokeTest` / `checkGlue` may assume at least one feature with Lua globals or target mods; fix the check rather than
  keep the module.
- The deployed Workshop folder may still hold the old jar contents until `deployWorkshop` runs (smokeTest depends on
  it).
