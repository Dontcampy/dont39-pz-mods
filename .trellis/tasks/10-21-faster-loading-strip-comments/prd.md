# Faster Loading mod and comment stripping (R3)

Parent: `.trellis/tasks/10-21-load-speed-optimization` (Goal, evidence and decisions live there).

## Goal

Create the new client-only mod that will hold every load speed-up, with its first fix: a linear
`ScriptParser.stripComments` that saves ~2 s per launch, join and Lua reload.

## Requirements

- New Gradle module and Workshop mod, client-only, preloaded with `Zabric-Hooks: launch`, Java hooks with JDK-only
  glue (pattern: `loading-progress/build.gradle.kts`, `loading-progress/README.md` "Constraints").
- Switches: an ini in the game cache's `Lua` folder, one key per fix, on by default; the mod options page shows them;
  a change applies from the next launch (pattern: `loading-progress` `progress/Settings.java` and its options Lua).
- With a switch off, the hooked method runs the game's own code.
- A target that does not match (missing method, changed signature, 42.21 revision mismatch) is reported and the mod
  disabled, never silently bypassed (`.trellis/spec/mod/error-handling.md`).
- R3: a non-cancelling `@ModifyVariable` at HEAD on `ScriptParser.stripComments(String)String`'s argument hands the
  game's method a linear implementation's result, which that method returns unchanged in one scan. The result must
  equal the original's for every input, including its quirks
  (`../10-21-load-speed-optimization/research/strip-comments.md`), and the game's method must be a fixpoint on it.
- Loading Progress keeps timing the call as a known call: the game's method is never cancelled, so its bracket runs in
  either advice order (when ours is outer, the bracket times only the game's scan; the step time still holds both).

## Acceptance Criteria

- [ ] Unit test: the replacement equals the real 42.21 method byte for byte on the vanilla and every installed mod's
  script and tile-geometry files and on the quirk cases, and the real method returns it unchanged (the jar is the
  reference, run in the test).
- [ ] In-game, the stripComments known-call time drops by ≥ 1.5 s per launch and per join (median of 3 runs, switch
  on vs off), read from Loading Progress reports.
- [ ] Launch and an MP join to the Linux test server: no new errors in `console.txt`, no checksum mismatch.
- [ ] Build gate passes; README and Workshop text written; the mod staged for the user's own upload.

## Out of Scope

- Other fixes (separate children).
