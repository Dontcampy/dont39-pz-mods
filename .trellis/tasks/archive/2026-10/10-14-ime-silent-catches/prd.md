# Remove silent catches in ImeWindowProc and ImeContext

## Goal

No error is dropped silently: the IME feature's undo paths stop swallowing failures, and an error raised after a
feature is already disabled still reaches `console.txt`, once, with context. `ime-keyboard-fix` is the spec's reference
feature, so its pattern gets copied.

## Background

- Silent paths:
  - `ImeWindowProc.restore` (ImeWindowProc.kt:52): `catch (t: Throwable) {}`; ignores `SetWindowLongPtr`'s result.
  - `ImeContext.restore` (ImeContext.kt:93): `catch (t: Throwable) {}`; ignores `ImmAssociateContextEx`'s result.
- Both reset their state on the way (`hwnd = 0L` before, `attached = true` after), so each install is undone at most
  once; later calls are no-ops.
- Callers: `ImeWindow.restore` (ImeWindow.kt:50), from `sync`'s inactive branch (an `always = true` hook, every frame
  while inactive) and from `ImeWindowProc.install` (re-subclassing a new window while active).
- A throw from a hook is caught by the generated glue → `Feature.disable`, which logs only on the transition to
  DISABLED (Feature.kt:136). So any error after the feature is disabled is dropped: undoing the install in `sync`'s
  inactive branch, or a paired exit's restore failing after a disable in the middle of the call.
- `smokeTest` takes each feature's last `[Dont39AllInOne] <feature>: ` line as its status and fails on one starting
  with `disabled` (dont39-all-in-one/build.gradle.kts:141–145).
- `ImeWindowProc.handle`'s catches (window-procedure callback, not a hook) call `disable`; they stay.

## Decisions

- **D1 (user, option A) — `Feature.disable` also reports when already disabled.** First error: unchanged
  (`disabled after an error while <what>`). Later errors: `disabled; then an error while <what>`, with the stack trace,
  at Error severity. Starts with `disabled` so the last status line still reads disabled. Covers the generated glue, IME
  undo and future features alike. Chosen over a separate `Feature.report` that each undo path must call explicitly.
- **D2 — undo paths do not retry.** An undo clears its state before acting, so a failure is attempted and reported once,
  never every frame. Already true for both IME restores; becomes a spec rule, since D1 would otherwise let an `always`
  hook spam.
- **D3 — undo failures throw.** The restores check the Win32 results and throw with what failed; `ImeWindow.restore`
  runs both, then throws the first failure with the second added as suppressed.

## Requirements

- R1: `Feature.disable`: when already disabled, log `${prefix}disabled; then an error while $what` with the stack trace
  (Error); state unchanged. KDoc says so.
- R2: `ImeWindowProc.restore`: no catch; clears `hwnd` first (as now); checks `SetWindowLongPtr` succeeded (0 with a
  non-zero last error is a failure, as `install` reads it) and throws naming the call and error code.
- R3: `ImeContext.restore`: no catch; sets `attached = true` before calling (no retry); `ImmAssociateContextEx`
  returning 0 throws, as in `sync`.
- R4: `ImeWindow.restore`: calls both even if the first throws; rethrows the first with the second suppressed.
- R5: Specs: `error-handling.md` — Restore paths row cites `ImeWindow.restore`; rule "an undo clears its state before
  acting, so it is not retried"; errors after a disable are still reported (`Feature.kt`). `logging-guidelines.md` if it
  lists lifecycle wording. Rule plus link, no restated code.

## Acceptance Criteria

- AC1: `./gradlew build` passes; `grep -n "catch (t: Throwable) {" ` with an empty body finds nothing in
  `ime-keyboard-fix`.
- AC2: `smokeTest` passes (ime-keyboard-fix ready/active).
- AC3 (by code review, failure is not reproducible on demand): after a disable, a throwing undo logs exactly one
  `disabled; then an error while running ImeWindow.sync on …` line with its stack, and nothing on later frames.

## Out of Scope

- `ImeWindowProc.handle`'s catches; other features; rate-limiting beyond D2.
