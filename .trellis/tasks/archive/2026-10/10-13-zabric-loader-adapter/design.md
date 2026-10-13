# Design: Zabric loader adapter layer

## Shape: a compile-time seam (D2)

Exactly two places may reference ZombieBuddy (ZB) types (`me/zed_0xff/...`):

1. **Zabric's loader package** `com.dont39.pz.zabric.loader`: the runtime services (R1, R2).
2. **`GlueProcessor` output**: the generated `@Patch` and Lua bridge classes, written into each bundling mod's glue
   package (`PzGlue.gluePackage`) (H1). The processor itself names ZB only as strings.

Everything else (the rest of Zabric, `zb-kt`, `zb-kt-utils`, feature modules, `dont39-all-in-one`) uses neutral API.
Switching loaders = rewrite the loader package + the processor's code generation, then rebuild. There is no interface
with multiple implementations and no runtime detection (it would conflict with fail-fast, and glue is compile-time
anyway).

## Runtime seam (R): `com.dont39.pz.zabric.loader`

Public API, neutral names and signatures, Java only (premain-safe, `CheckBootstrapClasses` still applies):

| API | Replaces | Notes |
|-----|----------|-------|
| `Loader.name()` | hard-coded "ZombieBuddy" in Zabric messages | For log/restart text in neutral code |
| `Loader.version()` | `ZombieBuddyAgent.version()` (private) | "(unknown version)" behavior kept as is |
| `Loader.instrumentation()` | `agent/ZombieBuddyAgent.instrumentation()` | Same contract: throws `IllegalStateException` naming loader + version. Reflection on `Loader.g_instrumentation` stays (ZB keeps it package-private; no typed alternative) |
| `Loader.keepPreloaded(modId, versionDir, commonDir)` → result (already current or not, where it was stored) | ZB-specific half of `Preload.update` | Holds: `config.json`/`preload_mods` read-write, `Agent.configDir()`, `checkPreloadable` (`javaPreload`, `ZB-Preload` manifest), identical-copy rule, Zabric-first ordering, mod.info reading "as ZB reads it", atomic write |

ZB implementation classes in the same package may be package-private. The `agent` package is removed.

Stays outside the seam, now neutral:

- `preload/Preload.java`: public `register` / `registerRequested` / `REQUEST_PREFIX` contract unchanged
  (`workshop.txt:28`). Resolves the mod's folders through the game (`ChooseGameInfo`), calls
  `Loader.keepPreloaded`, then restart list + log lines.
- `widener/AccessWidener.java`: calls `Loader.instrumentation()`.
- `zb-kt/.../PreMain.java`: calls `Loader.instrumentation()`; its `NoClassDefFoundError` handling (Zabric not loaded)
  stays.

Compatibility: Zabric/ZombieBuddyKotlin are not uploaded yet (no Workshop `id=`), so `ZombieBuddyAgent` is removed
without a shim. The author contract in `workshop.txt` does not name it and stays unchanged.

## Hook seam (H): neutral bindings

- `zb-kt-utils/.../glue/Glue.kt` gains parameter annotations `Hook.This`, `Hook.Argument(index)`, `Hook.Return`,
  `Hook.Thrown`, `Hook.AllArguments` (VALUE_PARAMETER, BINARY), mirroring today's allowed set
  (`GlueProcessor.kt` `BINDINGS` / `EXIT_ONLY_BINDINGS`).
- `Hook.Exit.onThrowable: KClass` becomes `onThrow: Boolean = true`. Only `Throwable::class` (default) and
  `Patch.NoException::class` (`ImeCaret.kt:23`) are used; `Thrown` requires `onThrow = true` (same rule as today).
- `GlueProcessor` reads the neutral annotations and emits the same `@Patch.*` code as today; existing validation
  messages are reworded to the neutral names. Generated code is unchanged in behavior.
- Glue listing (`META-INF/zbkt/glue/<Feature>.txt`) gains `hook <className> <methodName>` lines. `HookTargets.kt`
  checks those instead of reading `@Patch` off the generated classes. Other listing readers
  (`dont39-all-in-one/build.gradle.kts` `checkGlue`) must ignore the new line kind.
- Features migrate: `AimShift.kt`, `ImeCaret.kt`.

## Enforcement: `checkLoaderRefs`

A build check fails when compiled classes reference `me/zed_0xff/` (constant-pool class entries and descriptors, the
same scanning approach as `CheckBootstrapClasses.kt`; share the scanner instead of copying it). Applied to every
module's compiled classes, excluding:

- `com/dont39/pz/zabric/loader/**` in `zabric`;
- the glue package directory in modules that apply `pz-feature` (generated glue).

Wired into `check` (so `./gradlew build` runs it).

## Not abstracted (switch checklist, documented in spec)

Each needs rework on a switch but depends on the new loader's format: entry-point conventions (`PreMain.premain`,
`Main.main` in `javaPkgName`), Lua exposure from exactly `javaPkgName` (`RestartNotice.java`, generated Lua bridge),
mod.info fields (`require=\ZombieBuddy`, `ZBVersionMin`, `javaJarFile`, `javaPkgName`, `javaPreload`), manifest
`ZB-Preload`, `zb-gradle-plugin` + signing + compile classpath (`pz-java.gradle.kts`), smoke launch
(`PzSmoke.kt` `-agentlib:zbNative`), Workshop texts, and log/message strings that name ZombieBuddy outside Zabric.

## Rollback

Pure refactor with no data or config format change: `config.json` entries written by the new code are identical.
Rollback = revert the commit.
