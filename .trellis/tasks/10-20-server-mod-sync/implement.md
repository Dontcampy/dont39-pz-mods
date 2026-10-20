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
- [ ] Release: Zabric release first, then Dont39 All-in-One with the feature; version bumps per
      [kotlin-runtime.md](../../spec/mod/kotlin-runtime.md).
- [ ] Spec update for the new patterns (connection-details trailer, `CheckMods` hold, folder swap).
