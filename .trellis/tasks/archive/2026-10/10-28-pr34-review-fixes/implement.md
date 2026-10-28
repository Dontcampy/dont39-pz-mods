# Implement: PR 34 review fixes

1. In parallel (disjoint files):
   - Zabric (`zabric/`): R3, R10. Check: `:zabric:test`, `:zabric:build`.
   - appearance-override + allinone-common shared Lua: R2 (API side), R4, R5, R6, R7. Check: module tests, build.
2. Then sctd-render-takeover: R1, R2 (caller side), R8, R9, using step 1's API and helpers. Check: build assembleMod,
   Kahlua syntax check (scratchpad `lc/src/LuaSyntax.java`, run from the game folder), BlockHashes, smokeTest (back
   up LP reports, default.txt, ZombieBuddy config first).
3. trellis-check over all three.
4. In game on myserver2 (backup/restore as before, never the live `myserver`), results quoted in
   `research/in-game.md`, no raw logs committed: auto-hide broadcast count, migration and rejoin, takeover basics,
   Zabric join-only Java mod globals (viewer client with All-in-One only from the server's list).
5. Spec updates (Kahlua has no weak tables; shared Lua helpers location; appearance batching), commit, archive, push.

Rollback: revert the task's commit; nothing persistent changes format.
