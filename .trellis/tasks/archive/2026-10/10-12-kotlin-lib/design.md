# Design: ZombieBuddyKotlin

## Boundaries

```
buildSrc/src/main/kotlin/
  pz-java.gradle.kts        unchanged role: Java + ZombieBuddy/game compileOnly
  pz-kotlin.gradle.kts      NEW: applies pz-java + kotlin("jvm") + serialization plugin; compileOnly stdlib/kotlinx
  pz-mod.gradle.kts         NEW (extracted): Workshop staging (deployWorkshop + id guard) shared by both shipped mods
  kotlin-jvm.gradle.kts     template leftover; delete
zombie-buddy-kotlin/          NEW shipped mod
  build.gradle.kts          bundles kotlinx into the jar, ships kotlin-stdlib as a separate file, writes checksum
  src/main/java/com/dont39/pz/zombiebuddykotlin/Main.java       ZombieBuddy entry, Java only
  src/main/java/com/dont39/pz/zombiebuddykotlin/ZombieBuddyKotlin.java  public readiness API + swap logic, Java only
  src/main/kotlin/com/dont39/pz/zombiebuddykotlin/selfcheck/SelfCheck.kt  runs only after the swap; subpackage so ZombieBuddy's package-wide class loading never touches it
  mod/42/mod.info, mod/42/poster.png, mod/workshop/{workshop.txt,preview.png}
dont39-all-in-one/          applies pz-kotlin; Main.java -> Main.kt; mod.info/workshop.txt updated
```

`viewpoint-blood-fx-fix` keeps `pz-java`.

## Runtime Contract

### Library mod folder (assembled)

```
ZombieBuddyKotlin/42/mod.info
ZombieBuddyKotlin/42/media/java/ZombieBuddyKotlin.jar              com.dont39.pz.zombiebuddykotlin.* + kotlinx.* (unrelocated)
ZombieBuddyKotlin/42/media/java/lib/kotlin-stdlib-<ver>.jar      the unmodified Maven Central artifact
```

- The jar path has no `client/` or `server/`, so the library loads on both sides (ModdingGuide "Client/server JAR
  paths").
- The jar contains a resource `com/dont39/pz/zombiebuddykotlin/stdlib.properties` with `file=`, `version=` and `sha256=`.
  `build.gradle.kts` generates it from the resolved stdlib artifact, so the checksum is covered by ZombieBuddy's
  approval of the mod jar.
- `mod.info`:
  - `id=ZombieBuddyKotlin`, `require=\ZombieBuddy`, `javaJarFile=media/java/ZombieBuddyKotlin.jar`,
    `javaPkgName=com.dont39.pz.zombiebuddykotlin`.
  - `ZBVersionMin` is the same as All-in-One's. There is no `ZBVersionMax`; a missing field is handled at runtime.

### Swap sequence (`ZombieBuddyKotlin.install()`, called from `Main.main`)

1. Read `me.zed_0xff.zombie_buddy.Loader.g_instrumentation` by reflection (`Class.forName` + `setAccessible`).
   ZombieBuddy is in the unnamed module, so this is allowed. If the field is missing or null, the reason is
   "ZombieBuddy <ver> does not expose Instrumentation".
2. If `inst.getAllLoadedClasses()` contains any class named `kotlin.*`, stop: "Kotlin was already loaded (by another
   mod or a mod loaded before ZombieBuddyKotlin)". This scan runs once at startup.
3. Locate the stdlib file relative to the mod jar (`Main.class.getProtectionDomain().getCodeSource()`). Hash it and
   compare with `stdlib.properties`.
4. `inst.appendToBootstrapClassLoaderSearch(new JarFile(file))`.
5. Load `kotlin.KotlinVersion` reflectively and compare `CURRENT.toString()` with `version=`.
6. Run `SelfCheck` reflectively (`Class.forName("com.dont39.pz.zombiebuddykotlin.selfcheck.SelfCheck")`). `Main` and `ZombieBuddyKotlin` hold
   no static reference to any Kotlin type, so the verifier never resolves one early.
7. Set `ready = true` and log the ready line.

Every step catches `Throwable` and records the first reason. Nothing throws out of `main`.

### Why bootstrap works

- Every app-loader lookup of `kotlin.X` delegates to its parent first. Once the jar is appended, `kotlin.X` resolves
  to the 2.x class. This holds for classes loaded by ZombieBuddy into the system loader, and for game classes that
  ByteBuddy advice has inlined Kotlin calls into.
- Step 2 guarantees that no 1.4.10 class is already defined, so there are never two copies in play.
- The stdlib on bootstrap needs nothing from the app loader. kotlinx stays on the system loader, which sees bootstrap.
- `ServiceLoader` use in coroutines resolves through kotlinx's own (system) loader.

### Readiness API (Java, `com.dont39.pz.zombiebuddykotlin.ZombieBuddyKotlin`)

- `public static boolean isReady()`
- `public static String problem()`: null when ready.
- `public static String kotlinVersion()`

Dependent mods call it before running Kotlin-only features. All-in-One's `Main.kt`:
1. Logs its loaded line.
2. Calls `ZombieBuddyKotlin.isReady()` inside `try`/`catch (LinkageError)`. A `LinkageError` here means the library jar is
   absent or loaded later.
3. If the library is not ready, logs one line with the reason.
4. Calls `ViewpointBloodFxFix.init()` in every case.

There are no Kotlin-only features yet, so step 3 only logs.

### Logging

- All lines use `DebugType.Mod` with the prefix `[ZombieBuddyKotlin]`, following `.trellis/spec/mod/logging-guidelines.md`.
- The library logs a loaded line, then one of these:
  - `ready: Kotlin <ver>, kotlinx-coroutines <ver>, kotlinx-serialization <ver>`
  - `not ready: <reason>`

  The `smokeTest` expectations match these strings.

## Build Contract

- `pz-kotlin`:
  - Kotlin `jvmToolchain(25)`, `compilerOptions.jvmTarget = JVM_17`, `-Xjdk-release=17`. These match `pz-java`'s
    `release = 17`.
  - Applies `org.jetbrains.kotlin.plugin.serialization`.
  - Adds `compileOnly` on `kotlin-stdlib`, `kotlinx-coroutines-core`, `kotlinx-serialization-json` from the catalog.
  - Sets `kotlin.stdlib.default.dependency=false` in the root `gradle.properties`, so the Kotlin plugin never adds the
    stdlib as `implementation`.
- `zombie-buddy-kotlin/build.gradle.kts`:
  - Configuration `bundled` holds the kotlinx artifacts minus `kotlin-stdlib`. Their contents are merged into the jar,
    excluding `module-info.class`, `META-INF/versions/**/module-info.class` and signature files.
  - Configuration `stdlib` holds `kotlin-stdlib` only, without transitive dependencies. It is copied into
    `media/java/lib/`, and the generated `stdlib.properties` comes from the same file.
  - `SelfCheck.kt` compiles with `compileOnly` stdlib/kotlinx, so the module applies `pz-kotlin`. The bootstrap Java
    classes import nothing from `kotlin`. A check task greps the compiled `Main`/`ZombieBuddyKotlin` constant pools for
    `kotlin/` and fails the build if it finds one.
- `buildSrc` also needs `org.jetbrains.kotlin:kotlin-serialization` (Gradle plugin artifact, catalog Kotlin version)
  on its classpath.
- `pz-mod` (extracted from `dont39-all-in-one/build.gradle.kts`): `deployWorkshop` with its Workshop-id guard,
  parameterized by mod id and an `assembleMod` task. Both mods use it. Behavior for All-in-One is unchanged.
- `smokeTest` stays in All-in-One. It also depends on the library's `deployWorkshop` and adds the library's
  expectations.

## Compatibility

- **Existing All-in-One subscribers:** after updating, they must also subscribe to ZombieBuddyKotlin, which `require=`
  enforces. Without it, the game's mod manager will not enable All-in-One. The library must therefore be on the
  Workshop before All-in-One's update is uploaded (release order, out of scope).
- **Other mods that ship an unrelocated Kotlin stdlib:** if one loads first, the library reports not ready (step 2).
  If one loads after, it gets 2.x on the classpath, which is binary-compatible for code built against an older stdlib.
- **Split package risk:** a `kotlin.*` class that exists in 1.4.10 but not in 2.x would still load from
  `projectzomboid.jar` in a different loader. Kotlin keeps stdlib binary compatibility and the game references no
  Kotlin, so this is accepted.
- **ZombieBuddy upgrades:** if `g_instrumentation` is renamed, the library reports not ready instead of crashing.

## Rollback

- Revert All-in-One's `mod.info`/`Main` to the previous commit. The library is additive. Nothing writes outside the
  mod folders, and the game files are never modified.
