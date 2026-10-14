# Implementation Plan

Gradle needs JDK 25: run with `JAVA_HOME=~/.jdks/graalvm-ce-25.0.2`.

## Checklist

1. `Glue.kt`: add `always: Boolean = false` to `Hook.Enter` and `Hook.Exit`; update the KDoc (the glue gates and
   catches; paired Exit runs only after its call's Enter passed the gate; `always` for hooks that must run inactive).
2. `GlueProcessor.kt`:
   - read `always`; error when set on a paired target (R5);
   - require `public` hook functions (R5) — update the `callee` message for hooks only (`@LuaGlobal` stays as is);
   - facade lookup (R4), only when hooks exist;
   - Java type rendering for advice parameters and the `skipOn` return;
   - replace `patchesSource` / `appendAdvice` with Java output per design.md; write `.java` via `createNewFile(…, "java")`;
   - update the class KDoc.
3. Feature hooks (R6): `ImeCaret.publish`, `ImeWindow.sync` (+ `always = true`), `AimShift.lower` / `restore`,
   `LiftOffsets.liftBlood` / `liftGore`. Keep comments short, why only.
4. Specs (R7): `zombiebuddy-patching.md` (:97, :149 and Enter/Exit section), `quality-guidelines.md:61`. Link code,
   do not restate the generated shape.
5. Build and inspect generated `*Patches.java` in both feature modules (AC1).
6. Negative checks (AC2): temporarily add each bad case to a feature, confirm the compile error, revert.
7. `grep` for AC3.
8. `smokeTest` (AC4). In-game check (AC5) is the user's; report it as not done if not done.

## Validation commands

```bash
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew build
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew :dont39-all-in-one:smokeTest
```

## Risky points

- ByteBuddy inlining advice that contains an exception handler plus a Local: covered by `smokeTest` (patches applied).
  The render hooks only run in game, so AC5 is the real runtime check.
- KSP experimental APIs (`getOwnerJvmClassName`, `mapKotlinNameToJava`) need `@OptIn(KspExperimental::class)`.
- Generated Java must compile against Kotlin classes from the same module (KSP-generated Java is compiled by javac
  after kotlinc); confirm on the first build.
