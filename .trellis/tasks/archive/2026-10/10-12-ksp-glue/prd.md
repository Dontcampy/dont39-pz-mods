# KSP glue generation

Parent: `10-12-allinone-kotlin-sidebar`. Order: after `10-12-zombiebuddy-kotlin-utils` (the annotations live there),
before `10-12-viewpoint-kotlin` (its first user).

## Goal

Feature code declares hooks and Lua functions with annotations on ordinary Kotlin functions in its own package. The
ZombieBuddy glue — static, `@Patch`-annotated advice classes and `@LuaMethod` bridges in exactly
`com.dont39.pz.allinone` — is generated, so nobody writes `@JvmStatic`, nested patch objects, or glue classes by hand.

## Decisions (user, 2026-10-12)

- **D1 Adopt KSP** for glue generation (reverses the parent's earlier "not now"), after a feasibility spike.
- **D2 Fallback:** if the spike fails, glue stays hand-written Kotlin with `@JvmStatic` on advice methods (option A);
  this task then ends with the spike findings recorded and no build change.

## Background (evidence)

- ByteBuddy advice methods must be JVM-static; Kotlin `object` members are static only with `@JvmStatic`.
- ZombieBuddy finds patch classes and Lua bridges only in exactly `javaPkgName` (`PatchEngine.java:671`, `718-728`;
  `Exposer.addClassWithGlobalLuaMethod`, `Exposer.java:57`). It rewrites `Patch.*` annotations in any bytecode
  (`PatchTransformer`) and applies advice with `Advice.to` (inlined, `PatchEngine.java:551`).
- Kahlua binds global Lua functions to an instance via `getMethods()` (`LuaJavaClassExposer.exposeGlobalFunctions`,
  in `projectzomboid.jar`), and ZombieBuddy creates that instance through a private no-arg constructor
  (`Exposer.java:285`) — instance methods work; only patches need statics.
- No annotation processor exists in ZombieBuddy; zb-gradle-plugin's `annotationProcessor` wiring is unaffected.
- KSP on Maven Central: latest `com.google.devtools.ksp` 2.3.12 (KSP2, versioned independently of Kotlin). Repo
  Kotlin is 2.4.21 (`gradle/libs.versions.toml`); compatibility unverified.
- `smokeTest` hard-codes patch class names and the Lua exposure line (`dont39-all-in-one/build.gradle.kts`).

## Requirements

- R0 **Spike first:** KSP 2.3.x runs in this build with Kotlin 2.4.21, JDK 25 toolchain, configuration cache on, and
  generated Kotlin compiles into the module. Record version and findings in `research/ksp-spike.md`. Failure → D2.
- R1 **Annotations** in `zombiebuddy-kotlin-utils` (`glue` subpackage; design.md "Annotation API"): `@Hook.Enter` /
  `@Hook.Exit(className, methodName, …)` and `@LuaGlobal(name)` on functions of top-level `object`s or top-level
  functions. Hook parameters use ZombieBuddy's own `@Patch.Argument` / `This` / `Return` / `Thrown` / `AllArguments` /
  `Local`, copied verbatim. `Exit` runs on throw by default (spec restore rule).
- R2 **Generated patches:** per feature module, one outer class `com.dont39.pz.allinone.<Feature>Patches` with one
  nested class per target method, carrying `@Patch(className, methodName)` and static `@Patch.OnEnter` /
  `@Patch.OnExit(onThrowable = Throwable::class)` methods that only call the annotated feature function.
- R3 **Generated Lua bridge:** `com.dont39.pz.allinone.<Feature>Lua` with `@LuaMethod(name, global = true)` methods that
  only call the annotated feature function.
- R4 **Processor errors** at compile time (KSP `logger.error` naming the function) for every rule in design.md
  "Rules the processor enforces" (receiver, visibility, parameter bindings, `readOnly = false`, return types, duplicate
  hooks, class-name clashes, Lua name prefix/uniqueness).
- R5 **smokeTest from generated data:** the processor also writes a resource listing generated patch and Lua classes;
  `smokeTest` reads it instead of hard-coding `Found patch class:` / `Exposing global functions from class:` lines.
- R6 **Wiring:** a convention plugin for feature modules (`pz-feature`: pz-kotlin + KSP + utils + processor) so a new
  feature's build script is one plugin line. Processor module `zombiebuddy-kotlin-utils-processor`, an ordinary JVM
  Kotlin module (it runs in the compiler, not in the game). KSP version in `gradle/libs.versions.toml`.
- R7 Specs: `zombiebuddy-patching.md` and `directory-structure.md` describe annotations instead of hand-written glue;
  "Adding a Feature" loses the glue and smokeTest steps; `kotlin-runtime.md` "Version Bumps" mentions KSP. Link the
  processor and annotation files, don't restate.

## Acceptance Criteria

- [ ] `research/ksp-spike.md` records the KSP version and the configuration-cache result. (R0)
- [ ] With the viewpoint port: `viewpoint-blood-fx-fix` has no hand-written class in `com.dont39.pz.allinone`; its
      `build.gradle.kts` is the feature convention plugin only. (R1-R3, R6)
- [ ] `javap -v` on the generated patch classes: `@Patch` on each nested class, static `enter`/`exit` with the `Patch`
      annotations and `onThrowable = Throwable`. (R2)
- [ ] Each R4 rule, violated once temporarily, fails compilation with a message naming the function. (R4)
- [ ] `smokeTest` passes and its patch/Lua expectations come from the generated resource; console still shows
      `Found patch class:` for each generated class, `Transformed:` for each target, and the Lua exposure line. (R5)
- [ ] `./gradlew build` passes with the configuration cache enabled (as today). (R0, R6)
- [ ] Specs updated. (R7)

## Out of Scope

- MethodDelegation patches, `RuntimeType` / `SuperCall` / `SuperMethod`, `IKnowWhatIAmDoing`, `OnExit.suppress`,
  writable (`readOnly = false`) bindings (extend when a feature needs them).
- Generating Lua files or mod options.
- Exposing whole classes to Lua.
