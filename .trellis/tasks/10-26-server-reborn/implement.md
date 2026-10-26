# Implementation plan: Server Reborn

Design: `design.md`. Branch `feat/server-reborn-46640e` (from `feat/sync-guard` at `b22a9ec`). Each numbered step is
one commit; the build gate runs before each commit from step 2 on.

## Checklist

1. Old task: `git mv` `10-24-mp-sync-loss-recovery/research/harness` to this task's `research/harness`; add the
   "superseded by `10-26-server-reborn`" note and the open items' destination to the old task; archive it with
   `task.py archive`. Fix this task's links (`design.md`, `implement.jsonl`, `check.jsonl`) to the archived path.
2. Build: `pz-kotlin-mod`; `GlueProcessor`'s missing-option message. Module skeleton `server-reborn/`
   (`build.gradle.kts`, `mod/42/mod.info`, `mod/workshop/workshop.txt`, `ServerRebornMain` logging `loaded`, sandbox
   page name) registered in `settings.gradle.kts`.
3. Network sync optimization, JDK-only code with its tests first: `protocol`, `stats`, client `Ledger`/`Notices`, with
   the rewritten tests (`design.md`, "Port rules") green before the rest.
4. Network sync optimization, game code and the mod's lifecycle: `ServerRebornGate`, `ServerRebornMain` (design,
   "Lifecycle"), `game`, `server`, `client`, the `@LuaGlobal` function, hook objects, `.classtweaker`, open-check
   Lua, translations.
5. Server tick rate into `server-reborn` (package, gate, `disable` through the mod gate, mod-local sandbox reader,
   option name, sandbox options file, EN/CN translations, its test). Remove the old `server-tick-rate/` and
   `sync-guard/` modules, their `settings.gradle.kts` lines and `:server-tick-rate` in All-in-One's list; All-in-One
   `workshop.txt` and root `README.md` lines back to `main`'s.
6. Docs: `server-reborn/README.md`, Workshop text, specs per `design.md` "Specs and docs".
7. Harness: new names (`design.md`, "Harness and the old task") and a minimal mod list option for `setup-server.ps1`
   (Zabric, ZabricKotlin, Server Reborn; no `myserver1` mod copies); then the one smoke join (below).
8. Finish: `trellis-check`, spec update pass, journal; PR against `main` only after the user agrees.

## Validation

- Build gate (JDK 25): `JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew build` (includes `:dont39-all-in-one:build`)
  plus `:server-reborn:assembleMod` from step 2.
- Tests: every behaviour named in AC2 has a case; the old test names are the checklist.
- `grep -rn "Dont39SyncGuard\|syncguard\.\|sync-guard\|Dont39AllInOne_ServerTickRate"` outside
  `.trellis/tasks/archive`, journals and git history: only deliberate "formerly" mentions.
- Smoke join (one run, AC3), per the harness README:
  - before it: back up Loading Progress reports; check `default.txt` and the Workshop copies (the launcher rewrites
    them); stage the built mod where the harness copies mods from; ZombieBuddy must approve the new jar hash (the
    user's click or an approvals entry).
  - `setup-server.ps1` with the minimal list, server start, one `-nosteam` client join, ~60 s, stop, cleanup.
  - pass: server `[Dont39ServerReborn] active on a dedicated server ...` and the tick rate's `off: sandbox option ...
    is 10`; client `ready on a client` and active for the server; no `hooks stopped`, `disabled` or other error line
    from the mod, Zabric or ZombieBuddy on either side.
- Deferred to the user's combined Linux-server test: 20 Hz.

## Risky points

- KSP front end on a standalone mod: first use. A glue shape javac accepted and the KSP reader refuses is fixed in
  `zb-kt-utils-processor` with a test there, not worked around in the mod.
- Behaviour drift in the port: a rewritten test that fails is a port bug unless the old Java test shows the same
  expectation was never there; never loosen a case to make it pass.
- The tick rate's positional and descriptor-named targets (`UpdateLimit.<init>(J)V`, `WorldSound.init(...)`) through
  the KSP front end with an explicit gate: check their glue and weave lines.
- ZombieBuddy approval of the new jar before the smoke join.

## Before `task.py start`

- User approved the final planning summary.
- `implement.jsonl` and `check.jsonl` list the spec and research files.
