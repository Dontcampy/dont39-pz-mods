# ZBSkipOnlineChecks

A Java mod that makes ZombieBuddy skip its three network requests:

- the Steam ban check of Workshop jars (`SteamWorkshop.fetchItemDetails`), on every mod list load;
- the known authors list download (`KnownAuthors.loadAuthors`), on every mod list load;
- the Steam profile key lookup (`ZBSVerifier.fetchJavaModZBSHexesFromSteam`), only with ZBS verification on.

Each mod list load otherwise waits on them, up to their timeouts when Steam or GitHub do not answer (Java's
`HttpClient` ignores the Windows proxy).

## Trade-offs

- **For ZombieBuddy's `allow-all` policy only.** Under `prompt` or `deny-new`, signed mods find no author key and fail
  ZBS verification at once, and the approval dialog shows no author names. Disable the mod before switching policy.
- ZombieBuddy no longer skips a Workshop jar that Steam has banned: every jar's ban status is unknown.
- It depends on ZombieBuddy's method names. When they do not match, the mod logs an error and the requests run as
  before.

## Two ways to start

| Way | Covers | Setup |
|-----|--------|-------|
| Mod only | mod list loads (main menu, server join) | enable the mod; the first launch registers it to run at game start and asks for one restart |
| Agent start | also ZombieBuddy's requests at game start, before any mod | the mod only setup, plus the steps below |

The skip stays in effect for the whole launch once installed, whatever mod list loads later. Removing the mod from
the list takes effect after a restart; removing the `patches_jar` entry turns off the agent start.

### Agent start

1. Copy the jar into the game folder: `./gradlew :zb-skip-online-checks:deployAgentJar` (or copy
   `42/media/java/ZBSkipOnlineChecks.jar` from the mod by hand).
2. Back up `ProjectZomboid64.json` in the game folder, then append the entry to ZombieBuddy's agent options in its
   `vmArgs` (or to `-agentlib:zbNative=...` in the Steam launch options):

   ```
   "-agentlib:zbNative=policy=allow-all,patches_jar=ZBSkipOnlineChecks.jar:com.dont39.pz.zbskiponlinechecks"
   ```

   The path must be relative to the game folder: ZombieBuddy splits the entry on `:`, so a drive letter breaks it.

Keep the mod in the mod list as well: its log line (`installed by agent start`, with the requests skipped at game
start) and any error come from it; the agent start itself cannot log. Steam updates and file verification overwrite
`ProjectZomboid64.json` and drop the entry; the mod only way keeps working. After a mod update, copy the jar again:
when the mod is in the list, it logs an error naming both files while the copies differ.
