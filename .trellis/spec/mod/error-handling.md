# Error Handling

> A feature may switch itself off with an error, but it never breaks the game or the mod it patches, and never hides a
> problem.

---

## Rules

| Where | Rule | Reference |
|-------|------|-----------|
| Hook targets, and what the compiler cannot check | Throw, naming what does not match; the feature disables itself with that error | `HookTargets.kt`, `ImeKeyboardFix.resolve()` |
| Direct references to game or mod members | Leave a mismatch (`LinkageError`) to the generated glue's catch, which disables the feature | `ImeCaret.publish` |
| Access widening not in effect | Stay idle with the reason | `Widening.kt` |
| Hooks | No catch: the generated glue catches everything and disables the feature; the paired exit restores what the enter changed | `Glue.kt`, `ImeCaret.publish` |
| Restore paths | Keep restoring the rest after one failure, then throw it; an undo clears its state before acting, so a failure is never retried | `ImeWindow.restore` |
| Probes whether a target mod is there | "Not loaded" only when the class is absent; anything else is an error | `TargetMod.kt` |

Feature references are in the feature package of `ime-keyboard-fix`. The checking
and gating rules are implemented in `HookTargets.kt`, `TargetMod.kt`, `Widening.kt` and `Feature.kt`
([ZombieBuddy Kotlin Utils](./zb-kt-utils.md)).

Disabling is final for the session: no retries. Where feature code disables itself, it uses the facade's
`disable(...)` (`Feature.kt`) and phrases the description like the call in `ImeWindowProc.handle`. An error after the
feature is disabled, e.g. from an `always` hook undoing it, is still reported through `disable(...)`.

---

## Anti-Patterns

- Degrading, skipping a part, or returning a neutral value when another mod is not as expected: it hides the problem.
- Throwing from a static initializer: the failure surfaces inside the patched game method on first call.
- Changing state on enter without a paired exit that restores it: the target stays half-modified.
- Reporting the same failure more than once.
- A try/catch or `active()` gate in a hook function; the generated glue owns both.
