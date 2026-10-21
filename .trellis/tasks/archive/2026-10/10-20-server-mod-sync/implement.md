# Implement: server mod sync (parent)

The parent has no code of its own. It orders the children and owns the integration review.

## Order

1. `10-20-mod-sync-core`: planned in full now; starts first. Until `mod-sync-java` lands, a mismatched item whose
   download would change a jar stops the join with a reason.
2. `10-20-zabric-launch-override`: can run in parallel with core (different module). Its design is written when it is
   planned (its PRD lists the open design choices); its Zabric release goes out before `mod-sync-java` ships.
3. `10-20-mod-sync-java`: after both; planned in full when it starts.

Each child goes through its own review gate before `task.py start`.

## Integration Review (after the last child)

- [x] Run the parent's cross-child acceptance criteria on the Linux test server and a Windows client.
- [x] Vanilla client on the modded server; modded client on a vanilla server.
- [ ] Release: Zabric 0.5.0 first (its child left the version at 0.4.1: first to ship takes 0.5.0, see
      `10-20-zabric-launch-override` design "Compatibility"), then ZabricKotlin 0.6.0 (`Feature.error`, review F2), then
      Dont39 All-in-One 0.12.0 with the feature; version bumps per [kotlin-runtime.md](../../spec/mod/kotlin-runtime.md).
- [x] Spec update for the new patterns (connection-details trailer, `CheckMods` hold, folder swap):
      [zombiebuddy-patching.md "Extending the Join"](../../spec/mod/zombiebuddy-patching.md#extending-the-join).

### Integration Plan (2026-10-21)

All four children are on `claude/mod-sync-relaunch-fce8bc`. One run of two Linux test instances, both started on the
test item's current Steam version (v6): `myserver2` (mod-sync port 48310) and `myserver3` (Dont39 All-in-One, port 0,
game port 48271). Windows client with this branch deployed; a temporary Lua driver acts through real buttons.

| Round | Setup | Parent criterion |
|---|---|---|
| R0 | client v6 = servers v6 | Steam path, no mod-sync fetch (client log: no `preparing`, no manifest) |
| upload 1 (user) | v7: Lua only, v6's jar byte for byte | |
| R1 | client v7 → myserver2, `DoLuaChecksum=true` | join, only the changed Lua downloads, checksum passes, session runs v6 Lua; after leaving, main menu runs v7 |
| R2 | client without ZombieBuddy (json backed up) → myserver2 | vanilla `VersionMismatch` |
| R3 | client v7 → myserver3 (port 0) | vanilla `VersionMismatch` |
| R4 | client v7 → myserver2: decline the dialog; then a server file changed after start (restored after) | stop with a reason (declined, hash mismatch); HTTP errors and refused items stay unit-tested |
| upload 2 (user) | v8: new jar | |
| R5 | client v8, test item enabled on the main menu → myserver2 | restart stop → Restart game → server's jar and Lua; next launch back to v8 |

Then: PR into `main`, Zabric 0.5.0 (tag `zabric-v0.5.0`, Maven Central run approved by the user), Workshop uploads by
the user (Zabric, then Dont39 All-in-One 0.12.0).

### Integration Results (2026-10-21)

Branch `claude/mod-sync-relaunch-fce8bc` (all four children, `cranky-pike` merged); myserver2 (port 48310,
`DoLuaChecksum=true`) and myserver3 (port 0) started on v6; the user uploaded v7 (Lua only, v6's jar byte for byte)
and later v8 (new jar) while both ran. A temporary driver in a client-only local mod (the join's Lua checksum and Lua
reset leave it out) and window messages for "click to start" and the in-game menu; all removed after.

- R0, v6 = v6: Workshop path; mod-sync logged only the location at `joinStarts`, no manifest fetch, no `preparing`.
- R1, v7 → myserver2: confirm, "takes 4 files from your copy and 2 files (351 bytes) from the server", no restart;
  `DoLuaChecksum` start/end, in game with the server's `version 6` Lua; leaving: `swap off: the game ended`, the main
  menu then printed the client's `version 7`. A second join took the tree "ready from an earlier join" with no dialog.
- R2, without ZombieBuddy (json edited, restored): vanilla "已安装插件和服务端插件版本不匹配".
- R3, myserver3: "no mod sync, as the server sent no mod sync location", then the same vanilla error.
- R4, myserver2: decline → "加入已停止：你选择不下载服务器版本的创意工坊物品。"; a served file changed after start →
  stop naming the size ("larger than the 91 bytes the server's list gives"), and with a same-size change the SHA-256;
  the file was restored.
- R5, v8 → myserver2, the test item enabled on the main menu: restart stop → Restart game → the override launch's
  startup pass loaded the server's jar (`java version 6`, Lua `version 6` at the main menu) → Rejoin → "runs the
  server's Java code" → in game → leaving keeps v6 for that launch (by design) → the next launch `java version 8`.
- Found, vanilla (D6): with the item's jar loaded at startup, the join's Workshop update of that item fails
  (`onItemNotDownloaded result=33`): Windows keeps the loaded jar open. R5's first launch ran with the item off the main
  menu so the update could land, and the item back on for the override launch.
- Not run in game: single player after leaving (the main menu's pass resolves the same folders), an HTTP error and a
  refused item (unit tests).

### PR #22 Review Follow-ups (2026-10-21)

Review: another session's report on PR #22 (architecture, performance, reuse; no blocking bug). User: "M1 选保留，F1 到 F6 一起做".

1. [x] Zabric: M1 keep `JavaMods`/`LoadPhase`/`WidenRoots` as API (option 2): `LoadPhase.LATER` and `workshop.txt` say
       every later pass, main-menu reloads included; `JavaMods.Jar` a final class; `sha256` read only for
       `loaded()`/`lastPass()`, not in the `LoadStatus` shape the other reads share. F1 one reader of ZombieBuddy's
       Java-mod rules in `loader/`, used by `OverrideRequest`'s refusal and exposed for mod-sync; on the "recheck after
       a ZombieBuddy update" list. F3 `servingJar`/`pending`/`request`/`current` callable from any thread; packages
       indexed once per pass. F4 `Relaunch.restart` documents that it blocks up to the handshake. F6 one `children`.
2. [x] mod-sync: `ModInfo.kt` replaced by Zabric's reader; jar/manifest reads and `servingJar` off the game thread (the
       join's worker); `JoinArgs` compares IP literals first and resolves names off the game thread. F5 `breakable`,
       `plain`, `lines` into `Dont39AllInOne_Common.lua`; one dialog helper for the join and rejoin Lua.
3. [x] F2: an error level on zb-kt-utils' `Feature` (ZabricKotlin 0.6.0); the post-pass "served by another jar" per
       the user's choice (A: error log and a one-time notice in game; the join goes on).
4. [x] Gate: `build`, smokeTest, in-game spot check (restart stop, Rejoin, relaunch); push to PR #22.
       Spot check (myserver2 on v8, client v9 uploaded by the user): the shared confirm dialog (下载并加入 / 取消) →
       download → the Java check ("preparing") → restart stop with Restart game → relaunch → override at start → the
       shared Rejoin dialog (重新加入 / 暂不) → "runs the server's Java code" → in game with no notice; no Lua error.
