# Quality Guidelines

> Done means: it builds, it was tried in game, and it cannot hurt a game that differs from what it expects.

---

## Build Gate

```bash
./gradlew build assembleMod
```

- Gradle must run on the JDK version `buildSrc` requires (see `buildSrc/build.gradle.kts`). Nothing in the repo pins
  the Gradle JVM, so set `JAVA_HOME` accordingly if the shell default is older.
- `pzDir` in `gradle.properties` must point at a Project Zomboid install that contains the jars
  `buildSrc/src/main/kotlin/pz-java.gradle.kts` compiles against.
- The features' target mods, if any, must be subscribed in Steam.
- Check the assembled jar contains the feature's glue class and hook listings (output location: `assembleMod` in
  `dont39-all-in-one/build.gradle.kts`; `checkGlue` checks the listings).

## Tests

Feature modules have no unit tests: their code depends on other mods' classes that exist only in game. Do not add a
test framework as part of a feature task; propose it separately. `./gradlew check` runs the build checks and the JUnit
tests of Zabric's hook runtime and processor ([Zabric Hooks](./zabric-hooks.md#where-it-lives)), which need
`ZombieBuddy.jar` under `pzDir`.

The automated checks launch the real game (`smokeTest`, `dont39-all-in-one/build.gradle.kts`) or dedicated server
(`serverSmokeTest`, `zb-kt/build.gradle.kts`) with ZombieBuddy until init and check the log. Read the task for its
prerequisites. After a fresh preload registration a test fails with "run again"; run it twice. A broken widening list
in any installed mod fails both.

`smokeTest` fails on any mod's `hooks stopped` line. A feature with generated glue needs no edit to them; a Lua-only
feature adds its line to `smokeTest`
(see [Adding a Feature](./directory-structure.md#adding-a-feature)).

## In-Game Verification

1. Deploy with the `deployWorkshop` tasks (`buildSrc/src/main/kotlin/pz-mod.gradle.kts`).
2. Run `smokeTest`, then play: with what the feature needs in place (its target mods enabled, if any)
   `console.txt` shows the feature ready, then active, and the fix is visible.
3. Without it (a target mod disabled or at another version, or what `problem()` checks unmet): the feature reports
   idle/inactive, behavior unchanged, no errors.
4. Debug output on (the target mod's debug option, or `-debug`): at most periodic summary lines, no per-frame spam.
5. Hook not applying: raise ZombieBuddy's log verbosity
   ([CommandLine.md](../../../external-repos/ZombieBuddy/doc/CommandLine.md)) and check the glue's patch class was
   found.

If an in-game check was not done, say so in the task report.

---

## Code Style

Follow [`.editorconfig`](../../../.editorconfig). Bytecode level: see `pz-java.gradle.kts` and `pz-kotlin.gradle.kts`.
New code is Kotlin; Java only in `zabric`, `zabric-processor`, the samples, and where
[Kotlin Runtime](./kotlin-runtime.md) requires it.

## Forbidden Patterns

| Pattern | Why |
|---------|-----|
| Glue generated outside the `javaPkgName` package | ZombieBuddy skips its patch classes |
| A ZombieBuddy `@Patch` class, with or without `@UnsafeLoaderPatch` | Hooks go through Zabric ([Zabric Hooks](./zabric-hooks.md#hand-written-loader-patches)) |
| ZombieBuddy types outside Zabric's loader package and generated glue | Breaks the [Loader Seam](./zombiebuddy-patching.md#loader-seam); `checkLoaderRefs` fails |
| Reflection or method handles in feature code | See [Access Widening](./access-widening.md) |
| The `active()` gate or a try/catch in a handler | The generated glue owns both ([ZombieBuddy Patching](./zombiebuddy-patching.md#patch-classes)) |
| Hand-written glue or Lua bridge classes | Generated from `@Hooks` / `@LuaGlobal` ([ZombieBuddy Patching](./zombiebuddy-patching.md#patch-classes)) |
| Running against unchecked target versions | Internals may differ |
| Changing another mod's state without guaranteed restore | Leaves the target corrupted |
| `System.out`, unprefixed or per-frame logging | See [Logging Guidelines](./logging-guidelines.md) |
| Bundling game, ZombieBuddy or target-mod jars | Provided at runtime |
| `kotlin/` or `kotlinx/` classes in any jar but ZombieBuddyKotlin's | Shadowed or duplicated on the shared classpath ([Kotlin Runtime](./kotlin-runtime.md)) |
| `zb-kt-utils` classes in any jar but ZombieBuddyKotlin's | Duplicated on the shared classpath ([ZombieBuddy Kotlin Utils](./zb-kt-utils.md)) |
| Relocating `kotlin.*` | Not needed; ZombieBuddyKotlin installs the current stdlib |
| Feature code inside `dont39-all-in-one` | Each feature is its own module |

## Release Checklist

- Version bumped in the shipped module's `build.gradle.kts` (`dont39-all-in-one`, `zb-kt`); Zabric's, shared by
  `zabric-processor`, in `gradle/libs.versions.toml`.
- Zabric releases also go to Maven Central, only after the user confirms version and artifacts
  ([Zabric Hooks](./zabric-hooks.md#publishing-and-compatibility)).
- `mod.info` load order and `workshop.txt` updated for new target mods.
- Upload order when a release spans layers: Zabric, then ZombieBuddyKotlin, then All-in-One.
