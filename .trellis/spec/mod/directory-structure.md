# Directory Structure

> Where things live and what to touch when adding a feature.

---

## Layout

```
settings.gradle.kts                  module list
gradle.properties                    local paths (pzDir, pzServerDir)
buildSrc/src/main/kotlin/
  pz-java.gradle.kts                 convention plugin every module applies, except the processor
  pz-kotlin.gradle.kts               convention plugin every Kotlin module applies
  pz-mod.gradle.kts                  convention plugin every shipped mod applies
  pz-feature.gradle.kts              convention plugin every Kotlin feature module applies
  PzTargets.kt, ClassTweakerTasks.kt target mods and widened compile jars (see access-widening.md)
  CheckLoaderRefs.kt, CheckBootstrapClasses.kt  class-reference checks (see zombiebuddy-patching.md, kotlin-runtime.md)
<feature-module>/                    one per feature, e.g. viewpoint-blood-fx-fix/
  build.gradle.kts                   the pz-feature plugin line, plus pzTargets for target mods
  src/main/kotlin/com/dont39/pz/<featurepkg>/  feature logic, with @Hook / @LuaGlobal annotations
  src/main/lua/client/                         Lua glue, e.g. mod options (if any)
  src/main/resources/META-INF/dont39/<module>.classtweaker   non-public members it uses (if any)
  build/generated/ksp/main/                    generated glue (read it, never edit it)
dont39-all-in-one/                   shipped mod: all features
  build.gradle.kts                   version, feature list, packaging, smokeTest
  src/main/kotlin/com/dont39/pz/allinone/Main.kt
  mod/42/                            mod.info, poster
  mod/workshop/                      workshop.txt, preview
zabric/                              shipped mod: loader layer (see access-widening.md)
zb-kt/                               shipped mod: Kotlin runtime library (see kotlin-runtime.md)
zb-kt-utils/                         shared Kotlin runtime, shipped in zb-kt's jar (see zb-kt-utils.md)
zb-kt-utils-processor/               KSP processor for the glue; compile time only, not shipped
```

Build, signing, and packaging behavior (toolchain, Java release level, compile-only jars, how features' classes and Lua
are merged into the mod folder, deploy guards, smoke test) is defined in:

- `buildSrc/src/main/kotlin/pz-java.gradle.kts`, `pz-kotlin.gradle.kts`, `pz-mod.gradle.kts`, `pz-feature.gradle.kts`
- `dont39-all-in-one/build.gradle.kts`, `zb-kt/build.gradle.kts`, `zabric/build.gradle.kts`

Read those files rather than assuming; do not duplicate their settings in feature modules.

---

## Naming

All names derive from one kebab-case feature name. Follow the existing feature in `viewpoint-blood-fx-fix/`:

| Thing | Example |
|-------|---------|
| Module dir and the facade's `name` | `viewpoint-blood-fx-fix` |
| Feature package | `com.dont39.pz.viewpointbloodfxfix` |
| Facade object | `ViewpointBloodFxFix.kt` |
| Patches class (in `com.dont39.pz.allinone`) | `ViewpointBloodFxFixPatches`, generated from the module dir name |
| Lua bridge class (in `com.dont39.pz.allinone`) | `ViewpointBloodFxFixLua`, generated likewise |
| Lua file, mod-options id, global Lua function names | see `Dont39AllInOne_ViewpointBloodFxFix.lua` and `@LuaGlobal` in `ViewpointBloodFxFix.kt` |

Lua-visible names are global across all mods, so keep the mod-id prefix used there.

## Visibility

Keep the facade's public surface to what `Main` and the Lua bridge need, and keep the rest `internal` / `private`.
Annotated hook and Lua functions and their objects are `internal` or `public`, never `private`. Compare the modifiers
in the `viewpoint-blood-fx-fix` classes.

---

## Adding a Feature

1. New module dir whose `build.gradle.kts` applies `buildsrc.convention.pz-feature` and declares its target mods with
   `pzTargets` ([Access Widening](./access-widening.md)).
2. Register it in `settings.gradle.kts`.
3. Add it to the feature list in `dont39-all-in-one/build.gradle.kts`.
4. Facade + helpers in the feature package, modeled on `ViewpointBloodFxFix.kt`, built on `Feature`
   ([ZombieBuddy Kotlin Utils](./zb-kt-utils.md)); non-public members via
   [Access Widening](./access-widening.md).
5. Annotate the hook functions (`@Hook.Enter` / `@Hook.Exit`) and Lua functions (`@LuaGlobal`) in the feature package
   ([ZombieBuddy Patching](./zombiebuddy-patching.md#patch-classes)); the glue classes are generated. Add a Lua file in
   `src/main/lua/` if the feature has options.
6. Call the facade's `init()` from `dont39-all-in-one/src/main/kotlin/com/dont39/pz/allinone/Main.kt`.
7. Declare target mods in `dont39-all-in-one/mod/42/mod.info` (load order).
8. Add a section for the feature to `dont39-all-in-one/mod/workshop/workshop.txt`, following the existing one.
9. Bump the version in `dont39-all-in-one/build.gradle.kts` when releasing.

A feature with generated glue needs no `smokeTest` edit.

Do not put feature code inside `dont39-all-in-one`; each feature is its own module.

A feature that only changes vanilla Lua UI is **Lua-only**: steps 2, 3, 8 and 9 as above, a module that applies
`pz-kotlin` instead of `pz-feature`, no facade, hooks or `init()`. Add its `ready` line to `smokeTest` by hand.
Reference: `mod-options-sidebar/`; its rules are in
[ZombieBuddy Patching](./zombiebuddy-patching.md#wrapping-vanilla-lua-ui).

A further shipped mod applies `buildsrc.convention.pz-mod`; compare the shipped modules' build scripts.
