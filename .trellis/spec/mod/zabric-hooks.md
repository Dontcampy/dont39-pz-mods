# Zabric Hooks

> Every mod on Zabric hooks game and mod code through Zabric's annotations. A processor turns them into loader glue at
> compile time; Zabric's runtime gates the glue and reports what fails. Authors write Java (`zabric-processor`) or
> Kotlin (KSP, `zb-kt-utils-processor`); both share one generator.

---

## Where It Lives

| Part | Code |
|------|------|
| Author API, its contract in Javadoc | `zabric/src/main/java/com/pzlauncher/zabric/hook/` (start at `package-info.java`) |
| Runtime: mod state, default gate, target check, log lines | `hook/impl/HookRuntime.java`, `ModState.java`; opened and closed by `entrypoint/Lifecycle.java` |
| Weave check: was each hook woven | `hook/impl/WeaveCheck.java` |
| Listing format | Javadoc of `hook/impl/HookListing.java` |
| Glue shape and its rules | header of `zabric/src/test/java/com/pzlauncher/zabric/hook/weave/glue/WeaveGlue.java` |
| Generator, shared by both front ends | `zabric-processor/src/main/java/com/pzlauncher/zabric/processor/glue/` (`HookAnalyzer`, `GlueWriter`, `LoaderMatcher`, `Messages`) |
| javac front end, options | `ZabricProcessor.java` in `zabric-processor` |
| KSP front end, Kotlin rules | `GlueProcessor.kt`, `HookReader.kt` in `zb-kt-utils-processor` |
| Tests | `WeaveTest`, `WeaveCheckTest` and `HookRuntimeTest` in `zabric`; `GoldenTest`, `HookRulesTest`, `LoaderPatchTest` in `zabric-processor` |
| Standalone author build | `samples/zabric-java-hooks/` |

Reference hooks: `ImeCaret.kt` (`@Inject` at `RETURN` with `@Self`) and `ImeWindow.kt` (`FINALLY`, `always`) in
`ime-keyboard-fix`.

---

## Author API

- Names and semantics follow Mixin / MixinExtras where they match; Zabric-only additions (`@Self`, `@Thrown`,
  `FINALLY`, `always`) say so in their Javadoc.
- Anything the API cannot do is a compile error naming the handler or class, never a silent no-op. Mixin annotations
  get advice on what to use instead (`Messages.java`).
- A new injector or option is added to the annotation, both front ends, the generator, the reference glue and the
  weave test together.

## Generated Glue

- `WeaveGlue.java` is the shape; `GoldenTest` fails when the generator's output differs from it. Change both together
  and keep `WeaveTest` passing: it weaves that glue through ZombieBuddy's own patch engine. A new shape gets a weave
  test case: when woven advice fails verification, ZombieBuddy drops every patch of that target class and still logs
  `Transformed:`; the [weave check](#weave-check) then stops those hooks in game, so only a weave test catches it
  before release.
- Outside Zabric's loader package, the glue is the only code that names ZombieBuddy
  ([Loader Seam](./zombiebuddy-patching.md#loader-seam)). It calls only the runtime API in
  `com.pzlauncher.zabric.hook`, never `hook.impl`.
- Never edit generated glue; read it under `build/generated/` when in doubt.

## Listing Contract

One listing per hook class (`HookListing.java`). Its readers: the runtime target check (`HookRuntime.java`,
`ZabricHooks.problem` / `gateProblem`), the weave check (`WeaveCheck.java`) and `checkGlue` / `smokeTest` in
`dont39-all-in-one/build.gradle.kts`. Change
writer and readers together. Readers skip line kinds they do not use; a change a reader must understand takes a new
header version.

## Gates and Mod State

- A handler runs only while its mod's hooks are open and its gate is active (`ModHooks.java`, `HookGate.java`); what
  it throws goes to the gate and never into the hooked method.
- The default gate stops all of the mod's hooks for the session. A custom gate narrows that scope and checks its own
  hook classes' targets (`ZabricHooks.gateProblem`) before it turns active; Zabric checks only default-gate hook
  classes. Kotlin: `Feature` is the gate ([ZombieBuddy Kotlin Utils](./zb-kt-utils.md)).
- The runtime's log lines (`hooks ready`, `hooks stopped`) are a contract: `smokeTest` and players' `console.txt` read
  them.

## Weave Check

ZombieBuddy drops a patch without an error when ByteBuddy rejects its advice or the JVM rejects the woven class. A
hook that never runs is a failure of its hook class, reported to its gate like a handler's (`WeaveCheck.java`).

- The check finds woven advice by its read of the glue's `MOD`: every advice the generator writes keeps that read.
- It runs inside every class load: read-only, never throws, and costs unrelated classes one lookup.
- A missing target method is the target check's to report, not the weave check's.
- Pass-time checks finish before main entrypoints run, so a gate may have failed before its `init` (`Feature.init`).
- A mod entering a pass opens only after the pass-time check of its loaded targets (`HookRuntime.passProcessed`), so
  no hook runs on an unchecked target.
- `hooks ready` is logged after the pass-time check, so it means the loaded targets are woven; a mod it stopped logs
  only `hooks stopped`.

## Hand-Written Loader Patches

Both processors reject a class with ZombieBuddy's `@Patch` unless it carries `@UnsafeLoaderPatch`, which compiles with a
warning (`UnsafeLoaderPatch.java`, wording in `Messages.java`, shared by both). Compile time only: no runtime scan. This
repo uses none.

## ZombieBuddy's Matcher

ZombieBuddy picks the methods a patch applies to by name and bound argument types, not by descriptor. The processor
models that (`LoaderMatcher.java`) and rejects a hook ZombieBuddy would also apply elsewhere. After a ZombieBuddy
update, recheck `LoaderMatcher` against `PatchEngine.java` and run the weave tests (`WeaveTest`, `WeaveCheckTest`).

## Order

No priority. Within one compilation, hook classes run by qualified name and handlers in declaration order; across mods
ZombieBuddy's load order nests the advice. A cross-mod order would need a runtime dispatcher that elects the outermost
advice per call, which breaks on a failed weave, an `@UnsafeLoaderPatch` in between, or recursion.

## Publishing and Compatibility

- `zabric` (the mod jar) and `zabric-processor` are published as `com.pzlauncher:zabric` and
  `com.pzlauncher:zabric-processor` (`pz-publish.gradle.kts`, their build scripts). Releases are manual, after the
  user confirms version and artifacts: a Maven Central release is permanent.
- Once released, the runtime API the glue calls (`ZabricHooks.mod`, `ModHooks`, `HookGate`, `CallbackInfo`,
  `CallbackInfoReturnable`, `LocalRef`) and the listing header stay backward compatible: players' Zabric updates
  itself, mods' glue stays as compiled. Add, never change or remove.
- The published Javadoc covers every package of the jar; each package's `package-info.java` says which members are
  API. A new package or public member outside that API says it is internal there.
- `zb-kt-utils-processor` stays unpublished build tooling of this repo.
