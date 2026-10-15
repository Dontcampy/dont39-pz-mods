# Design: Zabric hooks, Kotlin glue on Zabric

Decisions D1–D9 are in `prd.md`. This file is the technical shape; names are proposals the implementation may refine
as long as the contracts hold.

## 1. Layers

```
author code (Java)            author code (Kotlin feature)
  @Hooks / @Inject ...          same Zabric annotations, in a top-level object
        |                               |
zabric-processor (javac AP)    zb-kt-utils-processor (KSP)
        \                             /
         zabric-processor's generator library (plain Java; model -> Java source + listing)
                        |
       generated glue: ZombieBuddy @Patch classes in javaPkgName   <- only place a mod jar names ZombieBuddy
                        |
       Zabric runtime (com.pzlauncher.zabric.hook): mod check, HookGate, CallbackInfo, target check
```

- `zabric` (mod jar, `com.pzlauncher:zabric`): annotations, runtime hook API, everything Zabric has today. Authors use
  it `compileOnly`.
- `zabric-processor` (new Gradle module, `com.pzlauncher:zabric-processor`): plain Java, depends on nothing from the
  game or ZombieBuddy (it writes ZombieBuddy names as strings, like `GlueProcessor.kt` today). Contains the javac
  front end and the generator library. Authors use it as `annotationProcessor`.
- `zb-kt-utils-processor` (KSP): a second front end on the same generator library. Keeps the Lua bridge
  (`@LuaGlobal`); drops its own hook annotations and patch generation.
- `zb-kt-utils`: `Glue.kt` keeps only `LuaGlobal`; `Feature` implements `HookGate`; `HookTargets.kt` is replaced by
  Zabric's runtime target check.

Kotlin features use Zabric's annotations directly (one vocabulary, D4); what the Kotlin layer adds is the facade as
default gate and Kotlin-specific checks (nullability of bindings, object members, JVM names).

## 2. Author API (`com.pzlauncher.zabric.hook`)

MixinExtras names where the semantics match (D4); every Zabric-only addition is marked.

| Annotation | Meaning |
|------------|---------|
| `@Hooks(value = {Class...}, targets = {"a.b.C"...}, gate = X.class)` | On a hook class (Java: final class with static handlers; Kotlin: top-level object). `targets` for classes not nameable as literals. `gate` omitted: the mod's default gate (Java) / the module's facade (Kotlin). |
| `@Inject(method = "name" or "name(desc)", at = @At("HEAD" \| "RETURN"), cancellable)` | Handler `void h([@Self T self,] [target params...,] CallbackInfo[Returnable<R>] ci [, @Share ...])`. `ci.cancel()` at HEAD skips the target; `ci.setReturnValue(v)` cancels with that value (HEAD) or replaces it (RETURN). Target params all or none, as in Mixin. |
| `@ModifyReturnValue(method = ...)` | `R h(R original [, @Self T self] [, target params...])`, returns the new value. Chains across mods. |
| `@ModifyVariable(method = ..., at = @At("HEAD"), argsOnly = true, index = n)` | `P h(P original ...)`, returns the new argument. Mixin's idiom for changing a parameter at HEAD (Mixin's `@ModifyArg` changes an argument of a call *inside* the method, which B cannot do; so D4's "modify argument" is spelled `@ModifyVariable`). |
| `@Share("key") LocalRef<T>` param | State between one call's HEAD and RETURN handlers of the same hook class (MixinExtras `@Share`), on ZombieBuddy's `@Patch.Local`. |
| `@Self` param | **Zabric-only**: the receiver. Mixin handlers are instance methods merged into the target; ours are static, so the receiver is a parameter. |
| `@At("FINALLY")` on `@Inject` | **Zabric-only**: runs after the target returns *or throws*, handler may take `@Thrown Throwable`. Needed for enter/exit restore (`zombiebuddy-patching.md#enterexit-change-only-for-the-duration`); Mixin would use `@WrapMethod`, which B cannot do (D4 ceiling). `RETURN` runs on normal return only, as in Mixin. |
| `always = true` on `@Inject` | **Zabric-only**: runs while the gate is closed (not while the mod is out of the pass or its targets mismatch), still caught. Only on a hook class whose other handlers do not share state with it. Today: `ImeWindow.sync`. |

Not supported (compile error saying so): other `@At` values (INVOKE, FIELD, NEW, ...), `@Redirect`, `@Overwrite`,
`@WrapOperation`, `@WrapMethod`, `@ModifyArg`, `@ModifyConstant`, `@Local` (target locals), `@Unique`, accessors and
duck interfaces (access widening covers visibility), `priority` (D11; ZombieBuddy nests by mod load order; documented).

Target resolution (compile time, both front ends):

- The target class must resolve on the compile classpath (game jar, widened target-mod jars). Unresolved: error.
- `method = "name"` must match exactly one method (or constructors for `<init>`); otherwise an error listing the
  overloads and asking for `name(desc)`. The generator then emits ZombieBuddy `strictMatch` with the exact parameter
  types, so ZombieBuddy's inference never picks another overload.
- Handler parameter and return types are checked against the target's (assignability; primitives exact).
- Constructors: `RETURN`/`FINALLY` only (as Mixin: no HEAD before `super`).
- ZombieBuddy's `warmUp` is not exposed (no user; it only forces a `Class.forName`).

## 3. Generated glue

Per target method and compilation, one ZombieBuddy `@Patch` class (D11: handlers of all hook classes on that method,
hook classes by qualified name, handlers in declaration order; each hook class keeps its own gate and Locals), nested
in one generated outer class per compilation in the mod's `javaPkgName` (processor option `zabric.javaPkgName`; KSP keeps `dont39.gluePackage`). Contents:

- `@Patch.OnEnter(skipOn = true)` when any HEAD handler is cancellable, else plain `OnEnter`; `@Patch.OnExit` with
  `onThrowable = Throwable` when there is a FINALLY handler, else `NoException`.
- Enter: `open = MOD.open() && GATE.active()` stored in a `@Patch.Local`; when not open, return (target runs). Then
  HEAD handlers in declaration order, each inside `try { ... } catch (Throwable t) { gate failure }`; argument
  modifiers write back through `@Patch.Argument(n, readOnly = false)`; a cancel with value stores it in a Local and
  returns `true`.
- Exit: runs handlers only when the Local says enter was open (exact per-call pairing, as today's D4 of
  hook-error-guard); writes a cancel value or modified return through `@Patch.Return(readOnly = false)`; RETURN
  handlers skip when `@Patch.Thrown` is non-null; FINALLY handlers always.
- `MOD` is `static final ModHooks MOD = ZabricHooks.mod("<modId>")` (option `zabric.modId`); `GATE` is the named gate's
  singleton (`INSTANCE` field of a Kotlin object, or a `public static final INSTANCE` the processor requires on a Java
  gate class), or `MOD.defaultGate()`.
- Failure: `GATE.failed("running <Hooks>.<handler> on <target>.<method>", t)`; never rethrows into the target.

Listing `META-INF/zabric/hooks/<HookClassBinaryName>.txt` (one per hook class, so bundling jars merge without
clashes): `mod`, `gate`, `patch <class>`, `hook <target> <method> <desc>` lines. Read by Zabric's runtime (target check),
`checkGlue` and `smokeTest`. The zb-kt listing keeps only `lua`/`luaGlobal` lines.

## 4. Zabric runtime

- `HookGate { boolean active(); void failed(String what, Throwable t); }`.
- `ModHooks` per mod id: `open()` is true while the mod is in the latest mod-list pass Zabric processed
  (`Lifecycle.modsLoaded`), its default-gate targets match, and its default gate has not failed. Closed before the first pass Zabric
  processed, so hooks start when main entrypoints run. Every generated advice checks it before the class's gate, so
  custom gates inherit the mod-level rules (D8).
- Default gate: `failed` closes all of that mod's hooks for the session and logs
  `[Zabric] <modId>: hooks stopped after an error while <what>` with the stack trace.
- Target check: `ZabricHooks.problem(Class<?> hookClass)` reads the listing from the hook class's own jar (not a class
  loader resource; all mod jars share one loader) and names a missing class/method/descriptor. Zabric checks the
  default-gate hooks of each mod at the pass and closes that mod's hooks on a mismatch; `Feature` calls it in
  `resolveOnce` for its own hook classes (replaces `checkHookTargets`).
- Enforcement (D9) is compile time only: both processors fail on a class annotated with
  `me.zed_0xff.zombie_buddy.Patch` (matched by name) unless it is also annotated with
  the marker `com.pzlauncher.zabric.hook.UnsafeLoaderPatch` (no arguments). The error names the class, points to
  Zabric's hook API, and mentions the marker as an escape that is not recommended and why. Each class carrying the
  marker gets a compile warning (javac `Messager` WARNING, KSP `logger.warn`) on that class with the same disclaimer.
  The processor generates and lists nothing
  for such a class; ZombieBuddy weaves it as usual, outside Zabric's gate, catch and target check. The marker is a
  disclaimer: its Javadoc and the Workshop text say Zabric does not support such code and takes no responsibility when
  it breaks (e.g. on a loader switch). In this repo `checkLoaderRefs` still limits where
  loader types may appear.

## 5. Kotlin layer

- `Feature : HookGate`; `failed` = `disable`. KSP fills an omitted `@Hooks.gate` with the module's facade (existing
  facade lookup in `GlueProcessor.kt`).
- `ImeCaret.publish` -> `@Inject(method = "render", at = @At("RETURN"))` with `@Self`; `ImeWindow.sync` ->
  `@Inject(method = "processMessages", at = @At("FINALLY"), always = true)` (keeps today's `onThrow = true`).
- KSP options gain `zabric.modId` (the bundling mod's id, from `PzGlue`).

## 6. Rename (D6)

`com.dont39.pz.zabric` -> `com.pzlauncher.zabric` everywhere (sources, `zabric/mod/42/mod.info` `javaPkgName`,
`checkLoaderRefs` exclusions, `CheckBootstrapClasses` config, `ClassTweakerTasks.kt`, `AllInOneMain.kt`,
`KotlinSwap.java`, `Widening.kt`, Workshop author contract, specs). Unchanged: mod id `Zabric`, manifest keys
`Zabric-Main`/`Zabric-PreLaunch`, `META-INF/zabric/` paths, log prefix `[Zabric]`. No compatibility shim.

## 7. Publishing (D5, D7)

- `com.vanniktech.maven.publish` on `zabric` and `zabric-processor`: sources and javadoc jars, POM (name, description,
  license, URL, SCM, developer), signing, Central Portal.
- `zabric`'s published jar is the mod jar (compiled against the game and ZombieBuddy jars, which are not POM
  dependencies; authors already have both locally, as ZombieBuddy's ModdingGuide prescribes).
- Version = Zabric's mod version; bumped for this change.
- Release is manual from the user's machine: credentials and signing key in `~/.gradle/gradle.properties`, never in
  the repo. No CI exists (`.github` absent); adding one is out of scope.
- One-time user actions: Central Portal account, namespace `com.pzlauncher` verified by a DNS TXT record on
  `pzlauncher.com`, GPG key published to a key server.

## 8. Verification

- `samples/zabric-java-hooks/`: standalone Gradle build (not in `settings.gradle.kts`) that resolves only
  `com.pzlauncher:zabric` / `zabric-processor` from `mavenLocal()` (then Maven Central), plus the local game and
  ZombieBuddy jars; one hook class using every supported injector. Its build asserts the generated glue and listing,
  and that a hand-written `@Patch` and an unsupported `@At` fail compilation and
  that a `@Patch` class with `@UnsafeLoaderPatch` compiles.
- Processor tests (`zabric-processor`): compile-testing style unit tests for each rule and error message.
- `smokeTest`: reads the new listings; checks patch classes found and applied, IME feature ready, no `hooks stopped`.

## 9. Risks and rollback

- Wrong overload or type mapping in generated advice fails only at weave time: covered by compile-time resolution and
  `strictMatch`, plus the runtime target check.
- Writable `Return`/`Argument` (`readOnly = false`) has no user in this repo yet: a weave test in `zabric` applies
  generated glue to a test target through ZombieBuddy's own `PatchTransformer` and ByteBuddy, and checks cancel,
  cancel-with-value, return and argument modification, `@Share`, FINALLY and failure catching. If ZombieBuddy's `PatchTransformer` mishandles it, that is a blocker to report, not a reason to drop the
  injector silently.
- Maven Central releases are permanent: the release step runs only after the user confirms the exact version and
  artifacts.
- Rollback: the rename, the hook stack and publishing are separate commits inside the task; reverting the hook commits
  restores `GlueProcessor`'s ZombieBuddy generation.

## 10. As built in group B (supersedes §2–§4 where they differ)

- Reference glue: `zabric/src/test/java/com/pzlauncher/zabric/hook/weave/glue/WeaveGlue.java` (header comment lists the
  rules); the generator must reproduce this shape. Listing format: Javadoc of `hook/impl/HookListing.java`
  (`zabric-hooks v1`, `mod`, `gate default|<class>`, `patch`, `hook <owner> <method> <descriptor>`).
- Glue calls `MOD.active(gate)` and `MOD.failed(gate, what, t)` (not `GATE.active()`/`failed` directly): a throwing
  `active()` is reported and treated as inactive; a throwing `failed()` stops the mod through its default gate.
  `ModHooks` is an interface (`modId`, `open`, `active`, `defaultGate`, `failed`); the implementation is in
  `hook.impl` (not API). One `MOD` per compilation in the outer glue class; the gate is read on each use.
- Locals per hook class: `hN$active` (or `hN$open` with an `always` handler), `hN$share$<key>`, plus `cancelled`/`value`.
- Constructors: `RETURN` only (ByteBuddy refuses throwable-catching exit advice on constructors), no `@Self` at HEAD.
- Overload matching: ZombieBuddy matches any method with at least N parameters assignable to the bound ones;
  `strictMatch` only matters for zero-argument advice. The processor must check at compile time that ZombieBuddy's
  matcher selects only the intended method and fail otherwise.
- `@ModifyVariable` follows Mixin: `index` = local slot, `ordinal` = n-th argument of the handler's type, neither =
  the only argument of that type.
- Target check runs the first pass a mod appears in; a mismatch, or a listing whose `mod` differs from the loader's
  recorded mod id, stops that mod's hooks for the session.
- Log lines: `[Zabric] <mod>: hooks ready (N hook classes)`, `hooks stopped: <problems>`,
  `hooks stopped after an error while <what>` (then `hooks stopped; then an error while <what>`).

## 11. As built in group C

- Module `zabric-processor`: javac front end `ZabricProcessor` (+ `JavacTypes`), front-end-neutral generator library
  in `com.pzlauncher.zabric.processor.glue` (`TypeSystem`, `Reporter`, `HookDecls`, `HookAnalyzer`, `GlueModel`,
  `GlueWriter`, `Messages`, `Names`, `LoaderMatcher`). KSP implements `TypeSystem`/`Reporter`, fills `HookDecls`
  (callee `pkg.Obj.INSTANCE`, facade as gate when omitted, members incl. compiler bridges) and reports `Messages`
  verbatim. The golden test shows generated output equals `WeaveGlue.java` and the reference listings.
- Outer class: mod id in PascalCase + `Glue`, overridable with option `zabric.glueClass` (needed when one mod is built
  from several compilations, e.g. All-in-One's feature modules). Patch classes `<TargetSimple>_<method>`, `init` for
  constructors, `_2`… on clashes.
- ZombieBuddy matcher consequences, all compile errors: array arguments are bound as `Object` and cast (a bound
  `String[]` never matches); a hook whose target has a compiler bridge with the same name is rejected (would run
  twice); `m(I)V` cannot be hooked when `m(ILjava/lang/String;)V` exists ("at least N parameters" matching).
- Stricter rules: `always` all-or-none per hook class per target method; `name(desc)` needs the full descriptor;
  `index` and `ordinal` exclusive; empty hook class, `@UnsafeLoaderPatch` without `@Patch`, and `@Hooks` from a later
  round are errors; inaccessible types the glue must name are errors suggesting access widening.
- FINALLY on a non-void target passes the return value only when nothing was thrown; a cancellable HEAD on a non-void
  target always gets an exit to write the cancel value.
- Gradle incremental-processing registration (aggregating) is in `zabric-processor`'s resources. Weave coverage for
  array binding, constructor RETURN, FINALLY on non-void and `@ModifyVariable` index/ordinal: `ShapeHooks` on `Shapes`
  in the weave test. It showed that a conditional among `CallbackInfoReturnable`'s constructor arguments (FINALLY on
  non-void) fails verification once inlined, and ZombieBuddy then drops every patch of the target class without an
  error; the glue sets the value with an if statement instead.

## 12. Weave verification (D12)

Facts (ZombieBuddy 2.3.4, `PatchEngine.applyPatches`): ByteBuddy errors are only logged; a VerifyError on
retransformation leaves the old class in place; `Transformed:` is logged when ByteBuddy produced bytes, before the JVM
accepts them. Retransform-capable transformers run in registration order, each seeing the previous one's output.

- **Observer.** After each mod-list pass (after ZombieBuddy registered that pass's patch transformers), Zabric
  (re)registers one retransform-capable `ClassFileTransformer` last in the chain. It only looks at classes some mod in
  the pass (open, or entering and not yet open) hooks, and never changes bytes.
- **Check.** For each hooked target method, the woven bytes must contain the patch's advice. Marker: inlined advice
  always reads the glue's `MOD` (`GETSTATIC <outer glue class>.MOD`), so the target method's code must contain that
  instruction for the outer class of the patch that hooks it. Parsed with ByteBuddy's bundled ASM
  (`net.bytebuddy.jar.asm`, inside ZombieBuddy.jar, like Zabric's other ByteBuddy use). The listing's `hook` line gains
  the patch class that hooks it (listing format is unreleased, so `zabric-hooks v1` changes in place).
- **When.** At the pass, for targets already loaded: `Instrumentation.retransformClasses(target)` with the observer in
  place; a thrown `VerifyError`/`LinkageError`/`UnmodifiableClassException` is itself the failure cause. Targets loaded
  later: checked when the observer sees them load (a VerifyError at first definition is not silent: the game fails to
  load the class).
- **Report.** `ModHooks.failed(gate, "weaving <Hooks>.<handler> into <target>.<method>", cause)` per hook class whose
  patch is missing: default gate stops the mod (`hooks stopped after an error while weaving ...`), a named gate gets
  `failed` (a Kotlin `Feature` disables). Runs before main entrypoints, so `Feature.init` sees its state.
- **Opening.** A mod entering a pass stays closed during its pass-time check and opens after it, unless it stopped
  (default-gate failure, or the check could not run); a named-gate failure still opens it.
- **Ready line.** `hooks ready` is logged after the pass-time check and opening, and not for a mod it or the target
  check stopped.
- **Cost.** One extra retransformation per hooked, already-loaded class per pass; the observer's per-class cost for
  other classes is one set lookup.
- Placement: `hook/impl` (generic Instrumentation); only "ZombieBuddy registers its transformers during the pass" is
  loader knowledge, noted in `loader/` docs and the switch checklist.
