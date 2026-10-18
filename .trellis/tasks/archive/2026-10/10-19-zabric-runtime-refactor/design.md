# Design: Zabric runtime refactor

## Layout

`zabric/src/main/java/com/pzlauncher/zabric/`
- `internal/` (new, not API; `package-info` says so) — `Log` (prefix), `Jars` (code-source jar, zip entry lines).
- `widener/AccessWidener` — public facade, unchanged signatures; `widener/WidenerScan` (premain scan, mod folders),
  `widener/WidenerTransform` (adjustments, agent). Diagnosis (`problem()` and friends) stays in the facade or a
  `WidenerDiagnosis` if it exceeds ~120 lines; `problem()` returns the problem, the facade's caller path adds the
  restart entry.
- `hook/impl/HookListing` — record + parse; `hook/impl/TargetCheck` (new) — reflection-based target check.
- `entrypoint/Lifecycle` — state machine; `entrypoint/Entrypoints` (new) — manifest reading, instantiation.
- `zabric/src/listingformat/java/com/pzlauncher/zabric/listing/HookListingFormat.java` (new srcDir) — constants;
  added to `zabric` and `zabric-processor` source sets the way `src/classtweaker` is added today
  (`zabric/build.gradle.kts:23-25`). `HookListing` and processor `Names` refer to it.

`zb-kt/KotlinSwap.java` keeps its own jar lookup (different jar, runs before the swap; not worth a dependency).

## Compatibility

- Classes moved out of public classes are new package-private or `internal`-package classes; public members of
  existing public classes keep signatures. `javap -public` diff is the gate.
- Duplicate `HookListingFormat` class in zabric and zabric-processor jars is fine: the processor runs on the
  annotation-processor path, never on the game class path.

## Rollback

One commit per requirement; R2 is the largest and is revertible alone.

## Implementation notes

- `internal/Jars` has `codeSource`, `bytes`, `lines(byte[])`, `lines(ZipFile, ZipEntry)`: AccessWidener needs the bytes
  (sha256) as well as the lines. `Log.PREFIX` is a compile-time constant, so every log string is byte-identical.
- `internal.Jars`, `internal.Log` and `listing.HookListingFormat` are public (used across packages); their
  `package-info` says internal. They are the only additions to `javap -public`. All other new classes
  (`WidenerScan`, `WidenerTransform`, `TargetCheck`, `Entrypoints`) are package-private.
- `HookListing` keeps its public `problem(Class)`, `gateProblem(Class)`, `problem(ClassLoader)` and constants (API
  baseline), and the package-private `problem/gateProblem(Class, Path)` the unchanged `HookListingTest` calls; all
  delegate to `TargetCheck`. `ZabricHooks` still goes through `HookListing` so `TargetCheck` stays package-private;
  `HookRuntime` calls `TargetCheck` directly.
- AccessWidener diagnosis (`check`, `verify`, both `report`s) stayed in the facade (~130 lines; file 235 lines).
  `check` is a pure query returning `Outcome.restart`; `problem()` and `report(modId, host)` add the restart entry
  before returning or logging, the same point as before. `check(Scan, ...)` is package-private for
  `AccessWidenerTest`.
- "Already loaded" marking stays inside `WidenerTransform.install`: it is the transform that finds those classes.
- Processor `Names.LISTING_*` removed; `GlueWriter` and `HookAnalyzer` use `HookListingFormat`; `listing.ftl` gets
  `header` from the model.
- buildSrc compiles the same srcDir (like `classtweaker`): `FeatureInfo.HOOKS` and its listing suffix come from
  `HookListingFormat`. The sample's build script still spells the dir (standalone author build).
