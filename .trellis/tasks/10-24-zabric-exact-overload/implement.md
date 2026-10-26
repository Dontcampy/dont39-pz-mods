# Implement

1. Generator: `LoaderMatcher` exact flag; `PatchBuilder.checkMatcher` fallback and messages; `GlueModel.Patch.positional`;
   `GlueWriter` param order; `glue.ftl` unannotated arguments.
2. Processor tests in `HookRulesTest` (AC1); update the existing "alone" expectations.
3. Weave case: `Shapes` overloads, `ShapeHooks` handlers, `WeaveTest` test; regenerate `WeaveGlue.java` and the
   ShapeHooks listing from the processor's output (AC2).
4. Sync Guard `ContentHooks` (AC3).
5. Spec `.trellis/spec/mod/zabric-hooks.md` rule (R6).
6. `JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew build` (AC4); commit.

Validation per step: `./gradlew :zabric-processor:test`, `:zabric:test --tests '*WeaveTest*'`, `:sync-guard:build`.
Rollback: one commit for Zabric (steps 1-3, 5), one for Sync Guard (4); either reverts alone.
