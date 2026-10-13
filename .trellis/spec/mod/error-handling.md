# Error Handling

> A feature may switch itself off with an error, but it never breaks the game or the mod it patches, and never hides a
> problem.

---

## Rules

| Where | Rule | Reference |
|-------|------|-----------|
| Hook targets, and what the compiler cannot check | Throw, naming what does not match; the feature disables itself with that error | `HookTargets.kt`, `ImeKeyboardFix.resolve()` |
| Direct references to game or mod members | Leave a mismatch to the hook's `try` (`LinkageError`), which disables the feature | `LiftOffsets.liftBlood` |
| Access widening not in effect | Stay idle with the reason | `Widening.kt` |
| Helpers called from patches | Catch everything at this boundary, restore what was changed, then disable the feature through the facade | `LiftOffsets.liftBlood` / `liftGore`, `AimShift.lower` |
| Restore paths | Never throw; keep restoring the rest after one failure | `LiftOffsets` (`Saved.restore`), `AimShift.restore` |
| Probes whether a target mod is there | "Not loaded" only when the class is absent; anything else is an error | `TargetMod.kt` |

Feature references are in the feature packages of `viewpoint-blood-fx-fix` and `ime-keyboard-fix`. The checking
and gating rules are implemented in `HookTargets.kt`, `TargetMod.kt`, `Widening.kt` and `Feature.kt`
([ZombieBuddy Kotlin Utils](./zombiebuddy-kotlin-utils.md)).

Disabling is final for the session: no retries. Use the facade's `disable(...)` (`Feature.kt`) and phrase the
description like the existing calls in `LiftOffsets.kt` and `AimShift.kt`.

---

## Anti-Patterns

- Degrading, skipping a part, or returning a neutral value when another mod is not as expected: it hides the problem.
- Throwing from a static initializer: the failure surfaces inside the patched game method on first call.
- Catching in a patch path without restoring and disabling: the target stays half-modified and the error repeats every
  frame.
- Reporting the same failure more than once.
- try/catch in the patches class; error handling belongs to the helpers it calls.
