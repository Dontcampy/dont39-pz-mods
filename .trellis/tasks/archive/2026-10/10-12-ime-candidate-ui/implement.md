# Implementation plan: IME candidate UI

## Checklist

1. Work on `feat/ime-keyboard-fix` (same branch and PR #3 as the keyboard fix).
2. No new module: add helpers in `ime-keyboard-fix/src/main/kotlin/com/dont39/pz/imekeyboardfix/`.
3. `ImeKeyboardFix.resolve()`: add the member checks (design.md); update its KDoc and `resolveContext()` wording.
4. Main-thread helper: `@Hook.Exit` on `zombie.ui.UITextBox2.render` (`@Patch.This`), caret rect as in design.md,
   packed `@Volatile Long`. Check the generated glue binds `this` as expected.
5. Render thread: extend the existing `processMessages` exit hook (or add a second one) to install the subclass per
   `hwnd` and apply rect changes; the `WindowProc` implements the message table; pending restore when inactive, per
   design.md. `ImeContext.restore()` and the new restore must both run on every disable path.
6. Extend the existing "Keyboard controls with an IME on" Workshop section: the IME windows show at the caret
   (borderless/windowed).
7. `javap -c`: no allocation/boxing/lambdas in our per-frame code and in the window proc's common path; check the
   LWJGL call-site overloads.

## Validation

```bash
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew build assembleMod
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew :dont39-all-in-one:smokeTest
```

- smokeTest: `ime-keyboard-fix: ready`/`active`, `Transformed:` lines for `org.lwjglx.opengl.Display` and
  `zombie.ui.UITextBox2`.
- In game (user): PRD AC1–AC4, with `ime-keyboard-fix` active.

## Risky points / rollback

- GLFW message-handling mismatch → doubled/missing characters (AC2). Rollback: revert this task's commit on the branch;
  the keyboard-fix commit stays.
- Window procedure exceptions: everything caught; verify one disable line and that typing still works after it.
