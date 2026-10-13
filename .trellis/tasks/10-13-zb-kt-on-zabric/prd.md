# Build zb-kt on Zabric

## Goal

Turn Zabric and the zb-kt series into a small Fabric-style platform for ZombieBuddy Java mods: Zabric is the
loader layer (access widening for any mod, preload registration), ZombieBuddyKotlin is the Kotlin layer on top of it
(Kotlin from premain, the shared `zb-kt-utils` runtime), and Dont39 All-in-One is one consumer of it. Other authors
can use the runtime; publishing the build tooling comes later.

## Background (repository evidence)

- Today the layering is reversed: Zabric is Java-only and knows nothing of Kotlin; `zb-kt-utils` probes Zabric by
  class name (`zb-kt-utils/src/main/kotlin/com/dont39/pz/zbktutils/Widening.kt`); `zb-kt` does not depend on Zabric.
  Both mods read ZombieBuddy's package-private `Loader.g_instrumentation` with duplicated code
  (`ZombieBuddyKotlin.instrumentation()` in `zb-kt/src/main/java/com/dont39/pz/zbkt/ZombieBuddyKotlin.java`,
  `AccessWidener.instrumentation()` in `zabric/src/main/java/com/dont39/pz/zabric/widener/AccessWidener.java`).
- ZombieBuddyKotlin swaps the stdlib at MAIN (`Main.main` → `ZombieBuddyKotlin.install`), so it must load right after
  ZombieBuddy and refuses if a `kotlin.*` class is already loaded. Zabric runs at PREMAIN (`PreMain.premain`).
- Zabric applies one list baked into its jar by `dont39-all-in-one`'s build (`PreMain.LIST`) and ships inside
  All-in-One's Workshop item (`3818388200`). ZombieBuddyKotlin has no Workshop id yet, so its packaging can change
  freely. `zb-kt-utils` is bundled into All-in-One's jar (`internalLibs`); all Java mod jars share the system class
  loader, so it can ship in only one jar. `HookTargets.kt` hard-codes `com.dont39.pz.allinone`; `Widening.kt` reports
  one global state. The KSP glue options are per feature (`buildSrc/src/main/kotlin/pz-feature.gradle.kts`) with
  All-in-One's values as literals. Build-side widening lives only in `buildSrc` (`PzTargets.kt`,
  `ClassTweakerTasks.kt`).
- ZombieBuddy (`external-repos/ZombieBuddy`, `Loader.java`, `ZBSVerifier.java`, `Config.java`, `Agent.java`):
  - At PREMAIN it knows only `preload_mods` in `~/.zombie_buddy/config.json`, not the mod list, and runs them in that
    map's order, which it rewrites itself. A preload entry's key must be the real mod id (injected into the mod list).
  - Its own approval dialog offers preload only to validly signed jars. A self-registered unsigned jar passes the
    PREMAIN check under the default `allow_unsigned_mods=true` with no check that its current hash was approved
    (**approval gap**): after a Workshop update the new code runs at premain before the MAIN approval prompt. Signing
    is off because signed jars are blocked for players whose ZombieBuddy cannot reach Steam (commit `a80ca29`).
  - Mods can load more than once per session (hook on `ZomboidFileSystem.loadMods`, mod sets for saves and servers),
    so a Java mod can start mid-session.
  - On a launch where ZombieBuddyKotlin did not swap, ZombieBuddy still loads its dependents, calls their `Main` and
    inlines their patches (e.g. `ViewpointBloodFxFixPatches` into BloodFX's `render`); they then run on the game's
    Kotlin 1.4.10 and can fail with linkage errors inside hooked methods.
  - Dedicated servers skip jars under `media/java/client/`. Client and server on one machine share `config.json`.
  - `exit_after_game_init` also fires on servers (`patches/Patch_GameServer.java`).
- The game finds mod folders in `ZomboidFileSystem.getAllModFolders` (default order `workshop,steam,mods`: staged
  `Workshop/`, Steam installed items, cache-dir `mods/`; `-cachedir`, `-modfolders`). At premain Steam is not up and the
  arguments are not parsed, so Zabric must derive the folders itself.
- `zb-kt/mod/workshop/workshop.txt` tells authors to call `ZombieBuddyKotlin.isReady()`, which does not exist.
- Dedicated server installed at `E:/SteamLibrary/steamapps/common/Project Zomboid Dedicated Server` (ZombieBuddy,
  `policy=allow-all`, main class `zombie/network/GameServer`, game jar `java/projectzomboid.jar`).

## Decisions (user, 2026-10-13)

- D1: A + B — ZombieBuddyKotlin is layered on Zabric, and Zabric + zb-kt become a platform other mods build on.
- D2: runtime public, build tooling unpublished. Zabric applies any mod's own `.classtweaker`; `zb-kt-utils` ships once,
  in ZombieBuddyKotlin, as public API; the KSP processor and widening Gradle logic stay in this repo. Publishing them
  later must not need a runtime contract change.
- D3: at premain Zabric applies the lists of every installed mod (Workshop content and local folders), enabled or not.
  No restart on a mod-list, save or server mod-set change. Accepted: over-widening, a scan at each start, locating
  folders without the game.
- D4: two separate Workshop items, Zabric and ZombieBuddyKotlin; ZombieBuddyKotlin requires Zabric; both are preload.
  All-in-One's item no longer carries Zabric.
- D5: preload registration is a public Zabric service for any mod that opts in. The approval gap is accepted for every
  such mod; signing stays off.
- D6: Kotlin is installed only at premain; no MAIN-phase swap. Not preloaded yet → ZombieBuddyKotlin reports not ready
  with the reason and the restart notice shows.
- D7: both mods support dedicated servers; Zabric's jar moves to `media/java/`; on a server a needed restart is logged.
- D8: server support is verified on the real dedicated server in this task.
- D9: on a launch without the swap, dependents are stopped by a readiness gate in generated code (KSP hook and Lua
  glue) and at the top of a mod's `Main` — the only readiness check dependents have. Off, not degraded.
- D10: the restart dialog lists the mods that need a restart to take effect.

## Requirements

- R1 (D4, D7): Zabric, ZombieBuddyKotlin and All-in-One build and deploy as three Workshop items; Zabric and
  ZombieBuddyKotlin jars load on client and dedicated server; ZombieBuddyKotlin requires Zabric; All-in-One requires
  both and no longer contains Zabric or `zb-kt-utils`.
- R2 (D2, D3): a mod gets members widened by shipping `META-INF/zabric/<modId>.classtweaker` in its jar. At premain
  Zabric applies the lists of all installed mods; a bad list affects only its own mod; each mod can ask whether its
  list is in effect and gets a reason when not; a jar the game uses outside the scanned folders is reported as an
  error naming it.
- R3 (D5): a mod opts into preload registration from its Java `Main`; Zabric registers it in ZombieBuddy's
  `preload_mods` from the folder the game uses; an entry pointing at a byte-identical copy counts as current; Zabric's
  own entry stays first.
- R4 (D6): ZombieBuddyKotlin swaps the stdlib at premain only, runs its self-check at MAIN and logs `ready` or
  `not ready: <reason>`; `ZombieBuddyKotlin.isReady()` is public Java API.
- R5 (D9): generated hook and Lua glue and All-in-One's `Main` do nothing while `isReady()` is false; the gate links on
  Kotlin 1.4.10.
- R6 (D10): on the client, a dialog on the main menu lists, by display name, every mod that needs a restart to take
  effect (preload mods registered this launch; mods whose list changed or appeared after game start); it shows again if
  a mod joins the list later in the session. On a server the same list is one log line.
- R7 (D2): `zb-kt-utils` has no All-in-One literals (`HookTargets.kt`, widening per mod); `Instrumentation` access
  exists once, in Zabric.
- R8: All-in-One's features behave as before once both preload mods are registered.
- R9: specs and Workshop texts describe the platform contract, the restart, the approval gap and the readiness gate;
  the stale `isReady()` text is fixed.

## Acceptance Criteria

- [ ] AC1 (R1, R7): `./gradlew build assembleMod` passes (JDK 25). `jar tf`: All-in-One's jar has
      `META-INF/zabric/Dont39AllInOne.classtweaker` and no `com/dont39/pz/zbktutils/`; ZombieBuddyKotlin's jar has
      `com/dont39/pz/zbktutils/`; Zabric's jar has no list and no `kotlin/` reference; no
      `com.dont39.pz.allinone` literal in `zb-kt-utils/src`; one `g_instrumentation` read in the repo.
- [ ] AC2 (R2–R6, R8): with the Zabric and ZombieBuddyKotlin entries removed from `~/.zombie_buddy/config.json`, the
      first client launch logs both registrations, `[ZombieBuddyKotlin] not ready: ...restart...`, no exception from
      All-in-One's glue or `Main`, and the main-menu dialog lists Zabric and ZombieBuddyKotlin by name; the second
      launch passes `smokeTest` (widening ok, `[ZombieBuddyKotlin] ready: ...`, feature and `Transformed:` lines as
      before, no dialog).
- [ ] AC3 (R2): a scratch mod with a list naming a missing member logs that mod's problem while All-in-One's widening
      stays ok; a jar whose list was added after start puts that mod on the restart list.
- [ ] AC4 (R1, R3, R4, D8): `serverSmokeTest` on the dedicated server with its own `-cachedir` copy: first run
      registers, second run logs widening ok and `[ZombieBuddyKotlin] ready`; afterwards the client `smokeTest` passes
      without registering again (identical-copy rule).
- [ ] AC5 (R5): generated glue under `*/build/generated/ksp/main/` starts every advice and Lua function with the
      `isReady()` gate; a `@LuaGlobal` with a non-null reference return fails the build with a processor error.
- [ ] AC6 (R8): in game, IME windows still sit at the caret and blood still sits on sloped Viewpoint terrain.
- [ ] AC7 (R9): spec files listed in `implement.md` step 9 and the three `workshop.txt` files are updated; no spec rule
      still says dependents never check readiness without naming the gate exception.

## Out of Scope

- Publishing the KSP processor or a Gradle plugin, author documentation beyond the Workshop texts (D2, later).
- Changes to ZombieBuddy; signing (D5).
- Choosing a mod's version dir like the game does (D3 accepts over-inclusion).
- Workshop uploads, required-item settings on the Workshop pages and preview images (done by the user; release order
  in `design.md` 9).
