# Directory Structure

> Where things live and what to touch when adding a feature.

---

## Layout

```
settings.gradle.kts                  module list
gradle.properties                    build settings; machine paths (pzDir, pzServerDir) go in ~/.gradle/gradle.properties
buildSrc/src/main/kotlin/
  pz-jvm.gradle.kts                  toolchain and bytecode level (mod/PzJvm.kt); zabric-processor applies it without pz-java
  pz-java.gradle.kts                 every game module: pz-jvm, zb-gradle-plugin, game and ZombieBuddy jars
  pz-kotlin.gradle.kts               every Kotlin module
  pz-mod.gradle.kts                  every shipped mod: pzMod from mod.info, assembleMod, deployWorkshop
  pz-bundled.gradle.kts              every module dont39-all-in-one bundles: Lua, media, classtweaker variants
  pz-bundled-feature.gradle.kts      every feature: pz-bundled plus the descriptor variant; Lua-only features apply it
  pz-feature.gradle.kts              every Kotlin feature module: pz-kotlin, pz-bundled-feature, pz-widening, KSP glue,
                                     allinone-common compile-only
  pz-widening.gradle.kts             pzTargets and the module's .classtweaker: widened compile jars (access-widening.md)
  pz-junit.gradle.kts, pz-loader-test.gradle.kts  JUnit; tests against ZombieBuddy's classes
  pz-bootstrap.gradle.kts            modules whose Java classes run before Kotlin is installed (kotlin-runtime.md)
  pz-publish.gradle.kts              the Maven Central artifacts (zabric, zabric-processor)
  pz-switches.gradle.kts             shipped mods with an ini of switches: mod-settings/ under the mod's own names
  mod/                               machine paths, mod.info, typed extensions, JVM levels, version catalog
  feature/                           the variants projects exchange data through, feature descriptors, initFeatures(),
                                     the merged media folder (MergeMedia.kt)
  smoke/                             smoke tests
  widening/                          target mods and widened compile jars (see access-widening.md)
  check/                             class-reference checks, checkGlue, checkJdkGlue (see zombiebuddy-patching.md,
                                     kotlin-runtime.md, zabric-hooks.md)
<feature-module>/                    one per feature, e.g. ime-keyboard-fix/
  build.gradle.kts                   the pz-feature plugin line, plus pzTargets for target mods
  src/main/kotlin/com/dont39/pz/<featurepkg>/  feature logic, with @Hooks objects and @LuaGlobal functions
  src/main/lua/client/                         Lua glue, e.g. mod options (if any)
  src/main/lua/shared/Translate/<LANG>/        translations of its own keys (UI.json, Sandbox.json)
  src/main/media/                              other media files, e.g. its own sandbox-options.txt (copied into 42/media)
  src/main/resources/META-INF/dont39/<module>.classtweaker   non-public members it uses (if any)
  build/generated/ksp/main/                    generated glue (read it, never edit it)
allinone-common/                     code the features share, bundled only into dont39-all-in-one (not a feature: no
                                     facade, hooks or glue; not in the features list)
  src/main/kotlin/com/dont39/pz/common/        Kotlin helpers, e.g. sandbox option reads, failJoin, writeAtomically
  src/main/lua/client/Dont39AllInOne_Common.lua   Lua helpers: Common.log, the fail-once Common.guard
dont39-all-in-one/                   shipped mod: all features
  build.gradle.kts                   version, feature list (init order), packaging, smokeTest
  src/main/media/                    the mod's own media files, e.g. the sandbox page's name; merged with the features'
                                     by MergeMedia: translations and sandbox-options.txt per name,
                                     a key or option twice fails, as does any other file in two modules
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
loading-progress/                    shipped mod: client-only Java mod, preloaded with launch-wide hooks; loading bar, own
                                     frames and a timing report per load, switches on a mod options page (its README)
faster-loading/                      shipped mod: client-only Java mod, preloaded with launch-wide hooks; load-time fixes,
                                     one switch each in its ini and options page (its README)
sync-guard/                          shipped mod: Java mod for client and dedicated server, not preloaded; detects and
                                     repairs lost container and item sync, with its own packets (zombiebuddy-patching.md)
mod-settings/                        no project: the switches ini reader/writer (Switches.java), its options page
                                     (Switches.lua) and the page's test check (SwitchesPage.java), which pz-switches
                                     compiles into <javaPkgName>.settings and ships as <modId>_Switches.lua, since the
                                     mods share one class loader and one Lua require namespace
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
| Lua file, mod-options id | `Dont39AllInOne_<Name>` (`<Name>`: the module dir name in PascalCase), as in `Dont39AllInOne_ModOptionsSidebar.lua`; a feature with several independent Lua parts names them `Dont39AllInOne_<Name><Part>.lua`, each with its own disable-on-error, and shares code through a module file that returns a table (`Dont39AllInOne_TunnelShared.lua`) |
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
7. Add one line for the feature to the list in `dont39-all-in-one/mod/workshop/workshop.txt`: what it does for a player and
   what a server sets, no details.
8. Bump the version in `dont39-all-in-one/build.gradle.kts` when releasing.

`AllInOneMain.kt` and `smokeTest` need no edit.

Do not put feature code inside `dont39-all-in-one`; each feature is its own module. Code a second feature needs goes
into `allinone-common` instead of a copy; a feature package with sub-packages keeps them layered (`mod-sync`:
`protocol` is used by `server` and `client`, neither uses the other).

A feature that only changes vanilla Lua UI is **Lua-only**: steps 2, 3, 7 and 8 as above, a module that applies
`pz-bundled-feature` instead of `pz-feature`, no facade, hooks or `init()`. Its Lua logs `<module>: ready`, which `smokeTest`
expects of every Lua-only feature.
Reference: `mod-options-sidebar/`; its rules are in
[ZombieBuddy Patching](./zombiebuddy-patching.md#wrapping-vanilla-lua-ui).

A Lua-only feature may fix another mod's Lua (the first one, `sctd-transmog-flush-fix`, is removed; see git
history): it gates on that mod's exact source text and goes idle once the text changes without the bug. Its target
is not added to `loadModAfter`: the mod
selector's auto-sort (`ModLoadOrderPanel:sortMods`) leaves a hole for a listed mod that is not enabled, and client Lua
already runs after every mod's shared Lua. Its status line goes in `smokeTest`'s `luaTargeted` list, where idle passes.

A further shipped mod applies `buildsrc.convention.pz-mod`; compare the shipped modules' build scripts.
