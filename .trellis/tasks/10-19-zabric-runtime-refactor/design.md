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
