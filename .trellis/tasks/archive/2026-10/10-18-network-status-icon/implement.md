# Implement: Multiplayer network status icon

## Checklist (in order)

1. **Tunnel stats** (`tunnel`), one reviewable step:
   - `Kcp.kt`: count first transmissions (`segmentsSent`) next to `timeoutRetransmits` / `fastRetransmits`; expose
     `rxSrtt` read-only.
   - `FecDecoder.kt`: count expected and missing data sequence numbers (gaps in `seq` of received data shards; a late
     shard that fills a counted gap is subtracted).
   - `Transport.kt`: `OpenTunnel.stats()`; `ClientTunnel` (TCP) and `KcpClient` → `KcpSession` (under `lock`)
     implement it.
   - `TunnelClient.kt`: `@LuaGlobal("Dont39AllInOne_getTunnelStatus")` returning the table in design.md, nil with no
     tunnel; synchronized like `connect`.
2. **New module `network-status`** following [Adding a Feature](../../spec/mod/directory-structure.md#adding-a-feature):
   `settings.gradle.kts`, feature list in `dont39-all-in-one/build.gradle.kts`, facade `NetworkStatus.kt` on
   `Feature`, `init()` in `AllInOneMain.kt`, workshop.txt section. No target mods, no hooks.
   - `@LuaGlobal("Dont39AllInOne_getNetworkStatus")` reading `GameClient.connection` as in design.md.
3. **Lua UI** `network-status/src/main/lua/client/Dont39AllInOne_NetworkStatus.lua`: mod option, element lifecycle,
   1 s sampling with a 5-sample window, icon, badge, tooltip. Check each global exists before calling it.
4. **Assets and text**: warning-triangle PNG under `network-status/src/main/media/ui/`; `UI.json` EN and CN keys
   `UI_Dont39AllInOne_NetworkStatus_*` (merge into an existing file of the same name if `assembleMod` complains).
5. **Spec update** (Phase 3): note the cross-feature Lua composition (a feature reading another feature's Lua global)
   if it is new to the specs.

## Validation

```bash
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew build assembleMod
```

```bash
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew :dont39-all-in-one:smokeTest
```

- `smokeTest` sees `network-status: ready` (generated glue, no edit needed).
- In game, on the Linux test server (memory: linux-test-server): join (a) direct UDP, (b) TCP tunnel, (c) KCP
  tunnel. For each: icon at bottom-left, tooltip rows match the method; KCP with induced loss (e.g. `tc netem loss 5%`
  on the server) shows the badge, TCP never does. Toggle the mod option off/on. Single player: no icon.
- Steam connection (`GetConnectionType` = Steam): check whether `getStatistics()` is non-null; the "not reported"
  path covers null. Report as not verified if no Steam server is available.

## Risky points and rollback

- `KcpSession` lock: stats must be read under the same lock as `tick`/`input`; never call into KCP from the render
  thread without it.
- `updatePing()` and `getStatistics()` are native calls; keep them at 1 Hz.
- Rollback points: commit the tunnel stats (step 1) separately from the new module (steps 2–4).

## Before `task.py start`

- PRD thresholds approved by the user.
- `implement.jsonl` / `check.jsonl` curated (done in planning).
