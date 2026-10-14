# Mod Code Guidelines (Kotlin / Java / ZombieBuddy)

> How code is written in this repository: Java mods for Project Zomboid Build 42, loaded and patched at runtime by
> ZombieBuddy.

---

## Overview

One Gradle build ships three Workshop items, layered as a small platform for ZombieBuddy Java mods:

- **Zabric** (`zabric`), the loader layer: access widening, phased entrypoints and preload registration
  ([Access Widening](./access-widening.md)), and the only runtime code that uses ZombieBuddy
  ([Loader Seam](./zombiebuddy-patching.md#loader-seam)).
- **ZombieBuddyKotlin** (`zb-kt`), the Kotlin layer on Zabric, including `zb-kt-utils`
  ([Kotlin Runtime](./kotlin-runtime.md)).
- **Dont39 All-in-One** (`dont39-all-in-one`), one consumer of both. Each fix is its own Gradle subproject (a
  *feature module*), and `dont39-all-in-one` packages all of them into one jar and mod folder.

The runtime of the two lower layers is public for other mods; the build tooling (KSP processor, widening Gradle logic)
stays in this repo.

Mod code is written in Kotlin. Features switch themselves off when the game or a target mod does not match what they
expect.

Lua is used only for small client-side glue such as mod options (each feature's `src/main/lua/`). There is no server
or database code; this `mod` layer is the only spec layer.

**These specs state rules and point at code. The code is the source of truth for details** (names, constants, exact
messages, signatures). When writing or updating a spec, link the file instead of restating what it does.

Reference implementation for every rule: the `viewpoint-blood-fx-fix` module. ZombieBuddy's docs and source are in the
`external-repos/ZombieBuddy` submodule; see [ZombieBuddy Patching](./zombiebuddy-patching.md#zombiebuddy-reference).

---

## Guidelines Index

| Guide | Description |
|-------|-------------|
| [Directory Structure](./directory-structure.md) | Modules, packages, mod assets, naming, adding a feature |
| [ZombieBuddy Patching](./zombiebuddy-patching.md) | Loader seam, patch classes, reaching other mods, gating, enter/exit restore |
| [Access Widening](./access-widening.md) | Reaching game and mod members, `.classtweaker`, Zabric |
| [Error Handling](./error-handling.md) | Never crash the game, never hide a problem: gate, report, disable |
| [Logging Guidelines](./logging-guidelines.md) | Logger, prefix, lifecycle lines, debug output |
| [Quality Guidelines](./quality-guidelines.md) | Build gate, in-game verification, style, forbidden patterns |
| [Kotlin Runtime](./kotlin-runtime.md) | Rules for Kotlin modules and the library mod, version bumps |
| [ZombieBuddy Kotlin Utils](./zb-kt-utils.md) | Runtime every Kotlin feature builds on: feature base, hook and widening checks |

---

## Pre-Development Checklist

1. [Directory Structure](./directory-structure.md) — always; it holds the "Adding a Feature" checklist.
2. [ZombieBuddy Patching](./zombiebuddy-patching.md) — for anything that hooks game or mod code.
   [Access Widening](./access-widening.md) — for anything that reads or calls game or mod members.
   [Kotlin Runtime](./kotlin-runtime.md) — for any Kotlin code, version bump, or change to `zb-kt`.
   [ZombieBuddy Kotlin Utils](./zb-kt-utils.md) — for a Kotlin feature or a change to that module.
3. [Error Handling](./error-handling.md) and [Logging Guidelines](./logging-guidelines.md) — for any runtime code.
4. Read the reference files those guides link before copying a pattern.

## Quality Check

1. Build gate passes ([Quality Guidelines](./quality-guidelines.md#build-gate)).
2. Hooks leave gating and catching to the generated glue; what an enter changes, its paired exit restores.
3. Logging follows [Logging Guidelines](./logging-guidelines.md); nothing logs per frame by default.
4. New target mods are declared in `dont39-all-in-one/mod/42/mod.info`; a Lua-only feature's `ready` line is in
   `smokeTest`.
5. Kotlin code follows [Kotlin Runtime](./kotlin-runtime.md).
6. In-game check done, or explicitly reported as not done.

---

**Language**: All documentation should be written in **English**.
