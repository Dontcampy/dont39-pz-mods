# Design: Server Reborn

Requirements: `prd.md`. Behaviour sources: Sync Guard's `design.md` (D1-D8, in the archived
`10-24-mp-sync-loss-recovery` task) and `server-tick-rate/README.md` (moved into `server-reborn/README.md`).

## Model

One mod, one Gradle subproject, one lifecycle. Network sync optimization (was Sync Guard) and the server tick rate
are areas of the same mod that are expected to depend on each other, so they are decided, gated and disabled
together. Each keeps its own package.

## Names

| Thing | Value |
|---|---|
| Module, mod id, jar | `server-reborn/`, `Dont39ServerReborn`, `media/java/Dont39ServerReborn.jar` (client and server, not preloaded) |
| `javaPkgName` | `com.dont39.pz.serverreborn`: `ServerRebornMain` (`Zabric-Main`), `ServerRebornGate`, and the generated `ServerRebornGlue` / `ServerRebornLua` |
| Areas | `...serverreborn.networksyncoptimization` (Sync Guard's `protocol`, `game`, `server`, `client`, `stats` beneath it); `...serverreborn.servertickrate` |
| Log prefix | `[Dont39ServerReborn] ` on every line; a line names its area where the text alone would not |
| Sandbox page | `Dont39ServerReborn`, "Server Reborn" / "服务器重生" |
| Sandbox option | `Dont39ServerReborn_ServerTickRate.TicksPerSecond` (10-20, default 10). One dot: `SandboxOptions.parseName` splits only `table.option`. |
| Lua | `Dont39ServerReborn_OpenCheck.lua`, global `Dont39ServerReborn_containerShown` (`@LuaGlobal`) |
| Translation | `UI_Dont39ServerReborn_SyncRepaired`; `Sandbox_Dont39ServerReborn`, `Sandbox_Dont39ServerReborn_ServerTickRateTicksPerSecond` (+ `_tooltip`) |
| Launch switches | `-Dserverreborn.summarySeconds`, `.debugDropDelta`, `.debugFieldDiff`, `.debugTouches`, `.debugDropModule` |
| Unchanged | packet id `0x5347`, trailer magic "D39SYNCG", mod sync's magic it steps over, protocol version 1, digest fields |

## Build: `pz-kotlin-mod`

No module builds a standalone Kotlin mod with hooks yet: `pz-feature` wires KSP only for All-in-One features. New
convention `buildSrc/src/main/kotlin/pz-kotlin-mod.gradle.kts`:

- applies `pz-kotlin`, KSP, `pz-mod`, `pz-widening`; `compileOnly` `:zb-kt-utils` and `:zabric`,
  `ksp(:zb-kt-utils-processor)`; no `allinone-common` (unrelocated in All-in-One's jar; a second copy would clash on
  the shared class loader);
- KSP options from `pzMod`: glue package = `javaPkgName`, Lua prefix `<modId>_`, mod id, glue class and Lua bridge
  name from the mod id without `Dont39` (`ServerRebornGlue`, `ServerReborn` → `ServerRebornLua`);
- `checkLoaderRefs` excludes the generated glue classes;
- jar: `Zabric-Main` = `<javaPkgName>.<Name>Main`, the expanded `.classtweaker` into
  `META-INF/zabric/<modId>.classtweaker`, `META-INF/dont39/**` excluded; `assembleMod` copies `src/main/lua` to
  `42/media/lua` and `src/main/media` to `42/media`;
- `smokeTest` (`GameSmokeTest`, `hooks = true`, `kotlinMod` from `:zb-kt`).

The module script holds the version and test dependencies (`pz-junit`, `testImplementation(libs.kotlinStdlib)`).
`GlueProcessor`'s missing-option message names both conventions. Every hook object names the gate, so the KSP
front end's facade default never applies and the processor is otherwise unchanged.

## Gate: `ServerRebornGate`

Sync Guard's `Gate` (`game/Gate.java`) ported as a Kotlin `object : HookGate`, the gate of every hook object:

- shut until `ServerRebornMain` opens it; `open(onFailure)` registers the undo of what the mod changed;
- the first failure (a hook's, or the mod's own via `disable`) shuts it, logs the error and runs the undo once;
  later errors are still logged.

Why not `Feature` or Zabric's default gate (`ModState.DefaultGate`): the default gate's first failure stops the mod
in Zabric, which also stops `always` hooks and has no callback, so the channel change and the 50 ms limiter would stay
in the game without their compensations. `Feature` decides lazily from `problem()` for features with target mods;
neither area has one.

## Lifecycle

`ServerRebornMain.onMain`, as Sync Guard's `MainEntry` today:

1. `loaded` line, `Settings.load()` (launch switches).
2. `AccessWidener.report` for the mod; not in effect → idle line, gate stays shut.
3. Hook targets (`ZabricHooks.gateProblem(ServerRebornGate)`, covers both areas' hooks) → off line on a mismatch.
4. `PacketScope.resolve`, `Containers.check` → off line on a mismatch.
5. Client: `exposeIfMissing` for `ServerRebornLua`, open with `ClientSync::stop`, `ready on a client ...`.
   Co-op host: off line (both areas; the tick rate never applied there). Dedicated server: `ServerSync.start()`, open
   with `ServerSync::stop`, `active on a dedicated server ...`.

Server tick rate inside that lifecycle:

- Its handlers run whenever the gate is open; on a client they stay inert as today (`calledFromServerMain` false,
  rate 10).
- It reads `TicksPerSecond` at `setLockFPS` from `GameServer.main` (its own state, not gating) through a mod-local
  sandbox reader (`SandboxOption.int` from `allinone-common`, copied).
- Its mismatches (no 100 ms limiter before `setLockFPS`, more ticks than the limiter allows) call
  `ServerRebornGate.disable`: the whole mod turns off, as the areas depend on each other. `TickStart` stays
  `always = true`: once the gate is shut it puts the 10 Hz limiter and lock fps back on `GameServer.main`'s thread,
  which an undo on the failing thread could not.
- A rate below the option after 10 s stays an error line only, as today.

## Port rules (Java → Kotlin)

- Byte-for-byte the same wire and digests: explicit widths and signedness, `ByteBuffer` order, `Int` overflow in
  hashes, map iteration order where the digest or wire depends on it (`LinkedHashMap` where Java used it).
- Records → `data class`, sealed interfaces → `sealed interface`, static utility classes → `object`, static mutable
  state → `object` properties with the same `@Volatile` / thread confinement.
- Hook classes → top-level `internal object`s with public handlers (`HookReader.kt`), `gate = ServerRebornGate::class`;
  the same targets, injection points, `always`, `@Share`/`@Thrown`/`@Self`, and the exact-overload targets
  (`AddItem(String)`, `AddItem(String,float)`).
- Idiomatic Kotlin over micro-allocation, also per tick; comments short, why only.
- The 90 JUnit tests become Kotlin tests with the same cases and expected values (golden digests included);
  `TickScaleTest` moves as is.

## All-in-One and old modules

- `dont39-all-in-one/build.gradle.kts`: drop `:server-tick-rate`; its `workshop.txt` line and the root README's
  feature list go back to `main`'s text. No version bump: no release had the feature.
- `settings.gradle.kts`: remove `:sync-guard` and `:server-tick-rate`, add `:server-reborn`; delete both directories.

## Harness and the old task

- `10-24-mp-sync-loss-recovery` is archived with a note that Server Reborn supersedes it and where its open items
  went (`prd.md`, Follow-ups). Its `research/harness/` moves (`git mv`) to this task's `research/harness/` first.
- Harness edits: mod id and folder `Dont39ServerReborn`, `-WithServerReborn` instead of `-WithSyncGuard`, the
  `-Dserverreborn.*` switches, the sampler's summary-line pattern, the driver's Lua global, build command and README;
  ZabricKotlin staged next to Zabric. The driver mod `SGTestDriver` keeps its name (test-only).

## Specs and docs

- `directory-structure.md`: `pz-kotlin-mod` and `server-reborn/` in the layout; `sync-guard/` and the tick-rate
  feature removed.
- `zb-kt-utils.md`, `kotlin-runtime.md`, `zabric-hooks.md`: a Kotlin mod built with `pz-kotlin-mod` may implement
  `HookGate` itself when `Feature`'s lazy model does not fit (it then runs the widening and target checks itself); the
  moved file references (`TickHooks.kt`).
- `zombiebuddy-patching.md`, `access-widening.md`, `quality-guidelines.md`, `index.md`: `sync-guard` references point
  at the Kotlin files; the overview lists Server Reborn.
- `server-reborn/README.md`; `server-reborn/mod/workshop/workshop.txt` from Sync Guard's text plus the tick rate,
  ZabricKotlin (3820734224) in the requirements.

## Rollback

Each step is its own commit on `feat/server-reborn-46640e`. The old modules are deleted only after the new module
builds and its tests pass; reverting those commits restores them. Nothing outside this repo changes: no Zabric or
ZabricKotlin release.
