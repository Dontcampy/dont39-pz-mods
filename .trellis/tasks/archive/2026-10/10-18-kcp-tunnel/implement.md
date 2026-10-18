# Implement: KCP tunnel

Requirements: [prd.md](./prd.md). Design: [design.md](./design.md). Gradle on JDK 25
(`JAVA_HOME=~/.jdks/graalvm-ce-25.0.2`). Build gate and in-game rules:
[quality-guidelines.md](../../spec/mod/quality-guidelines.md). Each step ends with a passing
`./gradlew build assembleMod`.

## Checklist

1. **Rename** `tcp-tunnel` to `tunnel` (design "Module and names"): `git mv`, package, facade, Lua file, globals,
   translation keys, settings, feature list, `AllInOneMain`. Keep `Dont39AllInOne_TcpTunnel.Port`. Behavior
   unchanged; build passes.
2. **Transport seam**: move today's TCP code under `transport.tcp` behind `Transport` / `OpenTunnel`; server start
   reads the options per transport.
3. **Store v2 + migration + dialog** (D3): new file format, migration from the released file, combo + port with
   per-transport slots, Save validation. Lua globals for get/set of mode and both ports.
4. **Join without fallback** (D4): cancellable `@Inject` with re-entrant `Connect`, `OnConnectFailed` texts
   (EN/CN). Verify early that the re-entrant call runs vanilla (fallback per design if not). Update the released
   tooltip text.
5. **Guard** (D5): `serverConnect` wrapper, Lua store reader, `Dont39AllInOne_getTunnelProblem`, fail-closed error
   path.
6. **KCP core**: port `ikcp.c` to `kcp` (kcp.kt as a guide), copy the Backblaze core to `fec.rs`, write `fec`.
   No game types. Harness in the scratchpad (not the repo, D9): two in-process ends over loopback UDP with a loss
   injector, 10 % and 20 % loss each way, steady low-rate stream plus bursts; checks in-order delivery, reports
   p50/p99 latency, recoveries, retransmits, parity 3 vs 0 (AC7). **Stop rule:** if in-order delivery fails or p99
   under 10 % loss is not clearly below the parity-0 run, fix before step 7.
7. **KCP transport**: wire protocol, client end, server listener and sessions, sandbox options + translations,
   parameters in WELCOME, log lines.
8. **Build gate**: `build assembleMod`, `:dont39-all-in-one:smokeTest`, `:zb-kt:serverSmokeTest`; feature `ready`.
9. **In game** (local Steam-mode instance first, then the Linux server after the user's Workshop release):
   AC1-AC6, AC9; AC8 with an OS-level loss tool if the user allows installing one at test time, otherwise reported
   as not done. Restore any server ini/sandbox file changed for testing.
10. **Docs and licenses**: Workshop section (AC11), `META-INF/licenses/` in the jar. Version bump only at release.

## Validation commands

```bash
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew build assembleMod
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew :dont39-all-in-one:smokeTest
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew :zb-kt:serverSmokeTest
```

## Risky points / rollback

- Step 4: the re-entrant `Connect` inside a hook of `Connect`; checked first, design names the fallback.
- Step 5: replacing a Java-exposed global from Lua; check in game that vanilla's `ConnectToServer` calls the wrapper.
- Step 6: the largest piece; the stop rule keeps a broken core out of the game.
- Store migration must never write the released file.
- Rollback points: after step 1 (pure rename), after step 5 (TCP-only with the new UI and no fallback), after step 7.

## Before `task.py start`

- `implement.jsonl` / `check.jsonl` curated (done with this plan).
- The user approved the final planning summary.
