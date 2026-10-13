# ZombieBuddy Kotlin Utils (internal module)

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

- Log wording is a contract: smokeTest and players' `console.txt` read it.
- Add API only when a feature needs it; remove what no feature uses.

## Packaging

- It is a bundled library, not a feature; see `internalLibs` in `dont39-all-in-one/build.gradle.kts` and
  `buildSrc/src/main/kotlin/pz-feature.gradle.kts`.
- Its classes are unrelocated on the shared classloader, so only one shipped mod may bundle it. A second mod needing it
  means moving it into ZombieBuddyKotlin first, which is a separate decision
  ([Kotlin Runtime](./kotlin-runtime.md#library-zb-kt)).
- The glue processor (`zb-kt-utils-processor`) runs only in the compiler and is never shipped. Change an
  annotation and the processor together.
