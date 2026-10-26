# Design: Server Reborn

Requirements: `prd.md`. Behaviour sources: Sync Guard's `design.md` (D1-D8, in the archived
`10-24-mp-sync-loss-recovery` task) and `server-tick-rate/README.md` (moved into `server-reborn/README.md`).

## Names

| Thing | Value |
|---|---|
| Module, mod id, jar | `server-reborn/`, `Dont39ServerReborn`, `media/java/Dont39ServerReborn.jar` (client and server, not preloaded) |
| `javaPkgName` | `com.dont39.pz.serverreborn`: only the generated hook glue (`ServerRebornGlue`) and Lua bridge (`ServerRebornLua`), since ZombieBuddy loads every class there while patching |
| Main entrypoint | `com.dont39.pz.serverreborn.entrypoint.ServerRebornMain` (`Zabric-Main`; not named `Main`) |
| Parts | `network-sync-optimization` (was Sync Guard), package `...serverreborn.networksyncoptimization` with the old sub-packages `protocol`, `game`, `server`, `client`, `stats`; `server-tick-rate`, package `...serverreborn.servertickrate` |
| Log prefix | `[Dont39ServerReborn] ` for mod lines, `[Dont39ServerReborn] <part>: ` for a part's lines (Feature's format) |
| Sandbox page | `Dont39ServerReborn`, "Server Reborn" / "服务器重生" |
| Sandbox options | `Dont39ServerReborn_NetworkSyncOptimization.Enabled` (boolean, default true); `Dont39ServerReborn_ServerTickRate.TicksPerSecond` (10-20, default 10). One dot each: `SandboxOptions.parseName` splits only `table.option`. |
| Lua | `Dont39ServerReborn_OpenCheck.lua`, global `Dont39ServerReborn_containerShown` |
| Translation | `UI_Dont39ServerReborn_SyncRepaired`; sandbox keys `Sandbox_Dont39ServerReborn_NetworkSyncOptimizationEnabled`, `Sandbox_Dont39ServerReborn_ServerTickRateTicksPerSecond` (+ `_tooltip`) |
| Launch switches | `-Dserverreborn.summarySeconds`, `.debugDropDelta`, `.debugFieldDiff`, `.debugTouches`, `.debugDropModule` |
| Unchanged | packet id `0x5347`, trailer magic "D39SYNCG", mod sync's magic it steps over, protocol version 1, digest fields |

## Build: a standalone Kotlin mod with KSP glue

No module does this yet: `pz-feature` wires KSP only for All-in-One features. New convention
`buildSrc/src/main/kotlin/pz-kotlin-mod.gradle.kts`, the standalone counterpart of `pz-feature`:

- applies `pz-kotlin`, KSP, `pz-mod` and `pz-widening` (no `pz-bootstrap`: no Java classes);
- `compileOnly` `:zb-kt-utils` and `:zabric`, `ksp(:zb-kt-utils-processor)`; no `allinone-common` (it is
  unrelocated in All-in-One's jar; a second copy would clash on the shared class loader);
- KSP options from `pzMod`: `dont39.gluePackage` = `javaPkgName`, `dont39.luaPrefix` = `<modId>_`, `zabric.modId`,
  `dont39.feature` and `zabric.glueClass` from the mod id without the `Dont39` prefix (`ServerReborn`,
  `ServerRebornGlue`);
- `checkLoaderRefs` excludes the glue package's own classes;
- jar: the expanded `.classtweaker` into `META-INF/zabric/<modId>.classtweaker`, `META-INF/dont39/**` excluded (as
  `sync-guard/build.gradle.kts` does today); `assembleMod` copies `src/main/lua` to `42/media/lua` and `src/main/media`
  to `42/media`.

The module script keeps only what is its own: version, `Zabric-Main`, `pz-junit`, test dependencies
(`testImplementation(libs.kotlinStdlib)`), `smokeTest` (`GameSmokeTest` with `hooks = true` and `kotlinMod` from
`:zb-kt`, as `dont39-all-in-one` passes it). `GlueProcessor`'s missing-option message names both plugins.

`mod.info`: `require=\ZombieBuddy,\Zabric,\ZabricKotlin`, `loadModAfter=\ZabricKotlin`, `ZBVersionMin=2.3.4`,
`versionMin=42.21.0`, no `javaPreload`.

## Gating: two parts, two gates

Each part has its own gate, named on every hook object (`@Hooks(gate = ...)`), so no facade default applies and a
failure in one part never shuts the other.

- **server-tick-rate**: unchanged `Feature` object, `Feature("server-tick-rate", "Dont39ServerReborn")`, `init()`
  called from `ServerRebornMain`. Reads its option with a mod-local copy of `SandboxOption.int`.
- **network-sync-optimization**: Sync Guard's `Gate` ported as a Kotlin `object : HookGate` with the same contract:
  shut until opened, one undo on the first failure, later errors still logged. Not a `Feature`: `Feature` decides
  lazily from `problem()` and caches the answer, has no explicit open and no undo hook; giving it those is a
  zb-kt-utils API change and a ZabricKotlin release (out of scope). Its log lines take the Feature format above.
- `ServerRebornMain.onMain` logs `loaded`, calls `reportWidening` for the mod, then starts each part in its own
  `try`, so one part's exception is that part's error line only.

## The sync part's switch

Sandbox options are loaded with the save, after Zabric's main entrypoint on a dedicated server. The tick rate already
reads its option at `PerformanceSettings.setLockFPS` called from `GameServer.main` (verified on the Linux server). The
sync part decides at the same point:

- `onMain` on a dedicated server runs the checks it runs today (widening, hook targets, `PacketScope.resolve`,
  `Containers.check`); on success it logs `ready` and waits. It no longer calls `ServerSync.start()` there.
- An `always = true` hook at `setLockFPS` HEAD (its own hook object, the sync gate) on `GameServer.main`'s first call
  reads `Enabled`: true → `ServerSync.start()`, open the gate, log `active ...` (today's line); false → log
  `off: sandbox option ... is false`, nothing changes in the game (no channel change, no trailer, hooks shut).
- `GameServer.main` calls `setLockFPS` (offset 2709) before its loop first handles net data (3060), so no join is
  answered before the decision (javap of 42.21's `GameServer.main`).
- Clients: unchanged. A client opens its side at main and turns active only for a server whose trailer offers the
  protocol; a server with the part off sends none, which the client logs as today for a server without the mod.
- Co-op hosts stay off, as today.

## Port rules (Java → Kotlin)

- Byte-for-byte the same wire and digests: keep explicit widths and signedness (`toByte()`, `and 0xFF`), `ByteBuffer`
  order, `Int` overflow in hashes, the iteration order of maps the digest or wire depends on (`LinkedHashMap` where
  the Java used it, `HashMap` where order did not matter).
- Records → `data class`, sealed interfaces → `sealed interface`, static utility classes → `object`, static mutable
  state → `object` properties with the same `@Volatile`/thread confinement.
- Hook classes → top-level `internal object`s with public handlers (`HookReader.kt` rules); the same targets,
  injection points, `always`, `@Share`/`@Thrown`/`@Self` and the exact-overload targets (`AddItem(String)`,
  `AddItem(String,float)`).
- `LuaGlobals.exposeIfMissing` exposes the generated `ServerRebornLua` object when the join's Lua environment predates
  the jar, as today.
- Idiomatic Kotlin over micro-allocation, also in per-tick code; comments short, why only.
- Tests: the 90 JUnit tests become Kotlin tests with the same cases and expected values (golden digests included);
  `TickScaleTest` moves as is.

## All-in-One and old modules

- `dont39-all-in-one/build.gradle.kts`: drop `:server-tick-rate`; its `workshop.txt` line and the root README's
  feature list go back to `main`'s text. No All-in-One version bump: no release had the feature.
- `settings.gradle.kts`: remove `:sync-guard` and `:server-tick-rate`, add `:server-reborn`; delete both directories.

## Harness and the old task

- `10-24-mp-sync-loss-recovery` is archived with a note that Server Reborn supersedes it and where its open items
  went (`prd.md`, Follow-ups). Its `research/harness/` moves (`git mv`) to this task's `research/harness/` before
  archiving.
- Harness edits: mod id and folder `Dont39ServerReborn`, `-WithServerReborn` instead of `-WithSyncGuard`, the
  `-Dserverreborn.*` switches, the sampler's summary-line pattern, the driver's Lua global name, build command and
  README. The driver mod `SGTestDriver` keeps its name (test-only).

## Specs and docs

- `directory-structure.md`: `pz-kotlin-mod` in the layout, `server-reborn/` replaces `sync-guard/`.
- `zabric-hooks.md`, `kotlin-runtime.md`, `zb-kt-utils.md`: the KSP front end serves features and standalone Kotlin
  mods; when a Kotlin mod may use its own `HookGate` instead of `Feature`; the moved file references.
- `zombiebuddy-patching.md`, `access-widening.md`, `quality-guidelines.md`, `index.md`: references to `sync-guard`
  files point at the Kotlin files.
- `server-reborn/README.md`: both parts, options, switches, how it works (from the tick-rate README and Sync Guard's
  design); `server-reborn/mod/workshop/workshop.txt` from Sync Guard's text plus the tick rate, ZabricKotlin added to the
  requirements.

## Rollback

Each step is its own commit on `feat/server-reborn-46640e`. The old modules are deleted only after the new module
builds and its tests pass; reverting those commits restores them. Nothing is published.
