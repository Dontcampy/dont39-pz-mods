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
