# Design: zb-kt on Zabric

Decisions D1–D9 are in `prd.md`. This file says how; code is the source of truth once written.

## 1. Layers and shipping

```
ZombieBuddy (agent)                       third party
  └─ Zabric            Workshop item 1     Java only; premain widening of every installed mod's list,
       │                                   preload registration service, restart notice
       └─ ZombieBuddyKotlin  Workshop item 2  preload; Kotlin swap at premain; ships zb-kt-utils runtime
            └─ Dont39 All-in-One  Workshop item 3818388200  (and any third-party Kotlin mod)
```

| Mod | Jar path | Preload | require / loadModAfter |
|-----|----------|---------|------------------------|
| Zabric | `media/java/Zabric.jar` (was `client/`, D7) | yes | ZombieBuddy |
| ZombieBuddyKotlin | `media/java/ZombieBuddyKotlin.jar` | yes (new) | ZombieBuddy, Zabric |
| Dont39AllInOne | `media/java/client/Dont39AllInOne.jar` | no | ZombieBuddy, Zabric, ZombieBuddyKotlin, target mods |

- `zabric` gets its own `mod/workshop/` (deployWorkshop then stages it as its own item); `dont39-all-in-one` stops
  deploying it. Zabric's jar no longer receives All-in-One's merged list.
- `zb-kt`'s jar also takes `zb-kt-utils`' classes (compiled against Zabric). All-in-One stops bundling them
  (`internalLibs` goes away); its features keep `compileOnly` on `zb-kt-utils`.

## 2. Public contract (Zabric)

Kept small and versioned by the list header so publishing tooling later (D2) needs no runtime change.

- **Widening list**: a jar entry `META-INF/zabric/<modId>.classtweaker`, header `zabric-classtweaker v1`, same
  directives and rules as today (`ClassTweaker.java`: `accessible`/`mutable`; classes, fields, constructors only).
  `<modId>` is the owning mod's id; one list per mod (All-in-One merges its features' lists into one).
- **Widening status**: `AccessWidener.problem(modId, host)` → null when the list in `host`'s own jar was applied at
  premain and every directive verifies; otherwise the reason. Read through `host`'s CodeSource, not the class loader:
  all mod jars share the system loader, so a resource lookup could return another mod's or another copy's list.
- **Preload registration**: `Preload.register(modId, preloadedThisLaunch)` from the mod's own Java `Main` (opt-in, D5).
  The mod also needs `javaPreload=true` in `mod.info`, `ZB-Preload: true` in its jar manifest (ZombieBuddy's
  requirements) and `require=\Zabric`. Registration only ever happens from code ZombieBuddy already approved and ran.
- **Rules for preload mods** (documented on the Workshop page and in spec): `PreMain` is Java and touches no game
  class (Zabric may not have installed its transformer yet; ZombieBuddy's preload order is not ours, see PRD).

## 3. Zabric at premain: scan and widen (D3)

1. Find mod folders without the game (Steam and the game's argument parsing are not up):
   - Steam installed items: `<cwd>/../../workshop/content/108600/*/mods/*` (client, game in a Steam library) and
     `<cwd>/steamapps/workshop/content/108600/*/mods/*` (dedicated server install); every existing one is scanned.
   - Cache dir: `-cachedir=` from the game's arguments (`sun.java.command`), default `<user.home>/Zomboid`; scan
     `<cache>/mods/*` and `<cache>/Workshop/*/Contents/mods/*`.
2. In each mod folder, every jar under any version dir's `media/java/` (version-dir choice needs the game version,
   which needs game classes: over-including is accepted by D3). Read only the `META-INF/zabric/*.classtweaker`
   entries; never load a class from these jars.
3. Parse each list separately. A list that does not parse is that mod's problem only. Directives are unioned into one
   ByteBuddy `ModifierAdjustment` transformer as today; a class already loaded is a problem for every list naming it.
4. Record per mod id: the content hashes found, entries, and problem. Nothing is logged at premain (the game's log is
   not up); `report()` at MAIN logs one summary line (`access widener: ok (N mods, M directives)` or the problems).

Safety net for missed folders (custom paths, `-modfolders`): `AccessWidener.problem(modId, host)` compares the hash of
the list in the jar the game actually uses with the hashes applied. A miss for a jar inside a scanned folder means it
appeared or changed after game start: the mod goes on the restart list (section 4). A miss for a jar outside every
scanned folder is an error naming the jar path. Never silence.

## 4. Zabric preload registration (D5, generalized `setup/Registration.java`)

- Paths come from the mod folder the game uses this session (`ChooseGameInfo`), as today.
- An existing entry is current when its paths are this copy, **or** its `mod.info` and jar exist and the jar is
  byte-identical (sha256) to this copy. This keeps a client and a dedicated server on one machine (shared
  `~/.zombie_buddy/config.json`) from overwriting each other while both have the same version.
- Zabric keeps its own entry first in `preload_mods` whenever it writes the file.
- Not preloaded this launch → the mod goes on the restart list; one log line per mod
  (`[Zabric] <modId> registered as a ZombieBuddy preload mod ...; restart once`).
- Restart notice (D10): one client Lua dialog on `OnMainMenuEnter` with generic text and the list of mods that need a
  restart to take effect, by display name (`mod.info` `name`, mod id if absent). The list holds:
  - preload mods registered or re-registered this launch, Zabric itself included;
  - mods whose widening list in the jar the game uses was not applied at premain only because that jar was not there
    or differed at game start (subscribed or updated while the game ran); other widening problems are errors, not a
    restart reason.
  Java exposes the list to Lua (`Zabric_restartMods()` returning a Kahlua table, replacing `Zabric_restartNeeded`).
  A mod enabled only later in the session (save or server mod set) is added when it loads; if the main menu was already
  shown, the dialog shows again on the next `OnMainMenuEnter`. Dedicated server: the same list as one log line (the Lua
  is under `client/`).

## 5. ZombieBuddyKotlin (D6, D9)

- `PreMain.premain` (Java): read `stdlib.properties`, verify the stdlib checksum, refuse if any `kotlin.*` class is
  loaded, append to the bootstrap search. Record the outcome; no logging, no game classes.
- `Main.main` (Java): `Preload.register("ZombieBuddyKotlin", PreMain.ran())`; then if premain swapped, run the
  self-check and log `ready: ...`; else log `not ready: <reason>` (not preloaded yet → "restart the game once").
- `ZombieBuddyKotlin.isReady()` (Java, public): true only after a premain swap and a passing self-check. The
  `Instrumentation` read moves to Zabric (one copy); ZombieBuddyKotlin calls Zabric for it.
- The readiness gate (D9) is the only readiness check in dependents, and it is generated or one fixed line:
  - KSP glue: every generated `@Patch` advice and Lua bridge function starts with
    `if (!ZombieBuddyKotlin.isReady()) return <zero>` (Unit → return; `skipOn` Boolean → `false`; Lua return
    values: zero for primitives, `null` for nullable; a non-null reference return becomes a processor error).
  - A Kotlin mod's `Main.main` starts with the same check (All-in-One's `Main.kt`). Spec states it as the one allowed
    readiness check.
  - The gate code is a static call and a branch, so it links on the game's Kotlin 1.4.10.
- `workshop.txt`: describe the premain swap, the one restart, the Zabric requirement, and for authors: require both
  mods, the generated glue gates itself, start `Main` with `ZombieBuddyKotlin.isReady()`.

## 6. zb-kt-utils as public runtime (D2)

- `HookTargets.kt`: no hard-coded `com.dont39.pz.allinone`. The glue listing the processor already generates
  (`META-INF/dont39/glue/<Feature>.txt`, renamed `META-INF/zbkt/glue/<Feature>.txt`) ships in the mod jar and names
  the patch classes; `checkHookTargets` reads it from the feature's own jar (CodeSource).
- `Widening.kt` / `Feature`: `Feature(name, modId, widened)` (the second argument already is the mod id); the widening
  check calls `AccessWidener.problem(modId, facadeClass)`. Zabric is a hard dependency of ZombieBuddyKotlin, so no
  probe by class name.
- KSP options stay unpublished (D2); `pz-feature.gradle.kts` keeps All-in-One's values but takes them from the
  aggregating mod's settings rather than literals, so a second mod in this repo can set its own.

## 7. Build changes

- `dont39-all-in-one`: `mergeClassTweakers` output goes into its own jar as
  `META-INF/zabric/Dont39AllInOne.classtweaker`; the jar keeps excluding feature-level build data.
- Feature modules keep their source list at `src/main/resources/META-INF/dont39/<module>.classtweaker` (build input
  only); the header in source lists becomes `zabric-classtweaker v1`.
- `checkBootstrapClasses` stays on `zb-kt` (Java classes incl. `PreMain`); add the same check to `zabric` (it must
  never reference `kotlin/`).
- Versions: Zabric 0.2.0, ZombieBuddyKotlin 0.3.0, All-in-One 0.4.0.

## 8. Tests

- Client `smokeTest` (All-in-One): deploys all three items; a launch that registered something fails with "run again";
  then checks Zabric's widening line, `[ZombieBuddyKotlin] ready`, the gate never closed (no `not ready`), feature and
  `Transformed:` lines as today, restart notice `not needed`.
- New `serverSmokeTest` (in `zb-kt`, which loads on servers): starts the dedicated server from `pzServerDir`
  (`gradle.properties`) the way its `ProjectZomboid64.json` does, with ZombieBuddy's `exit_after_game_init`
  (fires on servers too, `Patch_GameServer`) and a test server name whose ini enables `Zabric;ZombieBuddyKotlin`.
  Run with `-cachedir=<build dir>` and the mods deployed there, so the server uses a different copy than the client:
  first run registers (expected), second run must show widening ok, Kotlin ready, and no re-registration of the
  client's identical entries afterwards.

## 9. Migration and release order

1. Publish Zabric as a new item, then ZombieBuddyKotlin (first upload), then update All-in-One without its Zabric
   folder and with both items as required items (Workshop page settings are set by hand in Steam).
2. Existing `preload_mods` entry for Zabric points into All-in-One's folder; after the update that path is gone,
   ZombieBuddy drops the entry, Zabric re-registers from its own item, one restart.

## Rollback

Revert the commits; republishing All-in-One with the Zabric folder restores the old setup. Players' `preload_mods`
entries for ZombieBuddyKotlin then point at a jar without `ZB-Preload` and ZombieBuddy removes them by itself
(`Loader.preloadMods`).
