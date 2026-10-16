# Implementation plan

Branch `ci-maven-central`. Gradle: `JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew ...`.

1. Download DepotDownloader 3.4.0 linux-x64 (33 MB) and the Windows build into the scratchpad; record the linux zip's
   SHA-256. Run the Windows build with `-os linux` and the filelist to confirm the jar's path in the Linux depot and
   that anonymous access works; compare the jar with the local one (build may differ; record).
2. Resolve commit SHAs for `actions/checkout`, `actions/setup-java`, `gradle/actions/setup-gradle` at releases at
   least two weeks old.
3. Write `.github/actions/pz-jars/action.yml` and `.github/workflows/release-zabric.yml` (`design.md`).
4. Local check of the build part: `:zabric:check :zabric-processor:check` with a two-jar `pzDir` (already passed once,
   rerun after any build change). `actionlint` on the workflow if available.
5. Docs: spec lines and README section (`design.md` "Docs").
6. Review gate: user reads the workflow before it is merged; nothing runs until a `zabric-v*` tag is pushed.

## Verification on GitHub (user-side, after merge and setup)

- AC1: push a mismatched tag (e.g. `zabric-v0.0.0`) and see `verify` fail; delete the tag.
- AC2/AC3: push `zabric-v0.4.0`; `verify` green, `publish` waiting for approval.
- AC4: approve; both artifacts appear on Central.
- AC5: read the run's artifact list (empty) and the workflow's `env` placement.

Rollback point: the workflow is inert until a matching tag exists; reverting the commit removes it.
