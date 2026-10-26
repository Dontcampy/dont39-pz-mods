# Smoke join (AC3), 2026-10-26

Local `-nosteam` server `srmin` (`setup-server.ps1 -Minimal -WithServerReborn`: ZombieBuddy, Zabric 0.7.1,
ZabricKotlin 0.7.0, Server Reborn 0.1.0 from `server-reborn/build/mod`, the driver), one client, both with
ZombieBuddy `policy=allow-all` for the launch only (`-ZbAllowAll`; the user's choice instead of the approval dialog).
Runs in `%TEMP%\sgtest\runs\smoke3` (server, first join) and `smoke4` (client, second join).

## Result: pass

- Server: `0.1.0 loaded`, `access widener: ok (24 directives)`, `[Zabric] Dont39ServerReborn: hooks ready (17 hook
  classes)` (12 sync, 5 tick rate), `active on a dedicated server`, `tick rate off: sandbox option
  Dont39ServerReborn_ServerTickRate.TicksPerSecond is 10`, then `admin (connection 0): checking its sync (Server Reborn
  0.1.0 on the client)` and a connection summary with `mismatches=0`.
- Client (second join, existing character): `loaded`, `access widener: ok`, `Lua functions added to the Lua
  environment the game built before this mod loaded`, `ready on a client`, `active: the server checks this client's
  containers and items`.
- No `hooks stopped` or `disabled` line from Server Reborn or Zabric; no error naming the mod.

## Notes

- The first start of a fresh `srmin` registered Zabric's preload and needed one restart (`smoke2`), as the harness
  README says.
- The first join, which created the character, logged `join to 127.0.0.1:16361: inactive, as the game kept no
  connection-details`: `ConnectToServerState.exit()` sets `instance` to null when the join leaves for character
  creation, before `sendPlayerConnect`. Sync Guard behaved the same (its `warm2-b` run; 23 other joins with an
  existing character were active). Not a port regression; a follow-up (`prd.md`).
- Errors seen that are not the mod's: the game's `NoSuchFileException` for `media/AnimSets` and `actiongroups` in
  every mod folder (All-in-One's missing-anim-folders fixes that; it was not on the list), and Faster Loading's
  `hooks stopped` from the staged pre-#31 Faster Loading jar.
- `cleanup.ps1` restored the client (0 differences) and Loading Progress's reports.
