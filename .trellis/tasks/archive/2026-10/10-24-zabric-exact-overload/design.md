# Design: positional argument binding for one overload

## ZombieBuddy behaviour relied on

`PatchEngine.applyPatches` builds one argument map per advice method. An advice method with any `@Argument` gets an
"at least N parameters" map; one whose arguments are all unannotated gets an exact map (`targetParamCount ==
argMap.size()`), keyed by the advice parameter index, special parameters (`@This`, `@Return`, `@Local`, `@Thrown`)
skipped. A method is selected when any map matches, so every advice method that binds arguments must be unannotated
for the exact match to hold. Byte Buddy binds an unannotated advice parameter to the target argument at the same
index, read-only, static typing; so the arguments must be the advice's first parameters, in order.

## Generator

- `LoaderMatcher.selects` gains `exact`: with it, a map matches only a method with exactly `map.size()` parameters.
- `PatchBuilder.checkMatcher`: first the `@Argument` shape as today. If it selects another method, and the target has
  parameters and no writable argument, try the exact shape; use it when that selects only the target. Otherwise
  report: the error names the remaining methods and why (same count, or `@ModifyVariable` needs `@Argument`).
- `GlueModel.Patch` gains `positional`; `GlueWriter.view` puts the argument params first in enter and exit when set;
  `glue.ftl` writes them without `@Patch.Argument`.
- Writable arguments never use the positional shape (Byte Buddy's unannotated binding is read-only).

## Compatibility

- Existing hooks keep the `@Argument` shape; their glue and listings do not change (`GoldenTest` on current cases).
- No listing or runtime API change: the listing already names the descriptor, `TargetCheck`/`WeaveCheck` check that
  method.
- After a ZombieBuddy update, `LoaderMatcher` is rechecked against `PatchEngine` (existing spec rule); the weave case
  covers this shape.

## Tests

- `HookRulesTest`: `prefix(I)V` compiles, positional glue; a `@ModifyVariable` on it fails; a same-count clash fails.
- Weave: `Shapes.add(String)`, `add(String,float)`, `add(String,float,boolean)` (ItemContainer's shape); `ShapeHooks`
  hooks the first at HEAD (receiver + argument) and the second at RETURN; `WeaveTest` calls all three.
- `WeaveGlue.java` and the ShapeHooks listing regenerated.
