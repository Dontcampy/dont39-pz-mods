# Quality Guidelines

> Done means: it builds, it was tried in game, and it cannot hurt a game that differs from what it expects.

---

## Build Gate

```bash
./gradlew build assembleMod
```

- Gradle must run on the JDK version `buildSrc` requires (see `buildSrc/build.gradle.kts`). Nothing in the repo pins
  the Gradle JVM, so set `JAVA_HOME` accordingly if the shell default is older.
- `pzDir` must point at a Project Zomboid install that contains the jars `buildSrc/src/main/kotlin/pz-java.gradle.kts`
  compiles against, and `pzServerDir` at the dedicated server (`serverSmokeTest` only). Both are machine paths: they
  go in `~/.gradle/gradle.properties`, never in the repo's `gradle.properties` (`README.md`). A project configures
  without them; a task that needs one fails with a hint (`PzPaths.kt`).
- The features' target mods, if any, must be subscribed in Steam.
- Check the assembled jar contains the feature's glue class and hook listings (output location: `assembleMod` in
  `pz-mod.gradle.kts`; `checkGlue` checks the listings).

## Tests

Feature modules have no unit tests: their code depends on other mods' classes that exist only in game. Do not add a
test framework as part of a feature task; propose it separately. Game-free code (e.g. `tunnel`'s `kcp`/`fec`) is
exercised by a harness outside the repo, with its results in the task's research
(`10-18-kcp-tunnel/research/kcp-harness.md`). `./gradlew check` runs the build checks and the JUnit
tests of Zabric's hook runtime and both processor front ends
([Zabric Hooks](./zabric-hooks.md#where-it-lives)), which need `ZombieBuddy.jar` under `pzDir`. A module with JUnit
tests applies `buildsrc.convention.pz-junit`, plus `pz-loader-test` when its tests run against ZombieBuddy's classes;
it adds no JUnit setup of its own.

The automated checks launch the real game (`smokeTest` of `dont39-all-in-one`, `GameSmokeTest.kt`) or dedicated
server (`serverSmokeTest` of `zb-kt`, `ServerSmokeTest.kt`) with ZombieBuddy until init and check the log. Read the task
for its prerequisites. After a fresh preload registration a test fails with "run again"; run it twice. A broken widening
list in any installed mod fails both.

A new feature needs no edit to them, except a Lua-only feature with a target mod, which goes in `smokeTest`'s
`luaTargeted` (see [Adding a Feature](./directory-structure.md#adding-a-feature)).

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

6. Network features (tunnel, network status): loss and latency are injected with a user-space UDP/TCP proxy, since
   the test server has no root for `tc netem`. Direct UDP is proxied on the client: run the proxy on 127.0.0.1 at both
   the game port and the server's UDPPort, and join 127.0.0.1. RakNet's main traffic goes to the UDPPort the server
   announces, so a server-side proxy in front of the game port alone sees none of it. Tunnels are proxied on the
   server in front of the tunnel port. The proxy must not hold the local ports a tunnel client listens on.
7. Chinese text with missing characters is the game's CN bitmap fonts without a font patch, not the mod's strings.

If an in-game check was not done, say so in the task report.

---

## Code Style

Follow [`.editorconfig`](../../../.editorconfig). Bytecode level: see `PzJvm.kt`.
New code is Kotlin; Java only in `zabric`, `zabric-processor`, `zb-skip-online-checks`, the samples, and where
[Kotlin Runtime](./kotlin-runtime.md) requires it. Copied third-party code keeps its language, license header and
source commit, lives in a subpackage of the feature (all mod jars share one class loader), and its license text goes
into the jar's `META-INF/licenses/` (`tunnel`: `kcp/`, `fec/rs/`). Review it for bugs, not style.

## Forbidden Patterns

| Pattern | Why |
|---------|-----|
| Glue generated outside the `javaPkgName` package | ZombieBuddy skips its patch classes |
| A ZombieBuddy `@Patch` class, with or without `@UnsafeLoaderPatch` | Hooks go through Zabric ([Zabric Hooks](./zabric-hooks.md#rules)) |
| ZombieBuddy types outside Zabric's loader package and generated glue | Breaks the [Loader Seam](./zombiebuddy-patching.md#loader-seam); `checkLoaderRefs` fails |
| Reflection or method handles in feature code | See [Access Widening](./access-widening.md) |
| The `active()` gate or a try/catch in a handler | The generated glue owns both ([ZombieBuddy Patching](./zombiebuddy-patching.md#patch-classes)) |
| Hand-written glue or Lua bridge classes | Generated from `@Hooks` / `@LuaGlobal` ([ZombieBuddy Patching](./zombiebuddy-patching.md#patch-classes)) |
| Running against unchecked target versions | Internals may differ |
| Changing another mod's state without guaranteed restore | Leaves the target corrupted |
| `System.out`, unprefixed or per-frame logging | See [Logging Guidelines](./logging-guidelines.md) |
| Bundling game, ZombieBuddy or target-mod jars | Provided at runtime |
| `kotlin/` or `kotlinx/` classes in any jar but ZabricKotlin's | Shadowed or duplicated on the shared classpath ([Kotlin Runtime](./kotlin-runtime.md)) |
| `zb-kt-utils` classes in any jar but ZabricKotlin's | Duplicated on the shared classpath ([ZabricKotlin Utils](./zb-kt-utils.md)) |
| Relocating `kotlin.*` | Not needed; ZabricKotlin installs the current stdlib |
| Feature code inside `dont39-all-in-one` | Each feature is its own module |

## Release Checklist

- Version bumped in the shipped module's `build.gradle.kts` (`dont39-all-in-one`, `zb-kt`, `zb-skip-online-checks`);
  Zabric's, shared by `zabric-processor`, in `gradle/libs.versions.toml`.
- Zabric releases also go to Maven Central, only after the user confirms version and artifacts
  ([Zabric Hooks](./zabric-hooks.md#publishing-and-compatibility)).
- `mod.info` load order and `workshop.txt` updated for new target mods.
- Upload order when a release spans layers: Zabric, then ZabricKotlin, then All-in-One.
