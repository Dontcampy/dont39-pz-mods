# Implement: viewpoint-blood-fx-fix in Kotlin

## Checklist

1. `viewpoint-blood-fx-fix/build.gradle.kts`: `pz-java` -> `pz-feature`.
   Prerequisites: `10-12-zombiebuddy-kotlin-utils` steps 1-3, `10-12-ksp-glue` steps 1-6.
2. Port `Relief`, `LiftOffsets`, `AimShift`, `ViewpointBloodFxFix` to Kotlin objects (design.md); delete the Java
   files in the feature package. Keep the class docs (the "why" comments) on the Kotlin classes.
3. Add the `ready` gate to the facade; `Main.kt` calls `init()` only when Kotlin is ready; update its comment
   ("the features are Java and run either way").
   Also fix `DebugType.Mod.warn("...")` in `Main.kt`: from Kotlin it resolves to the `warn(String, Object...)` format
   overload, so a `%` in the message is misread; call it the way `Feature.warn` in `zombiebuddy-kotlin-utils` does.
4. Annotate the hook and Lua functions (design.md "Hooks"); delete `src/main/java` including both glue classes.
5. Specs: `mod/index.md`, `directory-structure.md` (glue is Kotlin in `src/main/kotlin/com/dont39/pz/allinone/`),
   `zombiebuddy-patching.md` (reference file paths; hooks via annotations, shared with `10-12-ksp-glue` R7),
   `kotlin-runtime.md` (Kotlin feature gating). Link, don't restate.
6. Version bump in `dont39-all-in-one/build.gradle.kts` (shared with the sibling child; bump once).

## Validation

```
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew build assembleMod
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew :dont39-all-in-one:smokeTest
```

- `unzip -l` the All-in-One jar: `com/dont39/pz/viewpointbloodfxfix/*.class` present, no `kotlin/`/`kotlinx/`.
- `javap -v` on each generated `ViewpointBloodFxFixPatches$*`: `enter`/`exit` are `static` and carry the `Patch`
  annotations.
- smokeTest log shows `Exposing global functions from class: com.dont39.pz.allinone.ViewpointBloodFxFixLua`; in game,
  toggling the option still reaches `setForce` (status line changes on next patched call).
- `javap -c` on `Relief`: `invokeExact` descriptors are `()Ljava/lang/Object;` and `(Ljava/lang/Object;DD)F`.
- Negative check: corrupt the deployed stdlib jar, launch → not-ready line, no render-thread exception.
- In game with Viewpoint + BloodFX (or reported as not done).

## Risky Points

- `invokeExact` descriptor mismatch (runtime-only failure; the feature would disable itself with an error).
- Facade `<clinit>` pulling in new stdlib APIs before the readiness gate.
- A `private` hook -> `IllegalAccessError` inside BloodFX at render time; the processor rejects it (ksp-glue R4).
