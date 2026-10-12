# Design: zombiebuddy-kotlin-utils

Sizes are sketches; the API is settled while porting viewpoint (its first user) and must not grow beyond what that
port needs plus the R2-R4 shape.

## Packaging

- `zombiebuddy-kotlin-utils/build.gradle.kts`: `buildsrc.convention.pz-kotlin` only.
- `dont39-all-in-one/build.gradle.kts`: a separate `internalLibs = listOf(":zombiebuddy-kotlin-utils")` next to
  `features`; `implementation(project(it))` and `tasks.jar { from(<main output>) }` like features, but no Lua copy and no
  smokeTest expectations.
- Feature modules depend on it with `implementation(project(":zombiebuddy-kotlin-utils"))`.
- Classes are unrelocated in the All-in-One jar on the shared system classloader. Only one of our mods may bundle it;
  a second user means moving it into the library mod first (spec note, R7).

## Resolver (R2)

```kotlin
class Resolver {                       // one per helper object, used only in its init block
    var problem: String? = null; private set
    fun type(name: String): Class<*>?
    fun field(owner: Class<*>?, name: String): Field?              // setAccessible(true)
    fun <T> staticValue(owner: Class<*>?, name: String): T?
    fun constructor(owner: Class<*>?, vararg params: Class<*>): Constructor<*>?
    fun static(owner: Class<*>?, name: String, type: MethodType): MethodHandle?
    fun virtual(owner: Class<*>?, name: String, type: MethodType): MethodHandle?
}
```

- After the first failure every call returns null without trying (a missing class makes the rest meaningless) and
  `problem` keeps the first `e.toString()`. Catches `ReflectiveOperationException | RuntimeException | LinkageError`.
- Values are assigned to plain `private val`s in the object's initializer → field reads on the hot path (R5). No
  property delegates (they add a `getValue` call and a `KProperty` reference per access).
- A small `Coords`-style grouping stays in the feature; the resolver does not try to model the target's types.

## Feature base (R3, R4, R6)

```kotlin
abstract class Feature(val name: String) {
    protected abstract fun problem(): String?            // feature-specific: null = can run
    fun init()                                           // marks initialized; logs "ready (<readyDetail()>)" / "idle: <why>"
    fun active(): Boolean                                // false before init(); cached decision; logs active/inactive once
    fun setForce(value: Boolean)                         // re-opens the decision unless disabled
    fun disable(what: String, t: Throwable)              // final; "disabled after an error while <what>"
    fun log(msg: String); fun warn(msg: String)
    protected open fun readyDetail(): String? = null
    protected val force: Boolean
}
fun targetVersion(versionClass: String, field: String = "VERSION"): String?
fun versionProblem(target: String, version: String?, known: Set<String>, force: Boolean, overrideHint: String): String?
```

- The facade is `object ViewpointBloodFxFix : Feature("viewpoint-blood-fx-fix")`. No Java callers remain, so no
  `@JvmStatic` is needed outside the advice methods themselves (see viewpoint design).
- State uses `@Volatile` fields like today (`force`, `failed`, `active: Boolean?` → an `Int` tri-state to avoid boxing
  on the read path).
- **Kotlin-not-ready:** `Feature`'s initializer and `active()`'s fast path use only language features that compile
  to plain field reads; no stdlib collection calls before `init()`. The facade's own `<clinit>` likewise (keep
  `setOf(...)` in `problem()`).
- Prefix: `[Dont39AllInOne] <name>: ` — the "Dont39AllInOne" part is a constructor parameter with that default, so
  the module stays mod-neutral.

## Rollback

Internal module with one user; reverting the viewpoint port and removing the module from `settings.gradle.kts` and
`dont39-all-in-one/build.gradle.kts` restores the previous state.
