# Access Widening

> Feature code reaches game and target-mod members with ordinary compiled code. Non-public members are listed in the
> feature's `.classtweaker` and made public at build time (compile jars) and at game start (Zabric).

---

## Where It Lives

| Part | Code |
|------|------|
| List format and parser | `zabric/src/classtweaker/java/.../classtweaker/ClassTweaker.java` |
| Target mod jars, widened compile jars | `buildSrc/src/main/kotlin/PzTargets.kt`, `ClassTweakerTasks.kt` |
| Game-start widening, self-registration, restart dialog | `zabric/` (`PreMain.java`, `widener/AccessWidener.java`, `setup/Registration.java`) |
| Feature side: gating on the widening state | `Widening.kt`, `Feature.kt` in `zb-kt-utils` |

Reference features: `viewpoint-blood-fx-fix` (target mods, nested classes, a constructor) and `ime-keyboard-fix`
(game fields).

---

## Rules

- No reflection or method handles in feature code: reach members directly.
- Declare every target mod with `pzTargets` in the feature's build script.
- List each non-public field, class or constructor the feature uses in
  `src/main/resources/META-INF/dont39/<module>.classtweaker`; the facade passes `widened = true` to `Feature`.
- Widen only fields, classes and constructors; other methods are not widened (visibility can change dispatch).
- Read a target mod's version by name (`TargetMod.kt`): a `static final` constant is inlined at build time.
- Target-mod types only in classes that load after the feature is active, never in the facade
  (`LiftOffsets.debug()`).

## Zabric

- Java and Lua only, no patches: its package is loaded at premain, before the Kotlin runtime.
- After a ZombieBuddy update, recheck the `Loader.g_instrumentation` read in `AccessWidener.java`.
- Why it preloads and registers itself: the class comments in `AccessWidener.java` and `Registration.java`.
