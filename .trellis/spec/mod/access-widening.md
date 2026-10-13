# Access Widening

> Feature code reaches game and target-mod members with ordinary compiled code. Non-public members are listed in the
> feature's `.classtweaker` and made public at build time (compile jars) and at game start (Zabric, for every
> installed mod).

---

## Where It Lives

| Part | Code |
|------|------|
| List format and parser | `zabric/src/classtweaker/java/.../classtweaker/ClassTweaker.java` |
| Target mod jars, widened compile jars, merged list | `buildSrc/src/main/kotlin/PzTargets.kt`, `ClassTweakerTasks.kt`; `mergeClassTweakers` in `dont39-all-in-one/build.gradle.kts` |
| Game-start scan and widening, per-mod status | `zabric/.../widener/AccessWidener.java`, `ModFolders.java` |
| ZombieBuddy access (Instrumentation, preload settings, mod records, mod-list hook) | `zabric/.../loader/Loader.java`, `PreloadConfig.java`, `LoadStatus.java`, `ModsLoadedHook.java` |
| Entrypoints, preload registration | `zabric/.../entrypoint/Lifecycle.java`, `Preload.java`, `PreLaunchHook.java` |
| Restart notice | `zabric/.../restart/RestartList.java`, `RestartNotice.java`, `src/main/lua/client/Zabric_RestartNotice.lua` |
| Feature side: gating on the widening state | `Widening.kt`, `Feature.kt` in `zb-kt-utils` |

Reference features: `viewpoint-blood-fx-fix` (target mods, nested classes, a constructor) and `ime-keyboard-fix`
(game fields).

---

## Rules for Features

- No reflection or method handles in feature code: reach members directly.
- Declare every target mod with `pzTargets` in the feature's build script.
- List each non-public field, class or constructor the feature uses in
  `src/main/resources/META-INF/dont39/<module>.classtweaker` (build input only); the facade passes `widened = true` to
  `Feature`. The aggregating mod ships the merged list in its own jar.
- Widen only fields, classes and constructors; other methods are not widened (visibility can change dispatch).
- Read a target mod's version by name (`TargetMod.kt`): a `static final` constant is inlined at build time.
- Target-mod types only in classes that load after the feature is active, never in the facade
  (`LiftOffsets.debug()`).

## Zabric

- Java and Lua only, no patches: its package loads at premain, before the Kotlin runtime.
- ZombieBuddy is used only in the loader package ([Loader Seam](./zombiebuddy-patching.md#loader-seam)); messages
  elsewhere name it through `Loader.name()`.
- Premain and preLaunch code touches no game class and logs nothing: the classes it widens are not loaded yet, and the
  game's log is not up. Outcomes are reported at MAIN.
- Its hooks run inside the game's `main` and ZombieBuddy's `loadMods`, so they never throw out. A hook that cannot be
  installed, or a preLaunch that did not run, stops every entrypoint and registration for the session with an error
  naming the versions. A main pass in which ZombieBuddy did not load Zabric itself (denied) stops them with a warning
  (`Lifecycle.java`).
- It applies the lists of every installed mod: premain cannot see the game's mod list (`ModFolders.java`).
- A problem is that mod's only and is never silenced (`AccessWidener.java`).
- The author contract is in `zabric/mod/workshop/workshop.txt`. Changing it needs a new list header version.
- After a ZombieBuddy update, recheck what `loader/` reads of ZombieBuddy's internals (`Loader.java`, `LoadStatus.java`)
  and the `loadMods` hook (`ModsLoadedHook.java`).

## Entrypoints and Preload Registration

- A mod names its entrypoint classes in its jar manifest, `Zabric-PreLaunch` and `Zabric-Main` (author contract:
  `zabric/mod/workshop/workshop.txt`; phases: `entrypoint/Lifecycle.java`). Startup code of a Zabric-based mod is an
  entrypoint, not a ZombieBuddy `PreMain` or `Main`.
- Order within a phase is undefined: no entrypoint may assume that another mod's entrypoint of that phase has run.
  Entrypoints ask for no load order among Zabric-based mods. Patch code is not an entrypoint: ZombieBuddy applies a
  mod's patches while it loads the jars one by one, so patch code that links another mod's classes needs that mod in
  `loadModAfter` ([Loading](./zombiebuddy-patching.md#loading)).
- The lifecycle acts only on jars with `Zabric-*` attributes; mods that use only ZombieBuddy load as without it.
- A mod with a preLaunch entrypoint is kept preloaded by Zabric at the main phase, under the id ZombieBuddy recorded for
  the jar, and only once ZombieBuddy approved and loaded it (`entrypoint/Preload.java`; ZombieBuddy's side
  `loader/PreloadConfig.java`).
- On a launch where Zabric was not preloaded no entrypoint runs; Zabric's `Main` registers itself and every preLaunch
  mod of the pass, so one restart covers all.
- Approval gap, accepted with signing off: ZombieBuddy runs a registered unsigned jar at premain without checking that
  its current hash was approved. The Workshop texts say so.
