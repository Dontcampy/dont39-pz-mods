# zabric, zabric-processor, zb-kt-utils-processor

Overall: sound. A shared processor core already exists (`zabric-processor/.../processor/glue/`: HookDecls,
HookAnalyzer, GlueModel, GlueWriter, TypeSystem, LoaderMatcher, Names, Messages); both front ends feed it, so rule
validation, glue generation and listing writing live once. A separate processor-core module adds little.

## Leave alone

- `zabric/.../hook` (17 files): all are author annotations, glue-facing classes, `SharedRef` (package-private behind
  `LocalRef.create()`) or package-info; referenced by released glue or `processor/glue/Names`. Moving any breaks
  released glue.
- `hook` ↔ `hook.impl` cycle: acceptable facade (ZabricHooks → HookRuntime/HookListing).
- Public internals (`Lifecycle.install/preLaunch/onZabricMain`, public `HookRuntime`/`HookListing`): cannot narrow
  without a compat break.
- WeaveCheck (244): cohesive. zb-kt-utils (272), zb-kt (186): fine.
- Dependency direction: KSP → zabric-processor → FreeMarker only; classtweaker parser shared with buildSrc via srcDir.

## P1. zb-kt-utils-processor has no tests

Only `src/main`. Untested: nullability rules (`HookReader.kt:180-185`), facade gate (`GlueProcessor.kt:153-170`),
object/visibility rules, Lua bridge, JVM-descriptor parsing (`KspTypes.kt:213`). javac side has GoldenTest,
HookRulesTest, LoaderPatchTest.

## P2. ZabricProcessor (511) does three jobs

Round handling + `@Patch`/stray-annotation policing (91-155), file generation (157-225), reading javac elements into
HookDecls (228-505: read, gate, handler, checkMixin, target + bridges, supertypes, byBinaryName, mirror helpers).
Move reading (~300) to `JavacHookReader`, mirroring `HookReader.kt`; ZabricProcessor → ~200.

## P3. GlueProcessor.kt (325) mixes the `@LuaGlobal` bridge

Lines 172-278 + `render`/`kotlinString` (292-325) are the Lua bridge, unrelated to hooks → `LuaBridge.kt` (~150).

## P4. Front-end leftovers

~130 lines per side do the same job against different AST APIs (loader-patch 109-127 vs 80-95, stray annotations
137-155 vs 101-119, Mixin 367-391 vs 209-229, bridge enumeration 394-461 vs 232-277, gate 290-315 vs 104-133). Do not
extract the logic. Do move word-for-word messages into `Messages`:
- "must be a public HookGate with a public static final INSTANCE..." (ZabricProcessor:310, HookReader:124)
- "@Hooks classes generated in a later processing round..." (ZabricProcessor:99, GlueProcessor:60)
- "the gate class X cannot be resolved" (ZabricProcessor:292, HookReader:107)
Move `ZabricProcessor.defaultClassName` (214) to `glue/Names` (KSP requires the option instead, set at
`pz-feature.gradle.kts:32`).

## P5. HookAnalyzer (725): cohesive, long

One linear pass with shared `failed` state. Parts: unit checks (46-57); per handler: shape (154-244), member/overload
resolution (247-312), parameter binding `bind` (314-477, 163 lines), modifiedArg (480-521); per site: patch assembly,
share/always, access (523-639), matcher simulation (641-671), naming (673-692). Natural split `HandlerBinder` (~370)
+ `PatchBuilder` (~170); minimum: split `bind` into INJECT and MODIFY paths. HookRulesTest covers it end-to-end.

## P6. Repeated helpers inside zabric

`[Zabric] ` prefix defined 4× (Lifecycle:36, Preload:18, AccessWidener:59, HookRuntime:19) + literal in
RestartList:30. Code-source jar lookup identical in `AccessWidener.jarOf` (388-394) and `HookListing.location`
(266-272), similar `zb-kt/KotlinSwap.java:65`. Zip entry as UTF-8 lines: AccessWidener:400, HookListing:162. Internal
`Jars` helper saves ~25 lines.

## P7. Listing format constants duplicated on purpose

Writer `Names.LISTING_DIR/SUFFIX/DEFAULT_GATE` + header in `listing.ftl:2`; reader `HookListing.DIR/SUFFIX/HEADER/
DEFAULT_GATE` (34-37). Processor deliberately does not link zabric (`Names.java:5`). Optional: share a tiny
`HookListingFormat` via srcDir like `zabric/src/classtweaker` (`zabric/build.gradle.kts:23-25`). Tests already compare
both sides.

## P8. Other large classes (optional splits)

- AccessWidener (420): premain scan (118-147, 217-242), ByteBuddy adjustments + agent (149-212), per-mod diagnosis
  (298-349), publicLookup verification (352-386), reporting (253-296); `apply()` ~97 lines. Split `WidenerScan` /
  `WidenerTransform` behind the `AccessWidener` facade. Smell: query `problem()` calls `RestartList.add` (331).
- Lifecycle (319): cohesive state machine; manifest reading (271-310) + reflective instantiation (237-262) could be an
  `Entrypoints` helper (~60). Low value.
- HookListing (273): record + parser + IO + runtime target check (`problem`/`gateProblem`/`missing` 169-264, ~100) →
  `TargetCheck` in `hook.impl`. Low-medium value.

## Dead code (verified)

- `Names.isPrimitive` (`Names.java:47`) never called.
- `Reporter.warning` (`Reporter.java:7`) implemented by both front ends, never called by the core.
- `GlueWriter.listingPath` public, only used internally.
