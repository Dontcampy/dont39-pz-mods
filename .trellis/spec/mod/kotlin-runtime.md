# Kotlin Runtime (ZombieBuddyKotlin)

> How mods in this repo get a current Kotlin at runtime, and the rules every Kotlin module and the library itself must
> keep.

---

## Scope / Trigger

Read this before writing Kotlin in any module, changing a Kotlin/kotlinx version, or touching `zombie-buddy-kotlin`.

The game's `projectzomboid.jar` bundles **kotlin-stdlib 1.4.10** (via okhttp). It sits first on the system classpath,
and ZombieBuddy appends every mod jar after it, so a stdlib inside a mod jar would be shadowed class by class. The
`ZombieBuddyKotlin` mod therefore appends the current, unmodified stdlib jar to the **bootstrap** class loader search
before anything loads a `kotlin.*` class. From then on every `kotlin.*` lookup resolves to the new stdlib. kotlinx
(coroutines, serialization-json) is merged into the library's own jar, unrelocated. The vanilla game never loads Kotlin
itself (nothing outside okhttp/okio references it), which is what makes the MAIN-phase swap safe.

## Signatures

| What | Where |
|------|-------|
| Swap sequence, readiness state, reasons | `zombie-buddy-kotlin/src/main/java/com/dont39/pz/zombiebuddykotlin/ZombieBuddyKotlin.java` (`install()`) |
| Public API for dependent mods: `isReady()`, `problem()`, `kotlinVersion()` | same file |
| ZombieBuddy entry point | `zombie-buddy-kotlin/src/main/java/com/dont39/pz/zombiebuddykotlin/Main.java` |
| Runtime self-check (2.x-only stdlib API, coroutine, serialization round-trip) | `zombie-buddy-kotlin/src/main/kotlin/com/dont39/pz/zombiebuddykotlin/selfcheck/SelfCheck.kt` |
| Shipped files, generated `stdlib.properties`, jar merge, `checkBootstrapClasses` | `zombie-buddy-kotlin/build.gradle.kts` |
| Convention for every Kotlin module | `buildSrc/src/main/kotlin/pz-kotlin.gradle.kts` |
| Versions (single source for compile and runtime) | `gradle/libs.versions.toml` (`kotlin`, `kotlinxCoroutines`, `kotlinxSerializationJSON`) |
| Reference consumer | `dont39-all-in-one/src/main/kotlin/com/dont39/pz/allinone/Main.kt` |

## Contracts

- **Mod folder:** `42/media/java/ZombieBuddyKotlin.jar` (library classes and kotlinx) plus
  `42/media/java/lib/kotlin-stdlib-<ver>.jar`. The jar path has neither `client/` nor `server/`, so it loads on both
  sides.
- **Checksum:** `stdlib.properties` (`file`, `version`, `sha256`, `coroutines`, `serialization`) is generated into the
  mod jar from the same resolved artifact that is shipped. That way ZombieBuddy's approval of the jar also covers the
  stdlib file.
- **Instrumentation:** read by reflection from ZombieBuddy's package-private `Loader.g_instrumentation`
  (`external-repos/ZombieBuddy/java/src/main/java/me/zed_0xff/zombie_buddy/Loader.java`). There is no public accessor.
- **Load order:** ZombieBuddy, per mod in list order, appends the jar, calls `<javaPkgName>.Main.main`, then applies
  patches. Applying patches loads every class in exactly `javaPkgName`; see `ApplyPatchesFromPackage` in `Loader.java`
  and `collectPatches` in `PatchEngine.java`. A dependent mod must therefore load after `ZombieBuddyKotlin`: list it in
  both `require=` and `loadModAfter=` (see `dont39-all-in-one/mod/42/mod.info`).
- **Log lines:** `[ZombieBuddyKotlin] <ver> loaded`, then exactly one of `ready: ...` or `not ready: <reason>`.
  `smokeTest` in `dont39-all-in-one/build.gradle.kts` matches these strings.

## Validation & Error Matrix

Every failure stops the swap, is logged once as `not ready: <reason>`, and is returned by `problem()`. Nothing throws
out of `main`. The exact wording is in `ZombieBuddyKotlin.java`.

| Condition | Result |
|-----------|--------|
| `g_instrumentation` missing or null (ZombieBuddy changed) | not ready; names the ZombieBuddy version |
| A `kotlin.*` class is already loaded (another mod's Kotlin, or a dependent mod loaded first) | not ready: Kotlin already loaded |
| stdlib file missing or SHA-256 mismatch | not ready; tells the player to reinstall |
| `KotlinVersion.CURRENT` differs from `version=` | not ready |
| `SelfCheck` throws | not ready, with the exception |
| Dependent mod's `ZombieBuddyKotlin` call throws `LinkageError` | the dependent mod logs "not loaded" and keeps its Java parts running (`Main.kt`) |

## Good / Base / Bad Cases

- **Good:** ZombieBuddy, ZombieBuddyKotlin, then the dependent mods. The library logs `ready`, and the Kotlin code in
  the dependent mods runs on the current stdlib.
- **Base:** the stdlib jar is corrupted. The library logs `not ready` with the checksum reason. All-in-One logs that the
  library is not ready, and its Java features still work.
- **Bad:** a dependent mod loads before the library. Its Kotlin code links against the game's 1.4.10, and the library
  then refuses with "Kotlin already loaded". This is detected, not worked around.

## Tests Required

No unit tests run in game, so verification happens through the build and `smokeTest`:

- The build runs `checkBootstrapClasses`, which fails if a Java class in the library references a `kotlin/` type.
- `unzip -l` of the assembled jars:
  - The library jar contains `kotlinx/` and no `kotlin/`.
  - A dependent mod's jar contains neither `kotlin/` nor `kotlinx/`.
- `smokeTest` asserts that the library's loaded line, its ready line, and All-in-One's loaded line appear in that
  order in `console.txt`.
- After a version bump, `smokeTest` is required: the ready line must show the new version.

---

## Rules

### Library (`zombie-buddy-kotlin`)

- The Java classes in `com.dont39.pz.zombiebuddykotlin` run before the swap and must not reference any `kotlin.*`
  type. `checkBootstrapClasses` enforces this. Reach Kotlin only through `Class.forName` / reflection after the swap.
- Kotlin code in the library goes in a **subpackage** of `javaPkgName` (today `...selfcheck`). ZombieBuddy loads every
  class in the exact `javaPkgName` package, and only the current order of main-then-patches keeps that load after the
  swap.
- Do not add helper APIs or more kotlinx artifacts here without a decision. Everything in this jar is shared,
  unrelocated, by every dependent mod, and pinned to a single version.
- When versions change, also update the version text in `zombie-buddy-kotlin/mod/workshop/workshop.txt`.

### Dependent Kotlin modules

- Apply `buildsrc.convention.pz-kotlin`. It sets bytecode level 17, the serialization plugin, and `compileOnly` on
  stdlib/kotlinx. It also moves `projectzomboid.jar` to the end of the Kotlin compile classpath, because otherwise
  the compiler resolves `kotlin.*` against the game's 1.4.10 (for example, "unresolved reference: lowercase").
- Never bundle `kotlin/` or `kotlinx/` classes; `kotlin.stdlib.default.dependency=false` is set in the root
  `gradle.properties`. Depend on the library with `compileOnly(project(":zombie-buddy-kotlin"))` only when calling its
  API.
- Code that runs before the readiness check (for example, the start of `Main.main`) may use only what 1.4.10 also has.
  Parameter null-checks are fine. See the comment in `Main.kt`.

### Wrong vs Correct

Wrong: compiling against the game jar first, or shipping the stdlib in a dependent mod.

```kotlin
// dependent module build.gradle.kts
plugins { id("buildsrc.convention.pz-java"); kotlin("jvm") }   // game's 1.4.10 wins at compile time
dependencies { implementation(libs.kotlinStdlib) }              // stdlib ends up shadowed at runtime anyway
```

Correct:

```kotlin
plugins { id("buildsrc.convention.pz-kotlin") }
dependencies { compileOnly(project(":zombie-buddy-kotlin")) }  // only if it calls ZombieBuddyKotlin.isReady()
```
