# Design: KSP glue generation

## Modules and wiring

| Piece | Where |
|-------|-------|
| Annotations | `zombiebuddy-kotlin-utils/src/main/kotlin/com/dont39/pz/zombiebuddykotlinutils/glue/` |
| Processor | new module `zombiebuddy-kotlin-utils-processor/` — `kotlin("jvm")` only (not pz-java/pz-kotlin), `implementation` on `symbol-processing-api` and the Kotlin stdlib (explicit: root `kotlin.stdlib.default.dependency=false`), `META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider` |
| Convention | `buildSrc/src/main/kotlin/pz-feature.gradle.kts`: applies `pz-kotlin` + `com.google.devtools.ksp`, adds `implementation(project(":zombiebuddy-kotlin-utils"))`, `ksp(project(":zombiebuddy-kotlin-utils-processor"))`, passes `ksp { arg("dont39.feature", <PascalCase of module name>) }` |
| Versions | `gradle/libs.versions.toml`: `ksp` version + plugin/library entries; `buildSrc/build.gradle.kts` adds the KSP Gradle plugin as an implementation dependency (like the Kotlin plugins today) |

`zombie-buddy-kotlin` keeps plain `pz-kotlin` (no KSP). `dont39-all-in-one` keeps `pz-kotlin` (its `Main` is not a
feature).

## Annotation API (proposal, see prd R1)

Package `com.dont39.pz.zombiebuddykotlinutils.glue`, retention `BINARY` (not needed at runtime; visible to `javap`).

```kotlin
annotation class Hook {
    /** Becomes a @Patch.OnEnter advice on className.methodName. */
    @Target(FUNCTION) @Retention(BINARY)
    annotation class Enter(
        val className: String,
        val methodName: String,
        val skipOn: Boolean = false,            // Patch.OnEnter.skipOn
        val strictMatch: Boolean = false,       // Patch.strictMatch
        val warmUp: Boolean = false,            // Patch.warmUp
    )

    /** Becomes a @Patch.OnExit advice; runs when the target throws unless told otherwise (spec restore rule). */
    @Target(FUNCTION) @Retention(BINARY)
    annotation class Exit(
        val className: String,
        val methodName: String,
        val onThrowable: KClass<out Throwable> = Throwable::class,   // ZombieBuddy's default is NoException
        val strictMatch: Boolean = false,
        val warmUp: Boolean = false,
    )
}

/** Becomes a global Lua function `name` (@LuaMethod(name, global = true)). */
@Target(FUNCTION) @Retention(BINARY)
annotation class LuaGlobal(val name: String)
```

Parameters reuse ZombieBuddy's own parameter annotations unchanged — `@Patch.Argument(n)`, `@Patch.This`,
`@Patch.Return`, `@Patch.Thrown`, `@Patch.AllArguments`, `@Patch.Local` — and the generator copies them verbatim onto
the generated advice parameters. No parallel `@Arg` family to learn or map.

Usage (viewpoint):

```kotlin
object LiftOffsets {
    @Hook.Enter("bloodfx.Gpu", "render") fun liftBlood() { ... }
    @Hook.Exit("bloodfx.Gpu", "render")  fun restoreBlood() { ... }
}
object AimShift {
    @Hook.Enter("bloodfx.Aim", "target") fun lower(@Patch.Argument(0) character: Any?) { ... }
    @Hook.Exit("bloodfx.Aim", "target")  fun restore() { ... }
}
object ViewpointBloodFxFix : Feature("viewpoint-blood-fx-fix") {
    @LuaGlobal("Dont39AllInOne_setViewpointBloodFxFixForce") fun setForce(value: Boolean) { ... }
}
```

### Rules the processor enforces (compile errors naming the function)

| Rule | Why |
|------|-----|
| Function is a member of a top-level `object` or a top-level function (called as `Obj.f()` / `f()`); not a class member, companion, extension, or local | the generated static advice needs a receiver-free call |
| Not `private` / `protected` (`internal` is fine) | the call is inlined into the target mod's class |
| Every hook parameter carries exactly one `Patch.*` parameter annotation | ByteBuddy must know what to bind |
| No `readOnly = false` | the call goes through a separate frame and Kotlin parameters are `val`; a write could never reach the target |
| `Hook.Enter` returns `Unit`, or returns a value only with `skipOn = true` | ZombieBuddy warns on non-void advice otherwise (`PatchTransformer`) |
| `Hook.Exit` returns `Unit` | same |
| At most one `Enter` and one `Exit` per target method per feature | ordering of chained advice is not defined |
| Target `className` + `methodName` pairs map to unique nested class names (`<SimpleName>_<method>`) | generated class names |
| `LuaGlobal.name` is unique in the build and starts with the KSP option `dont39.luaPrefix` (`Dont39AllInOne_`) | Lua globals are shared by all mods (`directory-structure.md` Naming) |

`strictMatch` / `warmUp` must agree between the `Enter` and `Exit` of the same target (they land on one `@Patch`).
MethodDelegation (`isAdvice = false`), `RuntimeType`, `SuperCall`, `SuperMethod`, `IKnowWhatIAmDoing`,
`OnExit.suppress` are not exposed; add them when a feature needs them.

## Generated code

Kotlin source in `com.dont39.pz.allinone`:

```kotlin
object ViewpointBloodFxFixPatches {
    @Patch(className = "bloodfx.Gpu", methodName = "render")
    object Gpu_render {
        @JvmStatic @Patch.OnEnter fun enter() = LiftOffsets.liftBlood()
        @JvmStatic @Patch.OnExit(onThrowable = Throwable::class) fun exit() = LiftOffsets.restoreBlood()
    }
    ...
}
object ViewpointBloodFxFixLua {   // instance method: Kahlua binds global functions to an instance
    @LuaMethod(name = "Dont39AllInOne_setViewpointBloodFxFixForce", global = true)
    fun setForce(value: Boolean) = ViewpointBloodFxFix.setForce(value)
}
```

- Nested class name: target simple class name + `_` + method name (unique per target method); several features
  hooking the same method get separate outer classes.
- Code generation by string templates (no KotlinPoet dependency); each generated file declares its originating
  `KSFile`s so KSP's incremental processing works.
- Generated patch class names change from today's (`$BloodRender` → `$Gpu_render`); smokeTest reads them from the
  resource below, so nothing else depends on the old names.

## smokeTest data (R5)

- The processor writes `META-INF/dont39/glue/<Feature>.txt` (KSP `createNewFileByPath`, resource) with lines
  `patch <fqcn>$<nested>` / `target <className>` / `lua <fqcn>`.
- `dont39-all-in-one`'s `smokeTest` reads these files from each feature's KSP resource output directory (a task
  input) and builds `Found patch class:`, `Transformed:` (only when the feature reports ready), and `Exposing global
  functions from class:` expectations.

## Spike (R0)

1. Add KSP plugin + processor module with a processor that generates one trivial file into the utils or a scratch
   feature; build with `--configuration-cache`.
2. Check: KSP2 accepts Kotlin 2.4.21 (no fatal version check), generated sources are compiled, config cache stores and
   reuses, `pz-kotlin`'s classpath reordering (`KotlinCompile.libraries`) does not break KSP's own compile task.
3. Pass → continue; fail → record and stop (D2).

## Rollback

Remove `pz-feature`, the processor module, and the KSP entries; restore hand-written glue (option A).
