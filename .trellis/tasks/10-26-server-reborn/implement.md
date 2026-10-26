# Implementation plan: Server Reborn

Design: `design.md`. Branch `feat/server-reborn-46640e` (from `feat/sync-guard` at `b22a9ec`). Each numbered step is
one commit; the build gate runs before each commit from step 2 on.

## Checklist

1. Old task: `git mv` `10-24-mp-sync-loss-recovery/research/harness` to this task's `research/harness`; add the
   "superseded by `10-26-server-reborn`" note and the open items' destination to the old task; archive it with
   `task.py archive`. Fix this task's links (`design.md`, `implement.jsonl`, `check.jsonl`) to the archived path.
2. Build convention: `buildSrc/src/main/kotlin/pz-kotlin-mod.gradle.kts`; `GlueProcessor`'s missing-option message.
   Module skeleton `server-reborn/` (`build.gradle.kts`, `mod/42/mod.info`, `mod/workshop/workshop.txt`,
   `ServerRebornMain` logging `loaded`), registered in `settings.gradle.kts` next to the old modules.
3. Sync part, JDK-only code with its tests first: `protocol`, `stats`, and the client `Ledger`/`Notices` with their
   tests (90 in all) ported; tests pass with the same expected values before anything else is ported.
4. Sync part, game code: `game` (gate, settings, containers, digests, packet scope, pinned id), `server`, `client`,
   Lua bridge (`@LuaGlobal`, `exposeIfMissing`), hook objects (KSP glue), the `.classtweaker`, the open-check Lua and
   translations under the new names; the sandbox switch and its `setLockFPS` decision hook.
5. Tick-rate part: `servertickrate` (facade, hooks with an explicit gate, `TickScale`, its test), the sandbox options
   file with both options and the page name, EN/CN translations, mod-local `SandboxOption`.
6. Remove the old modules: `sync-guard/`, `server-tick-rate/`, their `settings.gradle.kts` lines, `:server-tick-rate`
   in All-in-One's feature list; All-in-One `workshop.txt` and root `README.md` lines back to `main`'s.
7. Docs: `server-reborn/README.md`, Workshop text (EN + CN, both parts, ZabricKotlin
   `3820734224` in the requirements), specs per `design.md` "Specs and docs".
8. Harness: new names (`design.md`, "Harness and the old task"); then the one smoke join (below).
9. Finish: `trellis-check`, spec update pass, journal; PR against `main` only after the user agrees.

## Validation

- Build gate (JDK 25):
  `JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew :server-reborn:build :server-reborn:assembleMod :dont39-all-in-one:build`
  and, after step 6, a full `./gradlew build`.
- Test count: 90 network-sync-optimization tests + 5 tick-rate tests in the Gradle test report.
- `grep -rn "Dont39SyncGuard\|syncguard\|sync-guard\|server-tick-rate"` outside `.trellis/tasks/archive`, journals and
  git history: only intended mentions (e.g. "formerly Sync Guard" in the README).
- Smoke join (one run, AC3), per the harness README:
  - before it: back up Loading Progress reports; check `default.txt` and the Workshop copies (the launcher rewrites
    them); stage the built mod where the harness copies mods from; ZombieBuddy must approve the new jar hash (the
    user's click or an approvals entry).
  - `setup-server.ps1 -WithServerReborn`, server start, one `-nosteam` client join, ~90 s, stop, cleanup.
  - pass: server `[Dont39ServerReborn] network-sync-optimization: active ...` and `server-tick-rate: off: sandbox
    option ... is 10`; client `... ready on a client` and active for the server; no `hooks stopped`; `diff-errors.ps1`
    against the archived `compat-off2` consoles shows no new signature on either side.
- Deferred to the user's combined Linux-server test: 20 Hz, the sync part's `Enabled = false` path.

## Risky points

- KSP front end on a standalone mod: first such use. If a glue shape Sync Guard needs is refused by the KSP reader
  but not by javac, stop and report; the fix belongs in `zb-kt-utils-processor` with a test there.
- Digest and wire equality after the port: the golden values in the ported tests are the guard; never change an
  expected value to make a test pass.
- The `setLockFPS` decision hook: both parts hook `setLockFPS` (a `@ModifyVariable` and an `@Inject` HEAD); check the
  generated glue and the weave lines for both.
- ZombieBuddy approval of the new jar before the smoke join.

## Before `task.py start`

- User approved the final planning summary.
- `implement.jsonl` and `check.jsonl` list the spec and research files.
