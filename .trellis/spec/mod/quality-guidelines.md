# Quality Guidelines

> Done means: it builds, it was tried in game, and it cannot hurt a game where the target mods differ.

---

## Build Gate

```bash
./gradlew build assembleMod
```

- Gradle must run on the JDK version `buildSrc` requires (see `buildSrc/build.gradle.kts`). Nothing in the repo pins
  the Gradle JVM, so set `JAVA_HOME` accordingly if the shell default is older.
- `pzDir` in `gradle.properties` must point at a Project Zomboid install that contains the jars
  `buildSrc/src/main/kotlin/pz-java.gradle.kts` compiles against.
- The features' target mods must be subscribed in Steam.
- Check the assembled jar contains the new patches class (output location: `assembleMod` in
  `dont39-all-in-one/build.gradle.kts`).

## Tests

There are no unit tests and no test framework; `./gradlew check` compiles and runs the build checks
(`checkBootstrapClasses`, `checkGlue`). Feature code depends on other mods' classes that exist only in game. Do not add
a test framework as part of a feature task; propose it separately.

The automated checks launch the real game or server with ZombieBuddy until init and check its log; what they require
is in the task code (`smokeTest` in `dont39-all-in-one/build.gradle.kts`, `serverSmokeTest` in `zb-kt/build.gradle.kts`,
shared checks in `buildSrc/src/main/kotlin/PzSmoke.kt`).

- `smokeTest`: Steam running, ZombieBuddy installed in the game, the enabled-mod list including Zabric,
  ZombieBuddyKotlin and All-in-One.
- `serverSmokeTest`: `pzServerDir` in `gradle.properties` pointing at a dedicated server with ZombieBuddy installed,
  and the ZombieBuddy Workshop item downloaded on the client (the server runs without Steam and gets a copy).
- A launch that registers a preload mod fails with "run again": after a fresh registration (new setup, or entries
  pointing at a copy that is no longer identical) each test needs two runs.
- Every installed mod's widening list counts: uninstall scratch mods with broken lists before running either test.
- A feature with generated glue needs no edit to them; a Lua-only feature adds its line to `smokeTest`
  (see [Adding a Feature](./directory-structure.md#adding-a-feature)).

## In-Game Verification

1. Deploy with the `deployWorkshop` tasks (`buildSrc/src/main/kotlin/pz-mod.gradle.kts`); `smokeTest` runs those of all
   three shipped mods.
2. Run `smokeTest`, then play: with target mods enabled `console.txt` shows the feature ready, then active, and the
   fix is visible.
3. A target mod disabled or at another version: the feature reports idle/inactive, behavior unchanged, no errors.
4. Target mod's debug option on: periodic summary lines, no per-frame spam.
5. Patch not applying: raise ZombieBuddy's log verbosity
   ([CommandLine.md](../../../external-repos/ZombieBuddy/doc/CommandLine.md)) and check the patch class was found.

If an in-game check was not done, say so in the task report.

---

## Code Style

Follow [`.editorconfig`](../../../.editorconfig). Bytecode level: see `pz-java.gradle.kts` and `pz-kotlin.gradle.kts`.
New code is Kotlin; Java only in `zabric` and where [Kotlin Runtime](./kotlin-runtime.md) requires it.

## Forbidden Patterns

| Pattern | Why |
|---------|-----|
| Patch classes outside the `javaPkgName` package | ZombieBuddy skips them |
| Reflection or method handles in feature code | See [Access Widening](./access-widening.md) |
| Logic or try/catch in patches classes | Helpers own state and error handling |
| Hand-written patch or Lua bridge classes in a Kotlin feature | Generated from `@Hook` / `@LuaGlobal` ([ZombieBuddy Patching](./zombiebuddy-patching.md#patch-classes)) |
| Running against unchecked target versions | Internals may differ |
| Changing another mod's state without guaranteed restore | Leaves the target corrupted |
| `System.out`, unprefixed or per-frame logging | See [Logging Guidelines](./logging-guidelines.md) |
| Bundling game, ZombieBuddy or target-mod jars | Provided at runtime |
| `kotlin/` or `kotlinx/` classes in any jar but ZombieBuddyKotlin's | Shadowed or duplicated on the shared classpath ([Kotlin Runtime](./kotlin-runtime.md)) |
| `zb-kt-utils` classes in any jar but ZombieBuddyKotlin's | Duplicated on the shared classpath ([ZombieBuddy Kotlin Utils](./zb-kt-utils.md)) |
| Readiness checks on ZombieBuddyKotlin beyond the gate | See [Kotlin Runtime](./kotlin-runtime.md#dependent-kotlin-modules) |
| Relocating `kotlin.*` | Not needed; ZombieBuddyKotlin installs the current stdlib |
| Feature code inside `dont39-all-in-one` | Each feature is its own module |

## Release Checklist

- Version bumped in the shipped module's `build.gradle.kts` (`dont39-all-in-one`, `zabric` or
  `zb-kt`).
- `mod.info` load order and `workshop.txt` updated for new target mods.
- Upload order when a release spans layers: Zabric, then ZombieBuddyKotlin, then All-in-One.
