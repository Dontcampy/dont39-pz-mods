# Remove per-hook gate and try/catch boilerplate

## Goal

Every `@Hook` function repeats the same wrapper: gate on the facade's `active()`, `try` the body,
`catch (t: Throwable)` and call `Facade.disable("<what>", t)`. Move the gate and the catch into the generated glue so a
hook body is only its logic, and so no hook can forget them: an exception escaping a hook is thrown inside the target
mod's or the game's method, and a missing gate runs the feature against an unchecked target.

## Background

- Hooks today (4 files):
  - `ImeCaret.publish` (ime-keyboard-fix/.../ImeCaret.kt:22, Exit only, `onThrow = false`): gate; try; catch → disable.
  - `ImeWindow.sync` (ime-keyboard-fix/.../ImeWindow.kt:16, Exit only): when inactive it runs `restore()` (undoes the
    window-procedure subclass and IME context on the window thread); catch → `restore()` + disable.
  - `AimShift.lower` / `restore` (viewpoint-blood-fx-fix/.../AimShift.kt:15, :48, Enter/Exit pair): Enter gated, catch
    → `restore()` + disable; Exit ungated, try around the write.
  - `LiftOffsets.liftBlood` / `restoreBlood`, `liftGore` / `restoreGore` (viewpoint-blood-fx-fix/.../LiftOffsets.kt:27,
    :71, :76, :99, Enter/Exit pairs): Enter gated, catch → restore + disable; Exit ungated, `Saved.restore` keeps
    restoring the rest on a per-element failure.
- Generated glue today: `GlueProcessor.kt` (`patchesSource`, `appendAdvice`) writes a Kotlin `<Feature>Patches` object
  per module (25 and 52 lines), each advice a one-line forward. ZombieBuddy inlines the advice into the target method.
- ZombieBuddy's `@Patch.Local` maps to ByteBuddy `Advice.Local` (PatchTransformer.java:38), a local shared by the
  enter and exit advice of one call. It is written by assigning the parameter, which Kotlin cannot do (verified:
  `var a = active; a = true` compiles to a store into a new slot, not the parameter's). `Advice.Enter` is not exposed.
  Hence the patch class is generated as Java.
- `Feature.active()` and `Feature.disable(what, t)` are public (Feature.kt:72, :136); `disable` is idempotent and logs
  `disabled after an error while <what>` with the stack trace.
- The feature name option (`dont39.feature`, pz-feature.gradle.kts:23) equals the facade object's name; each feature
  module has exactly one top-level `object … : Feature`.
- ZombieBuddy's `Patch.OnEnter/OnExit.suppress` swallows silently and is not used.

## Decisions

- **D1 — the generated glue owns the catch.** Every generated advice wraps the hook call in a catch of `Throwable` that
  calls the facade's `disable`. Chosen over an inline `Feature.guard` helper (not enforced) and over both (two ways to
  write the same hook).
- **D2 — no immediate cleanup on failure.** Hooks drop their catch-and-restore; the paired Exit restores, as the spec
  already requires (zombiebuddy-patching.md:98). Accepted cost: e.g. `LiftOffsets` draws one call with a partial lift
  before its Exit restores it; the feature is then disabled for the session.
- **D3 — no hand-written context strings.** The `what` passed to `disable` is derived from the hook and its target
  (e.g. `running AimShift.lower on bloodfx.Aim.target`); the stack trace carries the rest. An optional `doing = "…"`
  argument is deferred until needed.
- **D4 — the generated glue owns the gate, exactly per call.** The patch class is generated as Java so the enter advice
  can store its `active()` result in a `@Patch.Local`; the paired exit runs only when that call's enter passed the gate,
  even if the feature is disabled in between. An unpaired Exit gates on `active()` itself.
- **D5 — `always = true` opt-out.** A hook that must run while the feature is inactive (today only `ImeWindow.sync`,
  which undoes a persistent install) sets `always = true` and checks `active()` itself; the catch still applies.
  Allowed only on an unpaired hook.

## Requirements

- R1: Each generated enter advice: gate on the facade's `active()` (skipped when `always`), call the hook inside a
  catch-all that calls `disable`; a `skipOn` enter returns false when gated off or failed, so the target runs.
- R2: Each generated exit advice paired with an enter runs the hook only when that call's enter passed the gate
  (`@Patch.Local`); an unpaired exit gates on `active()` unless `always`; always inside the catch-all.
- R3: The patches class is generated as Java (`<Feature>Patches.java`, same package and nested class names, so the
  glue listing's `patch` lines are unchanged). The Lua bridge stays Kotlin.
- R4: The processor finds the module's facade (the single top-level object extending
  `com.dont39.pz.zbktutils.Feature`); none or more than one is a compile error.
- R5: Hook functions must be public (Java calls them through `INSTANCE`; `internal` names are mangled); otherwise a
  compile error naming the function. `always = true` on a paired hook is a compile error.
- R6: All existing hooks drop their `active()` check and try/catch: `ImeCaret.publish`, `AimShift.lower` /
  `restore`, `LiftOffsets.liftBlood` / `liftGore`. `ImeWindow.sync` gets `always = true`, keeps its inactive branch,
  drops its try/catch. `Saved.restore`'s per-element loop and the "restore leftovers before lifting" calls stay (hook
  logic, not boilerplate).
- R7: `Glue.kt` docs, the processor's KDoc and the specs (zombiebuddy-patching.md:97, :149; quality-guidelines.md:61)
  state the new contract: hooks contain neither gate nor try/catch; the glue owns both.

## Acceptance Criteria

- AC1 (R1–R3): `./gradlew build` passes; the generated `ViewpointBloodFxFixPatches.java` shows, per target, an enter
  that stores `active()` in a `@Patch.Local` and an exit that reads it, each call inside a catch-all → `disable`.
- AC2 (R4, R5): a module with no facade, two facades, an `internal` hook function, or `always = true` on a paired hook
  fails to compile with an error naming the cause.
- AC3 (R6): `grep` finds no `try`/`catch` and no `.active()` call in any `@Hook` function except `ImeWindow.sync`'s
  inactive branch.
- AC4: `smokeTest` passes: patch classes found and applied, features reach ready.
- AC5 (in-game, by the user): Viewpoint + BloodFX blood/gore and the IME caret behave as before; a forced failure in a
  hook logs `disabled after an error while running <Hook> on <target>` once and the target keeps working.

## Out of Scope

- Passing values between Enter and Exit (a MixinExtras-`@Share`-like binding) and moving `AimShift` / `LiftOffsets`
  per-call state from fields into it: a follow-up task.
- `@LuaGlobal` bridge error handling; `ImeWindowProc.handle` (a window-procedure callback, not a hook).
- ZombieBuddy's `suppress` option; a `doing = "…"` context argument.
