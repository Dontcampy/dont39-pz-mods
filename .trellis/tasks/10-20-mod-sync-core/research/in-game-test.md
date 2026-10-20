# In-game test log

## 2026-10-20: Linux dedicated server (myserver2 test instance)

Setup: `~/pz-only/myserver2_Data`, dev builds of Dont39AllInOne, Zabric, ZabricKotlin in `mods/`,
`Mods=ZombieBuddy;Zabric;ZabricKotlin;Dont39AllInOne;SkillRecoveryJournal`, `WorkshopItems=2503622437`,
`Dont39AllInOne_ModSync.Port = 48310`. Started with `modsynctest-start.sh` in tmux `pzmodsync`. Originals backed up in
`myserver2_Data/modsynctest-backup/` (ini, SandboxVars, the three mod folders).

- Runtime: `servers/jre64/bin/java --list-modules` has `jdk.httpserver@25.0.1` and `java.net.http@25.0.1`.
- `[Zabric] access widener: ok`, `[Dont39AllInOne] access widener: ok (11 directives)`, `hooks ready (13 hook classes)`.
- Port 0: `mod-sync: off: no port set (sandbox option Dont39AllInOne_ModSync.Port is 0)`.
- Port 48310: `mod-sync: manifest: 1 Workshop items, 298 files, 4.3 MB, built in 63 ms`, then
  `serving the Workshop items on TCP port 48310`.
- HTTP from the LAN: `manifest.json` 200 (format 1, item 2503622437 at timestamp 1788537467); `files/<sha256>` returns
  bytes with that hash; unknown hash 404; `files/../manifest.json` 404; POST 405; `/` 404.
- Note: the first start rewrites `myserver2_SandboxVars.lua` with the registered options at their defaults; set the
  port after that start.

## 2026-10-20: Windows client joins myserver2 with a real version mismatch

Test item `3823294577` "Dont39 ModSync Test" (Unlisted, Lua-only). Server started on version 1; version 2 then
uploaded (changed `client/Dont39ModSyncTest.lua` and `mod.info`); the staged copy moved out of `~/Zomboid/Workshop`
(the game scans staged items first). The game port of myserver2 is 48261, not 16261.

- Vanilla Workshop screen first (item not subscribed); after install, the client log shows the take-over:
  `your copy is another version than the server's (1792472800); taking the server's`, then
  `takes 3 files from your copy and 2 files (269 bytes) from the server`.
- Dialog shown, accepted; `downloaded 2 files (269 bytes)`; `swap on: Workshop item 3823294577 uses
  ...\ModSync\items\192.168.50.249_48261\3823294577\1792472800\3823294577\mods`; in game `[Dont39ModSyncTest] version 1`.
  The tree holds the server's version 1 file. No "Invalid prefix found".
- Quit to the main menu: `swap off: the game ended`.
- Second join: `the server's version 1792472800 is ready from an earlier join`, swap on, no download, no dialog,
  `version 1` again.
- Dialog problems: the explanation line overflows the panel width; sizes under 1 MB show as "0.0 MB".
## 2026-10-20: dialog fix, "don't ask again", Lua checksum

- Dialog fixed: the rich text panel breaks only at spaces, so Chinese text is split to the panel width
  (`breakable` in `Dont39AllInOne_ModSyncJoin.lua`); sizes show as B / KB / MB.
- A server restart updates its Workshop items first: after restarting myserver2 it was on version 2, the client too,
  and the join took the vanilla path (D1) with no take-over. A mismatch needs an upload while the server runs.
- `DoLuaChecksum=true` exposes two environment problems, not mod-sync ones: a different All-in-One build on the server
  ("File doesn't match ... Dont39AllInOne_ModSyncJoin.lua"), and Zomboid Control Panel's
  `servers/media/lua/server/PanelBridge.lua`, which no client has ("File doesn't exist on the client"). For the test,
  the server got the client's All-in-One build and the client a temporary copy of `PanelBridge.lua` (removed after).
- Version 3 uploaded while the server ran version 2: take-over, dialog answered "remember" (`servers.json` =
  `{"format":1,"servers":["192.168.50.249:48261"]}`), 2 files (269 bytes), swap on, `version 2` in game.
- Next join (account "test" via the server list): `ready from an earlier join`, swap on, `version 2`; `DoLuaChecksum`
  ran on the client and the server raised no checksum warning or kick over more than 10 minutes in game.
## 2026-10-20: failure paths, through a local proxy

A proxy on the client (`proxy.py` in the session scratchpad) forwarded UDP 127.0.0.1:48261/48262 to myserver2 and
HTTP 127.0.0.1:48310 to its mod-sync port, with a mode file: `forward`, `down` (drops each connection), `corrupt`
(flips the first body byte of `files/` responses). Joined through the saved "proxytest" entry (127.0.0.1:48261), so the
client builds `http://127.0.0.1:48310/...` and is not remembered there. myserver2 kept running on test item version 2
(the client has version 3); the game and the UI were driven by screenshots and synthetic clicks.

- Dialog after the fix: text wraps inside the panel, sizes read `269 B`; the status line reads 等待你的回答.
- Decline: `answer: decline`, `stopped the join: the player declined the download`; the screen shows
  加入已停止：你选择不下载服务器版本的创意工坊物品。 The swap never went on.
- Back (停止) while the dialog waits: `the join ended while it waited for the server's Workshop items`; the dialog closed
  and the game returned to the server list.
- HTTP down: `stopped the join: preparing the server's Workshop items: IOException: HTTP/1.1 header parser received no
  bytes`; the screen names 127.0.0.1:48310 and asks to check with the server admin that the mod-sync port is open. The
  JDK client retried the manifest GET once by itself.
- Corrupt file: `stopped the join: mods/Dont39ModSyncTest/42/media/lua/client/Dont39ModSyncTest.lua from the server has
  the SHA-256 8343…, not c176…`; only `<id>.staging` was left (without the bad file), no tree.
- Recovery (proxy back to forward): the next join deleted the staging folder, downloaded 2 files, `swap on`, `version 2`.
## 2026-10-20: jar change and refused item

Made by editing the client's own Steam copy of the test item
(`E:/SteamLibrary/steamapps/workshop/content/108600/3823294577`) between joins to myserver2 (still on version 2, the
client on version 3), and undone after.

- Jar change: a dummy `mods/Dont39ModSyncTest/42/media/java/Dont39ModSyncTestDummy.jar` in the client's copy only.
  `stopped the join: the server's copy of "Dont39 ModSync Test" (3823294577) has other jars: mods/.../Dont39ModSyncTestDummy.jar`;
  the screen names the jar and asks the server admin to restart the server. Checked with a tree already ready from an
  earlier join, so the jars-only scan of a ready tree is covered.
- Refused item: an empty `mods/Zabric/` folder (no `mod.info`) in the client's copy.
  `stopped the join: "Dont39 ModSync Test" (3823294577) carries Zabric`; the screen says it is never taken from a server.
- After undoing both: `ready from an earlier join`, swap on, `version 2`.

## 2026-10-20: long hold

`servers.json` emptied and the 192.168.50.249 tree deleted so the dialog shows on a direct join to myserver2
(`DoLuaChecksum=true`, `PanelBridge.lua` copied to the client for the join, removed after).

- Dialog shown at 16:59:25 and left open until 17:04:36: no disconnect, timeout or lost connection in the client log,
  nothing new in the server log; the dialog and the 等待你的回答 status line still up.
- Then "don't ask again" + 下载并加入: `answer: remember`, 2 files downloaded, swap on, `version 2`, `DoLuaChecksum`,
  `game loading took 11 seconds`; in the world, and no checksum warning, kick or timeout on the server over the
  following 75 s. So the server does not drop a client held before login for at least 5 minutes; the
  download-then-rejoin fallback in the parent design is not needed.
- Not covered yet: a remembered server with a new download (dialog skipped).
