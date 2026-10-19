# ZombieBuddy Patching

> How features hook game and mod code, and how they talk to mods they cannot compile against.

---

## ZombieBuddy Reference

ZombieBuddy is the git submodule `external-repos/ZombieBuddy`. Read its docs before using a ZombieBuddy feature this
repo has not used yet:

| Topic | Doc |
|-------|-----|
| `mod.info` fields, client/server jar paths, `Main`, preload, patches, Lua exposure, testing | [ModdingGuide.md](../../../external-repos/ZombieBuddy/doc/ModdingGuide.md) |
| Launch parameters (patch logging verbosity, policy, ...) | [CommandLine.md](../../../external-repos/ZombieBuddy/doc/CommandLine.md) |
| Installation, Java mod approval | [Installation.md](../../../external-repos/ZombieBuddy/doc/Installation.md) |
| Lua-side API | [LuaAPI.md](../../../external-repos/ZombieBuddy/doc/LuaAPI.md) |
| In-game inspection helpers | [DevDebugFunctions.md](../../../external-repos/ZombieBuddy/doc/DevDebugFunctions.md) |
| Signing | [ModSigning.md](../../../external-repos/ZombieBuddy/doc/ModSigning.md) |

The docs do not cover every annotation option or loader rule. The source is authoritative:

- [`Patch.java`](../../../external-repos/ZombieBuddy/java/src/main/java/me/zed_0xff/zombie_buddy/Patch.java) — annotations and their defaults
- [`PatchEngine.java`](../../../external-repos/ZombieBuddy/java/src/main/java/me/zed_0xff/zombie_buddy/PatchEngine.java) — how patch classes are found and applied
- [`Loader.java`](../../../external-repos/ZombieBuddy/java/src/main/java/me/zed_0xff/zombie_buddy/Loader.java) — how mods and `Main` are loaded

When the docs and the source disagree, trust the source (see the package rule below).

---

## Loader Seam

Only two places use ZombieBuddy's API (`me.zed_0xff.*`), so that moving to another loader (a fork, or Zabric as its own
agent) rewrites those two and rebuilds every mod on Zabric:

- Runtime services: Zabric's loader package
  ([`Loader.java`](../../../zabric/src/main/java/com/pzlauncher/zabric/loader/Loader.java)). The rest of Zabric and
  `zb-kt` call it; other mods go through Zabric's public API and entrypoints ([Access Widening](./access-widening.md)).
- Hooks: the glue `zabric-processor`'s generator writes, through javac or KSP ([Zabric Hooks](./zabric-hooks.md)).
  Mod code uses only Zabric's hook API
  ([`com.pzlauncher.zabric.hook`](../../../zabric/src/main/java/com/pzlauncher/zabric/hook/package-info.java)).

`checkLoaderRefs` ([`CheckLoaderRefs.kt`](../../../buildSrc/src/main/kotlin/check/CheckLoaderRefs.kt), registered in
`pz-java.gradle.kts`) fails the build on any other reference. Do not widen its exclusions; extend the seam instead.

The one exception is a mod whose target *is* the loader: `zb-skip-online-checks`
([README](../../../zb-skip-online-checks/README.md)) advises ZombieBuddy's own methods and excludes its top-level
package in its build script. It also has a `PreMain`, for ZombieBuddy's `patches_jar` option: that runs before Zabric
is loaded, so the package uses no Zabric or game types and logs nothing (stdout at that point never reaches
`console.txt`); its Zabric main entrypoint reports for both ways. Its Zabric entrypoints live in a subpackage, which
stays checked.

No runtime loader detection or fallback: one loader per build.

### Switch Checklist

What is not behind the seam and needs rework when switching loaders:

| Touchpoint | Where |
|------------|-------|
| Entry points by convention (`PreMain.premain`, `Main.main` in `javaPkgName`) | `Main` / `PreMain` in `zabric`; `Main` in `zb-kt` |
| The game's main classes the preLaunch hook targets | `entrypoint/PreLaunchHook.java` in `zabric` |
| Lua functions exposed from exactly `javaPkgName` | `zabric/.../RestartNotice.java`; the Lua bridge `LuaBridge.kt` generates |
| The loader's names, annotation shape and method matching in generated glue | `Names.java`, `glue.ftl`, `LoaderMatcher.java` in `zabric-processor`; reference glue `WeaveGlue.java` and `WeaveTest` in `zabric` |
| The weave check relies on the loader registering its patch transformers before Zabric's mod-list callback | `hook/impl/WeaveCheck.java` in `zabric` |
| Rejection of hand-written loader patches (`@Patch`, `@UnsafeLoaderPatch`) | both processors, `Messages.java`; the Workshop author contract |
| `mod.info` fields (`require=\ZombieBuddy`, `loadModAfter`, `ZBVersionMin`, `javaJarFile`, `javaPkgName`; `javaPreload` in Zabric's) | every `mod/42/mod.info` |
| Manifest `ZB-Preload` | `tasks.jar` in `zabric/build.gradle.kts` |
| Build plugin, signing, `ZombieBuddy.jar` on the compile classpath | [`pz-java.gradle.kts`](../../../buildSrc/src/main/kotlin/pz-java.gradle.kts); test classpaths in `pz-loader-test.gradle.kts` and `zabric-processor`; the release workflow's `.github/actions/pz-jars` |
| The loader package `checkLoaderRefs` looks for | `CheckLoaderRefs.kt` |
| A mod whose target is the loader (rewrite or drop it) | `zb-skip-online-checks` |
| Smoke launch and the loader's log lines the tests expect | `PzSmoke.kt`, `GameSmokeTest.kt` (`smokeTest` of `dont39-all-in-one` and `loading-progress`), `ServerSmokeTest.kt` (`serverSmokeTest` of `zb-kt`) in `buildSrc` |
| Texts naming ZombieBuddy | Workshop texts (`mod/workshop/workshop.txt`), log messages and comments outside Zabric |

---

## Loading

- Mod declaration: `dont39-all-in-one/mod/42/mod.info`. The jar is under `media/java/` with no `client/` or
  `server/`, so it loads on clients and dedicated servers (ModdingGuide "Client/server JAR paths";
  `JavaModInfo.validateAndCreate` has the check, though ZombieBuddy 2.3.4 on the user's Linux server loaded a
  `client/` jar anyway: do not rely on either behavior). A client-only feature therefore goes idle on a server in
  `problem()` (`ImeKeyboardFix.kt`). A client-only mod of its own may put its jar under `media/java/client/`
  (`pzMod.jarFolder`) and still checks `GameServer.server` (`Loading.java` in `loading-progress`).
- Entry point: `dont39-all-in-one/src/main/kotlin/com/dont39/pz/allinone/AllInOneMain.kt`, a Zabric main entrypoint,
  calls each feature's `init()` through the generated `initFeatures()` (`GenerateFeatureInit` in `FeatureInfo.kt`);
  `init()` does not hook anything. No class named `Main` in `javaPkgName`: ZombieBuddy runs one as its own entry point.
- `loadModAfter` lists the mods a feature hooks and the mods whose jars its classes link: ZombieBuddy loads each
  jar's classes and applies its hooks one jar after another, and drops failed loads silently. Direct dependencies
  only; the chain covers the rest. Zabric needs no entry from mods that use only its entrypoints: it is preloaded on
  every launch that runs them.

---

## Patch Classes

- **Generated patch classes must be in exactly the `javaPkgName` package** (`com.dont39.pz.allinone`), not the
  feature package and not a subpackage; anything else is silently skipped. Enforced in `PatchEngine.collectPatches`;
  the ModdingGuide example's `.patches` subpackage would not load. `pz-feature.gradle.kts` reads the package from the
  bundling mod's `mod.info` (`pzGlue`).
- **Features never write patch classes.** Hooks are Zabric hook objects ([Zabric Hooks](./zabric-hooks.md)); the glue
  is generated. When in doubt, read it under `<module>/build/generated/ksp/main/`.
- Handlers bind the target only through Zabric's annotations, never ZombieBuddy's `Patch` ([Loader Seam](#loader-seam)).
- Reference hooks: `ImeCaret.kt` and `ImeWindow.kt` (`ime-keyboard-fix/src/main/kotlin/com/dont39/pz/imekeyboardfix/`).
- ZombieBuddy inlines advice into the target mod's method, so a hook runs inside that mod's class.
- Already-loaded game classes can be hooked; ZombieBuddy retransforms them. Reference: `ImeWindow.kt`.
- A handler is public and contains neither the `active()` gate nor a try/catch: the generated glue gates it on the
  module's facade (the default gate of a Kotlin hook object) and turns anything it throws into `disable`.
  `always = true` is only for a handler that must run while inactive and checks `active()` itself (`ImeWindow.sync`).
- A change made at `HEAD` is restored by a `FINALLY` handler of the same hook class, which runs whenever that call's
  `HEAD` passed the gate, including when the target or a handler threw.
- A capability the hook API lacks is added to Zabric's API and both processors first, never worked around with a
  ZombieBuddy option.

---

## Lua Bridge

- Functions called from Lua are `@LuaGlobal` functions in the feature (`Glue.kt`); their names keep the mod-id prefix
  and are unique across features. A facade exposes its mod option by overriding `setForce` (`Feature.kt`) with
  `@LuaGlobal`. Other exposure options: ModdingGuide "Exposing Classes to Lua".
- Lua files live in the feature module's `src/main/lua/` and must tolerate the Java side or the game API being absent:
  check a global exists before calling it.
- Features do not depend on each other in Kotlin (facades are `internal`). One feature uses another's data through
  that feature's Lua global, read from its own Lua, and still works when the global is absent or returns nil:
  `Dont39AllInOne_NetworkStatus.lua` reads `Dont39AllInOne_getTunnelStatus`, whose keys `TunnelStatus.kt` lists. A
  table shared this way has its keys written down once, at the writer.
- User-facing switches are mod options, not JVM flags: `PZAPI.ModOptions` from the feature's Lua, id
  `Dont39AllInOne_<Name>` (`Dont39AllInOne_NetworkStatus.lua`).

---

## Server Configuration

For what a server owner sets. Reference: `tunnel` (`ServerStart.kt`, `TunnelServer.kt`, `src/main/media/sandbox-options.txt`).

- A sandbox option of the feature (`sandbox-options.txt`, option `Dont39AllInOne_<Name>.<Option>`), translated in
  `Sandbox.json`. No other config files or JVM flags.
- Read it on the server at a `RETURN` hook of `GameServer.startServer`: earlier (`OnGameBoot`) the save's sandbox
  values are not loaded yet. An option `getOptionByName` cannot find is a mismatch: throw.
- A dedicated server writes no `map_sand.bin`; it reads `<server>_SandboxVars.lua` on every start, so "edit the file and
  restart" is the documented way to change it (measured in task `10-17-tcp-connection`, `research/in-game-test.md`).
- Skip co-op host processes (`GameServer.coop`) unless the feature is meant for them.

---

## Wrapping Vanilla Lua UI

For a feature that changes the game's own Lua UI. Reference:
`mod-options-sidebar/src/main/lua/client/Dont39AllInOne_ModOptionsSidebar.lua`.

- Wrap the vanilla function (keep the original, call it from the replacement); never copy or replace its body.
- Call vanilla outside `pcall`, so its errors (and those of mods it calls) surface as without this mod. Everything the
  feature does before and after it runs inside `pcall`; an error disables the feature for the session.
- Changes to vanilla objects for the duration of the call follow [Enter/Exit](#enterexit-change-only-for-the-duration),
  and are undone even when vanilla throws.
- Check that what vanilla built is what the feature expects before adding to it; if not, it is an error.
- Report errors with the Lua stack where they were raised: the game's `pcall` returns it as the third value.
- Per-frame hooks go on the specific instance, not the vanilla class, and stay cheap.
- The game's `rawget` falls back to the metatable (for ISUI objects, the class), so it cannot tell an instance field
  from a class method.

---

## Reaching the Game and Other Mods

- Call them directly: [Access Widening](./access-widening.md).
- No work that can fail in an object initializer: hooks live on those objects, and a failed initializer makes every
  later call fail inside the target mod.

---

## Gating

- Every feature decides on first use whether it runs: target mods loaded, target version known (or forced via a mod
  option), access widening in effect if the feature uses it. The decision is cached; an option change may re-open it, a
  runtime failure may not. Internals that do not match are an error, not a reason to stay idle. Build it on `Feature`
  and `TargetMod.kt` ([ZabricKotlin Utils](./zb-kt-utils.md)).
- `Feature` checks its hook targets exist (`ZabricHooks.gateProblem`); `resolve()` is only for what the compiler cannot
  check (`ImeContext.kt`).
- Every hook is gated on the facade's `active()` by the generated glue (see [Patch Classes](#patch-classes)).
- Keep a version allowlist for each target mod; a newer target version may change internals or fix the issue itself.
- A feature that patches only the vanilla game has no target mod: `problem()` gates on what it needs (e.g. the OS).
  Reference: `ImeKeyboardFix.kt`.

---

## Enter/Exit: Change Only for the Duration

When a feature changes another mod's state around a call (a `HEAD` handler and a `FINALLY` handler that undoes it):

- Save originals and write them back exactly; do not recompute.
- Restore any leftover change before applying a new one, so changes never stack.
- Restore must not overwrite a value the target replaced in the meantime, and keeps restoring the rest after one
  element fails.
- Adjust only what the draw call reads; leave the target's simulation state alone.

## Threading and Hot Paths

- Render-thread patches share state with the main thread lock-free (`ImeCaret.rect`).
- Read main-thread state once into a local on the render thread (`ImeWindow.kt`).
- Win32 calls on the game window run on the thread that calls `Display.processMessages()`, including their undo
  (`ImeWindow.kt`).
- Keep per-frame code cheap: reuse buffers instead of allocating per element, and build strings only when debug output
  is on. Know how often the patched method is called before adding work to it.
