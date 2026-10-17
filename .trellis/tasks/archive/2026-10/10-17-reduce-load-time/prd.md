# Reduce game load time

## Goal

Launching Project Zomboid (42.21, ~245 mods, ZombieBuddy 2.3.4 with `policy=allow-all`) and joining the `myserver1`
server is slow. Remove the measured avoidable waits with patch code in this repo and confirm them with a timed
relaunch.

## Baseline (session 2026-10-17 02:53)

Source: `~/Zomboid/Logs/2026-10-17_02-53_DebugLog.txt` (millisecond timestamps; `console.txt` has none). Launch →
main menu ~65 s (02:53:40 → 02:54:45); join → world loaded ~89 s (02:55:04 → 02:56:33, ~7 s of it connecting). The
79 s on the "click to start" screen was the player idle.

## Requirements

### R1 (B1) — skip all of ZombieBuddy's network requests (10.5 s + 25.9 s in the baseline, plus premain)

Each `ZomboidFileSystem.loadMods` call makes ZombieBuddy's `Loader.loadMods()` block in `loadZBSContext`
(`external-repos/ZombieBuddy/.../Loader.java:99-105`) on the Steam ban check (`SteamWorkshop.fetchItemDetails`, 10 s
connect / 25 s request timeout, `SteamWorkshop.java:56-58,116`) and the `authors.json` download
(`KnownAuthors.loadAuthors` → `fetchRemoteBody`, `KnownAuthors.java:79-93,185`). Earlier logs in
`~/Zomboid/Logs/logs_*` show the same wait clustering at ~10, ~20, ~26, ~35 and ~50 s (timeout combinations), and
1–3 s when the network answers. Java's `HttpClient` ignores the Windows proxy. Under `allow-all` the authors are unused
(`Loader.java:89-92`); the ban check still skips banned Workshop jars.

ZombieBuddy has exactly three network calls: the two above and `ZBSVerifier.fetchJavaModZBSHexesFromSteam` (scrapes the
uploader's Steam profile for keys missing from the authors list; only reached when ZBS verification is on, i.e. not
under `allow-all`). The premain call for preload mods (`Loader.java:440`) runs before any mod code; ZombieBuddy's
`patches_jar` agent option (`Agent.java:95-121`, passed through `-agentlib:zbNative=...` in `ProjectZomboid64.json`)
loads a jar and calls its `PreMain.premain` before it, and splits entries on `:` (so a Windows drive path breaks; a path relative to the
game folder works). Its cost is unknown: process start (Steam `gameprocess_log.txt`) to the first DebugLog line was ~2 s
until 2026-10-12 and 5–90 s since (28 s at 02:53), with no correlation to the menu-pass network wait; ZombieBuddy logs
fetch failures to stderr, which `console.txt` does not keep.

Decision (2026-10-17): a standalone Java mod that blocks all three requests and starts two ways (design D1):
through `patches_jar` it covers premain, menu and server join; without it, as a mod in the list (Zabric preLaunch), it
still covers menu and server join. `KnownAuthors.loadAuthors` returns an empty map without network or cache (decided 2026-10-17: under
`allow-all` the authors feed only ZBS verification, which is off, and the `prompt` approval dialog); ban status becomes
unknown.

### R2 (B3) — no walk of missing animation folders at server join (~7.5 s window)

`AdvancedAnimator.collectModFiles` → `loadModMedia` → `searchFolders(URI, Path)` walks each mod's `media/AnimSets` and
`media/actiongroups` without checking they exist; the visitor's `visitFileFailed` logs each `NoSuchFileException` with
a full stack trace (918 traces, ~27 k log lines, DebugLog lines 15051–42562, all in the server-join reset). Every mod
without those folders triggers it.

Decision: a feature module in Dont39 All-in-One (design D2).

## Acceptance criteria

- AC1 (R1, mod only): with the mod in the list and no `patches_jar`, in a timed relaunch with the same mod list, the
  gap after every `[ZB] checking mods ban status` is under 1 s, at the menu and at server join; the mod's log line names
  the mod-only way and its skip counts; Zabric reports no preLaunch error for it.
- AC1b (R1, agent start): with the `patches_jar` entry added, the same holds, the mod's log line names the agent-start way
  and counts a skip before the mod list loaded (ZombieBuddy's premain call). Process start to the first DebugLog
  line is reported next to the 28 s baseline where Steam's `gameprocess_log.txt` can time it (not for launches through
  PzLauncher, which Steam registers after the log starts).
- AC2 (R2): the server-join log has no `AdvancedAnimator$1.visitFileFailed` for missing folders and no animation error
  that the baseline log lacks.
- The menu and join wall times are reported next to the baseline.

## Out of scope

- B2, the server-join reload (decided 2026-10-17 not to pursue). `ConnectToServerState.receiveServerOptions` always
  calls `Core.ResetLua("client", "ConnectedToServer")`, which reloads scripts, Lua, mods, translations, models and
  sandbox options (javap of 42.21); it ran ~53 s in the baseline, ~20 s of it left after R1 and R2. Vanilla single
  player skips the reset for an unchanged ordered mod list (`ActiveMods.requiresResetLua`, `MainScreen.lua:1009,1164,1233`);
  skipping it on join is unsafe because the menu's Lua ran outside client mode and 166 subscribed-mod Lua files branch
  on `isClient()`/`isServer()` at file level. Shortening it would need a profile and in-session caches. The 245th mod at
  join is `Spawn Location_CN` (Workshop 3448235767, Chinese spawn-point names for map mods), loaded again as a client
  translation mod; menu and server lists hold the same 244 mods in different order.
- Vanilla script and world loading; upstream ZombieBuddy changes; publishing `ZBSkipOnlineChecks` to the Workshop.
- Editing `ProjectZomboid64.json` automatically, and a `patches_jar` switch in PzLauncher: the entry is a documented
  manual step.

## Risks

- With R1, ZombieBuddy no longer skips a Workshop jar that Steam has banned.
- No policy guard (decided 2026-10-17): the mod acts under any ZombieBuddy policy. Listed in a server's `Mods=` it is
  forced on every player, and under ZombieBuddy's default `prompt` signed Java mods then fail verification and are
  skipped (`Loader.java:844`, `ZBSVerifier.verify`).
- R1 makes `ZBSkipOnlineChecks` an `allow-all`-only mod: under `prompt` or `deny-new`, signed mods find no author key
  and fail ZBS verification, and the approval dialog shows no author names. Disable the mod before switching policy.
- R1 depends on ZombieBuddy's method names; a rename becomes a reported error and the requests run again.
- The agent-start copy in the game folder can fall behind the mod's jar; the mod reports a mismatch. Steam updates or
  file verification overwrite `ProjectZomboid64.json` and drop the `patches_jar` entry; the mod-only way still works.
