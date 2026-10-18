# Design: processor refactor

## Target layout

`zabric-processor/src/main/java/com/pzlauncher/zabric/processor/`
- `ZabricProcessor` — rounds, `@Patch` / stray-annotation policing, file generation (~200 lines).
- `JavacHookReader` (new) — javac elements → `HookDecls` (read, gate, handler, checkMixin, target + bridges,
  supertypes, byBinaryName, annotation-mirror helpers).
- `glue/HookAnalyzer` — entry point and shared `failed` state; delegates to:
  - `glue/HandlerBinder` (new) — shape, member/overload resolution, `bindInject`, `bindModify`, modifiedArg.
  - `glue/PatchBuilder` (new) — patch assembly, share/always consistency, access checks, matcher simulation, naming.
- `glue/Messages` — gains the shared texts.

`zb-kt-utils-processor/src/main/kotlin/com/dont39/pz/zbktutils/processor/`
- `GlueProcessor.kt` — hooks only.
- `LuaBridge.kt` (new) — `@LuaGlobal` collection, rendering, `kotlinString`.

Shared failure state: `HandlerBinder` and `PatchBuilder` receive the analyzer's reporter/context object rather than
duplicating `failed`; order of reported errors must not change (HookRulesTest asserts messages).

## KSP tests

- Preferred harness: kotlin-compile-testing fork (`dev.zacsweers.kctfork`) with KSP2, if a release supports Kotlin
  2.4.21 / KSP 2.3.12. Otherwise run KSP2 programmatically (`symbol-processing-aa-embeddable`,
  `KotlinSymbolProcessing`) over temp source dirs. Decide at step 1 by a spike; record which in the task report.
- Test classpath: zabric API jar (project dependency) and stubs for game types the fixtures reference; no game jar.
- Golden files live under `src/test/resources/golden/`, like the javac side.

## Compatibility

Processor jars are published (`zabric-processor` on Maven Central). Only internal classes change; the processor's
options, generated output and messages stay the same. Version bump is the release step, not this task.

## Rollback

Each requirement is its own commit; the golden diff (AC3) is the gate for every commit.
