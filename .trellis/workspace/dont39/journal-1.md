# Journal - dont39 (Part 1)

> AI development session journal
> Started: 2026-10-12

---



## Session 1: Bootstrap Trellis spec for ZombieBuddy mods
<!-- trellis-session: v=2 fp=bfd5365ce2b73e2e -->

**Date**: 2026-10-12
**Task**: Bootstrap Trellis spec for ZombieBuddy mods
**Branch**: `main`

### Summary

Replaced template backend/frontend specs with .trellis/spec/mod/ (index, directory-structure, zombiebuddy-patching, error-handling, logging, quality) for the Java ZombieBuddy mod repo. Specs state rules and link code files and the external-repos/ZombieBuddy docs/source instead of restating logic. Verified the @Patch/@LuaMethod exact-javaPkgName rule in PatchEngine.collectPatches (ModdingGuide example subpackage would not load). Synced with cbad9ce (mod/42 layout, Lua mod option, signing, smokeTest; new features must extend smokeTest expectations). Build gate needs a JDK 25 Gradle runtime; build assembleMod passed. Kept DebugType.Mod over ZombieBuddy's internal Logger. Bootstrap task archived.

### Git Commits

| Hash | Message |
|------|---------|
| `76fa026` | Remove outdated documentation files for database, directory structure, error handling, and quality guidelines |

### Status

[OK] **Completed**


## Session 2: ZombieBuddyKotlin library mod and Kotlin All-in-One
<!-- trellis-session: v=2 fp=cd2213133ef50829 -->

**Date**: 2026-10-12
**Task**: ZombieBuddyKotlin library mod and Kotlin All-in-One
**Branch**: `feat/kotlin-lib`

### Summary

Added ZombieBuddyKotlin, a prerequisite Workshop mod that swaps the game's bundled kotlin-stdlib 1.4.10 for 2.4.21 via the bootstrap class loader (MAIN phase, SHA-256 checked) and ships kotlinx-coroutines/serialization 1.11.0 unrelocated. New pz-kotlin and pz-mod build conventions. All-in-One 0.2.0: Kotlin Main, requires and loads after ZombieBuddyKotlin. smokeTest passed in game (ready: Kotlin 2.4.21); negative corrupted-stdlib check skipped. Specs: kotlin-runtime.md, trimmed to rules and links. PR Dontcampy/dont39-pz-mods#1. Next: upload ZombieBuddyKotlin first, record its Workshop id, link it from All-in-One's workshop.txt, then upload All-in-One. KSP deferred until a second Kotlin feature shows repeated boilerplate.

### Git Commits

| Hash | Message |
|------|---------|
| `7803c3d` | Add ZombieBuddyKotlin, a Kotlin runtime library mod |
| `9a9e383` | Move All-in-One's Main to Kotlin and require ZombieBuddyKotlin |
| `852ec6c` | Document the Kotlin runtime and the second shipped mod in the specs |
| `bbceb55` | Trim the specs to rules and links |

### Status

[OK] **Completed**


## Session 3: Kotlin utils, KSP glue, viewpoint in Kotlin, Mod Options sidebar
<!-- trellis-session: v=2 fp=32bebe155a61e1c0 -->

**Date**: 2026-10-12
**Task**: Kotlin utils, KSP glue, viewpoint in Kotlin, Mod Options sidebar
**Branch**: `feat/kotlin-features-sidebar`

### Summary

Added zombiebuddy-kotlin-utils (Feature/Resolver), KSP glue generation (@Hook/@LuaGlobal -> generated @Patch/@LuaMethod in com.dont39.pz.allinone, KSP 2.3.12), ported viewpoint-blood-fx-fix to pure Kotlin, added the Options > Mods sidebar (jump + highlight, last choice saved across restarts), All-in-One 0.3.0. build + smokeTest pass; in-game checks and corrupted-stdlib negative check not done. Follow-ups: reject @Patch.Local, type-check Thrown/Return/AllArguments params.

### Git Commits

| Hash | Message |
|------|---------|
| `5ca2f8e` | Add zombiebuddy-kotlin-utils and KSP glue generation |
| `594f9bb` | Port viewpoint-blood-fx-fix to Kotlin |
| `6d0f9ec` | Add a sidebar to Options > Mods |
| `39789de` | Wire the new modules into All-in-One; 0.3.0 |
| `b837be4` | Update the mod specs for Kotlin features, generated glue and Lua-only features |

### Status

[OK] **Completed**


## Session 4: PR #2 follow-ups: processor binding checks, no ZombieBuddyKotlin fallback, comment/spec trims
<!-- trellis-session: v=2 fp=2a8141ee5d8b0381 -->

**Date**: 2026-10-12
**Task**: PR #2 follow-ups: processor binding checks, no ZombieBuddyKotlin fallback, comment/spec trims
**Branch**: `feat/kotlin-features-sidebar`

### Summary

Processor rejects @Patch.Local and checks Thrown/AllArguments/Argument/Return types. Specs trimmed to rules and links. Dropped the ZombieBuddyKotlin-not-ready fallback (user: never handle that case): Main no longer checks readiness, Feature uses an enum state without INERT, library isReady()/problem() removed (ZombieBuddyKotlin 0.2.0), last 1.4.10 workarounds removed. BloodFX debug field resolved once. Feature modName is required. Comments cut to the non-obvious why. build + smokeTest pass; in-game checks still open on PR #2.

### Git Commits

| Hash | Message |
|------|---------|
| `b6ffd1b` | Check hook parameter bindings and types at compile time |
| `563dcd6` | Trim the mod specs to rules and links |
| `2c3452c` | Drop the fallback for ZombieBuddyKotlin not being ready |
| `f9c4a89` | Remove ZombieBuddyKotlin's readiness API; 0.2.0 |
| `69636d5` | Drop the last workarounds for the game's old Kotlin |
| `4abf653` | Resolve BloodFX's debug field once |
| `1463527` | Make Feature's mod name a required argument |
| `cdb4e1e` | Shorten the comments to the non-obvious reasons |

### Status

[OK] **Completed**


## Session 5: IME keyboard fix
<!-- trellis-session: v=2 fp=a2d7ed94fba9bcec -->

**Date**: 2026-10-12
**Task**: IME keyboard fix
**Branch**: `main`

### Summary

Added ime-keyboard-fix: on Windows the IME is detached from the game window outside text boxes (ImmAssociateContextEx, IACE_DEFAULT on re-attach keeps CN/EN mode), hooked at Display.processMessages exit for the window thread. smokeTest and in-game checks passed; patching spec updated; PR Dontcampy/dont39-pz-mods#3. Found that the bundled GLFW hides the IME composition/candidate UI (vanilla) - next task.

### Git Commits

| Hash | Message |
|------|---------|
| `a8aaf8b` | Add ime-keyboard-fix: keep the IME out of the game window outside text boxes |

### Status

[OK] **Completed**


## Session 6: IME candidate windows
<!-- trellis-session: v=2 fp=ebb0c9ccc0d33caa -->

**Date**: 2026-10-13
**Task**: IME candidate windows
**Branch**: `feat/ime-keyboard-fix`

### Summary

ime-keyboard-fix now subclasses the game window procedure to show the system IME composition/candidate windows (GLFW hides them), swallows WM_IME_CHAR, and places them at the UITextBox2 caret. Works in windowed mode; in the game's borderless mode (undecorated desktop-sized window) Windows treats the game as fullscreen and WeChat IME hides its windows - accepted limitation. Fixed Resolver.virtual default asType. Pushed to PR Dontcampy/dont39-pz-mods#3.

### Git Commits

| Hash | Message |
|------|---------|
| `31c8d9e` | Show the IME composition and candidate windows at the text caret |

### Status

[OK] **Completed**


## Session 7: Typed access via access widener (Zabric)
<!-- trellis-session: v=2 fp=eb89c50652dced4c -->

**Date**: 2026-10-13
**Task**: Typed access via access widener (Zabric)
**Branch**: `feat/ime-keyboard-fix`

### Summary

Replaced reflection with typed access: target mod jars on the compile classpath (pzTargets), Fabric-style .classtweaker widening at build time, and Zabric (new unsigned Java preload mod) widening the same members at runtime before classes load; it self-registers in ZombieBuddy config.json under policy=allow-all and shows a restart dialog. @Advice.FieldValue was tried first and fails in ZombieBuddy 2.3.4 (silently not woven). Migrated ime-keyboard-fix and viewpoint-blood-fx-fix, removed Resolver, rewrote specs. Verified by smokeTest and in game. PR Dontcampy/dont39-pz-mods#3.

### Git Commits

| Hash | Message |
|------|---------|
| `6634038` | Reach game and target-mod members by direct access instead of reflection |

### Status

[OK] **Completed**


## Session 8: Idiomatic Kotlin, lazy logging, module rename
<!-- trellis-session: v=2 fp=7d442686f7486225 -->

**Date**: 2026-10-13
**Task**: Idiomatic Kotlin, lazy logging, module rename
**Branch**: `feat/ime-keyboard-fix`

### Summary

Trimmed specs to rules and links. Rewrote the Kotlin code to kotlinlang.org idioms and coding conventions (forEach, property syntax, check/error, coerceAtMost) and dropped the spec bans on iterators/lambdas/allocation on per-frame paths, since the JIT handles short-lived objects. Added LoggerUtils (lzInfo/lzDebug/lzWarn DebugType extensions that check severity before building the message); Feature.log/debug wrap them with the prefix. Java logs use DebugType format arguments with compile-time-constant patterns; printException keeps concatenation because the game's varargs overload drops its arguments. Removed redundant 'as Any' casts and the wrong varargs-overload spec example. Added Kotlin/Java wrong-vs-correct logging examples to logging-guidelines.md. Renamed module zombie-buddy-kotlin to zombiebuddy-kotlin. smokeTest passes.

### Git Commits

| Hash | Message |
|------|---------|
| `5f15a46` | Trim the specs to rules and links |
| `22c5703` | refactor: simplify property access and improve readability |
| `2eeac59` | Write idiomatic Kotlin and build log messages only when they are written |
| `3539b32` | docs: update logging guidelines to clarify message building rules |
| `bac5b39` | Rename the zombie-buddy-kotlin module to zombiebuddy-kotlin |

### Status

[OK] **Completed**


## Session 9: Zabric as a standalone platform, ZombieBuddyKotlin built on it
<!-- trellis-session: v=2 fp=d762784848826b04 -->

**Date**: 2026-10-13
**Task**: Zabric as a standalone platform, ZombieBuddyKotlin built on it
**Branch**: `zb-kt-on-zabric`

### Summary

Planned (D1-D10) and implemented: Zabric as its own Workshop item that widens every installed mod's .classtweaker list at premain with per-mod status, a public preload registration service with an order-independent handshake and identical-copy rule, and a restart dialog listing mods; ZombieBuddyKotlin preloaded through Zabric, Kotlin swap at premain only, zb-kt-utils shipped inside it, isReady() gate in generated glue and Main; All-in-One ships its own merged list. Verified with client smokeTest (register, then pass), new serverSmokeTest on the dedicated server, and a scratch broken-list mod. Fixed load-order registration bug and pre-existing configuration-cache problems. Specs trimmed to rules and links after user feedback. PR Dontcampy/dont39-pz-mods#5. Not verified: mid-session restart-list path; in-game IME/blood check.

### Git Commits

| Hash | Message |
|------|---------|
| `6264e41` | Make Zabric a standalone platform and build ZombieBuddyKotlin on it |
| `54ccb04` | Trim the specs to rules and links |

### Status

[OK] **Completed**
