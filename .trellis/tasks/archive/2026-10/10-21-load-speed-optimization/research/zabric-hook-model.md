# Research: Zabric hook capabilities for a client-only mod

- **Date**: 2026-10-21. Split from the launch-items research; index and per-item verdict: `launch-items.md`.
- Jar: `E:/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid.jar`, 42.21.0 `4a0e9546ec`. Citations look
  like `Class.method Lnnn`, with source lines taken from the LineNumberTable.


## Files

| File | What it holds |
|---|---|
| `zabric/src/main/java/com/pzlauncher/zabric/hook/Inject.java:9-28` | `@Inject(method, at, cancellable, always)`; handler shape `static void h([@Self T,] [all-or-no target params,] CallbackInfo ci [, @Thrown Throwable] [, @Share LocalRef...])` |
| `zabric/.../hook/At.java:9-16` | Only `HEAD`, `RETURN` (normal return) and `FINALLY` (after a return, a throw or a cancel). Constructors take `RETURN` only. Any other point is a compile error |
| `zabric/.../hook/CallbackInfo.java:27-32` | `cancel()` skips the target at HEAD and any later handlers. It throws unless the hook is `cancellable` |
| `zabric/.../hook/CallbackInfoReturnable.java:20-29` | `setReturnValue(v)` = `cancel()` plus the value the target returns |
| `zabric/.../hook/ModifyReturnValue.java:9-20` | `static R h(R original [, @Self] [, params] ...)`, at `RETURN` only |
| `zabric/.../hook/ModifyVariable.java:9-31` | Replaces a target **argument** at HEAD (`argsOnly = true`), chosen by `index` or `ordinal` |
| `zabric/.../hook/Hooks.java:24-38` | `@Hooks(Target.class)` or `targets = "a.b.C"`; a final class of static handlers or a Kotlin top-level object; `gate =` |
| `zabric/.../hook/HookGate.java:7-16` | `active()` is checked on every call, on any thread; `failed(what, t)` |
| `zabric/.../hook/Self.java`, `Thrown.java`, `Share.java` | `this`, the thrown exception at FINALLY, and per-call shared locals |
| `zabric-processor/src/main/resources/com/pzlauncher/zabric/processor/glue/glue.ftl:86-139` | The generated ZombieBuddy `@Patch` glue |
| `zabric/README.md:16-30` | The author contract (manifest, preload, hooks, gates, timing, JDK-types rule) |
| `zabric/src/main/java/com/pzlauncher/zabric/entrypoint/Lifecycle.java:22-41` | `Zabric-PreLaunch`, `Zabric-Main`, `Zabric-Hooks: launch` |
| `zabric/src/main/java/com/pzlauncher/zabric/loader/PreloadConfig.java:93-95` | A preloaded mod's manifest must have `ZB-Preload: true` |
| `buildSrc/src/main/kotlin/check/CheckJdkGlue.kt:20-99` | Fails the build when a glue member names a type outside `java/` and `com/pzlauncher/zabric/` |
| `loading-progress/build.gradle.kts:8-67` | The manifest and build of a Java-only, preloaded, client-only mod |
| `loading-progress/README.md:53-63` | "Constraints" |
| `loading-progress/src/main/java/com/dont39/pz/loadingprogress/progress/Settings.java:14-105` | Per-mod switches in an ini file |
| `missing-anim-folders/src/main/kotlin/com/dont39/pz/missinganimfolders/AnimFolderWalk.kt:12-22` | A Kotlin `object` hook that replaces a return value at HEAD |
| `buildSrc/src/main/kotlin/pz-feature.gradle.kts:10-46` | How Kotlin features are built: KSP `zb-kt-utils-processor`, bundled into `dont39-all-in-one` |
| `external-repos/ZombieBuddy/java/src/main/java/me/zed_0xff/zombie_buddy/PatchEngine.java:164-300` | How ZombieBuddy stacks advices. The worktree's `external-repos/ZombieBuddy` is empty, so this was read from the main checkout `E:/Dev/Project/pz/dont39-pz-mods/external-repos/ZombieBuddy` (commit `9a08a54`) |

## Handler kinds

- **HEAD, RETURN, FINALLY**, all through `@Inject`. FINALLY also runs after a throw or a cancel, with `@Thrown`
  (`At.java:9-16`).
- **Skip the original, or replace the return value**: use `@Inject(at = HEAD, cancellable = true)`.
  - `ci.cancel()` on a void target, or `cir.setReturnValue(v)` on a non-void one.
  - The glue turns this into a ZombieBuddy `@Patch.OnEnter(skipOn = true)` advice that returns `true` once
    cancelled (`glue.ftl:91-97, 52-59`).
  - The exit advice then writes the cancelled value into the return slot (`glue.ftl:126-130`, `restoreValue`;
    `GlueWriter.java:116-119, 187-192`).
- **Rewrite the return value at RETURN**: `@ModifyReturnValue`, or `@Inject(at = RETURN, cancellable = true)` with
  `setReturnValue` (`zabric/src/test/java/com/pzlauncher/zabric/hook/weave/DefaultHooks.java:23-26`).
- **Rewrite an argument**: `@ModifyVariable(at = HEAD, argsOnly = true)` (`ModifyVariable.java:9-31`; examples in
  `zabric/src/test/java/com/pzlauncher/zabric/hook/weave/ShapeHooks.java:43-60`).
- **Can a hook replace a whole method body? Yes.**
  - A HEAD `@Inject(cancellable = true)` handler that calls `cir.setReturnValue(x)` skips the original body and
    returns `x`. This works for any return type, including a `String` from a static method.
  - Examples: `GatedHooks.java:19-27` (`compute` returns -1) and `AnimFolderWalk.kt:14-21` (returns a new `ArrayList`).
  - On a void method, `ci.cancel()` skips the body.
  - Constructors cannot be cancelled: they take RETURN only (`At.java:12-13`).
- **Not available**: `@Redirect`, `@ModifyArg` at a call site, injection inside the body (INVOKE), and priorities
  (`zabric/README.md:25`; `hook/package-info.java`).
  - **Consequence**: code in the middle of a method, such as the `SoundManager` constructions in
    `GameWindow.mainThreadInit`, cannot be moved or reordered. Only whole calls can be skipped or replaced, at a
    hooked method's HEAD.

## How a hook class is declared

- **Java**: a `@Hooks(Target.class)` final class of `public static` handlers. Loading Progress groups them as nested
  classes, e.g. `loading-progress/.../hooks/TriggerHooks.java:109-123`.
  - The processor options `-Azabric.modId`, `-Azabric.javaPkgName` and optionally `zabric.glueClass` are set in
    `loading-progress/build.gradle.kts:31-33`.
- **Kotlin**: `@Hooks(X::class) internal object Foo { @Inject(...) fun h(...) }` (`AnimFolderWalk.kt:12-22`). It is
  built through KSP (`pz-feature.gradle.kts:13, 23, 39-46`) inside `dont39-all-in-one`.
  - Kotlin needs ZabricKotlin, which is not installed at game start. `loading-progress/build.gradle.kts:8` says:
    "Java only: Zabric opens its hooks at game start, before ZabricKotlin could install Kotlin". The preLaunch
    entrypoint is also "plain Java, no Kotlin, no game classes" (`zabric/README.md:17`).
  - **So any launch-time hook that must be active before the first mod list (R5) has to be Java.**

## Manifest: client-only, preLaunch, ZB-Preload, Zabric-Hooks

From `loading-progress/build.gradle.kts` and `loading-progress/mod/42/mod.info`:

- **Client only**: `pzMod.jarFolder = "media/java/client"` (`build.gradle.kts:23-24`). The code also checks
  `GameServer.server`, because ZombieBuddy does not always skip such jars on a dedicated server
  (`loading-progress/README.md:60-63`).
- **Preloaded at game start**:
  - mod.info has `javaPreload=true`.
  - The manifest has `"ZB-Preload" to "true"` and `"Zabric-PreLaunch" to "<pkg>.entrypoint.PreLaunchEntry"`
    (`build.gradle.kts:47-58`).
  - Zabric refuses a preload without `ZB-Preload: true` (`PreloadConfig.java:93-95`).
- **`Zabric-Main`**: called once per session after the first mod list (`zabric/README.md:17`).
- **`"Zabric-Hooks" to "launch"`**: keeps the hooks open on mod lists that lack the mod, e.g. a server's
  (`build.gradle.kts:55-56`; `Lifecycle.java:28-41`). It requires a preLaunch entrypoint (`zabric/README.md:27`).
  - The README advises "for client-side UI only, as they also act on servers and saves without your mod"
    (`zabric/README.md:27`; `Lifecycle.java:40`).
- **Hook timing** (`zabric/README.md:27`; `Lifecycle.java:28-34`):
  - Without preload, a mod's hooks open with the first mod list that contains it, then follow the mod list.
  - **On an MP join the client loads the server's mod list.** A client-only mod the server lacks therefore has its
    hooks **off during the join**, unless it is preloaded with `Zabric-Hooks: launch`.

## The JDK-types-only glue constraint

- `zabric/README.md:28`: "ZombieBuddy, though, loads the glue's signature types when it preloads your jar (each
  target's argument and return types, arrays excepted, and the target class with @Self): to leave game classes to
  the game, hook methods whose signatures name only JDK types, without @Self."
- `loading-progress/README.md:55-59`:
  - Only the generated glue lives in the top-level `javaPkgName` package, because ZombieBuddy loads every class
    there at premain.
  - The glue binds every argument, plus the return value for RETURN and FINALLY handlers.
  - So only JDK-signature methods are hooked, never with `@Self`.
  - Where a method takes a game type, a JDK-only caller or callee is hooked instead: `Core.DoFrameReady`,
    `KahluaThread.pcall(int)`.
- `CheckJdkGlue.kt:74-80, 96-98` enforces this. Allowed prefixes are `java/` and `com/pzlauncher/zabric/`; array
  element types are checked too. It is wired into `check` and `jar` (`loading-progress/build.gradle.kts:40-48`).
- **Scope**: the rule applies to the glue's **signatures** only. Handler bodies, in other packages such as
  `hooks/`, may use game classes freely. Loading Progress's own handlers call `LuaManager.loadList` etc.
  (`LoadHooks.java:147-151`).
- **Consequence for this task**:
  - A preloaded mod (required for R5, and for anything that must act during MP joins to servers without the mod)
    can hook only methods whose descriptor is JDK-only, and cannot use `@Self`.
  - This checkJdkGlue rule is set per module, and every glue class lives in one package. One mod jar therefore
    cannot mix preloaded JDK-only hooks with game-typed hooks.
  - It needs either two mods, or a checked exception to `CheckJdkGlue`'s allow list.

## Gates and per-feature switches

- **Default gate** (`zabric/README.md:26`):
  - The first exception from a handler, or an unwoven hook, stops **all** of the mod's hooks for the session.
  - Zabric checks default-gate targets itself, from class bytes as each class loads, from game start on
    (`zabric/README.md:28`; `loading-progress/README.md:60`).
- **Named gate**: `@Hooks(gate = X.class)`, where `X` has a `public static final INSTANCE` or is a Kotlin object.
  - It scopes failure to its own hook classes, and `active()` can read a switch.
  - It must call `ZabricHooks.gateProblem(gate)` before it turns active, "no earlier than your main entrypoint (it
    loads the targets)" (`zabric/README.md:26`; `ZabricHooks.java:83-86`).
  - **So hooks that must act before the first mod list (R5's are at `RenderThread.init` / `mainThreadInit`) can
    only use the default gate.** Their switch has to be checked inside the handler.
- **Loading Progress switches** (`progress/Settings.java`):
  - A `record Settings(boolean display, boolean report)`, read from `Dont39LoadingProgress.ini` in the game cache's
    `Lua` folder (`Settings.java:14-20`).
  - `load` writes the defaults when the file is missing. An unreadable or invalid file is reported in one line and
    the defaults apply (`Settings.java:29-51`).
  - The strict parser rejects unknown keys, duplicates and non-booleans, and skips a BOM (`Settings.java:54-90`).
    The written text has a comment per key (`Settings.java:93-100`).
  - Read once per session at the first flow's begin (`hooks/Loading.java:117-118`).
  - The Lua options page `src/main/lua/client/Dont39LoadingProgress_Options.lua` shows and rewrites the same file;
    a change applies from the next launch (`loading-progress/README.md:37-44`).
  - `Settings.load` is plain JDK code, so it can also run in a preLaunch entrypoint.

## How ZombieBuddy combines several mods' patches

- Advice patches stack per method, one `.visit()` per advice class, in the order the patch classes were collected
  (`PatchEngine.java:164-171, 280-300`).
- Classes that are already loaded are retransformed (`PatchEngine.java:178-189, 221-228`).
- Only one MethodDelegation per method applies (`PatchEngine.java:166-170`). Zabric glue is always advice.
- When two mods hook the same method and one skips at HEAD, whether the other's HEAD/FINALLY still runs depends on
  which advice wraps which (load order, `zabric/README.md:27`). **Not verified for this case.**

---
