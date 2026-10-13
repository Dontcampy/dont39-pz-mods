# ZombieBuddy Kotlin Utils (shared runtime)

> What every Kotlin feature shares, written once. Code and KDoc:
> `zb-kt-utils/src/main/kotlin/com/dont39/pz/zbktutils/`. Reference user:
> `viewpoint-blood-fx-fix`.

---

## Use It

- A feature's facade extends `Feature` (`Feature.kt`), target-mod presence and version checks use `TargetMod.kt`, and
  hooks and Lua functions use the annotations in `glue/Glue.kt`
  ([ZombieBuddy Patching](./zombiebuddy-patching.md#patch-classes)).
- Do not re-implement in a feature what the module provides: gating state, the log prefix, lifecycle wording, hook
  checks, widening state.

## Rules for Changing It

- It is public runtime API for any Kotlin mod on ZombieBuddyKotlin: no mod-specific literals (package, mod id, Lua
  prefix); those come from the calling mod or its jar.
- What it reads from the feature's mod (glue listing, widening list) is read from the jar of the feature's own class,
  never through the shared class loader (`HookTargets.kt`, `Widening.kt`).
- Log wording is a contract: smokeTest and players' `console.txt` read it.
- Add API only when a feature needs it; remove what no feature uses.

## Packaging

- Shipped once, inside ZombieBuddyKotlin's jar (`zb-kt/build.gradle.kts`). Every other module has it compile-only;
  never bundle it into another jar: its classes are unrelocated on the shared classloader.
- The glue processor (`zb-kt-utils-processor`) runs only in the compiler and is never shipped. Change an
  annotation and the processor together. Its glue listing ships in the mod jar, where `HookTargets.kt` reads it.
