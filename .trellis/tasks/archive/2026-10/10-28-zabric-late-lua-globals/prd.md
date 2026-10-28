# Zabric: expose global Lua functions of jars loaded after Lua init

## Goal

A client that gets a ZombieBuddy mod only from the server's mod list gets that mod's global Lua functions: Zabric exposes them at the end of each ZombieBuddy pass when the current Lua state lacks them.

## Background

Root cause with evidence: [research/root-cause.md](research/root-cause.md). ZombieBuddy exposes `@LuaMethod(global = true)`
classes only when the game starts a Lua state; the join's `Core.ResetLua` starts it before it loads the server's mod
list, so a jar first loaded there never gets its global functions in that Lua state. Seen in the 2026-10-27 SCTD test
(st7b): All-in-One's appearance-override, sctd-render-takeover and network-status reported their Java side missing.

## Requirements

- At the end of each ZombieBuddy pass over the mod list, while a Lua state exists, every class ZombieBuddy found with
  global Lua functions has them in the current Lua state; classes whose functions are already there are left alone.
- At game start (no Lua state yet) Zabric does nothing; ZombieBuddy exposes them when Lua starts, as now.
- Generic: covers every ZombieBuddy mod (All-in-One, Server Reborn, others), not only Dont39 mods.
- One console line per class Zabric exposes, naming it; nothing on passes with nothing to do.
- A class that cannot be exposed is one error line naming it, once per session; the others are still exposed. A
  loader whose API does not match is one error naming the loader version, and this part stops for the session. Neither
  stops Zabric's entrypoints or hooks.
- Only Zabric's loader package uses ZombieBuddy's types (Loader Seam).
- Zabric version bump (0.7.2 → 0.7.3); README says what Zabric does for players.

## Acceptance Criteria

- [x] No Lua state → nothing (in game: no Zabric line at the client's or server's game start). Unit tests: missing globals → exposed under Kahlua's names; present → untouched (no new
      instance); a class that cannot be instantiated → one report across passes, the rest still exposed.
- [x] `./gradlew build` passes (including `checkLoaderRefs`).
- [x] In game on myserver2: a client whose menu mod list lacks Dont39AllInOne joins a server listing it; console shows
      Zabric's exposure line for All-in-One's bridge classes, and no "Java side is not loaded" / "Java part of Dont39
      All-in-One is not loaded" lines; appearance-override sync and network-status come up.
- [x] A client with All-in-One in its menu list still joins as before: no Zabric exposure line (ZombieBuddy already
      exposed them).
- [x] An upstream ZombieBuddy issue is drafted (not posted) in the task folder.

## Out of scope

- Launches where Zabric did not run at game start (its first launch): no pass hook; the existing restart notice covers it.
- Changing ZombieBuddy itself.
