# Load-order independence for Zabric

## Goal

Mods built on Zabric work whatever their position in the player's mod list and in ZombieBuddy's preload list, the way
Fabric mods on Minecraft do not care about load order. Mod authors write no order workarounds.

## Background

### How Fabric gets there

1. **Discover everything before running anything**: all mods' metadata read and all mods on one class loader before
   any mod code runs.
2. **Declarative**: mixins, access wideners and entrypoints are metadata, collected up front.
3. **Lazy transforms**: applied at target class load with every declaration known; conflicts settled by priority.
4. **Phased lifecycle**: entrypoints called by phase (`preLaunch`, `main`, ...); every mod finishes a phase before any
   starts the next; order within a phase is undefined.
5. **Late binding** between mods (events, registries, lookups after loading).

### Decisions

- Scope: Zabric gets its own phased entrypoints (point 4). Rejected: only hiding the handshake (authors would still
  care about order); full Fabric with dependency/version checks and events (too large for one task).
- Compatibility: Zabric is unpublished, so the old registration API (`Preload.register`, `zabric.preload.<modId>`
  system property) is removed without a deprecation path.
- Explicit class-path dependencies are declared: a mod whose classes in `javaPkgName` link another mod's jar lists it
  in `loadModAfter`, because ZombieBuddy loads those classes and applies patches as it reaches each jar, and drops
  failed loads silently. Dont39 All-in-One keeps `\ZombieBuddyKotlin`; ZombieBuddyKotlin lists `\Zabric` (its
  `Main` links Zabric on every launch, also Zabric's first). Other Zabric-based mods need no Zabric entry: it is
  preloaded on every launch that runs entrypoints. Entrypoint order stays free (user decision). A gate that links
  only the dependent's own jar was built and removed as overkill.
- ZombieBuddyKotlin is not a preload mod (user decision): it swaps Kotlin only from its ZombieBuddy `Main`, which
  ZombieBuddy reaches before any dependent (they `require` it). Every launch is the same: no registration, no restart.
  Dependents never check readiness. Other ways the swap could fail are treated as not happening: if one does, it is
  an error, never a degraded mode. Zabric's preLaunch phase stays for other mods; no mod in this repo uses it.

### Order dependencies today

| # | Dependency on order | Current workaround | Anchor | This task |
|---|---|---|---|---|
| O1 | Zabric's premain must run before every other preload mod, so its widening transformer is in place before they load game classes | Zabric rewrites ZombieBuddy's `preload_mods` with itself first | `zabric/.../loader/PreloadConfig.java:119-136`; ZB `Loader.preloadMods` (`Loader.java:401-460`) | stays, internal to Zabric |
| O2 | A dependent's premain that uses Zabric classes needs Zabric's jar already on the classpath | relies on O1; else `NoClassDefFoundError` and "restart once" | `zb-kt/.../PreMain.java` | removed |
| O3 | ZombieBuddy runs `Main` in mod-list order, adding each jar as it goes, so a dependent's `Main` may run before Zabric's | per-dependent system-property handshake drained by Zabric's `Main` | `zabric/.../preload/Preload.java` (`REQUEST_PREFIX`, `registerRequested`); `zb-kt/.../PreloadRequest.java`; `zabric/mod/workshop/workshop.txt` | removed |
| O7 | A dependent's `Main` needs a library's `Main` to have run first | `loadModAfter` | `dont39-all-in-one/.../Main.kt` → `ZombieBuddyKotlin.isReady()`, set by `report()` from ZBK's `Main` | removed |
| O4 | Classes loaded before Zabric's premain keep their access | reported per list | `zabric/.../widener/AccessWidener.java:175-193` | out of scope |
| O5 | A mod's game-start code runs only after registration and a restart | restart list and notice | `Preload.update`, `RestartNotice` | out of scope (the jar must be on the classpath at JVM start) |
| O6 | A feature that patches another mod must load after it | `loadModAfter` | `.trellis/spec/mod/zombiebuddy-patching.md:73` | out of scope |

Already order-free: access widening (every installed mod's list read at premain, applied at class load:
`ModFolders.java`, `AccessWidener.apply`); ZombieBuddy patches (retransform loaded classes, catch later loads:
`PatchEngine.java:181-240,625-645`).

### Technical findings

- `-agentlib:zbNative` proxies `instrument.dll` (`external-repos/ZombieBuddy/c/windows/zbNative.c`): ZombieBuddy is a
  normal Java agent on the system class loader, like every mod jar it adds and Zabric. Agent premain, and so every
  preload, finishes before the game's main class runs.
- Game main classes: client `zombie.gameStates.MainScreenState` (`ProjectZomboid64.json`), dedicated server
  `zombie.network.GameServer`.
- ZombieBuddy's MAIN phase is one `Loader.loadMods(List)` call from its `ZomboidFileSystem.loadMods` patch
  (`patches/Patch_ZomboidFileSystem.java`). It records every mod's decision in `g_jarLoadStatus` first, then per
  approved mod adds the jar, runs `Main`, applies patches (`Loader.java:~860-940`). It runs again on a mod list change;
  each `Main` runs once per session.
- A preloaded jar the player then denies stays on the classpath; ZombieBuddy skips its `Main`. `MF_ACTIVE` is sticky
  from the preload (`Loader.java:886-889`), so "active" is not "approved this pass".
- ZombieBuddy has no callback for "preload done" or "mod list loaded" (`Callbacks.java`).

Design: `design.md`. Plan: `implement.md`.

## Requirements

- R1 Declared entrypoints: a mod declares Zabric entrypoints in its jar metadata and needs no ZombieBuddy
  `PreMain`/`Main` for them.
- R2 preLaunch: every declared preLaunch entrypoint is called once per launch, after ZombieBuddy has preloaded every
  jar and before the game's main method runs, on client and dedicated server. No game classes, no game log.
- R3 main: after each ZombieBuddy MAIN pass, the main entrypoint of every mod ZombieBuddy approved and loaded in that
  pass is called, once per session per mod; never for a mod ZombieBuddy skipped or denied.
- R4 Order within a phase is undefined. A failing entrypoint is logged with mod, phase and class, and the others of
  that phase still run.
- R5 A mod declaring a preLaunch entrypoint is kept preloaded by Zabric at the main phase (today's `Preload.update`
  rules and restart list), with no request from the mod. The mod id comes from ZombieBuddy's record of the jar, so a
  jar cannot register another mod. On a launch where Zabric itself was not preloaded, every such mod approved in that
  pass is still registered, so one restart covers all.
- R6 Dependents always run on the current Kotlin and never check readiness: no `isReady()` in generated glue or in
  main entrypoints. ZombieBuddyKotlin swaps from its ZombieBuddy `Main`, before ZombieBuddy reaches any dependent's
  jar, on every launch. A swap failure is logged as an error naming the cause.
- R7 Migrate this repo: ZombieBuddyKotlin (swap in its ZombieBuddy `Main`; no preload: no `javaPreload`, no
  `ZB-Preload`, no `Zabric-PreLaunch`), Dont39AllInOne (`Main` → main entrypoint, no readiness check). Remove
  `PreloadRequest`, the handshake and its author text.
- R8 ZombieBuddy specifics (main hook, per-pass decisions) stay in Zabric's `loader/` package. If a hook cannot be
  installed, Zabric logs an error naming it and the versions and runs no entrypoints that session.
- R10 Mods that use only ZombieBuddy load and behave as without this change: Zabric acts only on jars declaring
  `Zabric-*` attributes, and its hooks never throw into the game's `main` or ZombieBuddy's `loadMods`.
- R9 Update `zabric/mod/workshop/workshop.txt` (author contract, Load order line), the Load order lines of the other
  Workshop texts, and `.trellis/spec/mod/` (`access-widening.md` Zabric and Preload Registration,
  `kotlin-runtime.md` Library and Dependent modules, `zombiebuddy-patching.md` Loading and Switch Checklist).

## Acceptance criteria

- AC1 (R3, R4, R6) The client `smokeTest` passes with Dont39 All-in-One moved above Zabric in the game's mod list: ZBK
  ready, All-in-One features init, a log line per entrypoint called in its phase.
- AC2 (R2–R4) Both smoke tests pass in the default order.
- AC3 (R6) Every launch, the first one included, logs ZombieBuddyKotlin's ready line from its `Main` and no
  registration or restart for it; a leftover ZombieBuddyKotlin entry in ZombieBuddy's `preload_mods` does not break
  the launch. Server smoke test covers it.
- AC10 (R6) No generated glue class and no main entrypoint references `ZombieBuddyKotlin.isReady`.
- AC4 (R3) A preloaded mod denied in ZombieBuddy's dialog (Shift at load): its main entrypoint is not called.
- AC5 (R4) An entrypoint that throws is logged naming mod and phase; the phase's other entrypoints still run.
- AC6 (R8) `./gradlew build` passes, including `checkLoaderRefs` (no `me.zed_0xff.*` outside `zabric/.../loader/`)
  and `checkBootstrapClasses`.
- AC7 (R7) No `zabric.preload.` property or `PreloadRequest` remains in the repo (outside `build/` and task archives).
- AC8 (R9) Specs and Workshop texts describe the entrypoint contract, ask for no load order for entrypoints, and
  name `loadModAfter` only where a mod's classes link another mod's jar.
- AC9 (R10) Client `smokeTest` with at least one installed ZombieBuddy-only Java mod enabled: its `Main` and patches
  still run (ZombieBuddy's "invoked successfully" / "Transformed" lines for it), and with a forced failure in Zabric's
  main hook the game still reaches init and that mod still loads.

## Out of scope

- O4, O5, O6; an event bus.
- Dependency/version declaration and checks: `mod.info` `require` is enough, since Workshop subscriptions update
  automatically and mismatched installed versions are rare (user decision).
- Changing ZombieBuddy.
- Lua load order.
