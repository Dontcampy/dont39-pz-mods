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
agent) rewrites those two and rebuilds:

- Runtime services: Zabric's loader package
  ([`Loader.java`](../../../zabric/src/main/java/com/dont39/pz/zabric/loader/Loader.java)). The rest of Zabric and
  `zb-kt` call it; other mods go through Zabric's public API and entrypoints ([Access Widening](./access-widening.md)).
- Hooks: the code
  [`GlueProcessor.kt`](../../../zb-kt-utils-processor/src/main/kotlin/com/dont39/pz/zbktutils/processor/GlueProcessor.kt)
  generates. Feature code uses only the annotations and bindings in
  [`Glue.kt`](../../../zb-kt-utils/src/main/kotlin/com/dont39/pz/zbktutils/glue/Glue.kt).

`checkLoaderRefs` ([`CheckLoaderRefs.kt`](../../../buildSrc/src/main/kotlin/CheckLoaderRefs.kt), registered in
`pz-java.gradle.kts`) fails the build on any other reference. Do not widen its exclusions; extend the seam instead.

No runtime loader detection or fallback: one loader per build.

### Switch Checklist

What is not behind the seam and needs rework when switching loaders:

| Touchpoint | Where |
|------------|-------|
| Entry points by convention (`PreMain.premain`, `Main.main` in `javaPkgName`) | `Main` / `PreMain` in `zabric`; `Main` in `zb-kt` |
| The game's main classes the preLaunch hook targets | `entrypoint/PreLaunchHook.java` in `zabric` |
| Lua functions exposed from exactly `javaPkgName` | `zabric/.../RestartNotice.java`; the Lua bridge `GlueProcessor.kt` generates |
| `mod.info` fields (`require=\ZombieBuddy`, `loadModAfter`, `ZBVersionMin`, `javaJarFile`, `javaPkgName`; `javaPreload` in Zabric's) | every `mod/42/mod.info` |
| Manifest `ZB-Preload` | `tasks.jar` in `zabric/build.gradle.kts` |
| Build plugin, signing, `ZombieBuddy.jar` on the compile classpath | [`pz-java.gradle.kts`](../../../buildSrc/src/main/kotlin/pz-java.gradle.kts) |
| The loader package `checkLoaderRefs` looks for | `CheckLoaderRefs.kt` |
| Smoke launch and the loader's log lines the tests expect | `PzSmoke.kt`; `smokeTest` in `dont39-all-in-one/build.gradle.kts`; `serverSmokeTest` in `zb-kt/build.gradle.kts` |
| Texts naming ZombieBuddy | Workshop texts (`mod/workshop/workshop.txt`), log messages and comments outside Zabric, the ZombieBuddyKotlin name |

---

## Loading

- Mod declaration: `dont39-all-in-one/mod/42/mod.info`. Check its jar path against the ModdingGuide
  "Client/server JAR paths" section before adding a feature that must run on a dedicated server.
- Entry point: `dont39-all-in-one/src/main/kotlin/com/dont39/pz/allinone/AllInOneMain.kt`, a Zabric main entrypoint,
  calls each feature's `init()`; `init()` does not hook anything. No class named `Main` in `javaPkgName`: ZombieBuddy
  runs one as its own entry point.
- `loadModAfter` lists the mods a feature patches and the mods whose jars its classes link: ZombieBuddy loads each
  jar's classes and applies its patches one jar after another, and drops failed loads silently. Direct dependencies
  only; the chain covers the rest. Zabric needs no entry from mods that use only its entrypoints: it is preloaded on
  every launch that runs them.

---

## Patch Classes

- **Patch classes must be in exactly the `javaPkgName` package** (`com.dont39.pz.allinone`), not the feature package
  and not a subpackage; anything else is silently skipped. Enforced in `PatchEngine.collectPatches`; the ModdingGuide
  example's `.patches` subpackage would not load.
- **Kotlin features never write glue classes.** Hooks are annotated feature functions
  ([`Glue.kt`](../../../zb-kt-utils/src/main/kotlin/com/dont39/pz/zbktutils/glue/Glue.kt));
  the glue is generated by
  [`GlueProcessor.kt`](../../../zb-kt-utils-processor/src/main/kotlin/com/dont39/pz/zbktutils/processor/GlueProcessor.kt).
  When in doubt, read the generated code under `<module>/build/generated/ksp/main/`.
- Hook parameters use the bindings in `Glue.kt`, never ZombieBuddy's `Patch` annotations
  ([Loader Seam](#loader-seam)).
- Reference hooks: the `@Hook` functions in `LiftOffsets.kt` and `AimShift.kt`
  (`viewpoint-blood-fx-fix/src/main/kotlin/com/dont39/pz/viewpointbloodfxfix/`).
- ZombieBuddy inlines advice into the target mod's method, so a hook runs inside that mod's class.
- Already-loaded game classes can be hooked; ZombieBuddy retransforms them. Reference: `ImeWindow.kt`.
- A hook function is public and contains neither the `active()` gate nor a try/catch: the generated glue gates it on
  the module's facade and turns anything it throws into `disable` (`Glue.kt`, `GlueProcessor.kt`). `always = true` is
  only for an unpaired hook that must run while inactive and checks `active()` itself (`ImeWindow.sync`).
- A hook that changes state on enter restores it in a paired exit, which runs whenever that call's enter passed the
  gate, including when the target or the enter threw.
- Before using a `Patch.java` option the repo has not used, check its default there, in particular how matching
  behaves for overloaded methods. An option `Glue.kt` does not expose needs a processor change first.

---

## Lua Bridge

- Functions called from Lua are `@LuaGlobal` functions in the feature (`Glue.kt`); their names keep the mod-id prefix
  and are unique across features. Reference: `setForce` in `ViewpointBloodFxFix.kt` (feature package of
  `viewpoint-blood-fx-fix`). Other exposure options: ModdingGuide "Exposing Classes to Lua".
- Lua files live in the feature module's `src/main/lua/` and must tolerate the Java side or the game API being absent
  (see `viewpoint-blood-fx-fix/src/main/lua/client/Dont39AllInOne_ViewpointBloodFxFix.lua`).
- User-facing switches are mod options, not JVM flags.

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
  and `TargetMod.kt` ([ZombieBuddy Kotlin Utils](./zb-kt-utils.md)); reference: `ViewpointBloodFxFix.kt`.
- `Feature` checks the hook targets exist (`HookTargets.kt`); `resolve()` is only for what the compiler cannot check
  (`ImeContext.kt`).
- Every hook is gated on the facade's `active()` by the generated glue (see [Patch Classes](#patch-classes)).
- Keep a version allowlist for each target mod; a newer target version may change internals or fix the issue itself.
- A feature that patches only the vanilla game has no target mod: `problem()` gates on what it needs (e.g. the OS).
  Reference: `ImeKeyboardFix.kt`.

---

## Enter/Exit: Change Only for the Duration

When a feature changes another mod's state around a call (`LiftOffsets.kt`, `AimShift.kt`):

- Save originals and write them back exactly; do not recompute.
- Restore any leftover change before applying a new one, so changes never stack.
- Restore must not overwrite a value the target replaced in the meantime, and keeps restoring the rest after one
  element fails (`Saved.restore` in `LiftOffsets.kt`).
- Adjust only what the draw call reads; leave the target's simulation state alone.

## Threading and Hot Paths

- Render patches run on the render thread; follow the reference feature's (lock-free) approach to shared state.
- Read main-thread state once into a local on the render thread (`ImeWindow.kt`).
- Win32 calls on the game window run on the thread that calls `Display.processMessages()`, including their undo
  (`ImeWindow.kt`).
- Keep per-frame code cheap: reuse buffers instead of allocating per element, and build strings only when debug output
  is on (see `LiftOffsets.kt`). Know how often the patched method is called before adding work to it.
