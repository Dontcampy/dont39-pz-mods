# Directory Structure

> Where things live and what to touch when adding a feature.

---

## Layout

```
settings.gradle.kts                  module list
gradle.properties                    local paths (pzDir)
buildSrc/src/main/kotlin/
  pz-java.gradle.kts                 Java + game/ZombieBuddy compile-only jars; every module applies it
  pz-kotlin.gradle.kts               pz-java + Kotlin; every Kotlin module applies it (see kotlin-runtime.md)
  pz-mod.gradle.kts                  a shipped mod: mod id from mod.info, jar name, assembleMod, deployWorkshop
<feature-module>/                    one per feature, e.g. viewpoint-blood-fx-fix/
  build.gradle.kts
  src/main/{kotlin,java}/com/dont39/pz/<featurepkg>/          feature logic (new features: Kotlin)
  src/main/java/com/dont39/pz/allinone/<Feature>Patches.java  patch classes
  src/main/java/com/dont39/pz/allinone/<Feature>Lua.java      Lua-exposed functions (if any)
  src/main/lua/client/                                        Lua glue, e.g. mod options (if any)
dont39-all-in-one/                   shipped mod: all features
  build.gradle.kts                   version, feature list, packaging, smokeTest
  src/main/kotlin/com/dont39/pz/allinone/Main.kt
  mod/42/                            mod.info, poster
  mod/workshop/                      workshop.txt, preview
zombie-buddy-kotlin/                 shipped mod: Kotlin runtime library (see kotlin-runtime.md)
  build.gradle.kts                   version, kotlinx merge, stdlib file + checksum, checkBootstrapClasses
  src/main/java/com/dont39/pz/zombiebuddykotlin/         Main, ZombieBuddyKotlin (Java only)
  src/main/kotlin/com/dont39/pz/zombiebuddykotlin/selfcheck/
  mod/42/, mod/workshop/
```

Build, signing, and packaging behavior (toolchain, Java release level, compile-only jars, how features' classes and Lua
are merged into the mod folder, deploy guards, smoke test) is defined in:

- `buildSrc/src/main/kotlin/pz-java.gradle.kts`, `pz-kotlin.gradle.kts`, `pz-mod.gradle.kts`
- `dont39-all-in-one/build.gradle.kts`, `zombie-buddy-kotlin/build.gradle.kts`

Read those files rather than assuming; do not duplicate their settings in feature modules.

---

## Naming

All names derive from one kebab-case feature name. Follow the existing feature in `viewpoint-blood-fx-fix/`:

| Thing | Example |
|-------|---------|
| Module dir and the facade's `NAME` | `viewpoint-blood-fx-fix` |
| Feature package | `com.dont39.pz.viewpointbloodfxfix` |
| Facade class | `ViewpointBloodFxFix.java` (new features: `.kt`) |
| Patches class (in `com.dont39.pz.allinone`) | `ViewpointBloodFxFixPatches.java` |
| Lua bridge class (in `com.dont39.pz.allinone`) | `ViewpointBloodFxFixLua.java` |
| Lua file, mod-options id, global Lua function names | see `Dont39AllInOne_ViewpointBloodFxFix.lua` and `ViewpointBloodFxFixLua.java` |

Lua-visible names are global across all mods, so keep the mod-id prefix used there.

## Visibility

Keep the facade's public surface to what `Main` and the Lua bridge need, make helpers `public` only when a class in
`com.dont39.pz.allinone` calls them, and keep the rest package-private. Compare the modifiers in the
`viewpoint-blood-fx-fix` classes.

---

## Adding a Feature

1. New module dir with a `build.gradle.kts` like `viewpoint-blood-fx-fix/build.gradle.kts`, but applying
   `buildsrc.convention.pz-kotlin` ([Kotlin Runtime](./kotlin-runtime.md#dependent-kotlin-modules)).
2. Register it in `settings.gradle.kts`.
3. Add it to the feature list in `dont39-all-in-one/build.gradle.kts`.
4. Facade + helpers in the feature package, modeled on `ViewpointBloodFxFix.java`.
5. `<Feature>Patches` (and `<Feature>Lua` plus a Lua file if the feature has options) in the module's
   `com/dont39/pz/allinone/` and `src/main/lua/`, modeled on the existing feature.
6. Call the facade's `init()` from `dont39-all-in-one/src/main/kotlin/com/dont39/pz/allinone/Main.kt`. Call a Kotlin
   feature's `init()` only when `kotlinProblem()` there returns null; Java features are initialized either way.
7. Declare target mods in `dont39-all-in-one/mod/42/mod.info` (load order).
8. Add the feature's patch classes, Lua exposure, and transformed targets to the `smokeTest` expectations in
   `dont39-all-in-one/build.gradle.kts`.
9. Add a section for the feature to `dont39-all-in-one/mod/workshop/workshop.txt`, following the existing one.
10. Bump the version in `dont39-all-in-one/build.gradle.kts` when releasing.

Do not put feature code inside `dont39-all-in-one`; each feature is its own module.

A further shipped mod applies `buildsrc.convention.pz-mod`, keeps its `mod/42/mod.info` (with `id=`) and
`mod/workshop/`, and adds its own files to `assembleMod` (compare the two shipped modules' build scripts).
