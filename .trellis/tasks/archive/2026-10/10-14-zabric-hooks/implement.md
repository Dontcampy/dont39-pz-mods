# Implementation plan

Build gate: `JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew build assembleMod` (JDK 25; shell default is 21).
One commit per step group (A–F) so each can be reverted alone.

## A. License and rename (D10, D6)

1. Add `LICENSE` (MIT) at the repo root; mention it in `README.md`.
2. Move `zabric/src/**/com/dont39/pz/zabric` to `com/pzlauncher/zabric`; update every reference (git grep
   `com.dont39.pz.zabric|com/dont39/pz/zabric`, outside `external-repos/` and archived tasks): `zabric/mod/42/mod.info`
   `javaPkgName`, `zabric/build.gradle.kts` exclusions, `ClassTweakerTasks.kt`, `AllInOneMain.kt`, `KotlinSwap.java`,
   `Widening.kt`, `zabric/mod/workshop/workshop.txt`, `.trellis/spec/mod/*.md`.
3. Build gate + `:dont39-all-in-one:smokeTest` + `:zb-kt:serverSmokeTest`. Preload entries are keyed by mod id and
   ZombieBuddy reads `javaPkgName` from `mod.info` at each launch (`external-repos/ZombieBuddy/.../Loader.java:410-421`);
   the changed jar may still need one re-registration launch, as after any Zabric change, before the restart notice
   reads `not needed`.

## B. Zabric runtime and annotations (D4, D8)

1. `com.pzlauncher.zabric.hook`: annotations (`Hooks`, `Inject`, `At`, `ModifyReturnValue`, `ModifyVariable`,
   `Share`, `Self`, `Thrown`, `UnsafeLoaderPatch`), `CallbackInfo`/`CallbackInfoReturnable`, `LocalRef`, `HookGate`, `ModHooks`,
   `ZabricHooks` (mod handle, default gate, target `problem`). Java only, no Kotlin (`checkBootstrapClasses`).
2. `Lifecycle`: record the processed pass; open/close `ModHooks`; check default-gate targets per mod.
3. Weave test (`zabric/src/test`): hand-written glue in the generated shape applied through ZombieBuddy's
   `PatchTransformer` + ByteBuddy to a test target; covers every injector, `@Share`, FINALLY, gate closed, failure.
   Needs `ZombieBuddy.jar` on the test classpath.

## C. Generator and javac front end (D2, D3, D4, D9 compile)

1. New module `zabric-processor` (plain Java 17, no game or ZombieBuddy dependency; add to `settings.gradle.kts`):
   neutral model, validator, Java source + listing writer (shape: `design.md` §3).
2. javac `Processor` (service file), options `zabric.modId`, `zabric.javaPkgName`; resolves targets with
   `javax.lang.model`; rejects `@Patch` without `@UnsafeLoaderPatch` and every unsupported construct with a message
   naming the handler or class.
3. Unit tests per rule and error message (in-process `javax.tools` compilation).

## D. Kotlin layer (D8 Kotlin side)

1. `zb-kt-utils-processor`: depend on the generator library; read Zabric annotations via KSP; facade as default gate;
   keep Kotlin checks and the Lua bridge; remove ZombieBuddy patch generation and `Hook.*` handling; new option
   `zabric.modId` from `PzGlue` (`pz-feature.gradle.kts`, `dont39-all-in-one/build.gradle.kts`); the same
   `@Patch`/`@UnsafeLoaderPatch` rule as javac.
2. `zb-kt-utils`: `Glue.kt` keeps `LuaGlobal`; `Feature : HookGate`; `resolveOnce` calls `ZabricHooks.problem` for the
   module's hook classes; delete `HookTargets.kt`.
3. Migrate `ImeCaret.publish` and `ImeWindow.sync` (`design.md` §5).
4. `checkGlue` / `smokeTest`: read `META-INF/zabric/hooks/` listings; expect no `hooks stopped` line.
5. Build gate + `smokeTest` + `serverSmokeTest`; inspect `ime-keyboard-fix/build/generated/ksp/main/`.

## E. Publishing (D5, D7)

1. `com.vanniktech.maven.publish` for `zabric` and `zabric-processor` (version in the catalog), POM with MIT,
   signing via Gradle properties; version bump of Zabric.
2. `publishToMavenLocal`; `samples/zabric-java-hooks/` builds against it alone and its checks pass (`design.md` §8).
3. **User gate:** user creates the Central Portal account, verifies `com.pzlauncher` (DNS TXT on `pzlauncher.com`),
   publishes a GPG key, puts credentials in `~/.gradle/gradle.properties`.
4. **User gate:** confirm version and artifact list, then `publishAndReleaseToMavenCentral`. Permanent; never rerun
   for the same version.
5. Switch the sample to `mavenCentral()` only (no `mavenLocal()`) and rebuild it.

## F. Docs and specs

1. `zabric/mod/workshop/workshop.txt` "For mod authors": hooks, Maven coordinates, processor options, ZombieBuddy jar
   on `compileOnly`, the `@Patch` rule and the `@UnsafeLoaderPatch` disclaimer, gate contract, nesting by load order, restart for new mods.
2. Specs: new `.trellis/spec/mod/zabric-hooks.md` (rules + links); update `zombiebuddy-patching.md` (Patch Classes,
   Loader Seam, switch checklist), `zb-kt-utils.md` (listing, gate), `access-widening.md` (Zabric section, package),
   `index.md` (published build tooling, guide row), `quality-guidelines.md` if it names the old hook contract.

## Checks before reporting done

- Build gate, `smokeTest`, `serverSmokeTest`, processor unit tests, weave test, sample build against Maven Central.
- `git grep -n "com.dont39.pz.zabric"` finds nothing outside `external-repos/` and archived tasks.
- `git grep -n "Hook.Enter\|Hook.Exit"` finds nothing.
- In-game check of the IME caret and window by the user, or reported as not done.

## G. Weave verification (D12)

1. Listing `hook` line carries its patch class (generator, `HookListing`, reference listings, golden test, checkGlue).
2. Observer + pass-time retransform check + load-time check in `hook/impl`; report through `ModState.failed`.
3. Weave tests (AC9): unverifiable patch on a loaded target, ByteBuddy-rejected advice, correct weave not reported,
   late-loaded target.
4. Specs (`zabric-hooks.md`, switch checklist), Workshop line if authors see a new log line.
5. Gates + `smokeTest` (approved earlier).
