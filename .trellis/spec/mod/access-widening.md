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
| ZombieBuddy access (Instrumentation, preload settings, mod records, mod-list hook, `mod.info` rules) | `zabric/.../loader/Loader.java`, `PreloadConfig.java`, `LoadStatus.java`, `ModsLoadedHook.java`, `ModInfo.java` |
| Entrypoints, preload registration | `zabric/.../entrypoint/Lifecycle.java` (phases), `Entrypoints.java` (manifest, instantiation), `Preload.java`, `PreLaunchHook.java` |
| Helpers shared by Zabric's packages (`[Zabric]` log prefix, jar reading) | `zabric/.../internal/` |
| Hook runtime for other mods' glue | `zabric/.../hook/` ([Zabric Hooks](./zabric-hooks.md)) |
| Restart notice | `zabric/.../restart/RestartList.java`, `RestartNotice.java`, `src/main/lua/client/Zabric_RestartNotice.lua` |
| Game restart with this launch's command line (Windows clients) | `zabric/.../relaunch/Relaunch.java`; helper and failure record `relaunch/impl/`; `CreateProcessW` / `MessageBoxW` in `src/ffm/java/.../internal/WindowsProcess.java` |
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
- A class loaded before Zabric's widener runs keeps its access ("already loaded before Zabric ran"), although the
  compile jars widen it. `zombie.ZomboidFileSystem` is one (ZombieBuddy patches it at premain); reach such a class
  through public members, as `Swap.kt` in `mod-sync` does. Only `smokeTest` catches this, not the build.
- Read a target mod's version by name (`TargetMod.kt`): a `static final` constant is inlined at build time.
- Target-mod types only in classes that load after the feature is active, never in the facade.

## Zabric

- Java and Lua only, no patches of its own: its package loads at premain, before the Kotlin runtime. Its hook runtime
  only serves the glue other mods generate. Its own advice on game code is limited to the preLaunch hook on `main`
  (`entrypoint/PreLaunchHook.java`) and, on a launch with a one-launch folder override, the installed items' folder
  list (`mods/impl/ItemFoldersHook.java`): a mod's hooks open too late for the startup pass, and a Kotlin mod cannot
  run code before it.
- Retransforming an already-loaded game class from premain describes it from its bytes (`POOL_ONLY`, so nothing loads)
  and must ignore every other class by name only: the default ignore matcher resolves each loaded class, and a runtime
  proxy has no class file (`ItemFoldersHook.java`; only a launch with a request showed it).
- ZombieBuddy is used only in the loader package ([Loader Seam](./zombiebuddy-patching.md#loader-seam)); messages
  elsewhere name it through `Loader.name()`.
- Premain and preLaunch code touches no game class and logs nothing: the classes it widens are not loaded yet, and the
  game's log is not up. Outcomes are reported at MAIN.
- Its hooks run inside code every mod depends on (the game's `main`, ZombieBuddy's `loadMods`): they never throw out,
  and a failure stops the whole lifecycle for the session instead of running part of it (`entrypoint/Lifecycle.java`).
- It applies the lists of every installed mod: premain cannot see the game's mod list (`ModFolders.java`). It also
  reads this launch's override folders and the folders mods registered through `WidenRoots`.
- Files it reads at premain live under `<cache>/Zabric/`; a request is per side, since a local dedicated server shares
  the cache folder. Premain cannot know the side, so the side is decided at preLaunch, which takes (deletes) its own
  side's file before applying it (`mods/impl/LaunchOverrides.java`).
- Premain reads the game's arguments from the process (`internal/GameArgs.java`), never `sun.java.command`: the
  game's native launcher starts the JVM through JNI, which leaves that property unset.
- FFM code (Windows calls) lives in the `ffm` source set, compiled at release 22 and packed into the jar; the rest
  stays at Zabric's release, since raising it breaks Gradle variant resolution for authors' release-17 builds
  (`zabric/build.gradle.kts`).
- A restart hands the game's own command line to a helper as the raw `GetCommandLineW` string and starts it with
  `CreateProcessW`: re-quoting split arguments (`ProcessBuilder`) does not round-trip every line. A caller drops
  arguments by index into `Relaunch.arguments()`, cut by span (`GameArgs.spansWindows`); `JoinArgs.kt` in `mod-sync`
  is the reference caller.
- The helper runs on the game's `javaw` from Zabric's jar, without `_JAVA_OPTIONS` / `JAVA_TOOL_OPTIONS` /
  `JDK_JAVA_OPTIONS` (they would load ZombieBuddy and the game's heap into it), uses no game class, and loads every
  class it needs before it answers: a Workshop update can replace the jar once the game quit
  (`relaunch/impl/Waiter.java`).
- Nothing after the game quit fails silently: the helper writes `<cache>/Zabric/relaunch.log` and a marker, shows a
  message box, and the next main menu shows the marker once, above the middle where a mod's own dialog for the same
  restart would cover it (`relaunch/impl/RelaunchFailure.java`, `Zabric_RestartNotice.lua`).
- Windows clients only: Steam's Linux runtime container ends the other processes in it when the game exits, the
  helper included; `Relaunch.unavailable()` names the reason, and callers keep their manual path.
- A problem is that mod's only and is never silenced (`AccessWidener.java`).
- The author contract is in `zabric/README.md` (the Workshop page only links it). Changing it needs a new list header version.
- After a ZombieBuddy update, recheck what `loader/` reads of ZombieBuddy's internals (`Loader.java`, `LoadStatus.java`),
  the `loadMods` hook (`ModsLoadedHook.java`), and its `mod.info` and jar rules, which `ModInfo.java` re-implements
  (ZombieBuddy's parser is private; `LaunchOverride`'s refusal and `JavaMods.inFolder` use it).

## Entrypoints and Preload Registration

- Startup code of a Zabric-based mod is an entrypoint, not a ZombieBuddy `PreMain` or `Main` (author contract:
  `zabric/README.md`; phases: `entrypoint/Lifecycle.java`).
- Order within a phase is undefined: no entrypoint may assume another mod's entrypoint of that phase has run.
  Class-path dependencies are a separate matter ([Loading](./zombiebuddy-patching.md#loading)).
- Mods without entrypoints are left alone: mods that use only ZombieBuddy load as without Zabric.
- Registration follows only code ZombieBuddy approved and loaded (`entrypoint/Preload.java`, `loader/PreloadConfig.java`).
- Approval gap, accepted with signing off: ZombieBuddy runs a registered unsigned jar at premain without checking that
  its current hash was approved. `zabric/README.md` says so.
