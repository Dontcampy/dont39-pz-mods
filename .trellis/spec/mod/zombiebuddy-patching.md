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

## Loading

- Mod declaration: `dont39-all-in-one/mod/42/mod.info`. Check its jar path against the ModdingGuide
  "Client/server JAR paths" section before adding a feature that must run on a dedicated server.
- Entry point: `dont39-all-in-one/src/main/kotlin/com/dont39/pz/allinone/Main.kt` calls each feature's `init()`.
  `init()` reports readiness only; it does not hook anything.
- Mods a feature patches must load before this one (load order in `mod.info`).

---

## Patch Classes

- **Patch classes must be in exactly the `javaPkgName` package** (`com.dont39.pz.allinone`), not the feature package
  and not a subpackage; anything else is silently skipped. Enforced in `PatchEngine.collectPatches`; the ModdingGuide
  example's `.patches` subpackage would not load.
- **Kotlin features do not write patch classes.** They annotate feature functions with `@Hook.Enter` / `@Hook.Exit`
  ([`Glue.kt`](../../../zombiebuddy-kotlin-utils/src/main/kotlin/com/dont39/pz/zombiebuddykotlinutils/glue/Glue.kt)),
  and the KSP processor
  ([`GlueProcessor.kt`](../../../zombiebuddy-kotlin-utils-processor/src/main/kotlin/com/dont39/pz/zombiebuddykotlinutils/processor/GlueProcessor.kt),
  applied by `pz-feature`) generates `<Feature>Patches` in `javaPkgName`: one nested class per patched method with
  static advice that only calls the annotated function. Its rules (receiver, visibility, parameter bindings, return
  types, one enter/exit per target) are compile errors; read the generated code in
  `<module>/build/generated/ksp/main/` when in doubt.
- Reference hooks: the `@Hook` functions in `LiftOffsets.kt` and `AimShift.kt`
  (`viewpoint-blood-fx-fix/src/main/kotlin/com/dont39/pz/viewpointbloodfxfix/`).
- The advice is inlined into the target mod's method, so a hook runs inside that mod's class, and it runs even when
  ZombieBuddyKotlin is not ready ([Kotlin Runtime](./kotlin-runtime.md#dependent-kotlin-modules)).
- Name target classes by string; they are not on the classpath.
- A hook function is feature code: it checks the facade's `active()` and owns its error handling (see Gating).
- Any hook that changes state on enter must restore it on exit, including when the target throws. `@Hook.Exit` does
  that by default; do not narrow `onThrowable` for a restore.
- Before using an annotation option the repo has not used, check its default in `Patch.java`. Note in particular how
  matching behaves for overloaded methods. Options `Glue.kt` does not expose need a processor change first.

---

## Lua Bridge

- Functions called from Lua are annotated `@LuaGlobal(name)` in a Kotlin feature (`Glue.kt`); the processor generates
  the `<Feature>Lua` bridge in exactly `com.dont39.pz.allinone` (same package rule). It enforces the mod-id prefix and
  unique names within the feature; `checkGlue` in `dont39-all-in-one/build.gradle.kts` checks across features.
  Reference: `setForce` in `ViewpointBloodFxFix.kt` (feature package of `viewpoint-blood-fx-fix`).
  Other exposure options: ModdingGuide "Exposing Classes to Lua".
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
- Changes to vanilla objects for the duration of the call follow [Enter/Exit](#enterexit-change-only-for-the-duration).
  If vanilla throws, the restore runs at the next opportunity (next call, or an event right after).
- Check that what vanilla built is what the feature expects before adding to it; if not, leave it and warn once.
- Per-frame hooks go on the specific instance, not the vanilla class, and stay cheap.
- The game's `rawget` falls back to the metatable (for ISUI objects, the class), so it cannot tell an instance field
  from a class method.

---

## Reaching Other Mods

- Resolve other mods' classes, fields, and methods once, in the helper object's `init` block, through `Resolver`
  ([ZombieBuddy Kotlin Utils](./zombiebuddy-kotlin-utils.md)), which records failure instead of throwing. See
  `LiftOffsets.kt`, `AimShift.kt`, `Relief.kt` (feature package of `viewpoint-blood-fx-fix`).
- Use method handles for calls on the per-frame path (`Relief.kt`). A Kotlin `invokeExact` call site takes its
  descriptor from the argument types and the cast on the result; check it with `javap -c` against the `asType`
  signature (a mismatch only fails at runtime).

---

## Gating

- Every feature decides on first use whether it runs: target mod loaded, required API present, target version known
  (or forced via a mod option), internals resolved. The decision is cached; an option change may re-open it, a runtime
  failure may not. `active()` / `setForce()` / `disable()` come from `Feature` and the version check from
  `TargetMod.kt` ([ZombieBuddy Kotlin Utils](./zombiebuddy-kotlin-utils.md)); the feature supplies `problem()`
  (`ViewpointBloodFxFix.kt`).
- Every patch entry point checks the facade's `active()` first.
- Keep a version allowlist for each target mod; a newer target version may change internals or fix the issue itself.
- A part that can work independently degrades on its own instead of disabling the whole feature (`AimShift.kt`).

---

## Enter/Exit: Change Only for the Duration

When a feature changes another mod's state around a call (`LiftOffsets.kt`, `AimShift.kt`):

- Save originals and write them back exactly; do not recompute.
- Restore any leftover change before applying a new one, so changes never stack.
- Restore must not throw and must not overwrite a value the target replaced in the meantime.
- Adjust only what the draw call reads; leave the target's simulation state alone.

## Threading and Hot Paths

- Render patches run on the render thread; follow the reference feature's (lock-free) approach to shared state.
- Keep per-frame code cheap: reuse buffers instead of allocating per element, and build strings only when debug output
  is on (see `LiftOffsets.kt`). Know how often the patched method is called before adding work to it.
- In Kotlin: read nullable resolved values into locals once per call and use indexed `for (i in 0 until n)` loops; no
  lambdas, iterators or boxing. Confirm with `javap -c` (no `kotlin/ranges`, `Iterator` or `valueOf` outside debug).
