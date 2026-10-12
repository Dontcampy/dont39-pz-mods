# zombiebuddy-kotlin-utils middle layer

Parent: `10-12-allinone-kotlin-sidebar`. Must land before `10-12-viewpoint-kotlin`, which is its first user.

## Goal

Later Kotlin features in All-in-One state only what is specific to them (target mod, version allowlist, the internals
they need, the patch logic). The shared shape the specs require of every feature lives in one internal module.

User request (2026-10-12): "可以先写个中间层，避免后续用起来太恶心".

## Decisions (user, 2026-10-12)

- **D1 Internal module, not a public library API:** module `zombiebuddy-kotlin-utils`, bundled into the All-in-One jar
  only. The ZombieBuddyKotlin library mod keeps its "no helper APIs" rule (archived kotlin-lib task, D4).
- **D2 Name:** module dir `zombiebuddy-kotlin-utils`; package `com.dont39.pz.zombiebuddykotlinutils` (repo naming
  rule: kebab name → concatenated package, `.trellis/spec/mod/directory-structure.md`).

## Background (repository evidence)

Boilerplate the specs require of every feature, as written today in the Java feature
(`viewpoint-blood-fx-fix/src/main/java/com/dont39/pz/viewpointbloodfxfix/`):

- Reflection resolved once, failure recorded not thrown: static blocks in `LiftOffsets.java`, `AimShift.java`,
  `Relief.java`; field helper `LiftOffsets.field`; method handles in `Relief.java`
  (`zombiebuddy-patching.md` "Reaching Other Mods", `error-handling.md`).
- Gating: `problem()` / cached `active()` / `setForce()` / final `disable()` / target version probe in
  `ViewpointBloodFxFix.java` (`zombiebuddy-patching.md` "Gating").
- Logging: `DebugType.Mod` with `[Dont39AllInOne] <name>: ` prefix, lifecycle lines ready/idle, active/inactive,
  disabled (`logging-guidelines.md`).
- Kotlin features must stay inert when ZombieBuddyKotlin is not ready (`kotlin-runtime.md`; `Main.kt`).

## Requirements

- R1 Module `zombiebuddy-kotlin-utils` (pz-kotlin), registered in `settings.gradle.kts`, compiled into the All-in-One
  jar; not a feature (no Lua, no `init()`, not in the feature list).
- R2 **Reflection resolver:** resolve classes, static/instance fields, constructors, and method handles by name in one
  place per helper object; on any failure keep the first problem as a string and return null/absent values instead
  of throwing. Feature code reads the problem once (`resolver.problem`).
- R3 **Feature base:** an abstract feature type that owns gating and lifecycle logging. A feature supplies its name and
  its own problem check; the base provides `init()` (ready/idle line), cached `active()` (active/inactive line on
  first use), `force` re-opening the decision, final `disable(what, t)`, `log` / `warn`. `active()` is false until
  `init()` ran, so a feature that `Main` never initialized (Kotlin not ready) is inert.
- R4 **Target-mod probe:** helper to read another mod's version constant and check it against an allowlist, with the
  override hint text ("Options > Mods > … > …").
- R5 **Hot-path neutral:** nothing on the per-frame path allocates, boxes, or goes through a lambda/delegate; resolved
  values are plain fields; `active()` is a field read after the first decision.
- R6 Log lines produced through the base are byte-identical to today's viewpoint lines for the same situations.
- R7 Specs: a short spec section saying features use this module (link the files), and that promoting it to the
  library mod is a separate decision.

## Acceptance Criteria

- [ ] `./gradlew build` passes; All-in-One jar contains `com/dont39/pz/zombiebuddykotlinutils/` classes and no
      `kotlin/`/`kotlinx/`. (R1)
- [ ] The ported viewpoint feature (sibling task) uses the resolver for every reflective lookup and the base for all
      gating/logging; its facade has no hand-written `active`/`failed` caching. (R2, R3, R4)
- [ ] `javap -c` of the ported `LiftOffsets.liftBlood` hot loop shows no allocation or delegate calls added by the
      utils. (R5)
- [ ] smokeTest's `viewpoint-blood-fx-fix:` status line and in-game lines match today's wording. (R6)
- [ ] Specs updated. (R7)

## Out of Scope

- Generating patch/Lua glue (KSP: not now, see parent).
- Public API in the ZombieBuddyKotlin library mod; use by other Workshop mods.
- Lua-side helpers.
- Unit-test infrastructure (no test setup in the repo today).
