# Implement: Zabric phased entrypoints

Gradle needs JDK 25: `JAVA_HOME=~/.jdks/graalvm-ce-25.0.2`.

## Checklist

1. **Seam reads** (`zabric/.../loader/`): `preloadedJars()`, `loadedThisPass(mods)` over `g_jarLoadStatus`; verify the
   record shape against the ZombieBuddy jar in `pzDir` (2.3.4+), throw with version on mismatch.
2. **Main hook** (`loader/`): advice on `Loader.loadMods(List)` exit, retransforming; `onModsLoaded(callback)`.
3. **preLaunch hook** (outside `loader/`): advice on `MainScreenState.main` / `GameServer.main` enter.
4. **Entrypoint API + Lifecycle** (`com.dont39.pz.zabric.entrypoint`): interfaces, manifest read, instantiation
   (object `INSTANCE` / no-arg constructor), per-call isolation, once-per-session main, phase log lines.
5. **Registration move**: `Preload.update` driven by the lifecycle; not-preloaded path in Zabric's `Main` (design §5);
   delete `REQUEST_PREFIX`, `registerRequested`, public `register`.
6. **ZombieBuddyKotlin**: not a preload mod; its ZombieBuddy `Main` swaps Kotlin, self-checks and logs the outcome on
   every launch; delete `PreMain`, `PreloadRequest`, `isReady()`; remove the readiness gate from `GlueProcessor.kt`
   and the check from `AllInOneMain.kt`.
7. **Dont39AllInOne**: `Main.kt` → `MainEntrypoint` object; manifest attribute.
8. **Smoke tests**: update the ordered log lines in `dont39-all-in-one/build.gradle.kts` (entrypoint lines instead of
   ZombieBuddy `Main` order); `serverSmokeTest`: the player's config, then an isolated `config_dir` with a leftover
   ZombieBuddyKotlin preload entry (ready line, no registration or restart, entry dropped).
9. **Docs**: `zabric/mod/workshop/workshop.txt` author contract and Load order line; "Load order" lines in the other
   Workshop texts; specs: `access-widening.md` (Zabric, Preload Registration), `kotlin-runtime.md` (Library,
   Dependent modules), `zombiebuddy-patching.md` (Loading, Switch Checklist: entry points, seam reads).

## Validation

```bash
./gradlew build
./gradlew :zb-kt:serverSmokeTest
./gradlew :dont39-all-in-one:smokeTest
```

- `build` runs `checkLoaderRefs` and `checkBootstrapClasses` (AC6; preLaunch classes of ZBK must stay Kotlin-free).
- Smoke tests twice when registrations change (first run registers, second must be clean; `checkPlatformLog`).
- Manual (AC1 client): move Dont39 All-in-One above Zabric in the game's mod list, run `smokeTest`.
- Manual (AC3 client): first launch shows ZombieBuddyKotlin's ready line, and the restart notice does not list it.
- Manual (AC4): hold Shift at load, deny Zabric: `entrypoints: not run this session` and no `[Zabric] main:` lines.
- Manual (AC5): temporarily throw from a test entrypoint; the others' lines still appear.
- Manual (AC9): enable an installed ZombieBuddy-only Java mod, run `smokeTest`; then temporarily throw inside the main
  hook's Zabric call: the game still reaches init, that mod's `Main` still logs, Zabric logs the hook error.
- `grep -rn "zabric.preload.\|PreloadRequest"` over the repo (excluding `build/`, task archive): empty (AC7).

## Rollback points

- Steps 1–4 are additive; the old handshake still works until step 5. Commit after step 4 builds.
- Steps 5–7 must land together (registration path switches).
