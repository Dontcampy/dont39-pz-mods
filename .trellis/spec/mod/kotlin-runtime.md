# Kotlin Runtime (ZabricKotlin)

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
- ZombieBuddy loads dependent mods and inlines their hook glue, and Zabric opens their hooks and calls their main
  entrypoints, whether or not the swap happened; without it they would run on 1.4.10.

## Library (`zb-kt`)

- The swap runs from ZabricKotlin's ZombieBuddy `Main`, not a Zabric entrypoint, and ZabricKotlin is not a
  preload mod: it must run before ZombieBuddy reaches any dependent's jar, which the dependents' `require` and
  `loadModAfter` guarantee ([Dependent Kotlin Modules](#dependent-kotlin-modules)). This is the one place it depends on
  mod order.
- Accepted risk: anything that loads `kotlin.*` before ZombieBuddy reaches this jar beats the swap, which then fails
  with an error. No known ZombieBuddy-only mod uses Kotlin.
- Its Java classes run before the swap and must not reference Kotlin types (`checkBootstrapClasses` enforces this).
  Put Kotlin code in a subpackage of `javaPkgName`; see the comment in `selfcheck/SelfCheck.kt`.
- A failure is an error in its own log; dependents get no flag or degraded mode ([Error Handling](./error-handling.md)).
- Everything in this jar is shared unrelocated by all dependent mods, including `zb-kt-utils`
  ([ZabricKotlin Utils](./zb-kt-utils.md)). Adding helper APIs or more kotlinx artifacts is a decision, not a
  drive-by change.

## Dependent Kotlin Modules

- Apply `buildsrc.convention.pz-kotlin` (`buildSrc/src/main/kotlin/pz-kotlin.gradle.kts`); All-in-One features get it
  through `pz-feature`. Never bundle `kotlin/` or `kotlinx/` classes.
- No entry with `kotlin/` classes follows the game jar on the Kotlin compile classpath, so the game's kotlin-stdlib
  1.4.10 never shadows the current one; `compileKotlin` fails otherwise (`pz-kotlin.gradle.kts`).
- The mod `require`s `\Zabric` and `\ZabricKotlin` and lists `\ZabricKotlin` in `loadModAfter`
  ([Loading](./zombiebuddy-patching.md#loading)).
- Mod-level startup code is a Zabric main entrypoint, not a ZombieBuddy `Main`.
- No readiness checks anywhere, generated glue included: dependents assume the current Kotlin.

## Version Bumps

- Versions come only from `gradle/libs.versions.toml`.
- After a bump, update the version text in `zb-kt/mod/workshop/workshop.txt` and run `smokeTest`. The
  ready line must show the new versions.
- Upload ZabricKotlin before any mod update that needs the new version.
- KSP is versioned independently of Kotlin. After a Kotlin bump, `./gradlew build` must still run KSP; if it does not,
  move KSP to its latest release.
