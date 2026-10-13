# Implementation plan: IME keyboard fix

## Checklist

1. Module `ime-keyboard-fix/` with `build.gradle.kts` applying only `buildsrc.convention.pz-feature`; register in
   `settings.gradle.kts` and the `features` list of `dont39-all-in-one/build.gradle.kts`.
2. Facade `ImeKeyboardFix.kt`: `problem()` (Windows check), `resolve()` (imm32 + `ImmAssociateContextEx` address,
   reflection checks of `Display.processMessages()` and `Core.currentTextEntryBox`), `resolveContext()`. Model on
   `viewpoint-blood-fx-fix/.../ViewpointBloodFxFix.kt`.
3. Helper (e.g. `ImeContext.kt`): `@Hook.Exit` on `org.lwjglx.opengl.Display.processMessages` → `sync()`; `restore()`;
   state per design.md. Check `active()` first.
4. Call `ImeKeyboardFix.init()` from `dont39-all-in-one/src/main/kotlin/com/dont39/pz/allinone/Main.kt`.
5. Add a section to `dont39-all-in-one/mod/workshop/workshop.txt` following the existing one. No `mod.info` target
   change (no target mod).
6. `javap -c` the helper: confirm the `JNI.callPPI(JJIJ)I` call site and no allocation/boxing in `sync()`.

## Validation

```bash
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew build assembleMod
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew :dont39-all-in-one:smokeTest
```

- Assembled jar contains the generated `ImeKeyboardFixPatches` class.
- smokeTest: `ime-keyboard-fix: ready` and `Transformed: org.lwjglx.opengl.Display`.
- In game: PRD AC1–AC5 (needs the user at the keyboard with a Chinese IME).

## Risky points

- Advice on the already-loaded `org.lwjglx.opengl.Display` (retransformation). If it does not apply, raise ZombieBuddy
  verbosity per quality-guidelines and revisit the hook point (e.g. a per-frame `zombie.*` method on the same thread).
- Wrong thread for IMM: guarded by hooking the message pump itself; a FALSE return disables with an error.

## Before `task.py start`

- Curated `implement.jsonl` / `check.jsonl` present.
- User approved the final planning summary.
