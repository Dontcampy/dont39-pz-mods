# Kotlin Runtime (ZombieBuddyKotlin)

> Rules for Kotlin code in this repo and for the `zb-kt` library mod. How the swap works is in the code:
> start at `KotlinSwap.java` and `Main.java` (`zb-kt/src/main/java/com/dont39/pz/zbkt/`) and `zb-kt/build.gradle.kts`.

---

## Background (not visible in this repo's code)

- The game's `projectzomboid.jar` bundles an old kotlin-stdlib (1.4.10, via okhttp). It is first on the classpath, so a
  stdlib inside a mod jar would be shadowed. The vanilla game itself never loads a Kotlin class.
- ZombieBuddy calls `Main.main` and then loads every class in exactly `javaPkgName` (not subpackages) while applying
  patches: `ApplyPatchesFromPackage` in
  [`Loader.java`](../../../external-repos/ZombieBuddy/java/src/main/java/me/zed_0xff/zombie_buddy/Loader.java), and
  `collectPatches` in
  [`PatchEngine.java`](../../../external-repos/ZombieBuddy/java/src/main/java/me/zed_0xff/zombie_buddy/PatchEngine.java).
- ZombieBuddy loads dependent mods and inlines their patches, and Zabric calls their main entrypoints, whether or not
  the swap happened; without it they would run on 1.4.10.

## Library (`zb-kt`)

- Dependents never check readiness, so ZombieBuddyKotlin installs the current stdlib on every launch before any
  dependent class loads: from its ZombieBuddy `Main` (`Main.java`, swap in `KotlinSwap.java`), deliberately not a
  Zabric entrypoint. ZombieBuddy calls it when it reaches ZombieBuddyKotlin's jar, before any dependent's jar:
  dependents `require` it and list it in `loadModAfter` ([Dependent Kotlin Modules](#dependent-kotlin-modules)). This
  `Main` is the one place ZombieBuddyKotlin depends on mod order. It links Zabric's jar, so
  `zb-kt/mod/42/mod.info` has `\Zabric` in `loadModAfter`.
- Not a preload mod (no `javaPreload`, no `ZB-Preload`, no `Zabric-PreLaunch`): every launch is the same, with no
  registration and no restart. ZombieBuddy drops a leftover `preload_mods` entry for it at premain, since its jar does
  not declare `ZB-Preload` (`preloadMods` in ZombieBuddy's `Loader.java`); `serverSmokeTest` covers this.
- A `kotlin.*` class already loaded at the swap is an error naming it. Accepted: anything that loads `kotlin.*` before
  ZombieBuddy reaches this jar beats the swap, such as a ZombieBuddy preload mod's `PreMain` or a Kotlin mod above
  ZombieBuddyKotlin in the mod list that does not require it. No known ZombieBuddy-only mod uses Kotlin.
- Its Java classes run before the swap and must not reference Kotlin types (`checkBootstrapClasses` enforces this).
  Put Kotlin code in a subpackage of `javaPkgName`; see the comment in `selfcheck/SelfCheck.kt`.
- `Main` logs the outcome on every launch: the ready line, or an error ("not ready") naming the cause of a swap or
  self-check failure. The game keeps running; there is no flag or degraded mode for dependents
  ([Error Handling](./error-handling.md)).
- Everything in this jar is shared unrelocated by all dependent mods, including `zb-kt-utils`
  ([ZombieBuddy Kotlin Utils](./zb-kt-utils.md)). Adding helper APIs or more kotlinx artifacts is a decision, not a
  drive-by change.

## Dependent Kotlin Modules

- Apply `buildsrc.convention.pz-kotlin` (`buildSrc/src/main/kotlin/pz-kotlin.gradle.kts`); All-in-One features get it
  through `pz-feature`. Never bundle `kotlin/` or `kotlinx/` classes.
- The mod's `mod.info` lists `\Zabric` and `\ZombieBuddyKotlin` in `require`, and `\ZombieBuddyKotlin` in
  `loadModAfter`: ZombieBuddy loads every class in `javaPkgName` and applies its patches as it reaches the jar, and
  those classes link ZombieBuddyKotlin's jar and need the current Kotlin, both there only once ZombieBuddy has reached
  its jar ([Loading](./zombiebuddy-patching.md#loading)).
- Mod-level startup code is a Zabric main entrypoint (`Zabric-Main` in the jar manifest), not a ZombieBuddy `Main`.
- No readiness check, in generated glue or in main entrypoints (`dont39-all-in-one/.../AllInOneMain.kt`): dependents
  assume the current Kotlin. If ZombieBuddyKotlin could not install it, its own error line says why; dependents add no
  fallback or degraded mode.

## Version Bumps

- Versions come only from `gradle/libs.versions.toml`.
- After a bump, update the version text in `zb-kt/mod/workshop/workshop.txt` and run `smokeTest`. The
  ready line must show the new versions.
- Upload ZombieBuddyKotlin before any mod update that needs the new version.
- KSP is versioned independently of Kotlin. After a Kotlin bump, `./gradlew build` must still run KSP; if it does not,
  move KSP to its latest release.
