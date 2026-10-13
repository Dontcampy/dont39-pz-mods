# Kotlin Runtime (ZombieBuddyKotlin)

> Rules for Kotlin code in this repo and for the `zb-kt` library mod. How the swap works is in the code:
> start at `PreMain.java`, `Main.java` and `ZombieBuddyKotlin.java` (`zb-kt/src/main/java/com/dont39/pz/zbkt/`) and
> `zb-kt/build.gradle.kts`.

---

## Background (not visible in this repo's code)

- The game's `projectzomboid.jar` bundles an old kotlin-stdlib (1.4.10, via okhttp). It is first on the classpath, so a
  stdlib inside a mod jar would be shadowed. The vanilla game itself never loads a Kotlin class.
- ZombieBuddy calls `Main.main` and then loads every class in exactly `javaPkgName` (not subpackages) while applying
  patches: `ApplyPatchesFromPackage` in
  [`Loader.java`](../../../external-repos/ZombieBuddy/java/src/main/java/me/zed_0xff/zombie_buddy/Loader.java), and
  `collectPatches` in
  [`PatchEngine.java`](../../../external-repos/ZombieBuddy/java/src/main/java/me/zed_0xff/zombie_buddy/PatchEngine.java).
- On a launch where the swap did not happen, ZombieBuddy still loads dependent mods, calls their `Main` and inlines
  their patches; they would run on 1.4.10.

## Library (`zb-kt`)

- The stdlib is swapped only at premain, before any mod can load a `kotlin.*` class. ZombieBuddyKotlin is preloaded
  through Zabric ([Access Widening](./access-widening.md#preload-registration)).
- Java classes in `javaPkgName` run before the swap or without it and must not reference Kotlin types
  (`checkBootstrapClasses` enforces this). Put Kotlin code in a subpackage; see the comment in `selfcheck/SelfCheck.kt`.
- Every failure reports "not ready" and leaves the game running ([Error Handling](./error-handling.md)).
- Everything in this jar is shared unrelocated by all dependent mods, including `zb-kt-utils`
  ([ZombieBuddy Kotlin Utils](./zb-kt-utils.md)). Adding helper APIs or more kotlinx artifacts is a decision, not a
  drive-by change.

## Dependent Kotlin Modules

- Apply `buildsrc.convention.pz-kotlin` (`buildSrc/src/main/kotlin/pz-kotlin.gradle.kts`); All-in-One features get it
  through `pz-feature`. Never bundle `kotlin/` or `kotlinx/` classes.
- The mod's `mod.info` lists `\Zabric` and `\ZombieBuddyKotlin` in both `require` and `loadModAfter`.
- The only readiness check a dependent has is the gate in generated glue and at the top of its `Main`
  (`dont39-all-in-one/.../Main.kt`); it must link on the game's Kotlin 1.4.10. Otherwise dependents assume
  ZombieBuddyKotlin works: no fallback or degraded mode. Its own log says why it is not ready.

## Version Bumps

- Versions come only from `gradle/libs.versions.toml`.
- After a bump, update the version text in `zb-kt/mod/workshop/workshop.txt` and run `smokeTest`. The
  ready line must show the new versions.
- Upload ZombieBuddyKotlin before any mod update that needs the new version.
- KSP is versioned independently of Kotlin. After a Kotlin bump, `./gradlew build` must still run KSP; if it does not,
  move KSP to its latest release.
