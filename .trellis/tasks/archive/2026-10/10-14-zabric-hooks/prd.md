# Zabric hook engine with Kotlin glue on top

## Goal

Anyone who loads Zabric can write a Java mod on it: entrypoints and access widening exist, hooks are the missing piece.
Zabric gets a hook API that a Java author writes by hand, a published annotation processor that turns it into loader
glue, and a runtime that gates, isolates and polices hooks. zb-kt's KSP glue becomes a layer on that API (adds the
Kotlin `Feature` gate and Kotlin checks) instead of generating ZombieBuddy `@Patch` classes. The primary audience is
third-party Java authors; the Kotlin glue is a consumer of the Java API, not its shape.

## Background

- Today: feature hooks use `Hook.Enter`/`Exit` in `zb-kt-utils/.../glue/Glue.kt`; `zb-kt-utils-processor/.../
  GlueProcessor.kt` generates Java ZombieBuddy `@Patch` advice per module (facade `active()` gate shared through
  `@Patch.Local`, catch-all into `disable`) and the listing `META-INF/zbkt/glue/<Feature>.txt`, read by
  `HookTargets.kt` and by `checkGlue`/`smokeTest` (`dont39-all-in-one/build.gradle.kts`).
- ZombieBuddy weaves each mod's `@Patch` classes (only in exactly `javaPkgName`) as it loads the mod list, one jar at
  a time on one shared class loader; retransforms already-loaded targets (advice only); nests advice by load order;
  only logs a missing target. Its only public entry is `PatchEngine.applyPatches(packageName, loader)`, a ClassGraph
  package scan (`external-repos/ZombieBuddy/java/src/main/java/me/zed_0xff/zombie_buddy/PatchEngine.java:134`, `:671`).
  `Patch.java` offers `OnEnter.skipOn`, `OnExit.onThrowable`, `readOnly = false` on `Return`/`Thrown`/`Argument`/
  `AllArguments`, `Local`, and method delegation. Authors compile against a local `ZombieBuddy.jar`
  (`external-repos/ZombieBuddy/doc/ModdingGuide.md:64-72`).
- Zabric is preloaded first, reaches ZombieBuddy only through `loader/` (`checkLoaderRefs`,
  `.trellis/spec/mod/zombiebuddy-patching.md#loader-seam`), and knows each mod-list pass and which mods ZombieBuddy
  approved and loaded (`Loader.loadedThisPass`, `entrypoint/Lifecycle.java`). ZombieBuddy cannot unload a jar.
- Hooks in use: `ImeCaret.publish` (Exit, `onThrow = false`, `@This`) and `ImeWindow.sync` (Exit, `always = true`, on
  the already-loaded `org.lwjglx.opengl.Display.processMessages`).
- Zabric and ZombieBuddyKotlin are not on the Workshop yet: no compatibility layer for API breaks. The repo has no
  license and no CI. `pzlauncher.com` uses the same Cloudflare name servers as `dont39.com`.

## Decisions

- D1 (user): a Zabric-based mod hooks through Zabric's API. A hand-written ZombieBuddy `@Patch` is accepted only as an
  explicit opt-out (D9).
- D2 (user): compile-time glue. Authors write Zabric annotations; Zabric's javac annotation processor generates the
  ZombieBuddy `@Patch` glue (gate and catch included) into the mod jar; zb-kt's KSP processor shares the generator.
  One loader per build; a loader switch means rebuilding every Zabric mod. Rejected: Zabric's own weaving engine,
  embedded SpongePowered Mixin, runtime translation of declarations into ZombieBuddy patches.
- D3 (user): capabilities: before/after the target, skip, read-only bindings, plus cancel with a handler-chosen return
  value, modify the return value, modify an argument. Excluded: method delegation (one mod wins silently).
- D4 (user): MixinExtras-flavored vocabulary, names and semantics matching MixinExtras where they match; anything
  unsupported is a compile error saying so. Known ceiling: `@WrapMethod`/`@WrapOperation` need around-calls that D2
  can only get from delegation.
- D5 (user): Fabric's distribution model: annotations/runtime API and the processor are published artifacts
  (`compileOnly` / `annotationProcessor`), not taken from the installed mod. Generated glue calls only a small runtime
  API that stays backward compatible once released (players' Zabric auto-updates).
- D6 (user): Zabric moves to `com.pzlauncher`: groupId `com.pzlauncher`, packages `com.pzlauncher.zabric.*`.
  ZombieBuddyKotlin and All-in-One stay `com.dont39`. No compatibility for the old package.
- D7 (user): one task covers the rename, hooks, Kotlin glue and the first Maven Central release.
- D8 (user): failure scope through gates: `HookGate` (`active()`, `failed(what, t)`) named per hook class, else the
  mod's default gate (one per mod; any handler failure stops all of that mod's hooks). Every hook also requires its
  mod to be in the current mod-list pass. Kotlin's `Feature` is a gate; the KSP glue uses the module's facade.
- D9 (user): D1 is enforced at compile time only: both processors reject a class annotated with ZombieBuddy's `@Patch`
  unless it also carries Zabric's marker `@UnsafeLoaderPatch` (no arguments). The marker is a disclaimer: the author
  may use it, but such a class is plain ZombieBuddy (no Zabric gate, catch or target check) and Zabric takes no
  responsibility when it breaks, e.g. on a loader switch. No runtime scan or refusal.
- D10 (user): MIT license for the whole repository.
- D11 (user): no `priority`. Cross-mod order would need a runtime dispatcher that elects the outermost advice per call,
  which breaks when a weave fails, an `@UnsafeLoaderPatch` sits in between, or the target recurses. Order is defined
  only within one compilation: handlers on the same target method are merged into one patch class, hook classes by
  qualified name, handlers in declaration order. Across mods, ZombieBuddy's load order decides (documented).
- D12 (user): detect hooks ZombieBuddy failed to weave (design.md §12). ZombieBuddy drops a target's
  patches when its retransformed bytes fail verification or ByteBuddy errors, and still logs `Transformed:`; a hook
  that never runs is a silent degrade. Zabric verifies every hooked target method actually contains its glue and
  treats a missing weave like a handler failure of that hook class's gate.
- Proposed in `design.md` (follow from D2–D8; up for review): Kotlin uses Zabric's annotations directly (`Glue.kt`
  keeps only `LuaGlobal`); "modify an argument" is `@ModifyVariable(at = HEAD, argsOnly = true)` because Mixin's
  `@ModifyArg` means a call-site argument; Zabric-only additions `@Self`, `@At("FINALLY")` (after return or throw, for
  restore), `always`; no `warmUp`; hooks start at the first mod-list pass Zabric processes; release is
  manual from the user's machine.

## Requirements

- R1 (D6, D10): MIT `LICENSE` at the root; Zabric's packages, `javaPkgName`, build exclusions, callers, Workshop texts
  and specs use `com.pzlauncher.zabric`.
- R2 (D3, D4): Zabric ships the annotations and runtime types of `design.md` §2 in `com.pzlauncher.zabric.hook`.
- R3 (D2, D4): `zabric-processor` validates hook classes against the compile classpath (target class, unique method or
  descriptor, handler types, supported constructs) and generates the glue and listing of `design.md` §3.
- R4 (D8): generated glue runs a handler only when the mod is open and its gate is active (except `always`), pairs
  HEAD and RETURN/FINALLY per call, and reports every handler throwable to the gate without rethrowing.
- R5 (D8): Zabric's runtime opens a mod's hooks only while it is in the processed pass; the default gate stops all of
  the mod's hooks on failure with a log line naming mod, handler and target; a target mismatch closes them too.
- R6 (D9): both processors reject a `@Patch` class without `@UnsafeLoaderPatch`. The error names the class, says to
  hook through Zabric's API, and mentions `@UnsafeLoaderPatch` as possible but not recommended (no Zabric gate, catch
  or target check; Zabric takes no responsibility when it breaks). With the marker, the class compiles unchanged, the
  processor generates nothing for it, and the build shows a compile warning on that class with the same disclaimer.
- R7 (D2, D8): zb-kt's KSP glue reads Zabric's annotations and uses the shared generator with the facade as default
  gate; `Feature` implements `HookGate`; `HookTargets.kt` is replaced by Zabric's target check; the IME hooks migrate.
- R8 (D5, D7): `com.pzlauncher:zabric` and `com.pzlauncher:zabric-processor` are released to Maven Central with
  sources, javadoc, signatures and MIT POM metadata.
- R10 (D12): a hook whose patch is not in its target method (verification failure on retransform, ByteBuddy error,
  overload not matched) is reported to its gate with mod, hook class, target and cause: the default gate stops the
  mod's hooks, a named gate gets `failed` (a Kotlin feature disables). Covers targets loaded before the pass
  (checked at the pass) and targets loaded later (checked when they load).
- R9: the Workshop author contract and the specs describe the hook API, the rules and the coordinates.

## Acceptance Criteria

- [ ] AC1 (R1): `git grep "com.dont39.pz.zabric"` finds nothing outside `external-repos/` and archived tasks; `LICENSE`
      is MIT; `./gradlew build assembleMod` passes on JDK 25.
- [ ] AC2 (R3, R6): processor tests show a compile error naming the handler or class for: unresolved target,
      ambiguous method without descriptor, wrong handler types, unsupported annotation or `@At`, `@Patch` without
      `@UnsafeLoaderPatch` (whose message also names the marker as not recommended); and that `@Patch` with `@UnsafeLoaderPatch` compiles
      with a warning naming the class and the disclaimer (javac and KSP).
- [ ] AC3 (R2, R4): the weave test applies generated-shape glue through ZombieBuddy's transformer and shows cancel,
      cancel with value, modified return, modified argument, `@Share`, FINALLY on throw, closed gate, and a throwing
      handler that reaches `failed` while the target still runs.
- [ ] AC4 (R5): runtime tests show a mod's hooks closed outside the processed pass and after a default-gate failure,
      with the `hooks stopped` line naming mod, handler and target.
- [ ] AC5 (R7): `smokeTest` and `serverSmokeTest` pass: patch classes found and applied, `ime-keyboard-fix` ready, no
      `hooks stopped` line; no `Hook.Enter`/`Hook.Exit` remains.
- [ ] AC6 (R8): `samples/zabric-java-hooks` builds with only `mavenCentral()` and the local game/ZombieBuddy jars, and
      its generated glue and listing match expectations.
- [ ] AC7 (R9): Workshop text and specs updated (`implement.md` F).
- [ ] AC9 (R10): weave tests show a deliberately unverifiable patch on an already-loaded target and a patch whose
      advice ByteBuddy rejects each reported to the gate naming hook class and target, a correctly woven target not
      reported, and a target loaded after the check verified when it loads; `smokeTest` still passes.
- [ ] AC8 (in-game, by the user): IME caret and window behave as before.

## Out of Scope

- Injection points beyond HEAD/RETURN/FINALLY (call sites, fields, constants, target locals), `@Redirect`,
  `@Overwrite`, `@WrapOperation`/`@WrapMethod`, priority, added members or interfaces on targets.
- A Gradle plugin for authors (Loom-like), CI, a ZombieBuddy Maven artifact.
- Lua bridge changes (`@LuaGlobal` stays in zb-kt).
- Renaming ZombieBuddyKotlin or All-in-One packages.
