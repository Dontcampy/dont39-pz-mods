# ZombieBuddy Kotlin Utils (internal module)

> The shape every Kotlin feature shares (gating, lifecycle logging, reaching other mods), written once. The code is in
> `zombiebuddy-kotlin-utils/src/main/kotlin/com/dont39/pz/zombiebuddykotlinutils/`; read the KDoc there for behavior.

---

## Use It

| Need | Use | File |
|------|-----|------|
| Facade: `init()`, cached `active()`, `setForce()`, final `disable()`, `log` / `warn` with the mod + feature prefix | an `object` extending `Feature`, supplying `problem()` (and `readyDetail()` if the ready line names a version) | `Feature.kt` |
| Other mods' classes, fields, static values, constructors, method handles | one `Resolver` per helper object, used only in its initializer; read `resolver.problem` once | `Resolver.kt` |
| Target mod version and allowlist with the mod-option override hint | `targetVersion`, `versionProblem` | `TargetMod.kt` |
| Hooks on other mods' methods, global Lua functions (the ZombieBuddy glue is generated) | `@Hook.Enter` / `@Hook.Exit`, `@LuaGlobal` ([ZombieBuddy Patching](./zombiebuddy-patching.md#patch-classes)) | `glue/Glue.kt` |

- A Kotlin feature does not hand-write gating state, the log prefix, lifecycle wording, or reflection try/catch; the
  rules in [ZombieBuddy Patching](./zombiebuddy-patching.md) (Gating, Reaching Other Mods), [Error
  Handling](./error-handling.md) and [Logging Guidelines](./logging-guidelines.md) are implemented here.

## Rules for Changing It

- Code that runs before the readiness check (`Feature`'s constructor and `active()` fast path, all of `Resolver`,
  `targetVersion`) may use only the JDK and plain language features. Its `kotlin/` constant-pool references
  (`javap -v`) must exist in the game's own Kotlin 1.4.10 ([Kotlin Runtime](./kotlin-runtime.md#background-not-visible-in-this-repos-code)).
- Hot-path neutral: `active()` stays a field read after the first decision; no allocation, boxing, lambdas, or
  property delegates on what a patch calls per frame. Resolved values go into plain `val`s.
- Log lines keep today's wording (smokeTest and players' `console.txt` read them).
- When calling a game API from Kotlin, check the overload in `javap -c`: Kotlin can pick a format overload where Java
  picks `(Object)` (see `warn` in `Feature.kt`).
- Add API only when a feature needs it; remove what no feature uses.

## Packaging

- Not a feature: listed in `internalLibs` in `dont39-all-in-one/build.gradle.kts` (bundled classes, no Lua, no
  smokeTest expectations). Feature modules get it through `pz-feature` (`buildSrc/src/main/kotlin/pz-feature.gradle.kts`).
- Its classes are unrelocated on the shared classloader, so only one shipped mod may bundle it. A second mod needing
  it means moving it into ZombieBuddyKotlin first, which is a separate decision ([Kotlin Runtime](./kotlin-runtime.md#library-zombie-buddy-kotlin)).
- The glue annotations are compile-time only (binary retention, never loaded in game). Their processor,
  `zombiebuddy-kotlin-utils-processor`, runs in the compiler: a plain JVM Kotlin module, not bundled, free to use the
  current stdlib. Change an annotation and the processor together; a rule the processor enforces is a compile error
  naming the function.
