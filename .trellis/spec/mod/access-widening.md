# Access Widening

> Feature code reaches game and target-mod members with ordinary compiled code. Non-public members are listed in the
> feature's `.classtweaker` and made public at build time (compile jars) and at game start (Zabric, for every
> installed mod).

---

## Where It Lives

| Part | Code |
|------|------|
| List format and parser | `zabric/src/classtweaker/java/.../classtweaker/ClassTweaker.java` |
| Target mod jars, widened compile jars, merged list | `buildSrc/src/main/kotlin/PzTargets.kt`, `ClassTweakerTasks.kt`; `mergeClassTweakers` in `dont39-all-in-one/build.gradle.kts` |
| Game-start scan and widening, per-mod status | `zabric/.../widener/AccessWidener.java`, `ModFolders.java` |
| Restart notice | `zabric/.../restart/RestartList.java`, `RestartNotice.java`, `src/main/lua/client/Zabric_RestartNotice.lua` |
| Feature side: gating on the widening state | `Widening.kt`, `Feature.kt` in `zb-kt-utils` |

Reference features: `viewpoint-blood-fx-fix` (target mods, nested classes, a constructor) and `ime-keyboard-fix`
(game fields).

---

## Rules for Features

- No reflection or method handles in feature code: reach members directly.
- Declare every target mod with `pzTargets` in the feature's build script.
- List each non-public field, class or constructor the feature uses in
  `src/main/resources/META-INF/dont39/<module>.classtweaker` (build input only); the facade passes `widened = true` to
  `Feature`. The aggregating mod ships the merged list in its own jar.
- Widen only fields, classes and constructors; other methods are not widened (visibility can change dispatch).
- Read a target mod's version by name (`TargetMod.kt`): a `static final` constant is inlined at build time.
- Target-mod types only in classes that load after the feature is active, never in the facade
  (`LiftOffsets.debug()`).

## Zabric

- Java and Lua only, no patches: its package loads at premain, before the Kotlin runtime.
- Premain code touches no game class and logs nothing: the classes it widens are not loaded yet, and the game's log is
  not up. Outcomes are reported at MAIN.
- It applies the lists of every installed mod: premain cannot see the game's mod list (`ModFolders.java`).
- A problem is that mod's only and is never silenced (`AccessWidener.java`).
- The author contract is in `zabric/mod/workshop/workshop.txt`. Changing it needs a new list header version.
- After a ZombieBuddy update, recheck the `Loader.g_instrumentation` read in `agent/ZombieBuddyAgent.java`.

## Preload Registration

- A mod asks for registration from its own Java `Main` only, so registration follows code ZombieBuddy approved and ran
  (`preload/Preload.java`; requester side `zb-kt/.../PreloadRequest.java`).
- ZombieBuddy runs the mods' `Main` in the player's mod-list order: a requester must not assume Zabric is loaded.
- Approval gap, accepted with signing off: ZombieBuddy runs a registered unsigned jar at premain without checking that
  its current hash was approved. The Workshop texts say so.
