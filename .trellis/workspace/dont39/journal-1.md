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
