# Implement: TCP tunnel

Design: [design.md](./design.md). Gradle on JDK 25 (`JAVA_HOME=~/.jdks/graalvm-ce-25.0.2`). Build gate and in-game
rules: [quality-guidelines.md](../../spec/mod/quality-guidelines.md).

## Checklist

1. **Steam-mode spike (gate).** Before feature code, on a local dedicated server in Steam mode (default launch) and
   the Windows client:
   - Server console: the `Startup: serverPort=... serverUDPPort=...` line; which UDP ports are bound.
   - A throwaway relay (scratchpad, not in the repo): client connects to `127.0.0.1:L`, relay forwards plain UDP to
     the server; client run with `-Dzomboid.znetlog=1` to see the `ConnectQueueAdd*` attempts. Find the server port
     `G` that completes a join (`DefaultPort` or `UDPPort`) and whether `useSteamRelay` changes it.
   - **Stop rule:** if no `G` completes a join through the relay in Steam mode, stop and return to planning with the
     findings. Record the result in `research/steam-mode-spike.md` and settle "Steam mode" in design.md.
2. **Module skeleton**: `tcp-tunnel/` per [Adding a Feature](../../spec/mod/directory-structure.md#adding-a-feature)
   (settings, feature list, `AllInOneMain` init, facade on `Feature` modeled on `ImeKeyboardFix.kt`).
3. **Tunnel core** (no game types): handshake, framing, client relay, server listener and per-connection relay, with
   logging per design. Small and self-contained so it can be exercised locally with two sockets before hooks exist.
4. **Server hook**: exit hook on `GameServer.startServer()`, sandbox option read, listener start; skip in coop host
   process. `sandbox-options.txt` + translation strings in the feature's mod assets.
5. **Client hook + store**: `@ModifyVariable` handlers on `UdpEngine.Connect`, probe, store read; Lua wrapper of
   `ISMPEditServer` and the store write via `@LuaGlobal`/Lua file API
   ([Wrapping Vanilla Lua UI](../../spec/mod/zombiebuddy-patching.md#wrapping-vanilla-lua-ui)).
6. **Build gate**: `./gradlew build assembleMod`; `:dont39-all-in-one:smokeTest` (client, feature `ready`) and
   `:zb-kt:serverSmokeTest` (server starts with the feature loaded).
7. **In game** (local first, then the Linux server after a Workshop release by the user):
   - Server with the port set: listener line in the console; with 0: "off" line, no listener.
   - Client with a TCP port on the entry: join goes through the tunnel (client log line, server sees 127.0.0.1),
     play a few minutes; with the TCP port wrong/closed: fallback line, UDP join works, freeze ≤ 2 s.
   - A second client on plain UDP at the same time.
   - Sandbox edit path: change the port via `<server>_SandboxVars.lua` and via the admin panel, restart, see which
     takes effect.
   - `ZNetStatistics` packet loss on the tunnelled client vs. UDP from the same network, if the user's lossy network is
     available; otherwise reported as not done.
8. **Docs**: Workshop section in `dont39-all-in-one/mod/workshop/workshop.txt` (what it does, server option and how to
   set it, the TCP port field, firewall, 127.0.0.1/`banip` caveat, Steam-mode notes from step 1). Version bump only at
   release.

## Validation commands

```bash
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew build assembleMod
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew :dont39-all-in-one:smokeTest
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew :zb-kt:serverSmokeTest
```

## Risky points / rollback

- Step 1 can invalidate the design for Steam-mode servers: stop rule above.
- `UdpEngine.Connect` is on every join and every server-list ping: addresses without a stored TCP port must pass
  through untouched (check in step 7).
- `ISMPEditServer` layout change: the vanilla dialog must still save entries without a TCP port.
- Rollback: drop the module from settings, the feature list and `AllInOneMain`.
