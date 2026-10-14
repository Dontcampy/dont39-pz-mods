# ZombieBuddy Kotlin Utils (shared runtime)

> What every Kotlin feature shares, written once. Code and KDoc:
> `zb-kt-utils/src/main/kotlin/com/dont39/pz/zbktutils/`. Reference user:
> `ime-keyboard-fix`.

---

## Use It

- A feature's facade extends `Feature` (`Feature.kt`), target-mod presence and version checks use `TargetMod.kt`, and
  hooks and Lua functions use the annotations in `glue/Glue.kt`
  ([ZombieBuddy Patching](./zombiebuddy-patching.md#patch-classes)).
- Do not re-implement in a feature what the module provides: gating state, the log prefix, lifecycle wording, hook
  checks, widening state.

## Rules for Changing It

- It is public runtime API for any Kotlin mod on ZombieBuddyKotlin: nothing mod-specific in it.
- Read a mod's own resources from the jar of its class, not through the class loader: all mod jars share it.
- Log wording is a contract: smokeTest and players' `console.txt` read it.
- The glue listing (`META-INF/zbkt/glue/`) is shared by `GlueProcessor.kt`, `HookTargets.kt` and the checks in
  `dont39-all-in-one/build.gradle.kts`; change them together. Readers skip line kinds they do not use.
- Add API only when a feature needs it; remove what no feature uses. Generic platform API (target-mod checks, mod
  options, Lua globals, hook bindings) stays when the feature that first used it is gone.

## Packaging

- Shipped once, inside ZombieBuddyKotlin's jar (`zb-kt/build.gradle.kts`). Every other module has it compile-only;
  never bundle it into another jar: its classes are unrelocated on the shared classloader.
- The glue processor (`zb-kt-utils-processor`) runs only in the compiler and is never shipped. Change an
  annotation and the processor together.
