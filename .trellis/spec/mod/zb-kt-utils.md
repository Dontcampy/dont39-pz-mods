# ZabricKotlin Utils (shared runtime)

> What every Kotlin feature shares, written once. Code and KDoc:
> `zb-kt-utils/src/main/kotlin/com/dont39/pz/zbktutils/`. Reference user:
> `ime-keyboard-fix`.

---

## Use It

- A feature's facade extends `Feature` (`Feature.kt`), target-mod presence and version checks use `TargetMod.kt`, hooks
  use Zabric's annotations ([Zabric Hooks](./zabric-hooks.md)) and Lua functions `@LuaGlobal` in `glue/Glue.kt`
  ([ZombieBuddy Patching](./zombiebuddy-patching.md#patch-classes)).
- `Feature` is the hook gate of the module's hook objects (`Feature.kt`), so a failing hook disables only its feature.
- `Feature.whyNotActive()` gives the player-facing reason a feature does not run (null when active), e.g. for a Lua
  check that refuses an action (`Tunnel.luaProblem`). New in ZabricKotlin after 0.4.0: release ZabricKotlin before a
  mod that calls it.
- Do not re-implement in a feature what the module provides: gating state, the log prefix, lifecycle wording, hook
  gate and target check, widening state.

## Rules for Changing It

- It is public runtime API for any Kotlin mod on ZabricKotlin: nothing mod-specific in it.
- Read a mod's own resources from the jar of its class, not through the class loader: all mod jars share it.
- Log wording is a contract: smokeTest and players' `console.txt` read it.
- The Lua bridge listing (`META-INF/zbkt/glue/`) is shared by `LuaBridge.kt` and the build's readers
  (`FeatureInfo.kt`, `CheckGlue.kt`, `GameSmokeTest.kt` in `buildSrc`); change them together. Readers skip line kinds
  they do not use. Hook listings are Zabric's ([Zabric Hooks](./zabric-hooks.md#rules)).
- Add API only when a feature needs it; remove what no feature uses. Generic platform API (target-mod checks, mod
  options, Lua globals) stays when the feature that first used it is gone.

## Packaging

- Shipped once, inside ZabricKotlin's jar (`zb-kt/build.gradle.kts`). Every other module has it compile-only;
  never bundle it into another jar: its classes are unrelocated on the shared classloader.
- The glue processor (`zb-kt-utils-processor`) runs only in the compiler and is never shipped or published. Change an
  annotation and the processor together; for hooks it is a front end on `zabric-processor`'s generator.
