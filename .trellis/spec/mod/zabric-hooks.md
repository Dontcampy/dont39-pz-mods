# Zabric Hooks

> Mods on Zabric hook game and mod code only through Zabric's annotations; a processor generates the loader glue and
> Zabric's runtime gates it. The code and its Javadoc are the reference: start at
> [`hook/package-info.java`](../../../zabric/src/main/java/com/pzlauncher/zabric/hook/package-info.java).

---

## Where It Lives

| Part | Code |
|------|------|
| Author API | `zabric/src/main/java/com/pzlauncher/zabric/hook/` |
| Runtime | `zabric/src/main/java/com/pzlauncher/zabric/hook/impl/`: listing reader `HookListing.java`, target check `TargetCheck.java` |
| Listing format (dir, suffix, header, default gate), compiled into Zabric, the processor and buildSrc | `zabric/src/listingformat/java/.../listing/HookListingFormat.java` |
| Generator, shared by both front ends | `zabric-processor/src/main/java/com/pzlauncher/zabric/processor/glue/`: entry `HookAnalyzer.java`, handler checks `HandlerBinder.java` / `HandlerBinding.java`, patches `PatchBuilder.java`, output `GlueWriter.java`; its FreeMarker templates under `src/main/resources` in the same package |
| Front ends | `ZabricProcessor.java`, `JavacHookReader.java` (javac); `GlueProcessor.kt`, `HookReader.kt` in `zb-kt-utils-processor` (KSP) |
| Tests | javac side and generator: `zabric-processor/src/test/`; KSP side: `zb-kt-utils-processor/src/test/`, its golden glue under `src/test/resources/golden/` |
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
- Hook listings (`HookListingFormat.java`, `HookListing.java`, `listing.ftl`): change the writer and all readers
  together; a change old readers must understand takes a new header version.
- Hooks open with the first pass that lists the mod; a mod Zabric keeps preloaded opens at preLaunch, before the game
  loads its targets, with the byte-based target check and held log lines
  ([Access Widening](./access-widening.md#entrypoints-and-preload-registration)). For such a mod ZombieBuddy loads the
  types in the glue's advice signatures at premain (target argument and return types, the target with `@Self`).
- Handlers never catch or gate; failures go to the hook class's gate (`HookGate.java`). Kotlin hook objects are gated by
  the module's facade ([ZabricKotlin Utils](./zb-kt-utils.md)).
- Zabric's hook log lines are a contract: `smokeTest` and players' `console.txt` read them.
- A hand-written ZombieBuddy `@Patch` is rejected unless marked `@UnsafeLoaderPatch`, a disclaimer that Zabric does not
  support it. This repo uses none.
- No `priority`: a cross-mod order needs a runtime dispatcher that breaks on failed weaves, unsafe patches in between
  and recursion. Across mods, ZombieBuddy's load order decides.
- After a ZombieBuddy update, recheck `LoaderMatcher.java` against ZombieBuddy's `PatchEngine.java`, run the weave
  tests, and bump the ZombieBuddy release in `.github/actions/pz-jars/action.yml`.

## Publishing and Compatibility

- `zabric` and `zabric-processor` are published to Maven Central (`pz-publish.gradle.kts`) only by
  `.github/workflows/release-zabric.yml`, on tag `zabric-v<version>`. Approving the run in the `maven-central`
  Environment is the user's confirmation: a release is permanent.
- The game jar the workflow fetches is proprietary: never in a workflow artifact, cache or the repo.
- Once released, the runtime API the glue calls and the listing header only grow: players' Zabric updates itself,
  while mods keep the glue they were compiled with.
- Each package's `package-info.java` says whether it is API; a new package says so too.
- `zb-kt-utils-processor` is not published.
