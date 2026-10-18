# In-game test (implement step 9), 2026-10-18

Server: temporary instance on the user's Linux box (Steam mode, `-cachedir=~/pz-only/myserver2_Data`, `-servername
myserver2`, DefaultPort 48261, UDPPort 48262, TCP tunnel 48300, KCP tunnel 48301/UDP, no password, mods ZombieBuddy,
Zabric, ZabricKotlin, Dont39AllInOne dev builds). Client: Windows, Steam mode, dev builds via `deployWorkshop`, LAN
`192.168.50.192` → `192.168.50.249`; the user played. Afterwards the instance was stopped with `quit`, `myserver2.ini`
and `myserver2_SandboxVars.lua` restored, the client store restored.

| AC | Result |
|----|--------|
| AC1 | KCP option 0: `off: no KCP port set (sandbox option Dont39AllInOne_Tunnel.KcpPort is 0)` next to the TCP listener line. Set to 48301: `listening on KCP port 48301, relaying to UDP ports 48261 and 48262, interval 10 ms, ... FEC 10+4, flush 20 ms`; UDP 48301 bound. No Zabric restart needed. |
| AC2 | The first run added all nine `Dont39AllInOne_Tunnel` options with defaults to `myserver2_SandboxVars.lua`; `FecParityShards = 4` + restart: server line and the client's join line both show `FEC 10+4`. |
| AC3 | The released `Dont39AllInOne_TcpTunnel.txt` (`192.168.50.249:48261=48300`) opened as Tunnel TCP 48300; the new file kept both ports per address across switches (`off,48300,48301`). Dialog and list tag checked by the user after the layout change (D10). |
| AC4 | KCP joins of about 1.5 and 2 minutes (server: `KCP tunnel from 192.168.50.192:... open as 127.0.0.1:...`, `Connected new client`); a TCP join of about 1 minute; an Off join over UDP. Not the full 5 minutes per transport; the user reported no problems. LAN, no loss: `FEC rebuilt 0`, KCP timeout resends 2-25 per session. |
| AC5 | TCP with the KCP port (48301): `stopped the join ...: no answer from a TCP tunnel at ...:48301`, no UDP join. Server stopped: KCP and TCP joins each show "... 的 KCP/TCP 隧道没有响应。请检查隧道端口，或询问服务器所有者隧道是否已开启。" with Back only. |
| AC6 | Jar renamed in the client's Workshop copy (Lua only): the join to the KCP entry shows "此服务器设置为通过隧道加入，但 Dont39 All-in-One 的 Java 部分未加载（请在 ZombieBuddy 中允许）。将此服务器的隧道设为“关闭”即可通过 UDP 加入。"; the server saw no connection. The feature-disabled path was not run in game. |
| AC8 | Not done: an OS-level loss tool on the server needs sudo and could affect the live server. The harness numbers stand in ([kcp-harness.md](./kcp-harness.md)). |
| AC9 | Not done: one client machine. Closing a client closed only its tunnel (`closed by the other end`), the server kept running. |
