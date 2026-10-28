# In-game check, 2026-10-28

Server: `myserver2` on the Linux test server (`-nosteam`, 48261/48262, RCON 48015, fresh save), mods ZombieBuddy (its
own copy, agent jar equal to the client's), Zabric 0.7.3 (this branch), ZabricKotlin 0.8.0 and Dont39AllInOne 0.13.0
(the client's Workshop staging), SGTestDriver. Its state was packed into `backups-2026-10-28-luaglobals/state.tgz`
first and restored after; the live `myserver` was not touched.

Client: the second client of the SCTD harness (`-nosteam -cachedir=C:\pz2c`, harness copied from branch
`feat/sctd-transmog-server-resnap-015452`), Zabric 0.7.3 preloaded from the Workshop staging (`deployWorkshop`),
joining as admin. Staging and `C:\pz2c` were restored to Zabric 0.7.2 and their mod list afterwards.

| Run | Menu list | Seen (lines in `lg2-serveronly-lines.txt`, `lg3-menu-lines.txt`) |
|---|---|---|
| lg2 | SGTestDriver only (All-in-One only from the server, as st7b) | the join's `LuaManager.init` exposes only `RestartNotice`; `loadMods(5 mods)`; then `[Zabric] Lua: exposed the global functions of …` for `ModSyncLua`, `AppearanceOverrideLua`, `NetworkStatusLua`, `TunnelLua`, before `Loader.loadMods() took`; `appearance-override sync: client part ready`, `network-status: active`, `mod-sync: active`, `tunnel: active`. No "not loaded" line, no error from Zabric or All-in-One |
| lg3 | ZombieBuddy, Zabric, ZabricKotlin, Dont39AllInOne, SGTestDriver (as st7c) | ZombieBuddy exposes the four classes at both Lua starts; no `[Zabric] Lua:` line; `client part ready` |

(A first attempt, lg1, was refused at the login: the harness passed one character of the password.)
