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

- [ ] Run the parent's cross-child acceptance criteria on the Linux test server and a Windows client.
- [ ] Vanilla client on the modded server; modded client on a vanilla server.
- [ ] Release: Zabric release first (its child left the version at 0.4.1: first to ship takes 0.5.0, see
      `10-20-zabric-launch-override` design "Compatibility"), then Dont39 All-in-One with the feature; version bumps per
      [kotlin-runtime.md](../../spec/mod/kotlin-runtime.md).
- [ ] Spec update for the new patterns (connection-details trailer, `CheckMods` hold, folder swap).

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
