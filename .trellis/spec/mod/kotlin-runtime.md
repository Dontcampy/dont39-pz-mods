# Kotlin Runtime (ZombieBuddyKotlin)

> Rules for Kotlin code in this repo and for the `zombie-buddy-kotlin` library mod. How the swap works is in the code:
> start at `ZombieBuddyKotlin.java` (`zombie-buddy-kotlin/src/main/java/com/dont39/pz/zombiebuddykotlin/`) and
> `zombie-buddy-kotlin/build.gradle.kts`.

---

## Background (not visible in this repo's code)

- The game's `projectzomboid.jar` bundles an old kotlin-stdlib (1.4.10, via okhttp). It is first on the classpath, so a
  stdlib inside a mod jar would be shadowed. The vanilla game itself never loads a Kotlin class, which is why swapping
  in the MAIN phase is safe.
- ZombieBuddy calls `Main.main` and then loads every class in exactly `javaPkgName` (not subpackages) while applying
  patches: `ApplyPatchesFromPackage` in
  [`Loader.java`](../../../external-repos/ZombieBuddy/java/src/main/java/me/zed_0xff/zombie_buddy/Loader.java), and
  `collectPatches` in
  [`PatchEngine.java`](../../../external-repos/ZombieBuddy/java/src/main/java/me/zed_0xff/zombie_buddy/PatchEngine.java).
- The library depends on ZombieBuddy internals (`Loader.g_instrumentation`). Recheck it after every ZombieBuddy update.

## Library (`zombie-buddy-kotlin`)

- Java classes in `javaPkgName` run before the swap and must not reference Kotlin types (`checkBootstrapClasses`
  enforces this). Put Kotlin code in a subpackage; see the comment in `selfcheck/SelfCheck.kt`.
- Every failure reports "not ready" and leaves the game running ([Error Handling](./error-handling.md)).
- Everything in this jar is shared unrelocated by all dependent mods. Adding helper APIs or more kotlinx artifacts is a
  decision, not a drive-by change.

## Dependent Kotlin Modules

- Apply `buildsrc.convention.pz-kotlin` (`buildSrc/src/main/kotlin/pz-kotlin.gradle.kts`); All-in-One features get it
  through `pz-feature`. Never bundle `kotlin/` or `kotlinx/` classes.
- The mod's `mod.info` lists `\ZombieBuddyKotlin` in both `require` and `loadModAfter`. A Kotlin mod that loads first
  makes the library refuse to swap.
- Kotlin-only code runs only after the readiness check. Follow
  `dont39-all-in-one/src/main/kotlin/com/dont39/pz/allinone/Main.kt`.
- Hooks run even when Kotlin is not ready (ZombieBuddy applies patches regardless). Code a hook reaches before the
  feature's `active()` gate may reference only `kotlin/` classes the game's 1.4.10 also has; check with `javap -v`
  (descriptors count, not just calls).

## Version Bumps

- Versions come only from `gradle/libs.versions.toml`.
- After a bump, update the version text in `zombie-buddy-kotlin/mod/workshop/workshop.txt` and run `smokeTest`. The
  ready line must show the new versions.
- Upload ZombieBuddyKotlin before any mod update that needs the new version.
- KSP is versioned independently of Kotlin. After a Kotlin bump, `./gradlew build` must still run KSP; if it does not,
  move KSP to its latest release.
