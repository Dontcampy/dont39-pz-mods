# Temporary fix for SCTD TransmogSync.Flush next() error

## Goal

Stop the per-tick `Object tried to call nil in Flush` error that Shadow Cat Tactics: Dispatch throws on multiplayer
clients, from inside Dont39 All-in-One, until the mod's author fixes it. The patch must not modify or shadow the
mod's files (multiplayer checks Lua checksums) and must stand down by itself once the author's fix ships.

## Background

Target mod (local Workshop copy, read 2026-10-17):

- Mod id `ShadowCatTacticsDispatch`, Workshop id 3689462578, `SCTD_VersionInfo.txt` = `131`; `mod.info` has no
  `modversion`. File `common/media/lua/shared/LingLi_SCTD_Skins.lua` (3870 lines, modified 2026-10-17 00:45).
- `SCTD_TransmogSync.Flush()` (lines 2014-2021) runs on every `OnTickEvenPaused` and `OnContainerUpdate` (registered
  by function value, lines 2024-2025) and calls `next(state.pending)` on line 2019.
- Kahlua has no `next`: `se.krka.kahlua.stdlib.BaseLib` (42.21 jar) registers pcall, print, select, type, tostring,
  tonumber, get/setmetatable, error, unpack, set/getfenv, rawequal, rawset, rawget, collectgarbage, debugstacktrace,
  bytecodeloader; no `next`. The author already avoids `next()` elsewhere in the same file (lines 840, 2826, 3035)
  and in BlinkSkin / LipsSkin.
- `state` exists only after the client got a transmog sync packet (`Receive`, line 1999-2004), and `Flush` returns
  early unless `isClient()`. So: multiplayer clients only, from the first transmog sync on, every tick. Pending
  syncs are then only retried when a new packet arrives, and their 30 s timeout cleanup in `FlushPlayer` never runs
  from `Flush`.

Source: user's bug report (pasted by the user, 2026-10-17): thousands of errors per session, stutter, started with the
2026-10-17 update.

## Requirements

- R1. A Lua-only feature module in Dont39 All-in-One replaces `SCTD_TransmogSync.Flush` at runtime (table field and
  both event registrations) with the same logic, checking `state.pending` for entries with `pairs` instead of
  `next`.
- R2. Gate, decided once per Lua load:
  - target mod not active → idle, with the reason;
  - the mod's `Flush` source is exactly the known buggy text and the live `Flush` function is the one defined there →
    apply;
  - the `Flush` source no longer calls `next(` → idle ("fixed upstream, not needed"), nothing replaced;
  - anything else (source or globals missing, a different `Flush` that still calls `next(`, the live function from
    elsewhere) → error, feature disabled, nothing replaced.
- R3. Errors from `FlushPlayer` reach the game as without this mod (not caught by the feature).
- R4. Declared and documented like other features: module + registration, Workshop section, version bump,
  `smokeTest` status check. No `loadModAfter` entry (see `design.md`).

## Acceptance Criteria

- [x] AC1 (R2). `smokeTest` with the user's mod list (ShadowCatTacticsDispatch enabled) shows
  `[Dont39AllInOne] sctd-transmog-flush-fix: ready` and passes.
- [x] AC2 (R4). `./gradlew build assembleMod` passes; the assembled mod has the Lua file.
- [ ] AC3 (R1). On a multiplayer client after a transmog change, `console.txt` has no `tried to call nil in Flush`.
  Needs a server running the mod; done by the user, or reported as not done.
- [x] AC4 (R2). Gate outcomes checked by editing the expected text in a scratch copy (not shipped): a mismatch logs
  `disabled`, a `Flush` without `next(` logs idle. Done in the smoke game, or reported as not done.

## Out of Scope

- Other SCTD mods (FordCVPI's `:next()` calls are Java iterator methods, not the bug).
- Defining a global `next` (would change behavior for every mod).
