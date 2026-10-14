# Design: glue-owned gate and catch

## Boundaries

- `zb-kt-utils-processor/.../GlueProcessor.kt`: hook collection gains `always` and the facade lookup; patch output
  switches from Kotlin to Java. Lua bridge output unchanged.
- `zb-kt-utils/.../glue/Glue.kt`: `always: Boolean = false` on `Hook.Enter` and `Hook.Exit`; KDoc states the contract.
- Feature modules: hook bodies lose gate and try/catch (PRD R6). No runtime API change in `Feature`.
- Glue listing format (`META-INF/zbkt/glue/<Feature>.txt`) unchanged: nested class names stay `<Class>_<method>`, and
  a Java static nested class has the same binary name (`Outer$Nested`) as the Kotlin nested object.

## Generated shape

`<Feature>Patches.java` in the glue package, one `public static class` per target, `static` advice methods.
`F` = facade object, `H` = the hook's JVM owner, `W` = `"running <Owner.fn> on <class>.<method>"` as a Java literal.

Paired target (Enter and Exit):

```java
@Patch.OnEnter
public static void enter(<bindings>, @Patch.Local("active") boolean active) {
  active = F.INSTANCE.active();
  if (!active) return;
  try { H.INSTANCE.fn(<args>); } catch (Throwable t) { F.INSTANCE.disable(W, t); }
}

@Patch.OnExit(onThrowable = …)
public static void exit(<bindings>, @Patch.Local("active") boolean active) {
  if (!active) return;
  try { H.INSTANCE.fn(<args>); } catch (Throwable t) { F.INSTANCE.disable(W, t); }
}
```

- Unpaired Enter or Exit: no Local; `if (!F.INSTANCE.active()) return;` instead.
- `always = true` (unpaired only): no gate line.
- `skipOn` Enter returns `boolean`: `return false` when gated off; `try { return H…; } catch (…) { disable; return false; }`.
- The Local name is fixed (`active`); each target is its own advice class, so names cannot clash across features.
  ZombieBuddy chains advice from different patch classes on one method; Locals are per advice class.

## Resolution in the processor

- Facade: among the module's own files (`resolver.getAllFiles()`), top-level `object` declarations whose supertypes
  include `com.dont39.pz.zbktutils.Feature`; exactly one, else a compile error. Only looked up when the module has hooks.
- JVM owner of a hook: object member → `<object FQN>.INSTANCE.fn`; top-level function → its file facade class via
  `resolver.getOwnerJvmClassName` (KSP experimental API, opt in), called statically. Same for the facade's `INSTANCE`.
- Visibility: hook function must be `public` (R5); its object may be `internal` (public in bytecode).
- Java types for advice parameters (rendered from `KSType`, erasing type arguments):
  - non-null Kotlin primitive → Java primitive; nullable → `java.lang` box;
  - `kotlin.Any` → `java.lang.Object`; `kotlin.Array<T>` → `T[]`; `IntArray` etc. → `int[]` etc.;
  - other `kotlin.*` mapped types (`String`, `Throwable`, collections) via `resolver.mapKotlinNameToJava`;
  - anything else: its qualified name (nested classes keep `.` in Java source).
- Existing nullability rules stay: the hook is still Kotlin, and its parameter null checks would throw (now caught →
  disable, but still a needless failure).

## Trade-offs

- Java glue unlocks `@Patch.Local` (and later `readOnly = false`) at the cost of a Kotlin→Java type mapping in the
  processor; the mapping is narrow because the binding rules already restrict parameter types.
- D2 moves cleanup from "before the target runs" to "the paired Exit"; with D4 the Exit runs for every call whose Enter
  passed the gate, so nothing a failed Enter changed outlives that call.
- `checkLoaderRefs` already excludes the glue package, so the Java file needs no new exclusion.

## Rollback

Single commit; revert restores the Kotlin generator and the hand-written guards together. No persisted data or
listing format change.
