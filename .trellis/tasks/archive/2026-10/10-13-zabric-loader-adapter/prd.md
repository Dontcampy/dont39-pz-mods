# Zabric loader adapter layer

## Goal

If ZombieBuddy (ZB) stops being maintained, switching this repo's mods to another loader (a ZB fork, Zabric becoming
its own agent, or another framework) touches one well-defined place (Zabric's loader package + the glue processor's
code generation) instead of every module, and the build keeps it that way.

## Background: ZB coupling today

Runtime services (R):

| # | Coupling | Where |
|---|----------|-------|
| R1 | `Instrumentation` via reflection on `me.zed_0xff.zombie_buddy.Loader.g_instrumentation`; version via `ZombieBuddy.getVersion()` | `zabric/src/main/java/com/dont39/pz/zabric/agent/ZombieBuddyAgent.java:12-38`; callers `AccessWidener.apply()`, `zb-kt/src/main/java/com/dont39/pz/zbkt/PreMain.java` (`swap()`) |
| R2 | Preload registration: ZB `config.json` `preload_mods` (`Agent.configDir()`), `javaPreload=true` + manifest `ZB-Preload: true`, Zabric-first ordering | `zabric/src/main/java/com/dont39/pz/zabric/preload/Preload.java:229-309,335` |
| R3 | Lifecycle by convention: ZB calls `<javaPkgName>.PreMain.premain` / `Main.main`; mod.info `javaJarFile`/`javaPkgName`/`javaPreload`/`ZBVersionMin`/`require=\ZombieBuddy` | `zabric/.../Main.java`, `PreMain.java`, every `mod/42/mod.info` |
| R4 | Lua exposure: ZB exposes Kahlua `@LuaMethod(global = true)` only from classes in exactly `javaPkgName` (`@LuaMethod` itself is the game's) | `zabric/.../RestartNotice.java`, `GlueProcessor.kt:348-355` |

Hook API (H):

| # | Coupling | Where |
|---|----------|-------|
| H1 | Generated glue is ZB `@Patch` classes | `zb-kt-utils-processor/.../GlueProcessor.kt:24,144-160,254` |
| H2 | Feature code imports `me.zed_0xff.zombie_buddy.Patch` for bindings | `ime-keyboard-fix/.../ImeCaret.kt:4,23-24`, `viewpoint-blood-fx-fix/.../AimShift.kt:5,17` |
| H3 | Hook-target check reads ZB's `@Patch` | `zb-kt-utils/src/main/kotlin/com/dont39/pz/zbktutils/HookTargets.kt:3,26`; listing written at `GlueProcessor.kt:87` |

Build/tooling (B): `zb-gradle-plugin`, `zbSigning`, `ZombieBuddy.jar` on the compile classpath
(`buildSrc/src/main/kotlin/pz-java.gradle.kts:5,9,30-36`); smoke launch via `-agentlib:zbNative`
(`buildSrc/src/main/kotlin/PzSmoke.kt:28-31`).

Other facts:

- ZB is MIT licensed (`external-repos/ZombieBuddy/LICENSE`); forking stays a legal fallback.
- Zabric's public author contract (`zabric/mod/workshop/workshop.txt:27-28`): `Preload.register`, system property
  `zabric.preload.<modId>`, `AccessWidener.problem`. Zabric and ZombieBuddyKotlin have no Workshop `id=` (only
  All-in-One does, `dont39-all-in-one/mod/workshop/workshop.txt:2`): not uploaded, so renaming API breaks no one.
- Constraints: fail fast, no fallback/degrade; typed access over reflection; Zabric/zb-kt bootstrap code is Java-only
  (`CheckBootstrapClasses`).

## Decisions

- D1 (user): scope is R + H. B is not abstracted.
- D2 (user): compile-time seam: one Zabric package is the only hand-written ZB code; no runtime backend selection.
- D3: `ZombieBuddyAgent` removed without a compatibility shim (not uploaded yet). `Preload`'s public contract unchanged.
- D4: R3/R4 entry and Lua-exposure conventions are documented in a switch checklist, not abstracted.

## Requirements

- REQ-1 (R1, R2): `com.dont39.pz.zabric.loader` holds all ZB runtime access behind neutral API (`Loader.name`,
  `version`, `instrumentation`, `keepPreloaded`); Zabric's other packages and `zb-kt` use it. Details: `design.md`.
- REQ-2 (R2): `Preload.register` / `registerRequested` / `REQUEST_PREFIX` behave as before; `config.json` entries are
  byte-for-byte the same as the current code would write.
- REQ-3 (H1, H2): feature hooks use neutral bindings from `Glue.kt` (`Hook.This/Argument/Return/Thrown/AllArguments`,
  `Hook.Exit.onThrow`); the processor maps them to ZB; generated glue is behaviorally unchanged.
- REQ-4 (H3): `HookTargets.kt` checks hook targets from the glue listing, without ZB types.
- REQ-5: a build check (`checkLoaderRefs`, part of `check`) fails on any `me/zed_0xff/` reference outside the loader
  package and generated glue.
- REQ-6: specs state the seam rule and the switch checklist of non-abstracted touchpoints (R3, R4, B, mod.info,
  manifest, Workshop texts, messages naming ZB), linking code.

## Acceptance Criteria

- AC-1 (REQ-1, REQ-5): outside `external-repos/` and `build/`, `me.zed_0xff` appears only in
  `zabric/.../loader/` and as strings in `GlueProcessor.kt`.
- AC-2 (REQ-5): adding a ZB reference to a feature class makes `./gradlew build` fail in `checkLoaderRefs`, naming the
  class.
- AC-3: `./gradlew build assembleMod` passes on JDK 25.
- AC-4 (REQ-2, REQ-3): `:dont39-all-in-one:smokeTest` and `:zb-kt:serverSmokeTest` pass with no new preload
  registration on an already-registered install; widening ok; ZombieBuddyKotlin ready.
- AC-5 (REQ-3): generated glue for `viewpoint-blood-fx-fix` and `ime-keyboard-fix` is equivalent before and after.
- AC-6 (REQ-4): a hook naming a missing class or method still fails the feature's gate with the class/method named.
- AC-7 (REQ-6): spec updated; `access-widening.md` no longer points at `agent/ZombieBuddyAgent.java`.

## Out of Scope

- Abstracting B (build plugin, signing, smoke launch) and mod.info / manifest formats.
- Implementing or choosing a second loader backend.
- Renaming ZombieBuddyKotlin / `zb-kt` (branding) or rewording log strings outside Zabric that name ZB (listed in the
  switch checklist instead).
