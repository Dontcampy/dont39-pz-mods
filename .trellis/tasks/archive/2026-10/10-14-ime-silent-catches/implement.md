# Implementation Plan

0. Before starting: commit the `remove-viewpoint-blood-fx-fix` work on its branch, open its PR, archive that task and
   record its journal; then branch `ime-silent-catches` from `main`.
1. `zb-kt-utils/.../Feature.kt`: `disable` per R1.
2. `ime-keyboard-fix`: `ImeWindowProc.restore` (R2), `ImeContext.restore` (R3), `ImeWindow.restore` (R4). Comments
   short, why only.
3. Specs per R5.
4. `JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew build`, AC1 grep, then `:dont39-all-in-one:smokeTest`.

Rollback: one commit; revert restores the old behaviour.
