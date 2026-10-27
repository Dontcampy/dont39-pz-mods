# Implement

1. **ItemStats reset.**
   - Add `accessible field` lines for the 22 fields to
     `server-reborn/src/main/resources/META-INF/dont39/server-reborn.classtweaker`.
   - Add the `@Hooks(ItemStatsPacket::class, gate = ServerReborn::class)` `HEAD` inject on `parse`, in
     `network/game/`.
   - Drop the -1 → 0 mapping for `poisonDetectionLevel` in `ItemDigests.food`, and update its comment.
2. **Model items.**
   - Add `ItemDigests.counted` and `ItemDigests.items(container)`, with the set built once from `BodyPartType`.
   - Use `items` in `ItemDigests.container`, `Snapshots` (server item list), `Reconcile.covered`, and the field diff
     calls in `ClientSync`.
3. **EFK keys.** Add the nine keys to `ItemDigests.KAHLUA.ownPerSide`.
4. **Versions and docs.**
   - `Protocol.VERSION` 1 → 2; `server-reborn/build.gradle.kts` version 0.1.0 → 0.2.0.
   - README: a short line under "Network sync optimization" saying the mod also fixes the game's stale ItemStats
     food stats, and that bandage and wound model items are not compared.
5. **Build gate.** Run with `JAVA_HOME=~/.jdks/graalvm-ce-25.0.2`; build and tests must pass.
6. **In-game check** on the test server (myserver2 recipe, own ports), with the field diff on, per the PRD's
   acceptance criteria. Also check that `debugDropDelta` repairs still happen. Done by the user or by me, as agreed
   at that step.

Rollback: revert the task's commit; the live server keeps 0.1.0 until the user uploads.
