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
- Check the assembled jar contains the new patches class (output location: `assembleMod` in
  `dont39-all-in-one/build.gradle.kts`).

## Tests

There are no unit tests and no test framework; `./gradlew check` compiles and runs the build checks. Feature code
depends on other mods' classes that exist only in game. Do not add a test framework as part of a feature task; propose
it separately.

The automated checks launch the real game (`smokeTest`, `dont39-all-in-one/build.gradle.kts`) or dedicated server
(`serverSmokeTest`, `zb-kt/build.gradle.kts`) with ZombieBuddy until init and check the log. Read the task for its
prerequisites. After a fresh preload registration a test fails with "run again"; run it twice. A broken widening list
in any installed mod fails both.

A feature with generated glue needs no edit to them; a Lua-only feature adds its line to `smokeTest`
(see [Adding a Feature](./directory-structure.md#adding-a-feature)).

## In-Game Verification

1. Deploy with the `deployWorkshop` tasks (`buildSrc/src/main/kotlin/pz-mod.gradle.kts`).
2. Run `smokeTest`, then play: with what the feature needs in place (its target mods enabled, if any)
   `console.txt` shows the feature ready, then active, and the fix is visible.
3. Without it (a target mod disabled or at another version, or what `problem()` checks unmet): the feature reports
   idle/inactive, behavior unchanged, no errors.
4. Debug output on (the target mod's debug option, or `-debug`): at most periodic summary lines, no per-frame spam.
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
| ZombieBuddy types outside Zabric's loader package and generated glue | Breaks the [Loader Seam](./zombiebuddy-patching.md#loader-seam); `checkLoaderRefs` fails |
| Reflection or method handles in feature code | See [Access Widening](./access-widening.md) |
| The `active()` gate or a try/catch in a `@Hook` function | The generated glue owns both ([ZombieBuddy Patching](./zombiebuddy-patching.md#patch-classes)) |
| Hand-written patch or Lua bridge classes in a Kotlin feature | Generated from `@Hook` / `@LuaGlobal` ([ZombieBuddy Patching](./zombiebuddy-patching.md#patch-classes)) |
| Running against unchecked target versions | Internals may differ |
| Changing another mod's state without guaranteed restore | Leaves the target corrupted |
| `System.out`, unprefixed or per-frame logging | See [Logging Guidelines](./logging-guidelines.md) |
| Bundling game, ZombieBuddy or target-mod jars | Provided at runtime |
| `kotlin/` or `kotlinx/` classes in any jar but ZombieBuddyKotlin's | Shadowed or duplicated on the shared classpath ([Kotlin Runtime](./kotlin-runtime.md)) |
| `zb-kt-utils` classes in any jar but ZombieBuddyKotlin's | Duplicated on the shared classpath ([ZombieBuddy Kotlin Utils](./zb-kt-utils.md)) |
| Relocating `kotlin.*` | Not needed; ZombieBuddyKotlin installs the current stdlib |
| Feature code inside `dont39-all-in-one` | Each feature is its own module |

## Release Checklist

- Version bumped in the shipped module's `build.gradle.kts` (`dont39-all-in-one`, `zabric` or
  `zb-kt`).
- `mod.info` load order and `workshop.txt` updated for new target mods.
- Upload order when a release spans layers: Zabric, then ZombieBuddyKotlin, then All-in-One.
