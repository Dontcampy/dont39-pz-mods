# Zabric Hooks

> Mods on Zabric hook game and mod code only through Zabric's annotations; a processor generates the loader glue and
> Zabric's runtime gates it. The code and its Javadoc are the reference: start at
> [`hook/package-info.java`](../../../zabric/src/main/java/com/pzlauncher/zabric/hook/package-info.java).

---

## Where It Lives

| Part | Code |
|------|------|
| Author API | `zabric/src/main/java/com/pzlauncher/zabric/hook/` |
| Runtime | `zabric/src/main/java/com/pzlauncher/zabric/hook/impl/` |
| Generator, shared by both front ends | `zabric-processor/src/main/java/com/pzlauncher/zabric/processor/glue/`; its FreeMarker templates under `src/main/resources` in the same package |
| Front ends | `ZabricProcessor.java` (javac); `GlueProcessor.kt`, `HookReader.kt` in `zb-kt-utils-processor` (KSP) |
| Reference glue | `zabric/src/test/java/com/pzlauncher/zabric/hook/weave/glue/WeaveGlue.java` |
| Author build | `samples/zabric-java-hooks/` |

Reference hooks: `ImeCaret.kt` and `ImeWindow.kt` in `ime-keyboard-fix`.

---

## Rules

- Names and semantics follow MixinExtras where they match. What the API cannot do is a compile error, never a silent
  no-op.
- A new injector or option goes into the annotation, both front ends, the generator, the reference glue and a weave
  test together.
- `WeaveGlue.java` is the generator's reference (`GoldenTest`). A new glue shape gets a weave test case: ZombieBuddy
  drops advice that fails verification without an error, so before release only a weave test catches it.
- Every advice the generator writes keeps its read of the glue's `MOD`: the weave check looks for it
  (`WeaveCheck.java`).
- The glue calls only the API package, never `hook.impl`; outside Zabric's loader package it is the only code that
  names ZombieBuddy ([Loader Seam](./zombiebuddy-patching.md#loader-seam)).
- Hook listings (`HookListing.java`): change the writer and all readers together; a change old readers must understand
  takes a new header version.
- Handlers never catch or gate; failures go to the hook class's gate (`HookGate.java`). Kotlin hook objects are gated by
  the module's facade ([ZombieBuddy Kotlin Utils](./zb-kt-utils.md)).
- Zabric's hook log lines are a contract: `smokeTest` and players' `console.txt` read them.
- A hand-written ZombieBuddy `@Patch` is rejected unless marked `@UnsafeLoaderPatch`, a disclaimer that Zabric does not
  support it. This repo uses none.
- No `priority`: a cross-mod order needs a runtime dispatcher that breaks on failed weaves, unsafe patches in between
  and recursion. Across mods, ZombieBuddy's load order decides.
- After a ZombieBuddy update, recheck `LoaderMatcher.java` against ZombieBuddy's `PatchEngine.java` and run the weave
  tests.

## Publishing and Compatibility

- `zabric` and `zabric-processor` are published to Maven Central (`pz-publish.gradle.kts`). Release only after the
  user confirms version and artifacts: a release is permanent.
- Once released, the runtime API the glue calls and the listing header only grow: players' Zabric updates itself,
  while mods keep the glue they were compiled with.
- Each package's `package-info.java` says whether it is API; a new package says so too.
- `zb-kt-utils-processor` is not published.
