# In-game test (implement step 7), 2026-10-18

Server: temporary test instance on the user's Linux box (Steam mode, `-cachedir=~/pz-only/myserver2_Data`,
`-servername myserver2`, DefaultPort 48261, UDPPort 48262, RCON 48015, no server password, mods ZombieBuddy, Zabric,
ZabricKotlin, Dont39AllInOne dev build, own ZombieBuddy `config_dir`). Client: Windows, Steam mode, dev build via
`deployWorkshop`, LAN `192.168.50.192` → `192.168.50.249`. Test account only. The instance was stopped with `quit`
and `myserver2.ini` restored afterwards.

| AC | Result |
|----|--------|
| AC1 | Run 1 (sandbox default): Zabric preload registration, restart needed. Run 2 after setting `Port = 48300` in `myserver2_SandboxVars.lua`: `tcp-tunnel: listening on TCP port 48300, relaying to UDP ports 48261 and 48262`, TCP 48300 listening. The "off" line was checked by the implementer on a local Windows server. |
| AC2 | Add-server dialog shows "TCP 端口" below the port with the CN tooltip; saving wrote `192.168.50.249:48261=48300` to `Zomboid/Lua/Dont39AllInOne_TcpTunnel.txt` (replacing an earlier value); the edit dialog prefilled 48300. |
| AC3 | Client: `tcp-tunnel: connecting to 192.168.50.249:48261 through the TCP tunnel on port 48300`, states `GettingServerInfo` → `UDPConnecting` → `Connected`. Server: `tunnel from 192.168.50.192:3843 open as 127.0.0.1:44037`, `Connected new client`, player created. Character creation, in world, walked around; TCP session ESTAB, no disconnect for 8 min. |
| AC4 | Stored TCP port 48301 (filtered, not refused): `no TCP tunnel at 192.168.50.249:48301 (SocketTimeoutException: Connect timed out); connecting to 192.168.50.249:48261 over UDP` after 1.04 s, then `Connected` over UDP. |
| AC5 | Not done: one client machine. |
| AC6 | Killing the client process: server logged one line `tunnel from 192.168.50.192:3843 closed: SocketException: Connection reset`; listener and server kept running. |
| AC8 | No `map_sand.bin` in the dedicated server's save, even after `quit`: the server reads `<server>_SandboxVars.lua` on each start, so edit + restart is the working path. |
| AC9 | AC3 ran against a Steam-mode server. |
| AC10 | Not done: no lossy network available. |
