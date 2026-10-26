# Zabric: hook one overload when longer overloads exist

## Goal

A Zabric hook on one overload must not need the method to be the longest of its name. Today `zabric-processor`
refuses `ItemContainer.AddItem(String)` and `AddItem(String,float)` ("Zabric cannot hook ... alone"), so Sync Guard
does not see Lua's `container:AddItem("Base.X")` (task `10-24-mp-sync-loss-recovery`, D7).

## Background

- The glue binds target arguments with `@Patch.Argument(k)`; ZombieBuddy reads that as "at least k+1 parameters" and
  applies the advice to every longer overload whose leading parameters accept the bound types
  (`PatchEngine.applyPatches`, signature-aware matcher).
- ZombieBuddy has no descriptor or `@Origin` support. Its matcher does compare the parameter count exactly for an
  advice method whose arguments are bound without `@Argument` (Byte Buddy binds an unannotated advice parameter to the
  target argument at the same index, read-only).
- Started by the Sync Guard session; branch `feat/sync-guard` (not merged to main), Zabric 0.7.1 unreleased there.

## Requirements

- R1. The processor accepts a hook on an overload that a longer overload of the same name would also match, when an
  exact-count binding selects only the named one, and generates that binding for it.
- R2. Glue that the current shape already binds to one method stays byte-for-byte the same (released mods, golden
  glue, listings unchanged; no listing header change, no runtime API change).
- R3. What exact-count binding cannot separate stays a compile error, with a message that says why: another overload
  with the same parameter count that accepts the bound types (bridges included), or a `@ModifyVariable` on such a
  target (a writable argument needs `@Argument`).
- R4. Both front ends (javac, KSP) get it through the shared generator.
- R5. Sync Guard's `ContentHooks` records `AddItem(String)` and `AddItem(String,float)`; hooks that only route
  through `AddItem(String)` (`addItem(ItemKey)`, `AddItems(String,int)`) are dropped.
- R6. The hooks spec says when the generator binds by position and what still cannot be hooked alone.

## Acceptance Criteria

- [x] AC1. Processor tests: a hook on `prefix(I)V` (longer `prefix(ILjava/lang/String;)V` exists) compiles, its glue
  binds arguments without `@Patch.Argument`, arguments first; a same-count clash and a `@ModifyVariable` on such a
  target still fail with the new messages.
- [x] AC2. Weave test: ZombieBuddy weaves the positional glue into the named overload only (HEAD and RETURN advice),
  the longer overloads run without the handlers; `GoldenTest` matches the regenerated `WeaveGlue.java` and listing.
- [x] AC3. `ContentHooks` hooks `AddItem(Ljava/lang/String;)Lzombie/inventory/InventoryItem;` and
  `AddItem(Ljava/lang/String;F)Z`; sync-guard compiles.
- [x] AC4. `JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew build` green.

## Decisions

- D1 (delegated by the starting session: "narrowest safe way"): use ZombieBuddy's existing exact-count match
  (unannotated advice arguments) instead of a runtime descriptor filter: ZombieBuddy offers no way for advice to know
  the woven method's descriptor, and the exact match leaves the other overloads unwoven. No ZombieBuddy change.
- D2: the positional shape only where the `@Argument` shape would select another method (R2).

## Out of Scope

- A runtime check that a patch got woven into a method other than its listed one (e.g. after a game update adds an
  overload); possible follow-up in `WeaveCheck`.
- Same-count overload separation (needs a ZombieBuddy feature).
