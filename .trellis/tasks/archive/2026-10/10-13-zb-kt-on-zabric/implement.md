# Implement: zb-kt on Zabric

One task, ordered so the build passes after each step. Run Gradle with JDK 25:
`JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew ...`.

## Steps

1. **Contract rename** (`zabric/src/classtweaker/.../ClassTweaker.java`, feature lists, `ClassTweakerTasks.kt`):
   header `zabric-classtweaker v1`. Build passes; nothing else changes yet.
2. **Zabric runtime** (`zabric/`):
   - premain scan of mod folders and per-mod lists (design 3), per-mod `problem(modId, host)`, `report()` summary;
   - `Preload.register` generalized from `setup/Registration.java` (identical-copy rule, Zabric entry first);
   - `Instrumentation` accessor shared for ZombieBuddyKotlin;
   - restart list + `Zabric_restartMods()` + Lua dialog listing display names; server log line;
   - jar to `media/java/`, `mod/workshop/` (workshop.txt with the author contract; preview image from the user),
     `checkBootstrapClasses`-style check that no class references `kotlin/`.
3. **All-in-One packaging** (`dont39-all-in-one/build.gradle.kts`): merged list into its own jar as
   `META-INF/zabric/Dont39AllInOne.classtweaker`; stop injecting into Zabric's jar and stop deploying Zabric;
   smokeTest deploys Zabric's own item. Rollback point: steps 1–3 are a working client setup.
4. **ZombieBuddyKotlin on Zabric** (`zb-kt/`): `PreMain` swap, `Main` registers + self-check + report,
   `isReady()`, `ZB-Preload` manifest, `javaPreload=true`, `require=\ZombieBuddy,\Zabric`, `compileOnly(zabric)`;
   jar takes `zb-kt-utils` classes; `workshop.txt` rewritten (fixes the stale `isReady()` text).
5. **zb-kt-utils public runtime**: `HookTargets.kt` reads the shipped glue listing via CodeSource;
   `Widening.kt`/`Feature` use `AccessWidener.problem(modId, host)`; All-in-One drops `internalLibs`.
6. **Readiness gate** (`zb-kt-utils-processor/.../GlueProcessor.kt`): gate at the top of every generated advice and Lua
   function, return rules per design 5, processor error for non-null reference Lua returns; `Main.kt` first line.
   Check generated code under `*/build/generated/ksp/main/`.
7. **pz-feature parameters**: glue package / Lua prefix / mod id from the aggregating mod, All-in-One's values
   unchanged; listing resource renamed to `META-INF/zbkt/glue/`.
8. **Tests**: update client `smokeTest`; add `serverSmokeTest` (`pzServerDir` in `gradle.properties`,
   `-cachedir=<build dir>`, test server ini enabling `Zabric;ZombieBuddyKotlin`).
9. **Spec** (`.trellis/spec/mod/`): access-widening (public contract, scan, per-mod status, preload service and its
   approval gap), kotlin-runtime (premain swap, the one allowed readiness gate replaces "never check readiness"),
   zb-kt-utils (shipped in ZombieBuddyKotlin, public API), directory-structure (layout, Zabric own item),
   zombiebuddy-patching (generated gate), quality-guidelines (serverSmokeTest), index overview.
10. **Versions**: Zabric 0.2.0, ZombieBuddyKotlin 0.3.0, All-in-One 0.4.0; Workshop texts.

## Validation

- `./gradlew build assembleMod` (includes `check`: bootstrap-class checks, glue checks).
- Jar contents: `jar tf` on the three jars (list location, `zbktutils` only in ZombieBuddyKotlin, no list in Zabric).
- Client: remove the `Zabric`/`ZombieBuddyKotlin` entries from `~/.zombie_buddy/config.json` (back up first), run
  `smokeTest` (expects "run again"), run it again (passes). Then in a normal launch: the restart dialog lists Zabric and
  ZombieBuddyKotlin on the first launch and does not show on the second.
- Server: `serverSmokeTest` twice, as for the client; afterwards the client `smokeTest` still passes without a new
  registration (identical-copy rule).
- In game: IME and BloodFX behavior unchanged (AC of task `10-13-no-reflection`).

## Risky files

- `zabric/.../widener/AccessWidener.java`, `setup/Registration.java`: premain code; must not load game classes or
  throw into ZombieBuddy.
- `zb-kt/.../ZombieBuddyKotlin.java`: the swap.
- `~/.zombie_buddy/config.json`: user's real config; back up before tests that edit it.

## Not done by the implementation

Workshop uploads, required-item settings on the Workshop pages, preview images: the user does these by hand
(release order in design 9).
