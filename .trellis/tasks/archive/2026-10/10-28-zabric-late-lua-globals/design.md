# Design

## Where

- `zabric/.../loader/Loader.java` (the seam): `globalLuaClasses()` returns ZombieBuddy's
  `Exposer.getClassesWithGlobalLuaMethod()` (public in the installed ZombieBuddy.jar), throwing an
  `IllegalStateException` naming the loader version on a `LinkageError`/`RuntimeException`, like `instrumentation()`.
  Upstream makes it package-private in its next version: that turns into this error, and the seam is where to adapt.
- `zabric/.../entrypoint/LuaGlobals.java` (package-private, new): the exposure. Uses only game/Kahlua types.
- `Lifecycle.modsLoaded`: calls it at the end of a pass that runs (after the mods' main entrypoints); it catches
  everything itself, so a failure here never calls `stop()`.

## Data flow

`Lifecycle.modsLoaded(pass)` → `LuaGlobals.exposeMissing()`:

1. `LuaManager.env` / `LuaManager.exposer` null → return (game start; ZombieBuddy exposes them at `LuaManager.init`).
   Both are null until the first `init()`; `init()` builds the exposer on the new env (bytecode, `research/root-cause.md`).
2. For each class of `Loader.globalLuaClasses()` not reported before: its global names as Kahlua's
   `exposeGlobalFunctions` takes them (`getMethods()`, `@LuaMethod(global = true)`, empty name → method name). All
   present in `env` (`rawget`) → skip. Otherwise create an instance as ZombieBuddy does (declared no-arg constructor,
   `setAccessible`) and call `exposer.exposeGlobalFunctions(instance)`; log one line.
3. A class that throws: one error line, remembered, not retried. `globalLuaClasses()` throwing: one error, the part
   stops for the session.

The core is `expose(KahluaTable env, Consumer<Object> exposer, Collection<Class<?>> classes)`, so tests run it on a
Kahlua table and `LuaJavaClassExposer` without `LuaManager`'s statics.

## Timing (why the end of the pass is right)

`ModsLoadedHook` fires at the exit of ZombieBuddy's `Loader.loadMods`, inside `ZomboidFileSystem.loadMods`. In
`Core.ResetLua` that is after `LuaManager.init()` (offset 129) and before `LuaManager.LoadDirBase` (offset 324), so the
mods' Lua files see the functions. Same in `IngameState.exit` (init 986, loadMods 1162, LoadDirBase 1314).

## Compatibility / rollback

- Mods already exposed by ZombieBuddy: skipped, no change in behavior or log.
- If upstream fixes `addClassWithGlobalLuaMethod`, every class is present and this does nothing.
- Rollback: revert the commit; no saved state.
