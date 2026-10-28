# Join-only client: Java mod's global Lua functions never registered (2026-10-28)

## Root cause

ZombieBuddy exposes `@LuaMethod(global = true)` classes (our KSP `@LuaGlobal` bridge objects, e.g.
`com.dont39.pz.allinone.*Lua`) only from `Exposer.afterExposeAll()`, which runs at the exit of
`LuaManager$Exposer.exposeAll` (`patches/Patch_Exposer.java`). When a jar loads, `PatchEngine` (~line 726) only
*queues* the class with `Exposer.addClassWithGlobalLuaMethod` — unlike `exposeClass`/`exposeMethod`, it does not
expose immediately when Lua is already up (`Exposer.java:57` vs `:88`, `:137`).

The game's Lua reload `Core.ResetLua(String,String)` (the join calls it from
`ConnectToServerState.receiveStartLocation`) runs, in bytecode order:

- offset 129 `LuaManager.init()` → `exposeAll` → ZB `afterExposeAll` (exposes only classes queued so far)
- offset 197 `ZomboidFileSystem.loadMods` → ZB `Loader.loadMods` → loads the server-list jars, queues their globals
- offset 324 `LuaManager.LoadDirBase` → mod Lua runs; the new globals are not there

At game start the order is the other way (`GameWindow`: `loadMods` 117, `init` 240), and on each later `ResetLua`
the already-queued classes are exposed again into the fresh env. So a mod's globals exist only if its jar was loaded
before the `LuaManager.init()` of the current Lua state. Same pattern in `IngameState.exit` (init 986, loadMods 1162),
and a mod enabled in the main menu's Mods screen without restart (ResetLua path).

Upstream ZombieBuddy origin/HEAD (2026-10-20, 7794d9f) still has it; there `getClassesWithGlobalLuaMethod` becomes
package-private.

## Evidence (st7b viewer, `C:\pz2c\Logs\logs_2026-10-28_00-06\2026-10-28_00-05_DebugLog.txt`)

- 442 `00:05:27.029` `Exposing global functions from class: com.pzlauncher.zabric.RestartNotice` (join's init; Zabric preloaded)
- 443 `00:05:28.027` `ZomboidFileSystem.loadMods(7 mods)`; 455 `added to classpath: ...Dont39AllInOne.jar`
- no `Exposing global functions from class: com.dont39...` afterwards
- 939 `appearance-override sync: client part idle: its Java side is not loaded`

st7c (All-in-One in the viewer's menu list, `C:\pz2c\Logs\2026-10-28_00-08_DebugLog.txt`): 8 `com.dont39` exposure lines.

## Affected

Every ZombieBuddy mod with global Lua functions that a client gets only from the server's list: All-in-One
(network-status, appearance-override, sctd-render-takeover, tunnel, mod-sync Lua), Server Reborn (`LuaGlobals.kt`).
`@LuaClass`/`exposeMethod` exposure is not affected.

## Fix options

1. Zabric, at the end of each ZombieBuddy pass (`Lifecycle.modsLoaded`, already hooked from premain; runs after the
   ResetLua's init, before LoadDirBase): when `LuaManager.exposer`/`env` exist, expose the `@LuaMethod(global)` classes
   in the pass's jars' `javaPkgName` whose globals the current env lacks. Generic; must not use ZB's
   `getClassesWithGlobalLuaMethod` (going package-private upstream).
2. Report upstream: `addClassWithGlobalLuaMethod` should expose at once when Lua is up, like `exposeClass`.
3. Document "enable at the main menu" as a requirement.
