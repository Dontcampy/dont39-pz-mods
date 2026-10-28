# Draft: ZombieBuddy issue (not posted)

Repository: zed-0xff/ZombieBuddy

**Title:** Global Lua functions (`@LuaMethod(global = true)`) of a mod loaded at a server join are never exposed

**Body:**

A Java mod that a client gets only from a server's mod list (not enabled in the main menu) loads, but none of its
`@LuaMethod(global = true)` functions exist in Lua afterwards. `@Exposer.LuaClass` classes and `Exposer.exposeMethod`
from the same jar work.

Cause (ZombieBuddy at 9a08a54, still the same at 7794d9f):

- `PatchEngine` queues such classes with `Exposer.addClassWithGlobalLuaMethod`, which only adds them to
  `g_classesWithGlobalLuaMethod`.
- They are exposed only from `Exposer.afterExposeAll()`, at the exit of `LuaManager$Exposer.exposeAll`, which the game
  calls only from `LuaManager.init()`.
- On a join the game reloads Lua with `Core.ResetLua(String, String)` (from `ConnectToServerState.receiveStartLocation`),
  which calls `LuaManager.init()` *before* `ZomboidFileSystem.loadMods(...)` and then `LuaManager.LoadDirBase()`. So a jar
  first loaded in that `loadMods` is queued after the only `afterExposeAll` of that Lua state, and the mod's Lua files
  run without its functions. `IngameState.exit` has the same order, as does a mod enabled in the main menu's Mods screen
  without a restart. At game start the order is the other way round (`GameWindow`: `loadMods`, then `LuaManager.init`),
  which is why enabling the mod at the main menu works.

Log of a client that enables the mod only through the server (42.21, ZombieBuddy v2.3.4):

```
[ZB] Exposing global functions from class: com.pzlauncher.zabric.RestartNotice     <- the join's LuaManager.init
[ZB] ZomboidFileSystem.loadMods(7 mods) ....
[ZB] added to classpath: ...\Dont39AllInOne\42\media\java\Dont39AllInOne.jar
                                                                  <- no "Exposing global functions" for its classes
```

Suggested fix: make `addClassWithGlobalLuaMethod` expose the class at once when Lua is already up, as `exposeClass`
and `exposeMethod` do:

```java
public static void addClassWithGlobalLuaMethod(Class<?> cls) {
    if (cls != null && hasGlobalLuaMethod(cls) && g_classesWithGlobalLuaMethod.add(cls)
            && LuaManager.exposer != null && LuaManager.env != null) {
        exposeGlobalFunctionsNow(cls); // the body of afterExposeAll's loop for one class
    }
}
```

`LuaManager.init()` builds the exposer on the new env, so between `init()` and `LoadDirBase()` both point at the Lua
state the mod's files will run in.

Workaround we ship meanwhile: Zabric 0.7.3 exposes, at the end of each `Loader.loadMods`, the classes of
`getClassesWithGlobalLuaMethod()` whose global functions the current env lacks. That relies on
`getClassesWithGlobalLuaMethod` staying reachable (it is package-private in the current `master`).
