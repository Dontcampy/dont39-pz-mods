# Implement: sctd-transmog-fix

Gradle: `JAVA_HOME=~/.jdks/graalvm-ce-25.0.2`. Pattern for logging and disable: `git show d57f9dc` (the removed
`sctd-transmog-flush-fix`); module file pattern: `tunnel/src/main/lua/client/Dont39AllInOne_TunnelShared.lua`.

## Steps

1. Module `sctd-transmog-fix/`: `build.gradle.kts` with `buildsrc.convention.pz-bundled-feature` (as
   `mod-options-sidebar/`); register in `settings.gradle.kts`; add to `features` and to `luaTargeted` in
   `dont39-all-in-one/build.gradle.kts`.
2. `src/main/lua/shared/Dont39AllInOne_SctdTransmogFixShared.lua`: module table with `log`, `fail` (disable + error
   line), the target check, `installFluids()`, `installResnap()`, `disable()`; idempotent per Lua state.
3. Fluids part (design "Part fluids"): the three wrappers, the context stack and `instanceItem` interceptor, the
   copy rule, the self-check, `disable` restoring globals.
4. Resnap part (design "Part resnap"): the `OnTick` scan with a weak-keyed `done` table, `pcall` per player.
5. Entries: `client/Dont39AllInOne_SctdTransmogFix.lua` and `server/Dont39AllInOne_SctdTransmogFixServer.lua`
   (returns on `isClient()`), each logging one status line.
6. Workshop: one section in `dont39-all-in-one/mod/workshop/workshop.txt` (what it does for players, temporary, idle
   without SCTD); bump `version` in `dont39-all-in-one/build.gradle.kts` (0.13.0 → 0.14.0).

## Gate

```
./gradlew build assembleMod
./gradlew :dont39-all-in-one:smokeTest
```

`smokeTest` with SCTD enabled in the client's mod list must show `sctd-transmog-fix: ready`; check `idle` once with
SCTD disabled (the mod list is rewritten by the launcher: check `default.txt` before and restore after). Temporary
edits (a missing global; a forced interceptor mismatch) show the `disabled` paths; revert them.

Review gate: `trellis-check` on the diff before the in-game run.

## In-game (myserver2, recipe in the archived `10-27-server-reborn-sync-false-repairs/research/in-game.md`)

- Back up `myserver2_Data` (ini, mods, ZombieBuddy config, save, db, Lua) first; own RCON port; restore after.
- Server mods: ZombieBuddy, Zabric, ZabricKotlin, Server Reborn with `-Dserverreborn.debugFieldDiff=true`, this
  All-in-One build, SGTestDriver, SCTD (same Workshop copy as the client's).
- Client: `backup-client.ps1`, `setup-server.ps1 -Minimal -WithServerReborn`, SCTD and this All-in-One into
  `~/Zomboid/mods` and the manifest, `start-client.ps1 -ServerHost 192.168.50.249 -ServerPort 48261`, `cleanup.ps1`
  at the end.
- New SGTestDriver test `sctdTransmog`, run on the server: give and wear a `KnapsackSprayer` with water and a
  `Bag_Satchel`; `SCTD_Transmog_HideItem_Server` both; report fluid amount/capacity of the placeholder on server and
  client; add water; `SCTD_Transmog_ShowItem` the sprayer and report again; hide it again and leave it hidden.
- Runs: first join (new character, Server Reborn inactive) to set up; server restart; second join: no `weight{...}`
  diff or repair for the placeholders, the hidden sprayer's amount equal on both sides. One run with SCTD but without
  this feature (the bug as shipped) as the control.
- Lines of each run under this task's `research/in-game-<date>/`, results in `research/in-game.md`.

## Finish

Spec: `.trellis/spec/mod/directory-structure.md` (the "fixes another mod's Lua" paragraph: a feature that must act on
the server has `client/` and `server/` entries over a `shared/` module; wrapping a target's globals instead of a
source-text gate, with a runtime self-check). Commit on this branch, PR to `main`, no Workshop upload.
