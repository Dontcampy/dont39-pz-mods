# Design: Server Reborn

Requirements: `prd.md`. Behaviour sources: Sync Guard's `design.md` (D1-D8, in the archived
`10-24-mp-sync-loss-recovery` task) and `server-tick-rate/README.md` (moved into `server-reborn/README.md`).

## Model

A Kotlin mod is one Gradle subproject; each part of it is a **feature**: a package with one `Feature` facade that
gates that package's hooks. Server Reborn has two features in one compilation. All-in-One keeps its model (one
subproject per feature, bundled), because its features may later move to mods of their own; Server Reborn's will not.

Built from scratch, without the old modules' constraints:

- both parts are `Feature`s; Sync Guard's own `Gate` goes away, and `Feature` gains the two things it lacked;
- the KSP front end serves a compilation with several features;
- generic helpers the mod needs move to `zb-kt-utils`.

## Names

| Thing | Value |
|---|---|
| Module, mod id, jar | `server-reborn/`, `Dont39ServerReborn`, `media/java/Dont39ServerReborn.jar` (client and server, not preloaded) |
| `javaPkgName` | `com.dont39.pz.serverreborn`: generated glue `ServerRebornGlue`, Lua bridge `ServerRebornLua`, `initFeatures()`, and `ServerRebornMain` (as `AllInOneMain` sits in All-in-One's) |
| Features | `network-sync-optimization`: package `...serverreborn.networksyncoptimization` (sub-packages `protocol`, `game`, `server`, `client`, `stats`), facade `NetworkSyncOptimization`; `server-tick-rate`: package `...serverreborn.servertickrate`, facade `ServerTickRate` |
| Log prefix | `[Dont39ServerReborn] ` for mod lines, `[Dont39ServerReborn] <feature>: ` from `Feature` |
| Sandbox page | `Dont39ServerReborn`, "Server Reborn" / "服务器重生" |
| Sandbox options | `Dont39ServerReborn_NetworkSyncOptimization.Enabled` (boolean, default true); `Dont39ServerReborn_ServerTickRate.TicksPerSecond` (10-20, default 10). One dot each: `SandboxOptions.parseName` splits only `table.option`. |
| Lua | `Dont39ServerReborn_OpenCheck.lua`, global `Dont39ServerReborn_containerShown` (`@LuaGlobal`) |
| Translation | `UI_Dont39ServerReborn_SyncRepaired`; sandbox keys `Sandbox_Dont39ServerReborn_NetworkSyncOptimizationEnabled`, `Sandbox_Dont39ServerReborn_ServerTickRateTicksPerSecond` (+ `_tooltip`) |
| Launch switches | `-Dserverreborn.summarySeconds`, `.debugDropDelta`, `.debugFieldDiff`, `.debugTouches`, `.debugDropModule` |
| Unchanged | packet id `0x5347`, trailer magic "D39SYNCG", mod sync's magic it steps over, protocol version 1, digest fields |

## Build: `pz-kotlin-mod`

New convention `buildSrc/src/main/kotlin/pz-kotlin-mod.gradle.kts`, a Kotlin mod in one subproject:

- applies `pz-kotlin`, KSP, `pz-mod`, `pz-widening`; `compileOnly` `:zb-kt-utils` and `:zabric`,
  `ksp(:zb-kt-utils-processor)`; no `allinone-common`;
- an extension with the features in init order (`features = listOf("server-tick-rate", "network-sync-optimization")`);
- KSP options from `pzMod`: glue package = `javaPkgName`, Lua prefix `<modId>_`, mod id, glue class and bridge name
  from the mod id without `Dont39` (`ServerRebornGlue`, `ServerRebornLua`);
- a task that writes `initFeatures()` into `javaPkgName`, calling each listed feature's facade by the naming rule
  (`server-tick-rate` → `<javaPkgName>.servertickrate.ServerTickRate`); a missing facade fails the compile.
  `GenerateFeatureInit` (All-in-One) stays as is; the two share the naming function;
- `checkLoaderRefs` excludes the generated glue classes;
- jar: `Zabric-Main` = `<javaPkgName>.<ModName>Main`, the expanded `.classtweaker` into
  `META-INF/zabric/<modId>.classtweaker`, `META-INF/dont39/**` excluded; `assembleMod` copies `src/main/lua` to
  `42/media/lua` and `src/main/media` to `42/media`;
- `smokeTest` (`GameSmokeTest`): `hooks = true`, `kotlinMod` from `:zb-kt`, and a new input with the in-module
  feature names, whose status lines it checks as it checks bundled features' (ready/active/idle pass, disabled
  fails).

The module script holds the version, the features list and its test dependencies.

## KSP front end (`zb-kt-utils-processor`, compile time only)

- A compilation may hold several `Feature` facades (top-level objects). A hook object without `gate` is gated by the
  facade in its own package or the nearest enclosing one; none or two at that level is a compile error naming them.
  An All-in-One feature has one facade at its package root, so its glue is unchanged (golden tests).
- New tests: two facades in sibling packages gate their own hooks; a hook outside both is an error; a facade in a
  sub-package of another's is chosen for that sub-package.
- The missing-option message names both conventions.

## Runtime (`zb-kt-utils`, ships in ZabricKotlin)

`Feature` (`Feature.kt`) gains:

- `decideAgain()` (protected): forgets an ACTIVE or INACTIVE decision so the next `active()` asks `problem()` again,
  as `setForce` already does inside; a no-op once DISABLED. For a feature whose decision needs state that appears
  after `init()` (the save's sandbox options on a server).
- `onDisabled()` (protected open, default empty): runs once, on the first `disable`, after the state is DISABLED; an
  exception there is logged as an error while undoing, the feature stays disabled.

`SandboxOption` moves from `allinone-common` to `zb-kt-utils` (generic platform API); `mod-sync`, `tunnel` and
All-in-One's other users import it from there.

ZabricKotlin's version goes from 0.7.0 to 0.8.0; `zb-kt/README.md` and the spec's "new in" notes say what is new. It
must be uploaded before Server Reborn and before the next All-in-One release. Tests: `Feature` state transitions in a
new `zb-kt-utils` test source set if `Feature` runs outside the game there (its logging goes through `DebugType`);
else the smoke join covers them and the plan says so.

## The two features

**server-tick-rate**: `ServerTickRate` as today, with `"Dont39ServerReborn"` as its mod id and the new option name;
hooks unchanged (they gate on it by package). README text moves to `server-reborn/README.md`.

**network-sync-optimization**: `NetworkSyncOptimization : Feature("network-sync-optimization",
"Dont39ServerReborn", widened = true)`, replacing `Gate` and `MainEntry`:

| Sync Guard today | Feature |
|---|---|
| `AccessWidener.report` check, `Gate.targetProblem()` | `Feature`'s own widening and hook-target checks |
| `PacketScope.resolve`, `Containers.check` | `resolve()` (a throw disables, with `resolveContext()`) |
| client: `LuaGlobals.exposeIfMissing`, then open | `resolve()` on a client also exposes `ServerRebornLua` when the join's Lua environment predates the jar |
| server: `ServerSync.start()`, open | the server-start decision below |
| co-op host: off | `problem()`: "this server runs a hosted (co-op) game; ..." |
| `Gate.failed` → `ClientSync::stop` / `ServerSync::stop` | `onDisabled()` stops the side that started |
| `Settings.load()` and the switch lines at main | the facade's `init` path, before the decision |

Server-start decision: the save's sandbox options are loaded after Zabric's main entrypoint on a dedicated server.
`problem()` returns "the server has not started its world yet" until then. An `always = true` hook at
`PerformanceSettings.setLockFPS` HEAD (gated by the facade, so it also runs while inactive) acts once on
`GameServer.main`'s first call: reads `Enabled`; true → `ServerSync.start()`; then `decideAgain()`. False →
`problem()` returns "sandbox option ... is false" and nothing in the game changes. `GameServer.main` calls
`setLockFPS` (offset 2709) before its loop first handles net data (3060), so no join is answered before the decision
(javap of 42.21's `GameServer.main`). Clients are unchanged: a server with the feature off sends no trailer, which a
client logs as for a server without the mod.

## Port rules (Java → Kotlin)

- Byte-for-byte the same wire and digests: explicit widths and signedness, `ByteBuffer` order, `Int` overflow in
  hashes, map iteration order where the digest or wire depends on it (`LinkedHashMap` where Java used it).
- Records → `data class`, sealed interfaces → `sealed interface`, static utility classes → `object`, static mutable
  state → `object` properties with the same `@Volatile` / thread confinement.
- Hook classes → top-level `internal object`s with public handlers (`HookReader.kt`); the same targets, injection
  points, `always`, `@Share`/`@Thrown`/`@Self`, and the exact-overload targets (`AddItem(String)`,
  `AddItem(String,float)`).
- Idiomatic Kotlin over micro-allocation, also per tick; comments short, why only.
- The 90 JUnit tests become Kotlin tests with the same cases and expected values (golden digests included);
  `TickScaleTest` moves as is.

## All-in-One and old modules

- `dont39-all-in-one/build.gradle.kts`: drop `:server-tick-rate`; its `workshop.txt` line and the root README's
  feature list go back to `main`'s text. Its features import `SandboxOption` from `zb-kt-utils`.
- `settings.gradle.kts`: remove `:sync-guard` and `:server-tick-rate`, add `:server-reborn`; delete both directories.

## Harness and the old task

- `10-24-mp-sync-loss-recovery` is archived with a note that Server Reborn supersedes it and where its open items
  went (`prd.md`, Follow-ups). Its `research/harness/` moves (`git mv`) to this task's `research/harness/` first.
- Harness edits: mod id and folder `Dont39ServerReborn`, `-WithServerReborn` instead of `-WithSyncGuard`, the
  `-Dserverreborn.*` switches, the sampler's summary-line pattern, the driver's Lua global, build command and README;
  ZabricKotlin staged next to Zabric. The driver mod `SGTestDriver` keeps its name (test-only).

## Specs and docs

- `directory-structure.md`: `pz-kotlin-mod` and `server-reborn/` in the layout; "a Kotlin mod with several features in
  one subproject" next to "Adding a Feature"; `sync-guard/` and the tick-rate feature removed.
- `zb-kt-utils.md`: `decideAgain`, `onDisabled`, `SandboxOption` (new in 0.8.0); facades per package.
- `zabric-hooks.md`, `kotlin-runtime.md`: the KSP front end serves bundled features and `pz-kotlin-mod` mods; the
  default gate by package; the moved file references (`TickHooks.kt`).
- `zombiebuddy-patching.md`, `access-widening.md`, `quality-guidelines.md`, `index.md`: `sync-guard` references point
  at the Kotlin files; the overview lists Server Reborn.
- `server-reborn/README.md`; `server-reborn/mod/workshop/workshop.txt` from Sync Guard's text plus the tick rate,
  ZabricKotlin (3820734224) in the requirements.

## Rollback

Each step is its own commit on `feat/server-reborn-46640e`. The old modules are deleted only after the new module
builds and its tests pass. The `Feature` additions and the processor change are additive; reverting their commits
restores the previous behaviour. Nothing is published until the user uploads.
