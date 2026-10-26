# Server Reborn: merge Sync Guard and Server tick rate into one Kotlin mod

## Goal

Ship Sync Guard (`sync-guard/`, Java) and the server tick rate (`server-tick-rate/`, an All-in-One Kotlin feature) as
one standalone mod, **Server Reborn**, written entirely in Kotlin. A server owner adds one mod for both server-side
improvements and switches each on or off in the sandbox options; the repo keeps one module instead of two.

User request (2026-10-26): "将syncguard和servertickrate合并，mod改叫serverreborn。mod内的实现全都改为kotlin。"

## Background (confirmed in the repo)

- Sync Guard: behaviour and decisions D1-D8 in `10-24-mp-sync-loss-recovery` (`design.md`, `research/`). Only on
  `feat/sync-guard` (Dontcampy/dont39-pz-mods#28, unmerged), never on the Workshop. Java with javac glue, its own
  `HookGate` (`game/Gate.java`) opened by `entrypoint/MainEntry.java` with an undo on the first failure; on a
  dedicated server it is always on. 90 JUnit tests.
- Server tick rate: `Feature("server-tick-rate", "Dont39AllInOne")`, option
  `Dont39AllInOne_ServerTickRate.TicksPerSecond` (10-20, default 10 = off), read at `setLockFPS` from
  `GameServer.main`. Only on `feat/sync-guard` (PR #30 closed); no released All-in-One has it, so no stored name
  depends on it. 5 tests.
- Neither wire format names the mod (packet id `0x5347`, trailer magic "D39SYNCG").
- No standalone Kotlin mod with hooks exists: the KSP glue (`zb-kt-utils-processor`) is wired only through
  `pz-feature`. It already supports a gate per hook object.
- `SandboxOptions.parseName` splits an option name at a single dot only (`table.option`); a name with two dots is not
  grouped and Lua cannot reach it as a table (javap, 42.21).

## Decisions (user, 2026-10-26)

- Sync Guard gets a sandbox on/off switch, default on.
- Sync Guard is renamed **Network Sync Optimization** (网络同步优化), internal name `network-sync-optimization`.
- Options grouped per part: `Dont39ServerReborn_NetworkSyncOptimization.Enabled` and
  `Dont39ServerReborn_ServerTickRate.TicksPerSecond`.
- `10-24-mp-sync-loss-recovery` is archived as superseded; its harness moves into this task; its open items become
  follow-ups (below).

Defaults taken (no objection needed to proceed): mod id `Dont39ServerReborn`, module `server-reborn/`, package
`com.dont39.pz.serverreborn`, version 0.1.0, display name "Server Reborn" / "服务器重生", the remaining names in
`design.md` "Names".

## Requirements

- R1. New module `server-reborn/`, mod id `Dont39ServerReborn`, one jar in `media/java` (client and server, not
  preloaded), version 0.1.0, requiring ZombieBuddy, Zabric (0.7.1+) and ZabricKotlin, loaded after ZabricKotlin.
- R2. All main and test code is Kotlin; hook glue comes from the KSP front end. No Java sources in the module.
- R3. Behaviour of both parts is unchanged except for the names and the switch below.
- R4. The two parts gate independently: a failure, mismatch or idle state in one leaves the other running.
- R5. The network-sync-optimization switch (`Enabled`, default true) is read once per server start, before the server
  answers any join. Off: the server changes nothing in the game and offers no protocol to clients, which then log the
  server as not running it. Co-op hosts stay off as before.
- R6. Renames per `design.md` "Names" (log prefix, Lua file and global, translation keys, sandbox page and options,
  `-Dserverreborn.*` launch switches). Wire formats, protocol version and digest fields stay.
- R7. All-in-One no longer contains the tick rate: feature list, merged widening list, `workshop.txt` and README back
  to `main`'s state for it; no All-in-One version bump.
- R8. `sync-guard/` and `server-tick-rate/` are deleted and removed from `settings.gradle.kts`; specs, READMEs and the
  harness point at `server-reborn`.
- R9. Workshop staging `server-reborn/mod/workshop/workshop.txt` (EN + CN, both parts, ZabricKotlin in the
  requirements), no Workshop id, no `preview.png`; the user uploads by hand.

## Acceptance Criteria

- [ ] AC1 (R1, R2, R7, R8). `JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew :server-reborn:build
  :server-reborn:assembleMod :dont39-all-in-one:build` and a full `./gradlew build` pass; the mod folder holds
  `mod.info`, the jar with its widening list, the Lua, EN/CN translations and `sandbox-options.txt` with both options.
- [ ] AC2 (R2, R3). The 90 Sync Guard tests and 5 tick-rate tests exist in Kotlin with unchanged expected values and
  pass.
- [ ] AC3 (R3, R4, R5). One smoke join on the harness's local `-nosteam` server (copy of `myserver1`, default
  options): the server logs network-sync-optimization active and server-tick-rate off at 10; the client logs the sync
  part active for the server; no `hooks stopped`; no error signature on either side that the archived `compat-off2`
  run lacks.
- [ ] AC4 (R6, R8). No `Dont39SyncGuard`, `syncguard.` switch, `sync-guard` or `server-tick-rate` module name left
  outside git history, archived tasks and journals, except deliberate "formerly" mentions.
- [ ] AC5 (R9). The Workshop staging and `server-reborn/README.md` describe both parts and both options.

## Out of Scope / Follow-ups

- 20 Hz and the sync switch's off path in game: the user's combined Linux-server test.
- Sync Guard's open items, carried over: AC2 (no field update lost after its add under loss), AC6 (two clients, 30
  min, no false mismatch), AC3 (its example is not rejected on B42; needs another rejected change), and the client
  error `container already has id` seen only with the mod active (`research/acceptance.md` of the archived task).
- zb-kt-utils API changes (a ZabricKotlin release).
- Workshop upload, preview image, PR #28's fate beyond saying this PR supersedes it.
