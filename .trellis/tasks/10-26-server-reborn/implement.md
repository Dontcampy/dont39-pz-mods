# Implementation plan: Server Reborn

Design: `design.md`. Branch `feat/server-reborn-46640e` (from `feat/sync-guard` at `b22a9ec`). Each numbered step is
one commit; the build gate runs before each commit from step 2 on.

## Checklist

1. Old task: `git mv` `10-24-mp-sync-loss-recovery/research/harness` to this task's `research/harness`; add the
   "superseded by `10-26-server-reborn`" note and the open items' destination to the old task; archive it with
   `task.py archive`. Fix this task's links (`design.md`, `implement.jsonl`, `check.jsonl`) to the archived path.
2. Runtime: `Feature.decideAgain()` and `Feature.onDisabled()`; `SandboxOption` moved to `zb-kt-utils` and its users
   (`mod-sync`, `tunnel`, `server-tick-rate`) switched; ZabricKotlin 0.8.0 (`zb-kt` version, README, spec "new in"
   notes); `Feature` tests if it runs outside the game.
3. KSP front end: several facades per compilation, default gate by package; processor tests; golden glue unchanged.
4. Build: `pz-kotlin-mod` (features list, `initFeatures()` writer sharing the naming function with
   `GenerateFeatureInit`, packaging, `GameSmokeTest` in-module features input). Module skeleton `server-reborn/`
   (`build.gradle.kts`, `mod/42/mod.info`, `mod/workshop/workshop.txt`, `ServerRebornMain`, sandbox page name)
   registered in `settings.gradle.kts`.
5. network-sync-optimization, JDK-only code with its tests first: `protocol`, `stats`, client `Ledger`/`Notices`; all
   90 tests ported and green with the same expected values before the rest.
6. network-sync-optimization, game code: facade (table in `design.md`), `game`, `server`, `client`, `@LuaGlobal`
   function, hook objects, the server-start decision hook, `.classtweaker`, open-check Lua, translations, the
   `Enabled` option.
7. server-tick-rate moved into `server-reborn` (package, mod id, option name, its test); remove the old
   `server-tick-rate/` and `sync-guard/` modules, their `settings.gradle.kts` lines, `:server-tick-rate` in
   All-in-One's list; All-in-One `workshop.txt` and root `README.md` lines back to `main`'s.
8. Docs: `server-reborn/README.md`, Workshop text, specs per `design.md` "Specs and docs".
9. Harness: new names (`design.md`, "Harness and the old task"); then the one smoke join (below).
10. Finish: `trellis-check`, spec update pass, journal; PR against `main` only after the user agrees.

## Validation

- Build gate (JDK 25), from step 2 on:
  `JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew build`
  (covers `:zb-kt-utils-processor` tests, `:dont39-all-in-one:build`, `:zb-kt`, and from step 4 `:server-reborn`),
  plus `:server-reborn:assembleMod` from step 4.
- Test count: 90 network-sync-optimization tests + 5 tick-rate tests in `server-reborn`'s test report.
- `grep -rn "Dont39SyncGuard\|syncguard\.\|sync-guard\|com.dont39.pz.common.SandboxOption"` outside
  `.trellis/tasks/archive`, journals and git history: only deliberate "formerly" mentions.
- Smoke join (one run, AC3), per the harness README:
  - before it: back up Loading Progress reports; check `default.txt` and the Workshop copies (the launcher rewrites
    them); stage the built Server Reborn and ZabricKotlin 0.8.0 where the harness copies mods from (back up the staged
    ZabricKotlin first); ZombieBuddy must approve the new jar hashes (the user's click or an approvals entry).
  - `setup-server.ps1 -WithServerReborn`, server start, one `-nosteam` client join, ~90 s, stop, cleanup.
  - pass: server `[Dont39ServerReborn] network-sync-optimization: active` after the world start and
    `server-tick-rate: off: sandbox option ... is 10`; client `network-sync-optimization: ready` and active for the
    server; ZabricKotlin's ready line shows 0.8.0; no `hooks stopped` and no `disabled` line; `diff-errors.ps1`
    against the archived `compat-off2` consoles shows no new signature on either side.
- Deferred to the user's combined Linux-server test: 20 Hz, `Enabled = false`.

## Risky points

- KSP front end with several features: first use. A glue shape javac accepted and the KSP reader refuses is fixed in
  `zb-kt-utils-processor` with a test there, not worked around in the mod.
- Digest and wire equality after the port: the ported tests' golden values are the guard; never change an expected
  value to make a test pass.
- Both features hook `setLockFPS` (a `@ModifyVariable` and an `always` `@Inject`, both HEAD): check the generated glue
  and the weave lines.
- Release order: ZabricKotlin 0.8.0 before Server Reborn and before the next All-in-One upload (`SandboxOption`
  moved). The user uploads both by hand.
- ZombieBuddy approval of two new jars before the smoke join.

## Before `task.py start`

- User approved the final planning summary.
- `implement.jsonl` and `check.jsonl` list the spec and research files.
