# Directory Structure

> Where things live and what to touch when adding a feature.

---

## Layout

```
settings.gradle.kts                  module list
gradle.properties                    build settings; machine paths (pzDir, pzServerDir) go in ~/.gradle/gradle.properties
buildSrc/src/main/kotlin/
  pz-jvm.gradle.kts                  toolchain and bytecode level (PzJvm.kt); zabric-processor applies it without pz-java
  pz-java.gradle.kts                 every game module: pz-jvm, zb-gradle-plugin, game and ZombieBuddy jars
  pz-kotlin.gradle.kts               every Kotlin module
  pz-mod.gradle.kts                  every shipped mod: pzMod from mod.info, assembleMod, deployWorkshop
  pz-bundled.gradle.kts              every module dont39-all-in-one bundles: Lua, media, descriptor variants
  pz-feature.gradle.kts              every Kotlin feature module: pz-kotlin, pz-bundled, KSP glue
  pz-junit.gradle.kts, pz-loader-test.gradle.kts  JUnit; tests against ZombieBuddy's classes
  pz-bootstrap.gradle.kts            modules whose Java classes run before Kotlin is installed (kotlin-runtime.md)
  pz-publish.gradle.kts              the Maven Central artifacts (zabric, zabric-processor)
  PzPaths.kt, ModInfo.kt             machine paths, mod.info
  PzArtifacts.kt                     the variants projects exchange data through
  FeatureInfo.kt, CheckGlue.kt       feature descriptors, generated initFeatures(), checkGlue
  PzSmoke.kt, GameSmokeTest.kt, ServerSmokeTest.kt  smoke tests
  PzTargets.kt, ClassTweakerTasks.kt target mods and widened compile jars (see access-widening.md)
  CheckLoaderRefs.kt, CheckBootstrapClasses.kt  class-reference checks (see zombiebuddy-patching.md, kotlin-runtime.md)
<feature-module>/                    one per feature, e.g. ime-keyboard-fix/
  build.gradle.kts                   the pz-feature plugin line, plus pzTargets for target mods
  src/main/kotlin/com/dont39/pz/<featurepkg>/  feature logic, with @Hooks objects and @LuaGlobal functions
  src/main/lua/client/                         Lua glue, e.g. mod options (if any)
  src/main/lua/shared/Translate/<LANG>/        translations (UI.json, Sandbox.json); merged into one mod folder,
                                               where a second feature's file of the same name fails assembleMod:
                                               merge the entries into the existing file instead
  src/main/media/                              other media files, e.g. sandbox-options.txt (copied into 42/media)
  src/main/resources/META-INF/dont39/<module>.classtweaker   non-public members it uses (if any)
  build/generated/ksp/main/                    generated glue (read it, never edit it)
dont39-all-in-one/                   shipped mod: all features
  build.gradle.kts                   version, feature list (init order), packaging, smokeTest
  src/main/kotlin/com/dont39/pz/allinone/AllInOneMain.kt   the mod's main entrypoint; calls the generated initFeatures()
  mod/42/                            mod.info, poster
  mod/workshop/                      workshop.txt, preview
zabric/                              shipped mod: loader layer, hook API and runtime (see access-widening.md, zabric-hooks.md)
zabric-processor/                    Zabric's annotation processor and glue generator; published, not shipped in a mod
samples/zabric-java-hooks/           standalone author build against the published artifacts; not in settings.gradle.kts
zb-kt/                               shipped mod: Kotlin runtime library (see kotlin-runtime.md)
zb-kt-utils/                         shared Kotlin runtime, shipped in zb-kt's jar (see zb-kt-utils.md)
zb-kt-utils-processor/               KSP front end on zabric-processor's generator, plus the Lua bridge; compile time only
zb-skip-online-checks/               Java mod that skips ZombieBuddy's network requests; targets the loader itself (see zombiebuddy-patching.md#loader-seam)
```

Build, signing, and packaging behavior (toolchain, Java release level, compile-only jars, how features' classes and Lua
are merged into the mod folder, deploy guards, smoke test) is defined in the convention plugins and task classes above
and in the shipped modules' build scripts. Read those files rather than assuming; do not duplicate their settings in
module scripts. A module script holds only what is specific to the module and never reads or configures another
project (`project(x).tasks`, `.extra`, `evaluationDependsOn`, ...): data crosses projects as a dependency on a variant
(`PzArtifacts.kt`), or as a repo file read by path (`modInfoOf` in `ModInfo.kt`).

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
| Global Lua function names | `Dont39AllInOne_<verb><Name>...`, e.g. `Dont39AllInOne_set<Name>Force`; `LuaBridge.kt` rejects a `@LuaGlobal` name without the prefix |

Lua-visible names are global across all mods, so they keep the mod-id prefix.

Names that players or server owners store are kept once released, even when the feature is renamed: sandbox options
(`Dont39AllInOne_TcpTunnel.Port` in `tunnel`, released as `tcp-tunnel`) and files in `Zomboid/Lua/`. A new format gets
a new file, migrated by reading the released one, which is never written or deleted (`TunnelStore.kt`).

## Visibility

Keep the facade's public surface to what `AllInOneMain` and the Lua bridge need, and keep the rest `internal` /
`private`. Hook objects and `@LuaGlobal` functions and their objects are `internal` or `public`, never `private`;
handlers are `public` (`HookReader.kt`). Compare the modifiers in the `ime-keyboard-fix` classes.

---

## Adding a Feature

1. New module dir whose `build.gradle.kts` applies `buildsrc.convention.pz-feature` and declares its target mods with
   `pzTargets` ([Access Widening](./access-widening.md)).
2. Register it in `settings.gradle.kts`.
3. Add it to the feature list in `dont39-all-in-one/build.gradle.kts`, at its place in the init order.
4. Facade + helpers in the feature package, modeled on `ImeKeyboardFix.kt`, built on `Feature`
   ([ZabricKotlin Utils](./zb-kt-utils.md)); non-public members via
   [Access Widening](./access-widening.md). The module name fixes the facade's name (`pz-feature.gradle.kts`); the
   generated `initFeatures()` calls its `init()`.
5. Write hook objects with Zabric's annotations (`@Hooks`, `@Inject`, ...) and annotate Lua functions (`@LuaGlobal`)
   in the feature package ([ZombieBuddy Patching](./zombiebuddy-patching.md#patch-classes)); the glue classes are
   generated. Add a Lua file in
   `src/main/lua/` if the feature has options.
6. Declare target mods in `dont39-all-in-one/mod/42/mod.info` (load order).
7. Add a section for the feature to `dont39-all-in-one/mod/workshop/workshop.txt`, following the existing one.
8. Bump the version in `dont39-all-in-one/build.gradle.kts` when releasing.

`AllInOneMain.kt` and `smokeTest` need no edit.

Do not put feature code inside `dont39-all-in-one`; each feature is its own module.

A feature that only changes vanilla Lua UI is **Lua-only**: steps 2, 3, 7 and 8 as above, a module that applies
`pz-bundled` instead of `pz-feature`, no facade, hooks or `init()`. Its Lua logs `<module>: ready`, which `smokeTest`
expects of every Lua-only feature.
Reference: `mod-options-sidebar/`; its rules are in
[ZombieBuddy Patching](./zombiebuddy-patching.md#wrapping-vanilla-lua-ui).

A Lua-only feature may fix another mod's Lua (the first one, `sctd-transmog-flush-fix`, is removed; see git
history): it gates on that mod's exact source text and goes idle once the text changes without the bug. Its target
is not added to `loadModAfter`: the mod
selector's auto-sort (`ModLoadOrderPanel:sortMods`) leaves a hole for a listed mod that is not enabled, and client Lua
already runs after every mod's shared Lua. Its status line goes in `smokeTest`'s `luaTargeted` list, where idle passes.

A further shipped mod applies `buildsrc.convention.pz-mod`; compare the shipped modules' build scripts.
