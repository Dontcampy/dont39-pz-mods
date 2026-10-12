# KSP spike and processor verification (2026-10-12)

## Result: pass (D1 holds, no fallback)

| Item | Value |
|------|-------|
| KSP | `com.google.devtools.ksp` **2.3.12** (KSP2, latest on Maven Central; metadata lastUpdated 2026-09-09) |
| Kotlin | 2.4.21 (unchanged) |
| Gradle / JDK | Gradle 9.6.0 on GraalVM CE 25.0.2; module toolchains 25 |
| Wiring | `symbol-processing-gradle-plugin` as a `buildSrc` implementation dependency; plugin id applied by `pz-feature` |

Checks (design.md "Spike"):

1. **Kotlin 2.4.21:** KSP2 2.3.12 ran with no version warning or error; the generated Kotlin compiled into the module
   and was usable from feature code.
2. **Generated sources compiled:** yes, `build/generated/ksp/main/kotlin` is part of `compileKotlin`; KSP also adds
   `build/generated/ksp/main/resources` to the `main` source set, so the glue resource lands in
   `build/resources/main` and the module jar (`dont39-all-in-one`'s jar excludes `META-INF/dont39/**`).
3. **Configuration cache:** KSP adds no configuration-cache problem.
   - The full `build assembleMod` graph was already *discarded* before this task (10 problems, all from
     zb-gradle-plugin 1.0.3's `JavaCompile` doFirst, marked `notCompatibleWithConfigurationCache` in `pz-java`).
     After the change it is the same 10 problems, same tasks; build passes.
   - A graph containing KSP but no `compileJava` (`:<feature>:compileKotlin -x :zombiebuddy-kotlin-utils:compileJava`,
     and `:zombiebuddy-kotlin-utils-processor:build`): first run "Configuration cache entry stored", second run
     "Configuration cache entry reused".
4. **pz-kotlin's `KotlinCompile.libraries` reordering:** KSP's task `kspKotlin` is `KspAATask`, not a `KotlinCompile`
   subtype, so the reordering does not touch it; `compileKotlin` (which compiles the generated sources) keeps the
   reordering as before. KSP itself sees the compile classpath in declaration order (game jar before the 2.4.21
   stdlib); no resolution problem showed up (`kotlin.Unit`, `kotlin.Boolean`, ZombieBuddy and Kahlua annotations all
   resolved). If KSP ever mis-resolves a `kotlin.*` type, apply the same reordering to `KspAATask`.

## Processor verification (temporary `ksp-sample` module, deleted afterwards)

Sample: `@Hook.Enter`/`@Hook.Exit` pair with `@Patch.Argument(0)` and `@Patch.This`, an exit with `@Patch.Return` and
`@Patch.Thrown`, `@Hook.Enter(skipOn = true, strictMatch = true)` returning `Boolean` on `<init>` with
`@Patch.AllArguments`, a top-level-function `@Hook.Exit` with `onThrowable = Patch.NoException::class` and
`@Patch.Local`, an `internal` hook, a nested target class (`zombie.Lua.LuaManager$Exposer`), two `@LuaGlobal`s (one
returning a value), and a viewpoint-shaped variant (`object X : Feature(...)` with `@LuaGlobal ... override fun
setForce`).

- Generated sources: `<Feature>Patches` (outer `object`, nested `object <Simple>_<method>` per target, `$` in class
  names escaped, `<init>` -> `_init_`), `<Feature>Lua` (`object`, instance methods named like the Lua function), and
  `META-INF/dont39/glue/<Feature>.txt`.
- `javap -v -p`: `RuntimeVisibleAnnotations: me.zed_0xff.zombie_buddy.Patch(className=..., methodName=...[,
  strictMatch=true])` on each nested class; `enter` / `exit` are `ACC_PUBLIC, ACC_STATIC, ACC_FINAL` with
  `Patch$OnEnter` / `Patch$OnEnter(skipOn=true)` (returns `boolean`) / `Patch$OnExit(onThrowable=class
  Ljava/lang/Throwable;)`; parameter annotations `Patch$Argument(value=0)`, `This`, `Return`, `Thrown`,
  `AllArguments`, `Local(value="x")` copied. Lua methods: public instance, `LuaMethod(name=..., global=true)`.
  Note: the advice methods also carry `kotlin.jvm.JvmStatic` (runtime-visible) and, for non-null parameters, an
  `Intrinsics.checkNotNullParameter` call that ByteBuddy inlines into the target; both exist in the game's own
  stdlib 1.4.10 as well.
- All-in-One with both sample modules in its feature list (temporarily): the jar contained the generated classes and
  no glue resource; `checkGlue` failed on a Lua name used by two modules
  (`Dont39AllInOne_sampleGet: ksp-sample, ksp-sample2`) and passed once renamed.

### Rules (each violated once, `kspKotlin` failed with `e: [ksp] <file>:<line>: <Owner.function>: <reason>`)

| Rule | Violation tried |
|------|-----------------|
| Receiver: member of a top-level object or top-level function | class member, companion member, nested-object member, local function, extension function |
| Not private / protected | private function, protected function, member of a private object |
| Exactly one `Patch.*` binding per parameter | none, two, `@Patch.SuperCall` (unsupported) |
| No `readOnly = false` | `@Patch.Argument(0, readOnly = false)` |
| `Return` / `Thrown` only in exit (extra) | `@Patch.Return` on an enter |
| Enter returns Unit, or Boolean with `skipOn = true` | enter returning `Int`; `skipOn = true` returning Unit |
| Exit returns Unit | exit returning `Boolean` |
| One Enter / Exit per target | two enters on `a.A.dup` |
| `strictMatch` / `warmUp` agree for one target | enter `strictMatch = true`, exit default |
| Unique nested class names | `x.Gpu.render` and `y.Gpu.render` |
| Lua name prefix, identifier, unique | `noPrefix`; `Dont39AllInOne_bad-name`; same name twice in one module (across modules: `checkGlue`) |
