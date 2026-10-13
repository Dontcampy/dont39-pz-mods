# Design: Zabric phased entrypoints

## 1. Author contract

A mod declares entrypoints in its jar manifest (where `ZB-Preload` already lives, set from Gradle):

| Attribute | Value | Phase |
|---|---|---|
| `Zabric-PreLaunch` | binary name of a class implementing `com.dont39.pz.zabric.entrypoint.PreLaunchEntrypoint` | preLaunch |
| `Zabric-Main` | binary name of a class implementing `com.dont39.pz.zabric.entrypoint.MainEntrypoint` | main |

- Instance: Kotlin `object` (static `INSTANCE` of the declared class) or a public no-arg constructor.
- `Zabric-PreLaunch` also means "keep me preloaded": the mod still needs `javaPreload=true` and `ZB-Preload: true`
  (checked by `PreloadConfig.checkPreloadable` as today). Zabric registers it; the mod asks for nothing.
- preLaunch code: Java only, no `kotlin.*`, no game classes, no game log (same as premain today). A preLaunch
  entrypoint keeps what it wants to report and reports it from its main entrypoint.
- The mod id is never declared: Zabric takes it from ZombieBuddy's record of the jar, so a jar cannot speak for another
  mod.
- A mod using Zabric entrypoints has no ZombieBuddy `PreMain`/`Main` in `javaPkgName` for that work.

Why the manifest and not a `META-INF/zabric/<modId>.*` file: the id comes from ZombieBuddy, so a fixed place is enough,
and the build already writes manifest attributes.

## 2. Phases and hook points

```
JVM start
 └ ZombieBuddy Agent.premain → preloadMods (Zabric first: O1)
     └ Zabric PreMain.premain: AccessWidener.install(); Lifecycle.install()  ← hooks below
 game main class loads (MainScreenState / GameServer)
 └ main() enter ─ advice → Lifecycle.preLaunch()          [preLaunch phase, once]
 ... game init ...
 ZomboidFileSystem.loadMods → ZB Loader.loadMods(List)   (ZB runs Mains, applies patches)
 └ Loader.loadMods exit ─ advice → Lifecycle.main(pass)   [main phase, every pass]
```

- **preLaunch hook** (game classes, not ZombieBuddy; outside `loader/`): Byte Buddy `Advice.OnMethodEnter` on
  `zombie.gameStates.MainScreenState.main(String[])` and `zombie.network.GameServer.main(String[])`. Neither is loaded
  at premain, so a normal (non-retransforming) transformer like the widener's catches the load. If either is already
  loaded at premain, that is an error (reported at main).
- **main hook** (ZombieBuddy; in `loader/`): `Advice.OnMethodExit` (normal return only) on
  `me.zed_0xff.zombie_buddy.Loader.loadMods(List)`, installed with retransformation (the class is loaded). Same
  class loader as Zabric (`zbNative` proxies `instrument.dll`; ZombieBuddy is a normal Java agent), so the advice
  calls Zabric directly.
- The main pass list is the advice's argument; ZombieBuddy runs `loadMods` again on a mod list change, and each mod's
  main entrypoint runs once per session.
- Both advices run inside code every mod depends on (the game's `main`, ZombieBuddy's `loadMods`), so they never throw
  out: the advice body is one call into Zabric that catches `Throwable`, keeps it, and Zabric reports it at its next
  log point (§6). A Zabric failure must not stop the game or ZombieBuddy's loading of other mods.

## 3. Which mods each phase covers (loader seam)

New `loader/` API (names indicative):

- `Loader.preloadedJars()` → `(modId, jar)` of every jar ZombieBuddy preloaded this launch.
- `Loader.loadedThisPass(List<String> mods)` → `(modId, jar)` of every mod ZombieBuddy approved and loaded in the pass
  that just ended.
- `Loader.onModsLoaded(Consumer<List<String>>)` installs the main hook.

Both reads come from ZombieBuddy's `g_jarLoadStatus` (`JavaModLoadState`: `id`, `jarPath`, `flags`, `reason`) by
reflection, like `g_instrumentation` today (ZombieBuddy is loaded before Zabric, so Zabric cannot widen it).
`loadedThisPass` takes entries whose `id` is in the pass and whose `reason` is `"loaded"`; never `MF_ACTIVE`,
which stays set on a preloaded jar the player then denies (`Loader.java:886-889`). Any shape mismatch throws with the
ZombieBuddy version, like `notExposed()`.

## 4. Lifecycle (new package `com.dont39.pz.zabric.entrypoint`)

- `preLaunch()`: for each preloaded jar with `Zabric-PreLaunch`, instantiate and call. Records which mods ran. Errors
  are kept per mod (no log yet).
- `main(pass)`: for each mod loaded this pass:
  1. If it declares `Zabric-PreLaunch`: preload registration exactly as `Preload.update` today, with
     `preloadedThisLaunch` = its preLaunch ran this launch (restart list, messages unchanged).
  2. If it declares `Zabric-Main` and it has not run this session: call it.
  Then log the preLaunch errors kept so far, and one line per entrypoint called (`[Zabric] <phase>: <modId>`), for the
  smoke tests.
- Each call is isolated: a `Throwable` is logged naming the mod, phase and class, and the others continue (R4).
  The failing mod is that mod's problem; Zabric does not disable anything else.
- Manifest read from the jar path ZombieBuddy recorded; class loaded through the system class loader (the jar is on
  it).

## 5. Launch where Zabric was not preloaded

Zabric's own `PreMain` did not run, so no hook exists and no entrypoint runs this launch (this is the existing
not-yet-preloaded case). Zabric's ZombieBuddy `Main` (runs inside the first pass):

- registers Zabric itself (as today);
- registers every mod ZombieBuddy approved in this pass that declares `Zabric-PreLaunch`, read from
  `loadedThisPass` (ZombieBuddy writes all of the pass's decisions before loading any jar, `Loader.java:~860-940`), so
  one restart still covers all mods, as the handshake did;
- logs that entrypoints run from the next launch on.

Main entrypoints are not called on that launch. ZombieBuddyKotlin still installs Kotlin from its ZombieBuddy `Main`
(§7).

## 6. Hooks fail

If the preLaunch or main hook cannot be installed, or preLaunch did not run before the first main pass: Zabric logs an
error naming the hook and the ZombieBuddy/game version at its `Main` and runs no entrypoints this session (no partial
lifecycle). Same reporting style as `AccessWidener.report()`.

## 7. Migrations

- **Zabric**: delete `Preload.REQUEST_PREFIX`, `registerRequested`, the public `register` (registration becomes
  internal to the lifecycle). `PreMain`/`Main` stay as Zabric's own ZombieBuddy bootstrap (O1).
- **ZombieBuddyKotlin**: not a preload mod. It swaps Kotlin only from its ZombieBuddy `Main`, which ZombieBuddy
  reaches before any dependent (they `require` it; `loadModAfter=\ZombieBuddy,\Zabric` because `Main` links Zabric's
  `Loader.instrumentation()`). `Main` runs the self-check and logs the ready line or the error. `PreMain`,
  `PreloadRequest`, `isReady()` and its readiness API are deleted: dependents never check readiness, and the generated
  glue has no gate (user decisions, see PRD).
- **Dont39AllInOne**: `Main` becomes a `MainEntrypoint` (Kotlin `object`); manifest attribute from its build script.
- The build sets the manifest attributes (`tasks.jar { manifest { attributes(...) } }` in each module).

## 8. Compatibility

- Zabric is unpublished: the old registration API is removed without a deprecation path (user decision).
- ZombieBuddy unchanged; `ZBVersionMin` unchanged unless the seam reads need a newer ZombieBuddy (check at
  implementation against 2.3.4).
- Release order: Zabric, then ZombieBuddyKotlin, then All-in-One (each needs the one before).

## 9. Mods that use only ZombieBuddy

Zabric's lifecycle acts only on jars with a `Zabric-*` manifest attribute; for every other mod ZombieBuddy behaves as
without Zabric:

- Their `PreMain`, `Main` and `@Patch` classes run exactly as ZombieBuddy runs them; Zabric changes no decision,
  order or classpath entry.
- Unchanged from today: Zabric keeps itself first in `preload_mods` and leaves the others' relative order
  (`PreloadConfig.withZabricFirst`); widening applies only to listed members.
- Timing: Zabric main entrypoints run after the whole pass, so after every ZombieBuddy-only `Main` of that pass. A
  ZombieBuddy-only mod that needs a Zabric-based mod initialized during its own `Main` would see it not yet
  initialized. Contract text: Zabric-based mods publish nothing that others need before the main phase ends.
- Their `@Patch` on the same game `main` or on ZombieBuddy's `loadMods` stacks with Zabric's advice (ordinary Byte
  Buddy chaining); Zabric's widener and preLaunch transformers are non-retransforming, so a later ZombieBuddy
  retransform keeps their output.

## 10. Risks

- The seam reads more ZombieBuddy internals (`g_jarLoadStatus`, `loadMods`). Recheck after every ZombieBuddy update; add
  them to the access-widening spec's "after a ZombieBuddy update" line.
- The swap runs when ZombieBuddy reaches ZombieBuddyKotlin's jar in its MAIN pass. Anything that loads `kotlin.*`
  before that point beats it: a preload mod's `PreMain`, or a Kotlin mod listed above ZombieBuddyKotlin that does not
  `require` it. ZombieBuddyKotlin then logs "Kotlin was already loaded" as an error. Accepted (user decision): no known
  ZombieBuddy-only mod uses Kotlin, and mods that use ZombieBuddyKotlin `require` it, so the game's mod manager saves
  them after it. The vanilla game never loads Kotlin.
