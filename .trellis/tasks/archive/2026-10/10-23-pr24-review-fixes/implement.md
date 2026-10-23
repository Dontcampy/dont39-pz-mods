# Implement: PR #24 review fixes

Branch `feat/gpu-bc7-encoder-2b7d0a` (same commit as `load-speed-optimization`, 02bea37). JDK 25 for Gradle:
`JAVA_HOME=~/.jdks/graalvm-ce-25.0.2`. Do not run `smokeTest` or `deployWorkshop` (they overwrite the user's
Workshop staging); in-game checks run in the parent's AC5 afterwards.

## Checklist

1. **Baseline (A).** Before changing BC7: add the `QualityCheck` hash dump (`-Dfasterloading.dump=<dir>`: one line
   per page/level/size with a SHA-256 of the BC7 blocks, also for the large/odd sizes) and the `widthHw` padded
   timing, run `:faster-loading:qualityCheck` at 02bea37's encoder, keep the dump and the printed GPU / GL-thread time
   a page in the task's `research/baseline/`.
2. **A1 + A10** grid and shader numbering; `Bc7EncoderTest` covers balanced grids (1, 65535, 65536, 4096²/16, ...).
   Run qualityCheck: hashes identical, time recorded.
3. **A2, A8, A3** each followed by a qualityCheck run (hashes identical; GPU and GL-thread time recorded).
4. **A5** shared memory per entry; measure; keep or revert.
5. **A4** small-level batching; tests for the batch/flush/mismatch logic that runs without a GPU; measure; keep or
   revert per the PRD.
6. **A6, A7, A9**; `Dxt5EncoderTest` and DXT5 qualityCheck numbers unchanged.
7. **C1-C6** in Loading Progress with tests (`FileWorkTest`, `ProgressTest`, `ReportTest`); README's report section
   documents `*-files.csv`.
8. **B3, B4, B5** in Faster Loading; `FolderWalkTest` still equal to vanilla.
9. **B2** `mod-settings/` templates + `pz-switches` convention; both mods switch to them; LP gains missing-key
   upgrade with a test; **B1** test per mod.
10. **Build gate** for both mods: `./gradlew :faster-loading:check :faster-loading:assembleMod
    :loading-progress:check :loading-progress:assembleMod`; inspect both jars (own `settings.Switches` class, no
    shared package) and the assembled Lua (`Dont39<Mod>_Switches.lua`).
11. Record measurements and kept/reverted decisions in `verification.md`; update specs
    (`error-handling.md` settings row, `quality-guidelines.md` GL section if A3/A4 change the rules,
    `directory-structure.md` for `mod-settings/`).

## Validation commands

```bash
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew :faster-loading:check :faster-loading:assembleMod
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew :faster-loading:qualityCheck
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew :loading-progress:check :loading-progress:assembleMod
```

## Review gates

- After 2-5: BC7 hashes identical to the baseline for every entry; otherwise stop and find why.
- After 7: Loading Progress report of a test fixture shows one wait span; line CSV columns as in 0.1.0.
- After 9: both mods' settings tests pass unchanged except the intended LP missing-key upgrade.

## Rollback points

- A4 and A5 are separate commits' worth of change (keep them in separate steps) so either can be reverted alone.
