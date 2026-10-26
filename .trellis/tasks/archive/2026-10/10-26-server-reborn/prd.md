# Server Reborn: merge Sync Guard and Server tick rate into one Kotlin mod

## Goal

Ship Sync Guard (`sync-guard/`, Java) and the server tick rate (`server-tick-rate/`, an All-in-One Kotlin feature) as
one standalone mod, **Server Reborn**, written entirely in Kotlin, with one lifecycle for both. A server owner adds
one mod for both server-side improvements; the repo keeps one module instead of two.

User request (2026-10-26): "将syncguard和servertickrate合并，mod改叫serverreborn。mod内的实现全都改为kotlin。"

## Background (confirmed in the repo)

- Sync Guard: behaviour and decisions D1-D8 in `10-24-mp-sync-loss-recovery` (`design.md`, `research/`). Only on
  `feat/sync-guard` (Dontcampy/dont39-pz-mods#28, unmerged), never on the Workshop. Java with javac glue; its own
  `HookGate` (`game/Gate.java`) opened by `entrypoint/MainEntry.java`, with an undo on the first failure; always on
  on a dedicated server. 90 JUnit tests.
- Server tick rate: `Feature("server-tick-rate", "Dont39AllInOne")`, option
  `Dont39AllInOne_ServerTickRate.TicksPerSecond` (10-20, default 10 = the game's rate), read at `setLockFPS` from
  `GameServer.main`. Only on `feat/sync-guard` (PR #30 closed); no released All-in-One has it. 5 tests.
- Neither wire format names the mod (packet id `0x5347`, trailer magic "D39SYNCG").
- No standalone Kotlin mod with hooks exists: the KSP glue (`zb-kt-utils-processor`) is wired only through
  `pz-feature`; it already accepts a gate named on each hook object.
- Zabric's default gate stops the mod on its first failure, `always` hooks included, with no callback
  (`ModState.DefaultGate`). `SandboxOptions.parseName` splits an option name at a single dot only (javap, 42.21).

## Decisions (user, 2026-10-26)

- Sync Guard is renamed **Network Sync Optimization** (网络同步优化).
- The two areas will likely depend on each other: one gate and one lifecycle for the mod; no per-area on/off switch.
  Network sync optimization is always on on a dedicated server, as Sync Guard was.
- The tick rate's option is `Dont39ServerReborn_ServerTickRate.TicksPerSecond`.
- `server-reborn/` is one subproject; the areas are packages in it. All-in-One keeps one subproject per feature (its
  features may later become mods of their own).
- Design from scratch; no backward compatibility needed. No `Feature`, `zb-kt-utils`, processor-rule or ZabricKotlin
  change is needed for this model.
- Tests are simplified: table-driven Kotlin unit tests covering the same behaviours (no test count, no byte-for-byte
  equality with the Java build: both sides run the new code and nothing was published); one smoke join on a minimal
  mod list instead of `myserver1`'s list and its error diff. Compatibility with other mods rests on Sync Guard's
  runs and the user's Linux-server test.
- `10-24-mp-sync-loss-recovery` is archived as superseded; its harness moves into this task; its open items become
  follow-ups (below).

Defaults taken: mod id `Dont39ServerReborn`, package `com.dont39.pz.serverreborn`, version 0.1.0, display name
"Server Reborn" / "服务器重生", the remaining names in `design.md` "Names".

## Requirements

- R1. New module `server-reborn/`, mod id `Dont39ServerReborn`, one jar in `media/java` (client and server, not
  preloaded), version 0.1.0, requiring ZombieBuddy, Zabric (0.7.1+) and ZabricKotlin, loaded after ZabricKotlin.
- R2. All main and test code is Kotlin in the one subproject, built by a new `pz-kotlin-mod` convention; hook glue
  comes from the KSP front end. No Java sources in the module.
- R3. Behaviour of both areas is unchanged except as stated here.
- R4. One mod gate: shut until the main entrypoint's checks pass; the first failure in either area shuts all hooks
  and undoes the mod's changes to the game (the sync channel change; the tick rate back to 10 on the server's main
  thread). A tick-rate mismatch therefore turns the whole mod off.
- R5. Renames per `design.md` "Names" (log prefix, Lua file and global, translation keys, sandbox page and option,
  `-Dserverreborn.*` launch switches). Wire formats, protocol version and digest fields stay.
- R6. All-in-One no longer contains the tick rate: feature list, merged widening list, `workshop.txt` and README back
  to `main`'s state for it; no version bump.
- R7. `sync-guard/` and `server-tick-rate/` are deleted and removed from `settings.gradle.kts`; specs, READMEs and the
  harness point at `server-reborn`.
- R8. Workshop staging `server-reborn/mod/workshop/workshop.txt` (EN + CN, both areas, ZabricKotlin in the
  requirements), no Workshop id, no `preview.png`; the user uploads by hand.

## Acceptance Criteria

- [x] AC1 (R1, R2, R6, R7). `JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew build :server-reborn:assembleMod` passes;
  the mod folder holds `mod.info`, the jar with its widening list, the Lua, EN/CN translations and
  `sandbox-options.txt`.
- [x] AC2 (R2, R3). Kotlin unit tests cover every behaviour Sync Guard's 90 tests and the tick rate's 5 tests cover
  (message and key round trips, refusals, truncation, digest relations, trailer placement, ledger and notice rules,
  timings, tick scaling), table-driven where cases share a shape, and pass.
- [x] AC3 (R3, R4). One smoke join on a local `-nosteam` server with only Zabric, ZabricKotlin and Server Reborn
  (default options): the server logs the mod active on a dedicated server and the tick rate off at 10; the client
  logs ready and active for the server; no `hooks stopped`, `disabled` or other error line from the mod, Zabric or
  ZombieBuddy on either side.
- [x] AC4 (R5, R7). No `Dont39SyncGuard`, `syncguard.` switch, `Dont39AllInOne_ServerTickRate`, `sync-guard` or
  `server-tick-rate` module name left outside git history, archived tasks and journals, except deliberate "formerly"
  mentions.
- [x] AC5 (R8). The Workshop staging and `server-reborn/README.md` describe both areas and the option.

## Out of Scope / Follow-ups

- 20 Hz and a tick-rate mismatch turning the mod off: passed on the Linux test instance (`research/linux-test.md`).
  A real client at 20 Hz passed there too. Still open: the full mod list.
- Sync Guard's open items, carried over: AC2 (no field update lost after its add under loss), AC6 (two clients, 30
  min, no false mismatch), AC3 (its example is not rejected on B42; needs another rejected change), and the client
  error `container already has id` seen only with the mod active (`research/acceptance.md` of the archived task).
- A join that creates a new character is not checked for that session: `ConnectToServerState.exit()` clears the
  instance the client reads the trailer from before `sendPlayerConnect` (pre-existing in Sync Guard;
  `research/smoke-join.md`). Fix: keep the trailer's result when the join parses the connection-details.
- Moving All-in-One to one subproject.
- Workshop upload, preview image, PR #28's fate beyond saying this PR supersedes it.
