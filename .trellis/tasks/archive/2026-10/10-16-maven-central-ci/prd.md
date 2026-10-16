# Release Zabric to Maven Central from GitHub Actions

## Goal

Releasing `com.pzlauncher:zabric` and `com.pzlauncher:zabric-processor` is a GitHub Actions run, not a local
command: no game install, credentials or signing key on the releasing machine. A release stays permanent, so a person
still approves each one.

## Background

- Publishing is already set up (`buildSrc/src/main/kotlin/pz-publish.gradle.kts`, vanniktech 0.37.0): Central
  credentials and the in-memory signing key come from Gradle properties, so `ORG_GRADLE_PROJECT_*` environment
  variables work without build changes. Without a signing key, a `*MavenCentral*` task fails fast.
- Both artifacts share one version, the catalog's `zabric` (`gradle/libs.versions.toml`, now 0.4.0), also Zabric's mod
  version.
- `zabric` compiles against `projectzomboid.jar` and `ZombieBuddy.jar` from `pzDir` (`pz-java.gradle.kts`); its tests
  need the game jar; `zabric-processor`'s tests need `ZombieBuddy.jar`. `pz-feature.gradle.kts` reads `pzDir` at
  configuration, so the whole build needs it set. Checked 2026-10-16: with `pzDir` holding only those two jars,
  `:zabric:check :zabric-processor:check` and both `publishToMavenLocal` pass (`--rerun-tasks`).
- The dedicated server's `java/projectzomboid.jar` is byte-identical to the client's (build 25485538); the full server
  install is 6.8 GB, the jar 63 MB. Hence DepotDownloader (SteamRE, GPL-2.0, latest 3.4.0), which fetches single files,
  over SteamCMD, which fetches the whole server.
- `ZombieBuddy.jar` of ZombieBuddy's GitHub release v2.3.4 is byte-identical to the installed one (MIT).
- The game jar is proprietary: never committed, stored in a secret, cached or uploaded as a workflow artifact
  (artifacts of a public repo are downloadable).
- The repo is public and has no workflows, tags or Environments yet.

## Requirements

- R1. Pushing tag `zabric-v<version>` runs a workflow that publishes and releases both artifacts to Maven Central.
- R2. It fails before downloading anything when `<version>` differs from the catalog's `zabric`.
- R3. It runs `:zabric:check` and `:zabric-processor:check` before asking for approval.
- R4. It fetches `java/projectzomboid.jar` alone from the dedicated server (app 380870, beta `unstable`) anonymously
  with DepotDownloader, and `ZombieBuddy.jar`, both from pinned GitHub releases via `gh release download` (no
  checksum: user's choice); no step that runs DepotDownloader sees a token or secret.
- R5. Publishing waits for a person's approval in the GitHub Environment `maven-central`.
- R6. Secrets reach only the publishing step; the game jar never leaves the runner.
- R7. The specs and README say how to release and what the one-time setup is.

## Acceptance Criteria

- [ ] AC1 (R2): a tag whose version differs from the catalog fails the run with a message naming both.
- [ ] AC2 (R1, R3, R4): on a fresh runner the checks pass and the run waits at publishing.
- [ ] AC3 (R5): publishing starts only after approval in `maven-central`.
- [ ] AC4 (R1): after an approved run, both artifacts with the tag's version are on Maven Central.
- [ ] AC5 (R6): the run has no artifacts, no cache entry holds `projectzomboid.jar`, and secrets are set only on the
  publishing step.
- [ ] AC6 (R7): `zabric-hooks.md`, the ZombieBuddy switch checklist and `README.md` describe the release and setup.

AC1–AC5 are verified on GitHub after merge and the user-side setup.

## Out of Scope

- CI builds on pushes or pull requests; manual workflow runs.
- GitHub Releases, Workshop uploads, switching the sample to `mavenCentral()` only (after the first release).
- `zb-kt-utils-processor` (not published).

## User-Side Setup (not automatable)

Central Portal account and `com.pzlauncher` namespace (DNS TXT on `pzlauncher.com`); a GPG key published to a
keyserver; a Central user token; the Environment `maven-central` with required reviewers and its four secrets.
