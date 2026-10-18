# Codebase structure review and refactor

## Goal

Make the codebase cheaper to change: extract real duplication, split classes and files that carry unrelated jobs,
remove dead and stale code, add the tests that make those changes safe, and remove cross-project configuration from
the build. No behavior change for players or mod authors.

## Background (survey, 2026-10-19)

~13k main lines. Largest file `HookAnalyzer.java` (725); ~15 files over 300 lines. Densest package
`zabric/.../hook` (17 files). `tunnel` (31 files, 4.5k lines) and `zb-kt-utils-processor` have no tests. Evidence
with file:line anchors:

- [research/zabric-processors.md](research/zabric-processors.md) — P1–P8, dead code
- [research/tunnel.md](research/tunnel.md) — T1–T4
- [research/build.md](research/build.md) — B1–B5

## Decisions

- D1 (user, 2026-10-19): scope is all three tiers (cleanup, tests + splits, optional large splits and build
  architecture).
- D2 (user, 2026-10-19): `pzDir` / `pzServerDir` leave the tracked `gradle.properties` for the user-level
  `~/.gradle/gradle.properties`; CI keeps passing `-PpzDir`.
- D3: one child task per area, one PR per child, in the order of the task map.
- D4 (evidence): kept as they are — `Kcp.kt` (ikcp.c port) and `tunnel/.../fec/rs/*` (Backblaze copy); the 17 files
  of `zabric/.../hook` (public API and glue-facing); the existing shared processor core `processor/glue/` (no new
  module); tiny feature modules (own gate, status line, glue class); per-file Lua helpers (a shared Lua file would
  couple independently packaged features); network-status ↔ tunnel decoupling through the Lua global;
  `smokeTest`'s empty `luaTargeted` list (documented slot, `directory-structure.md:111`).
- D5: this task resolves D9 of `10-18-kcp-tunnel` ("adding tests to the repo is proposed separately"): `tunnel` and
  `zb-kt-utils-processor` get JUnit tests in the repo.

## Task map

| Order | Child | Covers |
|-------|-------|--------|
| 1 | [10-19-build-conventions](../10-19-build-conventions/prd.md) | B1–B5, D2, test convention used by 2 and 4 |
| 2 | [10-19-processor-refactor](../10-19-processor-refactor/prd.md) | P1–P5, processor dead code |
| 3 | [10-19-zabric-runtime-refactor](../10-19-zabric-runtime-refactor/prd.md) | P6–P8 (after 2: P7 touches processor `Names`) |
| 4 | [10-19-tunnel-refactor](../10-19-tunnel-refactor/prd.md) | T1–T4 (after 1; independent of 2 and 3) |

## Cross-child acceptance criteria

- [ ] AC1. After each child: `./gradlew build assembleMod`, `:dont39-all-in-one:smokeTest` and
  `:zb-kt:serverSmokeTest` pass.
- [ ] AC2. Generated glue and hook listings of every feature are identical before and after children 1–3
  (diff of `build/generated/ksp` and `META-INF/zabric/hooks`).
- [ ] AC3. Public API of the `zabric` jar (`javap -public` over `com.pzlauncher.zabric.*` except `hook.impl`
  internals already public) is unchanged.
- [ ] AC4. Specs that describe changed structure are updated (Adding a Feature checklist, Tests section of
  quality-guidelines, build setup), linking code rather than restating it.

## Out of Scope

- Version bumps, Workshop uploads, Maven Central releases (the user's release step).
- Renaming `tunnel.kcp` / `tunnel.transport.kcp`; narrowing already-public Zabric internals (compat break).
- New features or behavior changes.
