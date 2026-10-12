# Kotlin rewrite, Mod Options sidebar, KSP evaluation

## Source Request (2026-10-12)

> viewpoint-blood-fx-fix改写为kotlin。allinone添加一个功能，设置界面的ui增加一个右边栏，现在所有的配置都汇聚在一起，很难找到一个特定mod的配置位置。我想把每个配置都分开来。外加考虑是否引入ksp。

## Task Map

| Child | Deliverable |
|-------|-------------|
| `10-12-zombiebuddy-kotlin-utils` | Internal Kotlin middle layer (reflection resolver, feature gating base, logging) bundled into All-in-One |
| `10-12-ksp-glue` | KSP processor generating patch classes and Lua bridges from annotations (after utils) |
| `10-12-viewpoint-kotlin` | Port `viewpoint-blood-fx-fix` to pure Kotlin on the middle layer, behavior unchanged (after utils and ksp-glue) |
| `10-12-mod-options-sidebar` | New All-in-One feature: a right sidebar on Options > Mods so each mod's options are separate |

KSP was evaluated here first (below); the user then adopted it for glue generation (child `10-12-ksp-glue`).

## KSP Evaluation (evidence)

- ZombieBuddy ships **no annotation processor**: `ZombieBuddy.jar` has no `META-INF/services/javax.annotation.processing.Processor`,
  and the submodule source has no `AbstractProcessor`. zb-gradle-plugin 1.0.3 only adds `ZombieBuddy.jar` to the
  `annotationProcessor` configuration (`ZBSigningPlugin.configureAnnotationProcessor`); today that is a no-op. So
  Kotlin code does not lose any ZombieBuddy build-time check, and there is nothing for KSP to replace.
- Patch classes are discovered at runtime by a ClassGraph scan of exactly `com.dont39.pz.allinone`
  (`PatchEngine.java:671`, `718-728`); no generated registry is needed.
- What KSP could generate here: the `<Feature>Patches` / `<Feature>Lua` glue classes and the `smokeTest` expectations.
  Today that is 1 feature, 3 patch classes, 1 Lua function — under 70 lines of hand-written glue.
- Cost: a KSP plugin pinned to the Kotlin version in `gradle/libs.versions.toml` (bumped together on every Kotlin
  bump), a processor module, generated sources in the `allinone` package, and another plugin to check against the
  configuration cache (`pz-java` already marks `JavaCompile` incompatible because of zb-gradle-plugin).

Recommendation: do not introduce KSP now; revisit when there are 3+ features with patches or the glue starts to drift.

## Cross-Child Acceptance Criteria

- [ ] `./gradlew build assembleMod` succeeds (JDK 25).
- [ ] `./gradlew :dont39-all-in-one:smokeTest` passes with all children merged.
- [ ] Spec files under `.trellis/spec/mod/` reflect the end state (Kotlin feature reference implementation, new
      feature, middle layer, annotation-based glue).

## Decision

- First recommendation (before the middle-layer and "no `@JvmStatic`" requests): KSP not now, see the evidence above.
- **User decision (2026-10-12): adopt KSP to generate the ZombieBuddy glue**, behind a feasibility spike; on failure
  fall back to hand-written Kotlin glue with `@JvmStatic` (child `10-12-ksp-glue`, D1/D2).

## Added Request (2026-10-12)

- "我要纯kotlin": the viewpoint port includes the patches and Lua bridge (child `10-12-viewpoint-kotlin`, R3).
- "可以先写个中间层，避免后续用起来太恶心": internal module `zombiebuddy-kotlin-utils` (child
  `10-12-zombiebuddy-kotlin-utils`; user chose internal over a public library API).
- No build check on inlined advice (personal mod); "省略写@JvmStatic" -> KSP glue generation (child `10-12-ksp-glue`).

## Shared Constraints

- Order: utils -> ksp-glue -> viewpoint port (written in the children's artifacts). The sidebar is independent.
- The code children touch `dont39-all-in-one/build.gradle.kts` (feature list / smokeTest) and bump its version; bump once
  for the release that ships them.
