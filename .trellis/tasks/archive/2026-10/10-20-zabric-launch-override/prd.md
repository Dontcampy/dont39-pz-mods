# Zabric: mod folder override for one launch

## Goal

Give mods on Zabric what `10-20-mod-sync-java` needs and cannot do itself: load a Workshop item from another folder in
the next launch's startup pass, ask which Java jars are loaded and which one serves a package, and widen classes of
mods in a cache folder. Parent: [10-20-server-mod-sync](../10-20-server-mod-sync/prd.md) (D8); findings in
[java-mod-restart.md §4, §6](../10-20-server-mod-sync/research/java-mod-restart.md).

## Background

- The startup pass resolves every main-menu mod at `GameWindow.init` before any jar loads; a mod's Zabric hooks open
  only after a pass is processed, so no ordinary mod can change that pass.
- The consumer is Kotlin, and preLaunch code must be plain Java, so the override is applied by Zabric from a file read
  at premain, not by the consumer's hooks ([design.md](./design.md), "Decision"). This adds a second game advice to
  Zabric beside its preLaunch hook, a deliberate change to [access-widening.md](../../spec/mod/access-widening.md#zabric).
- ZombieBuddy is reached only through the loader seam
  ([zombiebuddy-patching.md](../../spec/mod/zombiebuddy-patching.md#loader-seam)); its load records carry id, jar,
  SHA-256 and reason but no package.
- Widening lists are read at premain from fixed roots (`ModFolders.roots()`); a cache folder outside them is not
  widened unless its list is byte-identical to an installed copy's.
- Zabric is published; its API only grows ([zabric-hooks.md](../../spec/mod/zabric-hooks.md#publishing-and-compatibility)).

## Requirements

- R1. An API to request, for the next launch only, that given Workshop items' `mods` folders be replaced by given
  folders. The request is consumed at that launch's premain (a crash later in it does not carry it over) and is
  visible in the log.
- R2. During that launch, the override applies to every pass (startup, joins, leaving), so a mod's Lua and Java stay
  from the same folder for the whole launch.
- R3. Loader-seam accessors: the jars loaded in this JVM (mod id, path, SHA-256, loaded at preload / the startup pass /
  later), the jars of the last pass, and the jar the class path serves a given Java package from.
- R4. Widening applies to mods in this launch's override folders and in folders registered as widening roots.
- R5. Refuses overrides for items that carry ZombieBuddy, Zabric or ZabricKotlin.
- R6. A launch without a request or registered roots behaves exactly as today.

## Acceptance Criteria

- [ ] R1/R2: a request written in one launch makes the next launch's startup pass load the named item (Lua and jar)
      from the named folder, also after a join and leaving; the launch after that is back to normal; the log names
      each applied item.
- [ ] R1: a launch that crashes after premain does not apply the request again.
- [ ] A mod in the main-menu list loads the override's jar, with ZombieBuddy's approval for its hash.
- [ ] R3: the accessors answer correctly for a package loaded at startup, at a join, and not at all.
- [ ] R4: a widened class of a mod in an override folder and in a registered root is widened.
- [ ] R5: a request naming such an item throws and writes nothing; such a file found at premain is discarded with an
      error at MAIN.
- [ ] Weave and unit tests; `smokeTest` and `serverSmokeTest` pass; released as a new Zabric version.

## Out of Scope

- Anything in `mod-sync` (deciding when to request, Rejoin, the dialog): `10-20-mod-sync-java`.
- Adding folders for mods that are not installed Workshop items (the parent's server-only follow-up).
- Overrides that last more than one launch.
