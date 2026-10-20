# Zabric: mod folder override for one launch

## Goal

Give mods on Zabric what `10-20-mod-sync-java` needs and cannot do itself: load a mod id from another folder in the
next launch's startup pass, ask which Java packages are loaded, and widen classes of mods in a cache folder. Parent:
[10-20-server-mod-sync](../10-20-server-mod-sync/prd.md) (D8); findings in
[java-mod-restart.md §4, §6](../10-20-server-mod-sync/research/java-mod-restart.md).

## Background

- The startup pass resolves every main-menu mod at `GameWindow.init` before any jar loads; a mod's Zabric hooks open
  only after a pass is processed, so no ordinary mod can change that pass.
- Zabric has no patches of its own and its premain/preLaunch code touches no game class
  ([access-widening.md](../../spec/mod/access-widening.md#zabric)); ZombieBuddy is reached only through the loader seam
  ([zombiebuddy-patching.md](../../spec/mod/zombiebuddy-patching.md#loader-seam)). The design has to fit these rules or
  change them on purpose.
- Widening lists are read at premain from fixed roots (`ModFolders.roots()`); a cache folder outside them is not
  widened unless its list is byte-identical to an installed copy's.
- Zabric is published; its API only grows ([zabric-hooks.md](../../spec/mod/zabric-hooks.md#publishing-and-compatibility)).

## Requirements

- R1. An API to request, for the next launch only, that given mod ids resolve to given folders. The request is
  consumed by that launch (a crash before it does not leave it applying to later launches) and is visible in the log.
- R2. During that launch, the override applies to every pass (startup, joins, leaving), so a mod's Lua and Java stay
  from the same folder for the whole launch.
- R3. A loader-seam accessor: which jar (path, SHA-256) is loaded for a Java package in this JVM, and which jars the
  last pass loaded.
- R4. Widening applies to mods in folders an override or a registered cache root names.
- R5. Refuses overrides for ZombieBuddy, Zabric and ZabricKotlin.

## Open Design Choices (decided when this child is planned)

- Where the override is applied before the startup pass: an early hook capability for a preloaded mod (the feature
  would need a preLaunch entrypoint and become a preload mod), or a Zabric-owned transform of the folder resolution
  driven by a file read at premain. Either changes a rule in the specs above; the child's design records which.
- File format and location of the one-launch request.

## Acceptance Criteria

- [ ] A request written in one launch makes the next launch's startup pass load the named mod (Lua and jar) from the
      named folder; the launch after that is back to normal.
- [ ] A mod in the main-menu list loads the override's jar, with ZombieBuddy's approval for its hash.
- [ ] The accessors answer correctly for a package loaded at startup, at a join, and not at all.
- [ ] A widened class of a mod in a cache folder is widened.
- [ ] Weave and unit tests; `smokeTest` and `serverSmokeTest` pass; released as a new Zabric version.
