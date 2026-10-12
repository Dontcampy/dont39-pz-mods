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
- Check the assembled jar contains the new patches class (output location: `assembleMod` in
  `dont39-all-in-one/build.gradle.kts`).

## Tests

There are no unit tests and no test framework; `./gradlew check` only compiles. Feature code depends on other mods'
classes that exist only in game. Do not add a test framework as part of a feature task; propose it separately.

The automated check is `smokeTest` in `dont39-all-in-one/build.gradle.kts`: it deploys, launches the real game with
ZombieBuddy until init, and checks `console.txt`. Read the task for its prerequisites (Steam, ZombieBuddy installed,
the game's enabled-mod list). Every feature must extend its expectations (see
[Adding a Feature](./directory-structure.md#adding-a-feature)).

## In-Game Verification

1. Deploy with the `deployWorkshop` task (`dont39-all-in-one/build.gradle.kts`). It refuses unsigned builds; the task
   says how to configure signing or opt out for a local-only build.
2. Run `smokeTest`, then play: with target mods enabled `console.txt` shows the feature ready, then active, and the
   fix is visible.
3. A target mod disabled or at another version: the feature reports idle/inactive, behavior unchanged, no errors.
4. Target mod's debug option on: periodic summary lines, no per-frame spam.
5. Patch not applying: raise ZombieBuddy's log verbosity
   ([CommandLine.md](../../../external-repos/ZombieBuddy/doc/CommandLine.md)) and check the patch class was found.

If an in-game check was not done, say so in the task report.

---

## Code Style

Follow [`.editorconfig`](../../../.editorconfig). Java language level: see `pz-java.gradle.kts`.

## Forbidden Patterns

| Pattern | Why |
|---------|-----|
| Compile dependency on another mod's jar, or class literals of its classes | It is not on the classpath |
| Patch classes outside the `javaPkgName` package | ZombieBuddy skips them |
| Logic or try/catch in patches classes | Helpers own state and error handling |
| Running against unchecked target versions | Internals may differ |
| Changing another mod's state without guaranteed restore | Leaves the target corrupted |
| `System.out`, unprefixed or per-frame logging | See [Logging Guidelines](./logging-guidelines.md) |
| Bundling game or ZombieBuddy jars | Provided at runtime |
| Feature code inside `dont39-all-in-one` | Each feature is its own module |

## Release Checklist

- Version bumped in `dont39-all-in-one/build.gradle.kts`.
- `mod.info` load order and `workshop.txt` updated for new target mods.
