# Access Widening

> Feature code reaches game and target-mod members with ordinary compiled code. Members that are not public are listed
> in the feature's `.classtweaker` file and made public, at build time in the jars the feature compiles against and at
> game start in the running game.

---

## Where It Lives

| Part | Code |
|------|------|
| List format and parser (Fabric access widener semantics) | `zabric/src/classtweaker/java/.../classtweaker/ClassTweaker.java` |
| Widened compile jars, validation, merged list | `buildSrc/src/main/kotlin/ClassTweakerTasks.kt`, wired in `pz-feature.gradle.kts`; merge in `dont39-all-in-one/build.gradle.kts` (`mergeClassTweakers`) |
| Target mod jars on the compile classpath | `buildSrc/src/main/kotlin/PzTargets.kt` (`pzTargets { workshopMod(...) }`) |
| Game-start widening | `zabric`: `PreMain.java`, `widener/AccessWidener.java` |
| Self-registration as a preload mod, restart dialog | `Main.java`, `setup/Registration.java`, `RestartNotice.java`, `src/main/lua/client/` of `zabric` |
| Feature side: state, outcome line, gating | `Widening.kt`, `Feature.kt` (`widened`) in `zombiebuddy-kotlin-utils` |

Reference: `viewpoint-blood-fx-fix` (target mods, nested classes, a record constructor) and `ime-keyboard-fix`
(game fields).

---

## Rules

- Reach game and target-mod classes, fields and methods directly. No reflection or method handles in feature code; the
  only reflection is inside `zombiebuddy-kotlin-utils` (presence/version probes in `TargetMod.kt`, the hook-target
  check in `HookTargets.kt`) and the preload mod (`Loader.g_instrumentation`).
- Declare every target mod in the feature's build script with `pzTargets` (Workshop id + mod folder). The jars are
  compile-only; a mod that is not downloaded fails the build naming it.
- List each non-public field, class or constructor the feature uses in
  `src/main/resources/META-INF/dont39/<module>.classtweaker`, and pass `widened = true` to `Feature`. A listed member
  that does not exist or has another type fails the build naming the line.
- Widen only fields, classes and constructors (`accessible method` only for `<init>`): making another method public can
  change virtual dispatch. Use `mutable` only for a final field the feature writes.
- Presence and version of a target mod stay probed by class name (`TargetMod.kt`): the mod may be absent, and a
  `static final` constant is copied into our class at build time, so a direct reference reads the build-time value.
- Target-mod types belong in classes that load only once the feature is active (the helpers its hooks call), not in the
  facade, which loads at `init()` whether or not the target mod is there. Reference: `LiftOffsets.debug()`.
- A direct reference that does not match the running game or mod fails with a `LinkageError` at first use; it happens
  inside the hook's `try` and disables the feature ([Error Handling](./error-handling.md)).

## The Preload Mod

- ZombieBuddy can change modifiers only when a class is defined, so the widening runs from a preload mod's `PreMain`,
  before the game loads the listed classes. A listed class that is already loaded then is reported, and the features
  that need widening stay off.
- It is a separate mod (`Zabric`, shipped in All-in-One's Workshop item), not All-in-One itself:
  ZombieBuddy applies a package's patches in the first phase that loads it (`ApplyPatchesFromPackage` in
  `Loader.java`), so a preloaded All-in-One would load its Kotlin glue at premain, before ZombieBuddyKotlin's runtime
  swap ([Kotlin Runtime](./kotlin-runtime.md)).
- It is Java and Lua only, with no patches: every class in its `javaPkgName` is loaded at premain. Global Lua functions
  there are fine (ZombieBuddy only records them then and exposes them later).
- `Instrumentation` comes from one reflective read of ZombieBuddy's `Loader.g_instrumentation`: the game JRE has no
  `jdk.attach`. Recheck it after every ZombieBuddy update.
- The widener registers itself without retransformation, so ZombieBuddy's later retransformations start from the
  widened bytes.
- It is not signed. ZombieBuddy's approval dialog registers a preload mod only with a valid signature, which
  `policy=allow-all` never checks; instead the mod's `Main` writes its own entry (absolute paths, from the folder the
  game uses this session) into `preload_mods` of ZombieBuddy's `config.json` and asks for one restart (log line and
  main-menu dialog). ZombieBuddy loads such entries at premain without a signature check under `allow-all`, and for
  unsigned jars when unsigned mods are allowed (its default).
- ZombieBuddy writes `config.json` from memory when it saves its own settings; if that drops the entry, the mod
  registers again on the next launch.
- Output at premain does not reach `console.txt` reliably; the outcome line is logged later by All-in-One's `Main`
  (`reportWidening` in `Widening.kt`).
