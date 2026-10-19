# Access Widening

> Feature code reaches game and target-mod members with ordinary compiled code. Non-public members are listed in the
> feature's `.classtweaker` and made public at build time (compile jars) and at game start (Zabric, for every
> installed mod).

---

## Where It Lives

| Part | Code |
|------|------|
| List format and parser | `zabric/src/classtweaker/java/.../classtweaker/ClassTweaker.java` |
| Target mod jars, widened compile jars, merged list | `buildSrc/src/main/kotlin/widening/PzTargets.kt`, `ClassTweakerTasks.kt`; `mergeClassTweakers` in `dont39-all-in-one/build.gradle.kts` |
| Game-start scan and widening, per-mod status | `zabric/.../widener/`: facade and per-mod status `AccessWidener.java`, scan `WidenerScan.java` / `ModFolders.java`, modifier changes `WidenerTransform.java` |
| ZombieBuddy access (Instrumentation, preload settings, mod records, mod-list hook) | `zabric/.../loader/Loader.java`, `PreloadConfig.java`, `LoadStatus.java`, `ModsLoadedHook.java` |
| Entrypoints, preload registration | `zabric/.../entrypoint/Lifecycle.java` (phases), `Entrypoints.java` (manifest, instantiation), `Preload.java`, `PreLaunchHook.java` |
| Helpers shared by Zabric's packages (`[Zabric]` log prefix, jar reading) | `zabric/.../internal/` |
| Hook runtime for other mods' glue | `zabric/.../hook/` ([Zabric Hooks](./zabric-hooks.md)) |
| Restart notice | `zabric/.../restart/RestartList.java`, `RestartNotice.java`, `src/main/lua/client/Zabric_RestartNotice.lua` |
| Feature side: gating on the widening state | `Widening.kt`, `Feature.kt` in `zb-kt-utils` |

Reference feature: `ime-keyboard-fix` (game fields).

---

## Rules for Features

- No reflection or method handles in feature code: reach members directly.
- Declare every target mod with `pzTargets` in the feature's build script.
- List each non-public field, class or constructor the feature uses in
  `src/main/resources/META-INF/dont39/<module>.classtweaker` (build input only); the facade passes `widened = true` to
  `Feature`. The aggregating mod ships the merged list in its own jar.
- Widen only fields, classes and constructors; other methods are not widened (visibility can change dispatch).
- Read a target mod's version by name (`TargetMod.kt`): a `static final` constant is inlined at build time.
- Target-mod types only in classes that load after the feature is active, never in the facade.

## Zabric

- Java and Lua only, no patches of its own: its package loads at premain, before the Kotlin runtime. Its hook runtime
  only serves the glue other mods generate.
- ZombieBuddy is used only in the loader package ([Loader Seam](./zombiebuddy-patching.md#loader-seam)); messages
  elsewhere name it through `Loader.name()`.
- Premain and preLaunch code touches no game class and logs nothing: the classes it widens are not loaded yet, and the
  game's log is not up. Outcomes are reported at MAIN; the hook runtime holds its lines until then
  (`HookRuntime.releaseLog`).
- Its hooks run inside code every mod depends on (the game's `main`, ZombieBuddy's `loadMods`): they never throw out,
  and a failure stops the whole lifecycle for the session instead of running part of it (`entrypoint/Lifecycle.java`).
- It applies the lists of every installed mod: premain cannot see the game's mod list (`ModFolders.java`).
- A problem is that mod's only and is never silenced (`AccessWidener.java`).
- The author contract is in `zabric/mod/workshop/workshop.txt`. Changing it needs a new list header version.
- After a ZombieBuddy update, recheck what `loader/` reads of ZombieBuddy's internals (`Loader.java`, `LoadStatus.java`)
  and the `loadMods` hook (`ModsLoadedHook.java`).

## Entrypoints and Preload Registration

- Startup code of a Zabric-based mod is an entrypoint, not a ZombieBuddy `PreMain` or `Main` (author contract:
  `zabric/mod/workshop/workshop.txt`; phases: `entrypoint/Lifecycle.java`).
- Order within a phase is undefined: no entrypoint may assume another mod's entrypoint of that phase has run.
  Class-path dependencies are a separate matter ([Loading](./zombiebuddy-patching.md#loading)).
- Mods without entrypoints are left alone: mods that use only ZombieBuddy load as without Zabric.
- The hooks of a mod Zabric keeps preloaded open at preLaunch (`HookRuntime.preLaunch`), so their targets are checked
  from class bytes as each loads (`WeaveCheck`, `TargetCheck`), never by loading them. From the first pass they follow
  the mod list, unless the manifest's `Zabric-Hooks: launch` keeps them open on every pass: for client-side UI only
  (`Lifecycle`, author contract in `zabric/mod/workshop/workshop.txt`).
- Registration follows only code ZombieBuddy approved and loaded (`entrypoint/Preload.java`, `loader/PreloadConfig.java`).
- Approval gap, accepted with signing off: ZombieBuddy runs a registered unsigned jar at premain without checking that
  its current hash was approved. The Workshop texts say so.
