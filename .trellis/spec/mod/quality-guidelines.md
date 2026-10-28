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

Feature code that uses the game or other mods has no unit tests: those classes exist only in game. A feature's
game-free code is tested in its module, without the game jar on the test classpath; where it needs game state, that
comes in as a parameter with the game-backed default at the call site (`TunnelStore`'s folder). Reference:
`tunnel/src/test/` (KCP and FEC under loss in virtual time, both transports end to end on 127.0.0.1, and fixtures
pinning the store format its Lua parser shares). `./gradlew check` runs the build checks and these JUnit tests, as
well as those of Zabric's hook runtime and both processor front ends
([Zabric Hooks](./zabric-hooks.md#where-it-lives)), which need `ZombieBuddy.jar` under `pzDir`. A module with JUnit
tests applies `buildsrc.convention.pz-junit`, plus `pz-loader-test` when its tests run against ZombieBuddy's classes;
it adds no JUnit setup of its own.

The automated checks launch the real game (`smokeTest` of `dont39-all-in-one`, `loading-progress`,
`faster-loading` and `server-reborn`, `GameSmokeTest.kt`; a mod without a widening list sets its `widener` false) or
dedicated server (`serverSmokeTest` of `zb-kt`, `ServerSmokeTest.kt`) with ZombieBuddy until init and check the log.
Read the task for its prerequisites. After a fresh preload registration a test fails with "run again"; run it twice. A
broken widening list in any installed mod fails both.

A new feature needs no edit to them, except a Lua-only feature with a target mod, which goes in `smokeTest`'s
`luaTargeted` (see [Adding a Feature](./directory-structure.md#adding-a-feature)).

## In-Game Verification

1. Deploy with the `deployWorkshop` tasks (`buildSrc/src/main/kotlin/pz-mod.gradle.kts`).
2. Run `smokeTest`, then play: with what the feature needs in place (its target mods enabled, if any)
   `console.txt` shows the feature ready, then active, and the fix is visible.
3. Without it (a target mod disabled or at another version, or what `problem()` checks unmet): the feature reports
   idle/inactive, behavior unchanged, no errors.
4. Debug output on (the target mod's debug option, or `-debug` with `debuglog.cfg`
   ([Logging](./logging-guidelines.md#debug-output))): at most periodic summary lines, no per-frame spam. An
   unattended run avoids `-debug`: it stops in the Lua debugger at the first Lua error of any mod (Map Mod Manager
   throws one under `-nosteam`).
5. Hook not applying: raise ZombieBuddy's log verbosity
   ([CommandLine.md](../../../external-repos/ZombieBuddy/doc/CommandLine.md)) and check the glue's patch class was
   found.

6. Network features (tunnel, network status): loss and latency are injected with a user-space UDP/TCP proxy, since
   the test server has no root for `tc netem`. Direct UDP is proxied on the client: run the proxy on 127.0.0.1 at both
   the game port and the server's UDPPort, and join 127.0.0.1. RakNet's main traffic goes to the UDPPort the server
   announces, so a server-side proxy in front of the game port alone sees none of it. Tunnels are proxied on the
   server in front of the tunnel port. The proxy must not hold the local ports a tunnel client listens on.
7. Driving the game without a person: a temporary Lua file in the deployed mod that acts through the real buttons'
   callbacks and writes its state with `getFileWriter` (under `~/Zomboid/Lua/`). `console.txt` can lag or stop
   while the game runs; the main menu fires `OnFETick`, not `OnTick`; a window in the background loads the world
   very slowly. A Workshop upload asks for a native confirmation that a person must click. In multiplayer the server
   decides damage: a test character that must survive gets `/godmode -true` (self; another player:
   `/godmodplayer <name> -true`) and waits for the server's "is now invincible" chat reply; the client's own
   `setGodMod` does not reach the server.
8. A real client launch goes through `ProjectZomboid64.exe` (JNI), not `java`: `smokeTest` does not show what differs
   there (e.g. `sun.java.command` is empty at premain).
9. Chinese text with missing characters is the game's CN bitmap fonts without a font patch, not the mod's strings.
10. Load-time gains: a join that connects right after the menu is up (the test driver's default) still waits on the
    launch's own file tasks, which a player who stays in the menu ~15 s never sees. Before crediting a gain to the
    join, repeat the on/off joins after a realistic menu stay (≥ 30 s). The asset wait's phases by task type come from
    Loading Progress's `<report>-files.csv` (`research/timeline.py` of `archive/2026-10/10-22-depth-map-speedup`); its
    pack-image backlog varies by run, so compare phases, not just the wait.
11. Server load without people: the game's `zombie.network.FakeClientManager` logs in headless players that walk a
    scenario and own and simulate zombies as game clients do. The server runs `-nosteam`; one client process per
    `-id` (each takes local port 17500+id). Scenario, start script, A/B harness and summarizer:
    `research/prototype/` of `archive/2026-10/10-24-server-tick-20hz`. Restore the save from one snapshot before
    each run, or the runs drift apart as the world fills.
12. A fix of the game's own client behavior: run the same scripted check once with the client's Java mods off
    (`start-client.ps1 -NoZombieBuddy`), which must show the bug, so a pass with the fix is not a check that never
    triggers it. Example: `falseRepairs` in `.trellis/tasks/archive/2026-10/10-27-server-reborn-sync-false-repairs/research/`.

If an in-game check was not done, say so in the task report.

Results go in the task's `research/in-game.md`, with the few lines that prove each result quoted there. Raw test logs
(consoles, driver output, per-run line files) are not committed.

---

## Code Style

Follow [`.editorconfig`](../../../.editorconfig). Bytecode level: see `PzJvm.kt`.
New code is Kotlin; Java only in `zabric`, `zabric-processor`, `zb-skip-online-checks`, the samples, and where
[Kotlin Runtime](./kotlin-runtime.md) requires it. Copied third-party code keeps its language, license header and
source commit, lives in a subpackage of the feature (all mod jars share one class loader), and its license text goes
into the jar's `META-INF/licenses/` (`tunnel`: `kcp/`, `fec/rs/`). Review it for bugs, not style. A port into another
language (an HLSL shader as GLSL) keeps the license header and source commit and lists its changes from the source
in its header (`faster-loading` `textures/bc7/BC7Encode.comp`, `META-INF/licenses/DirectXTex.txt`).

## GL Work on the Game's Render Thread

What a hook does in GL runs inside the game's frame, on a driver that runs GL commands on a thread of its own
(NVIDIA's threaded optimization). Measured in `faster-loading`'s BC7 encoder (`textures/bc7/Bc7Encoder`, numbers in
`archive/2026-10/10-22-gpu-bc7-encoder/verification.md` and `10-23-pr24-review-fixes/verification.md`):

- Any `glGet*` or `glGetError` waits for the driver thread to catch up; count them per call into a hot path. Once
  one remains per call, it waits for the driver's copy of what the call before uploaded, and fewer queries barely
  change the GL thread's time.
- Never update a uniform of a program whose dispatches may still be in flight: the update waits for them. Pass
  per-call values in a buffer.
- Adding a level to a texture with GPU writes pending into it waits for them: allocate the levels first, then fill
  them with `glCompressedTexSubImage2D` / `glTexSubImage2D`.
- Every level of a texture needs the same internal format or the texture is incomplete; a fix that changes the format
  takes every level or none.
- Prove GL paths outside the game first with a hidden-window harness on the game's LWJGL (`qualityCheck`), and time
  the GL thread and the GPU separately (`GL_TIME_ELAPSED`), at the sizes the game sends: its textures are padded to
  powers of two (`ImageData.widthHw`). Timed at the images' own sizes, a dispatch grid with rows of 65,535 groups hid
  twice the groups a 1024x1024 level needs (`Bc7Encoder.groups`).
- An encoder change that should not change the output is compared bit for bit (`qualityCheck -PqualityDump`).
- Shared memory per work group limits the groups a GPU runs at once: each compute entry declares only what it uses
  (`BC7Encode.comp`).

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
| Non-ASCII string literals in Lua source | The game does not read Lua source as UTF-8, so `"。"` never equals a translation's `。`; compare code points (`string.byte`), as `breakable` in `Dont39AllInOne_Common.lua` does, and keep text in translations |

## Release Checklist

- Version bumped in the shipped module's `build.gradle.kts` (`dont39-all-in-one`, `zb-kt`, `zb-skip-online-checks`);
  Zabric's, shared by `zabric-processor`, in `gradle/libs.versions.toml`.
- Zabric releases also go to Maven Central, only after the user confirms version and artifacts
  ([Zabric Hooks](./zabric-hooks.md#publishing-and-compatibility)).
- `mod.info` load order and `workshop.txt` updated for new target mods.
- Upload order when a release spans layers: Zabric, then ZabricKotlin, then All-in-One.
