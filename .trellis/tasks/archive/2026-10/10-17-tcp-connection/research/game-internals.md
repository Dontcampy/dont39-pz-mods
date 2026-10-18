# Research: game internals for the UDP-over-TCP tunnel

- **Query**: Client connect path, server-list storage, ISMPEditServer, server UDP port, sandbox-option timing, per-IP
  logic, shipped Java version, client file storage (questions 1-8 of the task brief)
- **Scope**: internal (game bytecode + vanilla Lua; nothing was run)
- **Date**: 2026-10-18

Sources: `E:/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid.jar`, which is byte-identical to the dedicated
server's `java/projectzomboid.jar` (md5 `dae4f59c3eab7a04b70d1c751b7ab37a`, revision string `4a0e9546ec` in
`GameServer.main`). I read it with `javap -c -p -l`. Java citations are `Class.method (File.java:line)`, with line
numbers taken from the class LineNumberTables. Lua citations are relative to
`ProjectZomboid/media/lua/` and use the form `file:line`.

## 1. Client connect path

### The three `UdpEngine.Connect` calls in `GameClient.startClient` (GameClient.java:237-247)

| # | Condition | Args `(host, port, pwd, useSteamRelay)` | Line |
|---|---|---|---|
| a | `clientStarted` already true (engine reused) | `GameClient.ip, GameClient.port, serverPassword, useSteamRelay` | 238 |
| b | First start, and `CoopMaster.instance != null && isRunning()` (hosting a coop game) | `"127.0.0.1", CoopMaster.getServerPort(), serverPassword, false` | 243 |
| c | First start, otherwise (normal dedicated-server join) | `GameClient.ip, GameClient.port, serverPassword, useSteamRelay` | 245 |

Before calls b and c, `startClient` builds a fresh client engine with
`new UdpEngine(Rand.Next(10000)+12345, 0, 1, null, false)`.

### Which branch `UdpEngine.Connect` takes (UdpEngine.java:419-439)

The boolean does **not** pick the branch. The port does:

- `port == 0 && SteamUtils.isSteamModeEnabled()` takes the **Steam ID branch**. It parses the host with
  `SteamUtils.convertStringToSteamID` and calls `peer.ConnectToSteamServer(id, hash(pwd), useSteamRelay)`. Only coop
  invites use it: `GameClient.doConnectCoop` sets `port = 0` and `ip = steamID`.
- Any other case takes the **IP branch**. It calls `InetAddress.getByName(host).getHostAddress()` and then
  `peer.Connect(resolvedIp, port, hash(pwd), useSteamRelay)` (line 439). An `UnknownHost` error fires the
  `OnConnectFailed` event with the text `UI_OnConnectFailed_UnknownHost`.
- So calls a, b and c all take the IP branch for direct-IP joins. `useSteamRelay` is passed straight through to the
  native `RakNetPeerInterface.Connect`.

### Where `GameClient.ip`, `port` and `useSteamRelay` come from

`GameClient.doConnect(...)` (GameClient.java:1813-1818) sets them as follows:

- `ip = serverIP.trim()`
- `port = Integer.parseInt(port.trim())`
- `useSteamRelay` = the value passed in

The IP string is **exactly what the player typed**:

- `Server.getIp()` returns the raw `host` field (Server.java:87).
- `Server.setIp` stores the text in `host` and clears the cached resolved `ip` (Server.java:91).
- `Server.getIp2()` is the only getter that resolves the host. The connect path does not use it.
- DNS resolution happens only inside `UdpEngine.Connect`.

`GameClient.ip` is also read after connect, so rewriting the field (rather than the arguments of `UdpEngine.Connect`)
changes more than the transport. Readers found:

- `doConnect` builds `Core.gameSaveWorld = ip + "_" + port + "_" + hash(user)` (GameClient.java:1822-1824).
- The `getServerIP()` and `getServerPort()` Lua globals.
- The `Ping` packet payload in `UdpEngine.connected()`.
- `ConnectToServerState.receiveServerOptions` (`connection.setIP(GameClient.ip)`).
- `ClientPlayerDB` (`clientLoadNetworkPlayer`, `loadNetworkPlayerInfo`, `getNextServerPlayerIndex`).
- `LoadPlayerProfilePacket.processClient`.
- `IngameState.enter` and `IngameState.updateInternal`.
- `inviteFriend`.

### Thread

Everything below runs synchronously on the **game thread** (`GameWindow.gameThread`, created by `MainThread.init` in
`GameWindow.InitGameThread`, GameWindow.java:508). The Lua button callback goes through:

1. `GameWindow.frameStep` → `logic()` (GameWindow.java:791) → `UIManager.update()` (GameWindow.java:348) → Lua
   `onClick`.
2. `serverConnect` global (LuaManager.java:3047) → `ConnectionManager.serverConnect`, which queues the request and
   calls `process()` immediately (ConnectionManager.java:100).
3. `process` → `doServerConnect` (ConnectionManager.java:133).
4. `GameClient.doConnect` (ConnectionManager.java:201/203) → `GameClient.init()` → `startClient()`
   (GameClient.java:233) → `UdpEngine.Connect`.

`ConnectionManager.process()` is also driven later from `IngameState.updateInternal` and `LoadingQueueState.update`,
again on the game thread.

`frameStep` runs `logic()` and then builds the render state (`renderInternal`, GameWindow.java:812). A separate
`RenderThread` draws that state. A 2-3 s blocking TCP probe at the `UdpEngine.Connect` point would therefore stall
game logic and the UI for that whole time:

- No input is processed and no new frame state is produced.
- `ConnectToServer:connect` makes its "Connecting" panel visible just before it calls `serverConnect`
  (`client/OptionScreens/ConnectToServer.lua:218-242`), so that panel would not appear until the call returns.
- Not verified: whether the OS reports the window as "Not Responding". Message pumping may live on the render thread.
- `InetAddress.getByName` in `Connect` is already a blocking DNS call on this thread today.

The same `doServerConnect` → `startClient` → `UdpEngine.Connect` path also carries other request types
(`ConnectionManager.process` switch cases for `askPing`, `askCustomizationData` and `sendQR`). Their Lua entry points
are `ping(...)`, `getCustomizationData(...)` and `sendSecretKey(...)`, e.g. `client/ISUI/ISMPEditAccount.lua:224`.
A hook on `UdpEngine.Connect` also sees these short-lived connections.

## 2. Lua connect paths and the saved server list

### Saved-entry path

1. `client/OptionScreens/MultiplayerUI.lua:829-831`: the CONNECT button, `self:connectToServer(button.server, button.account)`.
2. `MultiplayerUI:connectToServer` (`MultiplayerUI.lua:834-856`):
   - `localIP = getSteamModeActive() and server:getLocalIP() or ""`
   - `useSteamRelay = getSteamModeActive() and account:getUseSteamRelay()`
   - Calls `ConnectToServer.instance:connect(self, server:getName(), user, pwd, server:getIp(), localIP,
     tostring(server:getPort()), server:getServerPassword(), useSteamRelay, false, authType)` (line 855).
   - When the account has no saved password, it opens `ISMPEnterPassword` instead, and that dialog makes the same call
     (`client/ISUI/ISMPEnterPassword.lua:55-69`).
3. `ConnectToServer:connect` (`client/OptionScreens/ConnectToServer.lua:218-243`) calls
   `serverConnect(userName, password, IP, localIP, port, serverPassword, serverName, useSteamRelay, doHash,
   authType, "")` (line 242).
4. Java side: `LuaManager.GlobalObject.serverConnect` → `ConnectionManager` → `GameClient.doConnect`, as in §1.

`useSteamRelay` is a per-**account** flag: the "steam relay" tick box in `ISMPEditAccount.lua:69`, stored in the DB
column `account.isUseSteamRelay`.

### Joins without a saved server entry

No connect-by-IP field exists in the B42 MultiplayerUI. Three paths join without a saved entry:

- **Command line `+connect ip:port`** (system property `args.server.connect`, read by `getServerAddressFromArgs`).
  `MainScreen.lua:1802-1816` (and 1833-1840) → `BootstrapConnectPopup:connect(ip, port, pwd)`
  (`client/OptionScreens/BootstrapConnectPopup.lua:58-93`). The next step depends on the mode:
  - Steam mode: `steamRequestServerDetails` runs first. A coop host goes to `connectCoop` (Steam branch). Anything
    else opens `ServerConnectPopup` (lines 96-104).
  - Non-Steam mode: `ServerConnectPopup` opens directly.
  - `ServerConnectPopup` then calls `ConnectToServer.instance:connect(self, "", ...)` with `serverName = ""`
    (`client/OptionScreens/ServerConnectPopup.lua:200-212`).
- **Steam invite or join**: `MainScreen.onAcceptInvite` (`MainScreen.lua:2076-2090`) follows the same Bootstrap flow.
- **Public server browser, server not in favourites**: `MultiplayerUI:connectFromBrowser` (`MultiplayerUI.lua:1474-1506`).
  - The `addServerToAccountList(server)` call is commented out (line 1495).
  - `ISMPEditAccount` opens with `connectAfter = true`, and `connectToServer` runs with the unsaved browser `Server`
    object (`ISMPEditAccount.lua:256-263`; with `connectAfter`, the account is not saved either).

### Storage

- The Lua globals live in `LuaManager.GlobalObject` (LuaManager.java:4245-4275): `addServerToAccountList`,
  `updateServerToAccountList`, `deleteServerToAccountList`, `addAccountToAccountList`, `updateAccountToAccountList`,
  `deleteAccountToAccountList` and `getServerList`. All of them delegate to `zombie.savefile.AccountDBHelper`.
- `AccountDBHelper.create()` (AccountDBHelper.java:51-79) opens SQLite at `<cacheDir>/db/ServerListSteam.db`. In
  non-Steam mode the file is `ServerList.db`.
  - Table `server`: `id INTEGER PRIMARY KEY AUTOINCREMENT, name, ip, port, serverPassword, description, mods, icon,
    banner, panelBackground, screenBackground, lastOnline, lastDataUpdate`.
  - Table `account`: `id, serverId → server.id, playerFirstAndLastName, username, password, isSavePassword,
    isUseSteamRelay, authType, icon, timePlayed, lastLogon`.
  - Neither table has a spare column.
  - `getServerListFile()` (`ServerList[Steam].txt`) is legacy. `parseInputFile` imports it.
- **Identity.** The only unique key is the SQLite `server.id`. It is exposed as `Server:getID()`, and
  `updateServer`/`deleteServer` use `WHERE id = ?` (AccountDBHelper.java:357). Several details matter for a key:
  - `ip` and `port` have no UNIQUE constraint, so duplicate entries are possible.
  - `saveNewServer` returns the generated id but does not call `server:setID` (AccountDBHelper.java:318-332). A
    freshly created Lua `Server.new()` object keeps its default id. Rows get their ids when `getServerList()` reloads
    them (AccountDBHelper.java:243-249).
  - Vanilla code that matches an entry by address uses `ip` + `port`:
    - `ServerConnectPopup.lua:156` (`server:getIp() == self.ip and server:getPort() == self.port`; this compares a
      Lua number with a string, so it looks like it never matches. Not verified).
    - `AccountDBHelper.updateAccountIconAndData`: `WHERE ip = ? AND port = ?`.
  - `ISMPEditServer` edits mutate `ip` and `port` on the same row (same id). A key built from ip+port therefore goes
    stale when the player edits the address.

## 3. `ISMPEditServer.lua` (`client/ISUI/ISMPEditServer.lua`, 311 lines)

- `ISMPEditServer:new(ui, server)` (296-310) sets `o.server = server`, which is nil for "add server", plus
  `o.width = 550` and `o.height = 348`.
- `ISMPEditServer:initialise()` (11-91):
  - Builds `serverName`, `serverAddress`, `serverPort` and `serverPassword` (`ISTextEntryBox`) and `seePasswordBtn`.
    The port field sits on the same row as its label, offset by `portOffset` (31-39). The password field is placed
    at `self.serverPort:getBottom() + UI_BORDER_SPACING + FONT_HGT_MEDIUM` (41).
  - Then builds `cancelBtn` and `saveBtn` (61-75).
  - For an edit, it fills the fields from `self.server` (77-83): `getName()`, `getIp()` and
    `tostring(getPort())`; the password is left blank.
  - Sets the height from `self.serverPassword:getBottom()` (85), then centres the dialog.
- `ISMPEditServer:onClick(button)` (103-130) handles SAVE as follows:
  - **Edit** (`self.server` set): `setName`, `setIp` and `setPort(tonumber(...) or 16262)`, then `setServerPassword`
    if the password was modified, then `updateServerToAccountList(newServer)` (109-117).
  - **New**: `Server.new()`, the same setters, then `addServerToAccountList(newServer)` (119-124).
  - In both cases it then calls `self.ui:refreshList()`, `self:destroy()` and `self.ui.modal = nil`.
- `ISMPEditServer:prerender()` (137-175) draws the labels (the port label is drawn at `serverPort:getY()`, same row),
  pins `saveBtn`/`cancelBtn` to the bottom, and validates the fields to enable `saveBtn`.
- `ISMPEditServer:onOtherKey` (245-263) handles Tab order: name → address → port → password.
- Callers: `MultiplayerUI:onPressButtonOnAccountList` "EDIT_SERVER" (`MultiplayerUI.lua:805-811`) and the new-server
  item.

A "TCP port" row has to be inserted after `serverPort`. The `serverPassword` y position and the final `setHeight`
both come from widgets created inside `initialise`, so a wrapper has to move `serverPassword`/`seePasswordBtn` and
re-set the height after the original `initialise` runs, or replace `initialise`. The hooks for save and load are
`onClick` with `button.internal == "SAVE"` and the edit branch of `initialise`. A server id only exists for edits
(`self.server:getID()`). For new entries, the id becomes known only after the next `getServerList()`.

## 4. Server UDP port

- The `ServerOptions` constructor defines `DefaultPort` (0-65535, default **16261**) and `UDPPort` (default **16262**)
  (ServerOptions.java:67-68).
- `GameServer.main`:
  - Parses the CLI overrides `-port` and `-udpport` (GameServer.java:563-566).
  - Calls `ServerOptions.init()` (636) and applies the overrides (639+).
  - Copies the values into the static fields `GameServer.defaultPort` and `GameServer.udpPort` (648-649).
  - Steam mode then calls `SteamGameServer.Init(ip, defaultPort, udpPort, ...)` (686).
- `GameServer.startServer()` (GameServer.java:1552) creates
  `udpEngine = new UdpEngine(defaultPort, udpPort, 255, password, true)`.
  - The constructor calls `peer.SetServerPort(port, udpPort)` and `peer.Startup(maxConnections)`
    (UdpEngine.java:66-75).
  - The binding itself is native.
- Console text in `startServer` (lines 1557-1560):
  - Steam mode: `"Server is listening on port <defaultPort> (for Steam connection) and port <udpPort> (for UDPRakNet
    connection)"`, then `"Clients should use <defaultPort> port for connections"`.
  - Non-Steam mode: `"server is listening on port <defaultPort>"`.
- Reading the values at runtime from Java: `GameServer.udpPort` / `GameServer.defaultPort` (public static int, set
  before `startServer`), or `ServerOptions.instance.udpPort.getValue()` / `.defaultPort.getValue()`.
- **Unverified (native) and critical for the tunnel**: which UDP port the client's direct-IP RakNet traffic actually
  reaches on a Steam-mode server. Players type 16261 (DefaultPort), but the log says UDPRakNet listens on UDPPort.
  `ZNetJNI64.dll` contains a connect queue that tries both kinds of connection (`"ConnectQueueAddSteamConnect address:
  %s:%d"`, `"ConnectQueueAddUDPConnect address: %s:%d"`, `"DoNextConnect: try connect to [type:%d, index:%d]"`,
  `"UDP Connection attempt %s"`). So one `Connect(ip, 16261, ...)` may try Steam networking and/or UDP to a port the
  native code picks. Ways to confirm:
  - Client console with `-Dzomboid.znetlog=1`: look for the `ConnectQueueAdd*` lines.
  - Server console: look for `Startup: serverPort=%d serverUDPPort=%d`.
  - `ss -lunp` on the Linux server.
  - Check whether `useSteamRelay=true` forces the Steam path.

## 5. Sandbox options on the dedicated server

### Order of events in `GameServer.main`

1. `doMinimumInit()` (GameServer.java:804) runs these steps in order:
   1. `LuaManager.init` (1455).
   2. `CustomSandboxOptions.instance.init()`, which reads `media/sandbox-options.txt` of the base game and of the
      active mods, then `initInstance(SandboxOptions.instance)` (1483-1484), which registers the mod options.
   3. Loads the Lua dirs.
   4. If `Server/<servername>_SandboxVars.lua` exists, `SandboxOptions.loadServerLuaFile(serverName)`; then
      `saveServerLuaFile` and `toLua()` (1522-1532).
   5. `OnGameBoot` (1537).
2. `IsoWorld.init()` (GameServer.java:817) fires `OnInitWorld` (IsoWorld.java:2183). Because
   `GameClient.client == false`, it then calls `SandboxOptions.instance.load()` (IsoWorld.java:2187).
   - `load()` reads `<save>/map_sand.bin` and parses options by name (SandboxOptions.java:1720).
   - If the file is missing or the read fails, it calls `resetToDefault()` + `updateFromLua()` (SandboxOptions.java:1742-1743).
   - `GameWindow.save` writes `map_sand.bin`.
   - **Unverified**: whether a dedicated-server save always has `map_sand.bin`, and therefore whether values in the
     save override an edited `_SandboxVars.lua` on restart. An option that is missing from the bin (for example a
     mod option added later) keeps the value from the Lua file.
3. `OnGameTimeLoaded` (GameServer.java:835).
4. `startServer()` (GameServer.java:864) creates `UdpEngine`, which starts RakNet and its receive thread, so clients
   can connect from here on. It then fires the Lua event **`OnServerStarted`** (GameServer.java:1578).
5. The main loop starts. Its first iteration handles queued connects (`DelayedConnection.connect`).

Hook points that run after **all** sandbox loading, as clients start to be accepted:

- Lua `Events.OnServerStarted` (vanilla users: `shared/Util/LuaNet.lua:288`, `shared/Fishing/fishing_properties.lua:76`).
- A Java exit hook on `GameServer.startServer()`.
- `OnGameBoot` is too early: it fires before `IsoWorld.init` → `SandboxOptions.load()`.
- `OnServerStarted` also fires inside a coop host's server process (`CoopSlave`).

### Reading a mod option from Java

- `SandboxOptions.instance.getOptionByName("Page.Name")` (SandboxOptions.java:1315) returns a `SandboxOption`. For an
  integer option, use `((IntegerConfigOption) opt.asConfigOption()).getValue()`.
- Option names are split by `SandboxOptions.parseName` into `tableName` (before the dot) and `shortName`, so Lua sees
  the value as `SandboxVars.<Page>.<Name>`.
- `getOptionByName` returns null when no mod registered the option.

### `sandbox-options.txt` format

Parsed by `CustomSandboxOptions.parse` with `ScriptParser` (CustomSandboxOptions.java:82-125):

- `VERSION = 1,` is required.
- Each option is an `option <Id>` block.
- `type` is one of `boolean|double|enum|integer|string`.
- Integer options read `min`, `max` and `default`, plus optional `page` and `translation`.

Real example from Workshop 2503622437, `Skill Recovery Journal/42.20.1/media/sandbox-options.txt`:

```
VERSION = 1,

option SkillRecoveryJournal.RecoveryPercentage
{type = integer, min = 1, max = 100, default = 100, page = SkillRecoveryJournal, translation = SkillRecoveryJournalPercentage,}
```

No `sandbox-options.txt` exists in this repo yet.

## 6. Per-IP handling on the server

None of the checks found limits connections per IP, so several clients from 127.0.0.1 are not rejected in Java:

- **Already connected**: `LoginPacket.processServer` checks by Steam ID in Steam mode (LoginPacket.java:78,
  `access-denied-already-connected-cs`) and by username (LoginPacket.java:135,
  `access-denied-already-connected-username`). Neither check uses IP.
- **`ServerWorldDatabase.authClient`** (ServerWorldDatabase.java:1049) checks the `bannedip` table only when **not**
  in Steam mode and the IP is not `127.0.0.1`/`localhost`.
- **`MaxAccountsPerUser`**: `isNewAccountAllowed` counts accounts by **steamid** (ServerWorldDatabase.java:1205-1217).
- **`LoginQueue`** uses the IP for log lines only (e.g. LoginQueue.java:162, `ConnectionImmediate ip=%s`).
- **Delayed disconnects**: `GameServer.addDelayedDisconnect` keys `MainLoopDelayedDisconnectQ` by **username**
  (GameServer.java:2570). `DelayedConnection.hostString` is set (GameServer.java:4181) but no code reads it.
- **Admin bans**: `BanSystem.BanIP` / `BanUserByIP` (the `banip` command) store the connection IP. Banning a
  tunnelled player by IP therefore bans `127.0.0.1`, which is every tunnelled player (the ban is enforced only in
  non-Steam mode, per `authClient`). `BanSystem.isIpUsedBySteamRelayConnection` exists for the analogous relay case.
- Other ServerOptions: `MaxPlayers`, `PingLimit`, `DenyLoginOnOverloadedServer`, `LoginQueueEnabled` and
  `MaxPacketsPerSecond` are not per IP.
- **Native (unverified)**: `ZNetJNI64.dll` imports RakNet `AddToBanList` but not `SetLimitIPConnectionFrequency`.
  RakNet's per-IP connection-frequency limit is off by default. When and why ZNet calls `AddToBanList` is unknown; if
  it bans an address, a ban on 127.0.0.1 would block every tunnelled player.

## 7. Shipped Java

The client and dedicated-server `jre64/release` files are identical: **Azul Zulu 25.30+17-CA, Java 25.0.1+8-LTS**
(Windows x86_64). The dedicated server starts `zombie/network/GameServer` with `-Dzomboid.steam=1
-Dzomboid.znetlog=1` (`Project Zomboid Dedicated Server/ProjectZomboid64.json`). The Linux server's `jre64` was not
checked; only Windows installs are present here.

## 8. Client file storage

- `getFileWriter(name, createIfNull, append)` (LuaManager.java:5861-5865) and `getFileReader(name, createIfNull)`
  (LuaManager.java:5203) work relative to `LuaManager.getLuaCacheDir()`, which is `<cacheDir>/Lua`
  (LuaManager.java:1282).
- The cache dir defaults to `<user.home>/Zomboid` (ZomboidFileSystem.java:283). `-cachedir=` or
  `deployment.user.cachedir` override it. On Windows the files land in `C:/Users/<user>/Zomboid/Lua/<name>`.
- Rules: `..` is rejected (`hasRelativePath`). `getFileWriter` only allows the extensions `ini, cfg, txt, log, json`
  (`LuaManager.ALLOWED_FILE_EXTENSIONS`) and creates any subdirectories it needs.
- `LuaFileWriter` has `write`, `writeln` and `close`. Reads go through a `BufferedReader` (UTF-8) with `readLine`.
- Vanilla examples: `client/ISUI/ISLayoutManager.lua:127,172` (`layout.ini`), `client/ISUI/ISUIEmoteConfig.lua:99,121`.
- `getModFileWriter(modId, name, create, append)` writes under the mod's common dir instead, which is unsuitable for
  user data in Workshop installs.
- From Java, the same location is `ZomboidFileSystem.instance.getCacheDir()`.

## Related Specs

- `.trellis/spec/mod/directory-structure.md`: where a feature module lives (D8).
- `.trellis/spec/mod/zabric-hooks.md` and `.trellis/spec/mod/zombiebuddy-patching.md`: how a hook on
  `UdpEngine.Connect` or `GameServer.startServer` would be written.

## Caveats / Not Found

- Everything native (RakNet/ZNet socket binding, the Steam/UDP connect queue, what `useSteamRelay` does, the meaning
  of `GetConnectionType`) is inferred from DLL strings only. See §4 and §6.
- Not verified: whether `map_sand.bin` overrides `_SandboxVars.lua` on restart (§5).
- No Linux server files are present. Only the Windows dedicated server install was read; its jar is byte-identical to
  the client's.
