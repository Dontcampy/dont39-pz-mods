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
- Entry point: `dont39-all-in-one/src/main/java/com/dont39/pz/allinone/Main.java` calls each feature's `init()`.
  `init()` reports readiness only; it does not hook anything.
- Mods a feature patches must load before this one (load order in `mod.info`).

---

## Patch Classes

- **Patch classes must be in exactly the `javaPkgName` package** (`com.dont39.pz.allinone`), not the feature package
  and not a subpackage; anything else is silently skipped. Enforced in `PatchEngine.collectPatches`; the ModdingGuide
  example's `.patches` subpackage would not load.
- Follow the shape of `viewpoint-blood-fx-fix/src/main/java/com/dont39/pz/allinone/ViewpointBloodFxFixPatches.java`:
  one outer class per feature, one nested class per patched method.
- Name target classes by string; they are not on the classpath.
- Patch methods only delegate to the feature package. No logic, state, or try/catch in the patches class.
- Any patch that changes state on enter must restore it on exit, including when the target throws (see how the
  reference patches declare their exit advice).
- Before using an annotation option the repo has not used, check its default in `Patch.java`. Note in particular how
  matching behaves for overloaded methods.

---

## Lua Bridge

- Java functions called from Lua go in a `<Feature>Lua` class, also in exactly `com.dont39.pz.allinone` (same package
  rule, see the comment in `viewpoint-blood-fx-fix/src/main/java/com/dont39/pz/allinone/ViewpointBloodFxFixLua.java`).
  It only delegates to the facade. Exposure options: ModdingGuide "Exposing Classes to Lua".
- Lua files live in the feature module's `src/main/lua/` and must tolerate the Java side or the game API being absent
  (see `viewpoint-blood-fx-fix/src/main/lua/client/Dont39AllInOne_ViewpointBloodFxFix.lua`).
- User-facing switches are mod options, not JVM flags.

---

## Reaching Other Mods

- Resolve other mods' classes, fields, and methods once, in a static initializer, and record failure instead of
  throwing. See the static blocks in `LiftOffsets.java`, `AimShift.java`, `Relief.java` (feature package of
  `viewpoint-blood-fx-fix`).
- Use method handles for calls on the per-frame path (`Relief.java`); reuse the feature's existing field helper rather
  than adding another (`LiftOffsets.java`).

---

## Gating

- Every feature decides on first use whether it runs: target mod loaded, required API present, target version known
  (or forced via a mod option), internals resolved. The decision is cached; an option change may re-open it, a runtime
  failure may not. See `problem()` / `active()` / `setForce()` / `disable()` in `ViewpointBloodFxFix.java`.
- Every patch entry point checks the facade's `active()` first.
- Keep a version allowlist for each target mod; a newer target version may change internals or fix the issue itself.
- A part that can work independently degrades on its own instead of disabling the whole feature (`AimShift.java`).

---

## Enter/Exit: Change Only for the Duration

When a feature changes another mod's state around a call (`LiftOffsets.java`, `AimShift.java`):

- Save originals and write them back exactly; do not recompute.
- Restore any leftover change before applying a new one, so changes never stack.
- Restore must not throw and must not overwrite a value the target replaced in the meantime.
- Adjust only what the draw call reads; leave the target's simulation state alone.

## Threading and Hot Paths

- Render patches run on the render thread; follow the reference feature's (lock-free) approach to shared state.
- Per-frame code must not allocate or build strings unless debug output is on (see `LiftOffsets.java`).
