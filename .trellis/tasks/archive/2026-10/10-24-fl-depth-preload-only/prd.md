# Faster Loading depth map order without its main entrypoint

## Goal

Faster Loading's depth map order works, or stays idle with its reason, in a session where the mod is preloaded but
the session's mod list lacks it. Today its first depth maps stop all of the mod's hooks for the session.

## Root cause

- Zabric calls a mod's main entrypoint only after a loader pass that loaded the mod's jar for the mod list
  (`zabric/.../entrypoint/Lifecycle.java`, `modsLoaded` → `load`). A mod that is only preloaded never gets it.
- Faster Loading is preloaded with launch-wide hooks on purpose (`PreLaunchEntry.java`), so its hooks run in such
  sessions.
- `DepthOrder.widened()` threw unless `MainEntry` had reported the access widener first, so the first
  `TileDepthTextureManager.init` threw from `DepthHooks.initBegins` and Zabric stopped all of the mod's hooks
  (comment stripping, texture compression, Lua folder walk included).

Evidence (`~/Zomboid/Logs`, 2026-10-20 to 2026-10-24): every client run with the error has no
`[Zabric] main: Dont39FasterLoading`; no run with that line has the error. All 33 harness client runs under
`%TEMP%\sgtest\runs` have it: the harness's client mod list is the server's, which lacks the mod. The user's own
`default.txt` lists the mod, so their normal launches and Steam joins run its main entrypoint at game start and do
not hit it.

## Requirements

- The widener status is asked of Zabric (`AccessWidener.report`) once per session, at the first of: the main
  entrypoint, or the first depth-map queue with the fix on. The answer and its log line hold for the session.
- With the list in effect, the depth map order runs in a preload-only session as it does with the mod in the list.
- With the list not in effect, the fix stays idle with the reason logged; nothing throws.
- A mod-list session keeps its current log: the widener line right after `<version> loaded` (the smoke test reads it).

## Acceptance Criteria

- [x] `./gradlew :faster-loading:build` green.
- [x] A harness client run (preload-only, `-nosteam` join of the 244-mod server) logs no `hooks stopped` for
      `Dont39FasterLoading`, logs `[Dont39FasterLoading] access widener: ok`, and `depth map order:` lines (`research/harness-join.md`, 2026-10-26).
- [x] Spec line in `access-widening.md` updated to say where the status is asked.

## Out of scope

- Zabric running main entrypoints for preload-only mods (a loader-layer behaviour change for every mod).
