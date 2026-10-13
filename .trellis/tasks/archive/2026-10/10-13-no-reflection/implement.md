# Implementation plan: access widener instead of reflection

## Checklist (in order)

1. Experiment gate (one member): widener list with `zombie.ui.UITextBox2.xOffset`; build-time widened game jar;
   Java `PreMain` + `AccessWidener` in zombiebuddy-kotlin-utils; All-in-One signed + preload; `ImeCaret` reads
   `box.xOffset` directly. The user approves preload in game once; then smokeTest with
   `JAVA_TOOL_OPTIONS=-Dnet.bytebuddy.dump=<scratch>`: widening ok line, `ime-keyboard-fix: active`, and the dumped
   `UITextBox2` has `xOffset` public and our call woven. If widening does not survive ZombieBuddy's retransform or
   `Instrumentation` cannot be obtained, stop and report.
2. Full widener: merged list, all three `UITextBox2` fields, `Feature` gating on the widening state (design §4),
   failure reasons.
3. Generated hook-target checks (design §5); drop the hand-written checks.
4. `pzTargets` (design §1); widened mod jars; migrate `viewpoint-blood-fx-fix` (design §6); `javap -c` per-frame
   paths.
5. Prune unused `Resolver` API.
6. smokeTest changes (design §7); Workshop text / signing note.
7. Spec rewrite (design §8); the three already-removed wrong lines stay removed.

## Validation

```bash
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew build assembleMod
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew :dont39-all-in-one:smokeTest
grep -rnE "Resolver\.|java\.lang\.reflect|MethodHandle|MethodType" viewpoint-blood-fx-fix/src ime-keyboard-fix/src
```

- AC2: wrong target id, and a non-existent widened member, each fail the build naming it (then revert).
- AC5: scratch build without the `PreMain` call → features needing widening log why they are off; game runs.
- AC4 in game by the user.

## Risky points

- Preload approval is manual once: ZombieBuddy stores preload mods by mod id + paths (`Loader.PreloadMods`) and
  re-checks only the signature (ZBS) at each launch; approve with "trust author" so rebuilt jars need no new
  approval. The ZBS check needs Steam reachable.
- `Loader.g_instrumentation` is a ZombieBuddy internal; a ZombieBuddy update can break it (fails loudly per R3).
- A listed class loaded before `PreMain` cannot be widened (fails loudly per R3).
