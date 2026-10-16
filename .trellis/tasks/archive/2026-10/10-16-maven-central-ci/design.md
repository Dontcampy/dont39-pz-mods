# Design

## Workflow

`.github/workflows/release-zabric.yml`, on `push: tags: ['zabric-v*']`; `permissions: contents: read`; one concurrency
group, no cancel.

Two jobs, so the approval request comes only after a green build and the secrets never meet the verify steps:

| Job | Environment | Steps |
|-----|-------------|-------|
| `verify` | none | version check; fetch jars; `./gradlew :zabric:check :zabric-processor:check -PpzDir=...` |
| `publish` | `maven-central` (required reviewers) | fetch jars; `./gradlew publishAndReleaseToMavenCentral -PpzDir=...` with the secrets on this step only |

`publish` needs `verify`. Jars are fetched in each job: an artifact would publish the game jar, and a cache entry
would hold it.

## Version check

Tag `zabric-v<version>` must equal `zabric` in `gradle/libs.versions.toml`; otherwise fail with both values. Shell
only, before anything is downloaded.

## Fetching the jars (a composite action, used by both jobs)

`.github/actions/pz-jars/action.yml`, output a `pzDir` under `$RUNNER_TEMP` holding exactly:

- `gh release download` (with `github.token`) fetches DepotDownloader 3.4.0 `DepotDownloader-linux-x64.zip` and
  ZombieBuddy v2.3.4 `ZombieBuddy.jar`; no checksum (user's choice: the releases are pinned by tag).
- `projectzomboid.jar`: in a separate step without the token, DepotDownloader runs `-app 380870 -branch unstable -os
  linux -filelist <file> -dir <dir>`, the filelist naming only the jar. Anonymous: no Steam login.

The game jar is not pinned: the unstable branch moves, and Zabric is compiled against the current game like local
builds. A missing jar fails the step.

## Build environment

`actions/setup-java` (Temurin 25) and `gradle/actions/setup-gradle` (caches Gradle, never `pzDir`). Actions are
referenced by full release tag (user's choice over commit SHAs), each at a release at least two weeks old.
`-PpzDir` overrides the root `gradle.properties` value.

## Secrets (repository or Environment secrets)

`MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `SIGNING_KEY` (ASCII-armored private key), `SIGNING_KEY_PASSWORD`,
mapped on the publish step to `ORG_GRADLE_PROJECT_mavenCentralUsername`, `..._mavenCentralPassword`,
`..._signingInMemoryKey`, `..._signingInMemoryKeyPassword`. Storing them as Environment secrets of `maven-central`
keeps them out of any job that does not wait for approval.

## Rollback

Before approval: reject the run, delete the tag. After release: none; fix forward with a new version.

## Docs

- `.trellis/spec/mod/zabric-hooks.md` "Publishing and Compatibility": releases go through the workflow; the
  Environment approval is the user's confirmation.
- `.trellis/spec/mod/zombiebuddy-patching.md` switch checklist and `zabric-hooks.md` rules: bump the workflow's ZombieBuddy release.
- `README.md`: a short "Releasing Zabric" section with the tag command and the one-time setup list.
