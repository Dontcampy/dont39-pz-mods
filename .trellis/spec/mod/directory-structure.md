# Directory Structure

> Where things live and what to touch when adding a feature.

---

## Layout

```
settings.gradle.kts                  module list
gradle.properties                    local paths (pzDir, pzServerDir)
buildSrc/src/main/kotlin/
  pz-java.gradle.kts                 convention plugin every module applies, except the processors
  pz-kotlin.gradle.kts               convention plugin every Kotlin module applies
  pz-mod.gradle.kts                  convention plugin every shipped mod applies
  pz-feature.gradle.kts              convention plugin every Kotlin feature module applies
  pz-publish.gradle.kts              convention plugin of the Maven Central artifacts (zabric, zabric-processor)
  PzTargets.kt, ClassTweakerTasks.kt target mods and widened compile jars (see access-widening.md)
  CheckLoaderRefs.kt, CheckBootstrapClasses.kt  class-reference checks (see zombiebuddy-patching.md, kotlin-runtime.md)
<feature-module>/                    one per feature, e.g. ime-keyboard-fix/
  build.gradle.kts                   the pz-feature plugin line, plus pzTargets for target mods
  src/main/kotlin/com/dont39/pz/<featurepkg>/  feature logic, with @Hooks objects and @LuaGlobal functions
  src/main/lua/client/                         Lua glue, e.g. mod options (if any)
  src/main/resources/META-INF/dont39/<module>.classtweaker   non-public members it uses (if any)
  build/generated/ksp/main/                    generated glue (read it, never edit it)
dont39-all-in-one/                   shipped mod: all features
  build.gradle.kts                   version, feature list, packaging, smokeTest
  src/main/kotlin/com/dont39/pz/allinone/AllInOneMain.kt   the mod's main entrypoint
  mod/42/                            mod.info, poster
  mod/workshop/                      workshop.txt, preview
zabric/                              shipped mod: loader layer, hook API and runtime (see access-widening.md, zabric-hooks.md)
zabric-processor/                    Zabric's annotation processor and glue generator; published, not shipped in a mod
samples/zabric-java-hooks/           standalone author build against the published artifacts; not in settings.gradle.kts
zb-kt/                               shipped mod: Kotlin runtime library (see kotlin-runtime.md)
zb-kt-utils/                         shared Kotlin runtime, shipped in zb-kt's jar (see zb-kt-utils.md)
zb-kt-utils-processor/               KSP front end on zabric-processor's generator, plus the Lua bridge; compile time only
```

Build, signing, and packaging behavior (toolchain, Java release level, compile-only jars, how features' classes and Lua
are merged into the mod folder, deploy guards, smoke test) is defined in:

- `buildSrc/src/main/kotlin/pz-java.gradle.kts`, `pz-kotlin.gradle.kts`, `pz-mod.gradle.kts`, `pz-feature.gradle.kts`,
  `pz-publish.gradle.kts`
- `dont39-all-in-one/build.gradle.kts`, `zb-kt/build.gradle.kts`, `zabric/build.gradle.kts`,
  `zabric-processor/build.gradle.kts`

Read those files rather than assuming; do not duplicate their settings in feature modules.

---

## Naming

All names derive from one kebab-case feature name. Follow the existing feature in `ime-keyboard-fix/`:

| Thing | Example |
|-------|---------|
| Module dir and the facade's `name` | `ime-keyboard-fix` |
| Feature package | `com.dont39.pz.imekeyboardfix` |
| Facade object | `ImeKeyboardFix.kt` |
| Hook glue class (in `com.dont39.pz.allinone`) | `ImeKeyboardFixGlue`, generated from the module dir name (`pz-feature.gradle.kts`) |
| Lua bridge class (in `com.dont39.pz.allinone`) | `ImeKeyboardFixLua`, generated likewise when the feature has `@LuaGlobal` functions |
| Lua file, mod-options id | `Dont39AllInOne_<Name>` (`<Name>`: the module dir name in PascalCase), as in `Dont39AllInOne_ModOptionsSidebar.lua` |
| Global Lua function names | `Dont39AllInOne_<verb><Name>...`, e.g. `Dont39AllInOne_set<Name>Force`; `GlueProcessor.kt` rejects a `@LuaGlobal` name without the prefix |

Lua-visible names are global across all mods, so they keep the mod-id prefix.

## Visibility

Keep the facade's public surface to what `AllInOneMain` and the Lua bridge need, and keep the rest `internal` /
`private`. Hook objects and `@LuaGlobal` functions and their objects are `internal` or `public`, never `private`;
handlers are `public` (`HookReader.kt`). Compare the modifiers in the `ime-keyboard-fix` classes.

---

## Adding a Feature

1. New module dir whose `build.gradle.kts` applies `buildsrc.convention.pz-feature` and declares its target mods with
   `pzTargets` ([Access Widening](./access-widening.md)).
2. Register it in `settings.gradle.kts`.
3. Add it to the feature list in `dont39-all-in-one/build.gradle.kts`.
4. Facade + helpers in the feature package, modeled on `ImeKeyboardFix.kt`, built on `Feature`
   ([ZabricKotlin Utils](./zb-kt-utils.md)); non-public members via
   [Access Widening](./access-widening.md).
5. Write hook objects with Zabric's annotations (`@Hooks`, `@Inject`, ...) and annotate Lua functions (`@LuaGlobal`)
   in the feature package ([ZombieBuddy Patching](./zombiebuddy-patching.md#patch-classes)); the glue classes are
   generated. Add a Lua file in
   `src/main/lua/` if the feature has options.
6. Call the facade's `init()` from `dont39-all-in-one/src/main/kotlin/com/dont39/pz/allinone/AllInOneMain.kt`.
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
