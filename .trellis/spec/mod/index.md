# Mod Code Guidelines (Java / ZombieBuddy)

> How code is written in this repository: Java mods for Project Zomboid Build 42, loaded and patched at runtime by
> ZombieBuddy.

---

## Overview

One Gradle build ships one Workshop mod, **Dont39 All-in-One**. Each fix is its own Gradle subproject (a *feature
module*); `dont39-all-in-one` packages all features into one jar and mod folder. Features patch other mods that are not
compile dependencies, so they reach them by reflection and switch themselves off when anything does not match.

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
| [ZombieBuddy Patching](./zombiebuddy-patching.md) | Patch classes, reaching other mods, gating, enter/exit restore |
| [Error Handling](./error-handling.md) | Never crash the game: probe, degrade, disable |
| [Logging Guidelines](./logging-guidelines.md) | Logger, prefix, lifecycle lines, debug output |
| [Quality Guidelines](./quality-guidelines.md) | Build gate, in-game verification, style, forbidden patterns |

---

## Pre-Development Checklist

1. [Directory Structure](./directory-structure.md) — always; it holds the "Adding a Feature" checklist.
2. [ZombieBuddy Patching](./zombiebuddy-patching.md) — for anything that hooks game or mod code.
3. [Error Handling](./error-handling.md) and [Logging Guidelines](./logging-guidelines.md) — for any runtime code.
4. Read the reference files those guides link before copying a pattern.

## Quality Check

1. Build gate passes ([Quality Guidelines](./quality-guidelines.md#build-gate)).
2. Every patch entry is gated and every runtime path ends in restore + disable on failure.
3. Logging follows [Logging Guidelines](./logging-guidelines.md); nothing logs per frame by default.
4. New target mods are declared in `dont39-all-in-one/mod/42/mod.info`; `smokeTest` expectations cover new classes.
5. In-game check done, or explicitly reported as not done.

---

**Language**: All documentation should be written in **English**.
