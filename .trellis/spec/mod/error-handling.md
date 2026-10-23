# Error Handling

> A feature may switch itself off with an error, but it never breaks the game or the mod it patches, and never hides a
> problem.

---

## Rules

| Where | Rule | Reference |
|-------|------|-----------|
| Hook targets, and what the compiler cannot check | Throw, naming what does not match; the feature disables itself with that error | `ZabricHooks.gateProblem` (from `Feature.kt`), `ImeKeyboardFix.resolve()` |
| Direct references to game or mod members | Leave a mismatch (`LinkageError`) to the generated glue's catch, which disables the feature | `ImeCaret.publish` |
| Access widening not in effect | Stay idle with the reason | `Widening.kt` |
| Hooks | No catch: the generated glue catches everything and reports it to the gate, which disables the feature; a `FINALLY` handler restores what `HEAD` changed | `HookGate.java`, `ImeCaret.publish` |
| Fail closed (a hook whose only safe failure is to cancel the target) | The code the handler calls catches everything, disables the feature and cancels: the glue's catch would let the target run, here a UDP join the user ruled out. A Lua guard in front of the same action refuses it on its own error too, and when the feature is disabled or its Java is missing | `TunnelClient.connect`, the `serverConnect` guard in `Dont39AllInOne_TunnelGuard.lua` (`tunnel`); the `CheckMods` hold in `ModSyncClient.hold` (`mod-sync`) |
| Restore paths | Keep restoring the rest after one failure, then throw it; an undo clears its state before acting, so a failure is never retried | `ImeWindow.restore` |
| Probes whether a target mod is there | "Not loaded" only when the class is absent; anything else is an error | `TargetMod.kt` |
| A user's settings file, and diagnostics the mod writes for the user | Not a mismatch: a settings problem is one line naming file and key, the defaults apply for the session and the file stays as it is; a report that cannot be written is one error line and ends only the report for the session. A key missing because the file predates it is no problem: it takes its default and is added to the file with the values read kept, so a new switch never resets the others | `mod-settings/Switches.java` (`Switches.lua` its options page), `Loading.write` in `loading-progress` |
| GL errors of the mod's own calls | Check `glGetError` right after the call and throw with context. The game's texture path never reads the flags (`PZGLUtil.checkGLErrorThrowTexture` is off), so clear pending flags just before the call, or they read as the mod's | `Upload.compressed` in `faster-loading` |
| GL state the mod changes on the game's render thread | Save what it binds (program, buffer bindings with their ranges) before and restore it in `finally`, as a restore path; the game caches some of it and binds its own SSBOs. State the hook already checked (the unpack buffer is 0) is put back without a query | `textures/bc7/GlState` in `faster-loading` |
| A GL capability the mod's preferred path needs (a GL version) | Not a mismatch: choose the other implementation once per session and log the choice with its reason (renderer, version). A failure on a GL that has the capability (shader compile or link, a GL error) is a mismatch: throw | `textures/Encoders` in `faster-loading` |
| Network I/O (peers closing, resets, timeouts) | A runtime event, not a mismatch: close only what it affects and log one line; only non-`IOException` throwables disable the feature. No fallback the user did not choose: a tunnel join that cannot open stops with the reason on the join screen | `transport/tcp/Relay.kt`, `transport/kcp/KcpSession.kt`, `TunnelClient.kt` in `tunnel` |

Feature references are in the feature package of `ime-keyboard-fix`. The checking and gating rules are implemented in
`TargetMod.kt`, `Widening.kt` and `Feature.kt` ([ZabricKotlin Utils](./zb-kt-utils.md)), the hook side in
Zabric's hook runtime ([Zabric Hooks](./zabric-hooks.md#rules)).

Disabling is final for the session: no retries. Where feature code disables itself, it uses the facade's
`disable(...)` (`Feature.kt`) and phrases the description like the call in `ImeWindowProc.handle`. An error after the
feature is disabled, e.g. from an `always` hook undoing it, is still reported through `disable(...)`.

---

## Anti-Patterns

- Degrading, skipping a part, or returning a neutral value when another mod is not as expected: it hides the problem.
- Throwing from a static initializer: the failure surfaces inside the patched game method on first call.
- Changing state at `HEAD` without a `FINALLY` handler that restores it: the target stays half-modified.
- Reporting the same failure more than once.
- A try/catch or `active()` gate in a handler; the generated glue owns both (the fail-closed row is about code the
  handler calls, not the handler).
