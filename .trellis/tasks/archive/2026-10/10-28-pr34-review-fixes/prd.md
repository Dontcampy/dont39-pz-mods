# PR 34 review fixes

## Goal

Fix every finding of the architecture / performance / reuse review of PR 34 (branch
`feat/sctd-transmog-server-resnap-015452`): `appearance-override`, `sctd-render-takeover` (takeover and migration),
Zabric 0.7.3's late Lua globals. User: "Fix all" (2026-10-28).

## Requirements

### Must (bugs)

- R1 **No leak on a dedicated server.** Kahlua ignores `__mode` (no `__mode` in the game jar; `KahluaTableImpl` wraps a
  plain Map), so `setmetatable({}, {__mode="k"})` is a strong table. The migration's `scanned`/`left` keep every
  joined `IsoPlayer` and left items for the server's uptime. No Lua table in the PR may rely on weak keys; per-player
  state is pruned against `getOnlinePlayers()` (or keyed by something small, e.g. item id, and pruned), and comments
  that claim weak keys go.
- R2 **One broadcast per batch.** Auto-hide and its undo (and SCTD's `MigrateLegacy` through the takeover) publish the
  player's appearance set once, not once per item. The appearance API gets a write that does not publish (validated,
  item mod data synced); the takeover honours SCTD's `deferSync` and publishes once at the end. The migration and the
  client's skin edit use that API instead of writing `modData[Appearance.KEY]` directly.
- R3 **Zabric survives the next ZombieBuddy.** `Exposer.getClassesWithGlobalLuaMethod()` becomes package-private
  upstream (`7794d9f`). The late exposure keeps working with both the installed and the next ZombieBuddy, keeps its
  presence check (no re-exposing classes already in the env, no new instance of them) and its error on a silent skip.
  Loader knowledge (how ZombieBuddy instantiates a global class) lives in the seam (`Loader`), not in `LuaGlobals`.

### Should (architecture / reuse / performance)

- R4 Lua `log` / fail-once `guard` shared by shared and server files: move them to `allinone-common`'s
  `src/main/lua/shared/`, used by appearance-override and the takeover (all copies removed). Client users of
  `Dont39AllInOne_Common` keep working.
- R5 The look's fields are listed once (Kotlin parser); Lua's signature (`describe`) and the stored copy derive from it.
- R6 Server per-second publish check does not re-validate unchanged overrides (cache by override table identity, pruned
  like R1, no weak tables).
- R7 `luaClear` at a new session resets the models of players it dropped.
- R8 Takeover structure: busy flag through one helper (save/restore, error-safe), `converting` dropped; the generic
  source gate as its own module (`SourceGate`, FILES/BLOCKS passed in); `lookOf`/`copyLook` into appearance-override's
  Lua API; the 625-line server file split into runtime, authoritative and UI parts; the gate reads each file once
  (header index) instead of once per block; the look check, `idText`, the animal check and the 1 Hz ticker shared.
- R9 SCTD UI functions that are reachable globals/closures are wrapped, not copied, where SCTD's code allows it
  (e.g. by answering `SCTD_Transmog_IsInvisVariant` for the current item during the call, restored after); if a case
  cannot be wrapped safely, keep the gated copy and say why in design.md.
- R10 Zabric: `exposeMissing` runs regardless of an earlier entrypoint stop (still only when Zabric is loaded); tests
  cover once-per-session reporting, the unavailable latch and the null-env path; build-script and log nits.

## Constraints

Behaviour unchanged apart from the fixes (same records, overrides, log lines' meaning, gate strictness). Project rules:
mismatch is an error, never a silent degrade; prefer typed access, reflection only as last resort (with a comment why);
comments short, why only; idiomatic code over micro-allocation; no version bump (0.14.0 / Zabric 0.7.3 unreleased).

## Acceptance

- [x] Build, tests (`:zabric:test`, appearance-override tests), Kahlua syntax check, BlockHashes, smokeTest pass.
- [x] In game on myserver2: auto-hide with a body form → one `set` broadcast; migration converts as before and a
      rejoin leaves no growing state; takeover hide/transmog/skin panel as before; a join-only Java mod gets its Lua
      globals (Zabric).
- [x] No `__mode` left in the PR's Lua; no copy of log/guard helpers.
