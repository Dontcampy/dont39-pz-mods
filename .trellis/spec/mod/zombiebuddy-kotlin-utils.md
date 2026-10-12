# ZombieBuddy Kotlin Utils (internal module)

> What every Kotlin feature shares, written once. Code and KDoc:
> `zombiebuddy-kotlin-utils/src/main/kotlin/com/dont39/pz/zombiebuddykotlinutils/`. Reference user:
> `viewpoint-blood-fx-fix`.

---

## Use It

- A feature's facade extends `Feature` (`Feature.kt`), its helpers reach other mods through `Resolver`
  (`Resolver.kt`), target-mod version checks use `TargetMod.kt`, and hooks and Lua functions use the annotations in
  `glue/Glue.kt` ([ZombieBuddy Patching](./zombiebuddy-patching.md#patch-classes)).
- Do not re-implement in a feature what the module provides: gating state, the log prefix, lifecycle wording,
  reflection error handling.

## Rules for Changing It

- Hooks run even when ZombieBuddyKotlin is not ready ([Kotlin Runtime](./kotlin-runtime.md#dependent-kotlin-modules)),
  so code a hook can reach before the readiness gate may reference only `kotlin/` classes the game's Kotlin 1.4.10 also
  has. Check with `javap -v`; descriptors count, not just calls.
- Nothing it puts on a per-frame path may allocate, box, or go through lambdas or property delegates.
- Log wording is a contract: smokeTest and players' `console.txt` read it.
- Kotlin can resolve a game API call to a different overload than Java would (e.g. a varargs format overload instead of
  `(Object)`); confirm game API calls with `javap -c`.
- Add API only when a feature needs it; remove what no feature uses.

## Packaging

- It is a bundled library, not a feature; see `internalLibs` in `dont39-all-in-one/build.gradle.kts` and
  `buildSrc/src/main/kotlin/pz-feature.gradle.kts`.
- Its classes are unrelocated on the shared classloader, so only one shipped mod may bundle it. A second mod needing it
  means moving it into ZombieBuddyKotlin first, which is a separate decision
  ([Kotlin Runtime](./kotlin-runtime.md#library-zombie-buddy-kotlin)).
- The glue processor (`zombiebuddy-kotlin-utils-processor`) runs only in the compiler and is never shipped. Change an
  annotation and the processor together.
