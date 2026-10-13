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
| Preload registration service | `zabric/.../preload/Preload.java`; requester side: `zb-kt/.../PreloadRequest.java` |
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

The public runtime contract is documented for authors in `zabric/mod/workshop/workshop.txt`; keep it and the code in
step. Changing it means a new list header version.

- Java and Lua only, no patches: its package is loaded at premain, before the Kotlin runtime
  (`checkBootstrapClasses` in `zabric/build.gradle.kts`).
- Premain code touches no game class and logs nothing; outcomes are reported at MAIN (`Main.java`).
- At premain it applies the lists of every installed mod, enabled or not: the game's mod list is not known yet. Mod
  folders are located without the game (`ModFolders.java`); over-including is accepted.
- Status is per mod: a broken list, an unreadable jar or an already-loaded class is that mod's problem only. A mod
  asks through `AccessWidener` with a class from its own jar; the list is read through that class's code source,
  never through the shared class loader.
- A list that appeared or changed after game start puts the mod on the restart list; a jar outside the scanned
  folders is an error naming the jar. Neither is silenced.
- After a ZombieBuddy update, recheck the `Loader.g_instrumentation` read in `agent/ZombieBuddyAgent.java`; it is the
  only one in the repo, and ZombieBuddyKotlin uses it too.

## Preload Registration

- A mod opts in from its own Java `Main`, never from premain, so registration only follows code ZombieBuddy approved
  and ran. Zabric checks the mod's preload prerequisites before writing (`Preload.java`).
- ZombieBuddy calls the mods' `Main` in the player's mod list order, so a requester may run before Zabric is loaded.
  Requests therefore go through an order-independent handshake (class doc of `Preload.java`,
  `PreloadRequest.java`); the requester never links to Zabric when Zabric is absent.
- An existing entry pointing at a byte-identical copy counts as current, so a client and a dedicated server sharing
  `config.json` do not overwrite each other. Zabric's own entry stays first: ZombieBuddy preloads in that order.
- Approval gap (accepted): ZombieBuddy runs an unsigned registered jar at premain without checking that its current
  hash was approved, so an update runs once before the approval prompt. Signing stays off. The Workshop texts say so.
- Every mod that needs a restart to take effect is listed once: a main-menu dialog on the client, a log line on a
  server. Display names are resolved only when the list is read, on the main thread.
