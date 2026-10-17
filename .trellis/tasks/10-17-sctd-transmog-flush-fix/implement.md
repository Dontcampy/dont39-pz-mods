# Implement: sctd-transmog-flush-fix

## Checklist

1. Branch `sctd-transmog-flush-fix` from `main` (do not touch `loading-progress`).
2. `sctd-transmog-flush-fix/build.gradle.kts` (`pz-kotlin`), `include(":sctd-transmog-flush-fix")`, add to
   `features` in `dont39-all-in-one/build.gradle.kts`.
3. `sctd-transmog-flush-fix/src/main/lua/client/Dont39AllInOne_SctdTransmogFlushFix.lua` per `design.md`; comments
   short, why only.
4. javap check of how the game treats `loadModAfter` for a missing mod; then `mod.info` `loadModAfter`.
5. `smokeTest` status check for the new feature; version 0.9.0; Workshop section + intro sentence.
6. Build gate, then smoke test (Steam running, user's mod list).
7. Optional AC4: in the deployed copy only, alter the expected text, run smokeTest, confirm `disabled`; restore and
   redeploy.
8. Report; commit / PR only after the user confirms.

## Validation commands

```bash
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew build assembleMod
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew :dont39-all-in-one:smokeTest
grep -a "sctd-transmog-flush-fix" ~/Zomboid/console.txt
```

## Review gates

- `trellis-check` against `.trellis/spec/mod/` (directory structure, patching, error handling, logging).
- In-game multiplayer check (AC3) needs a server with the mod; report as not done if unavailable.
