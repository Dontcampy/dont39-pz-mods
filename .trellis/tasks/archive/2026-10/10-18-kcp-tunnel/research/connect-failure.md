# Research: stopping a tunnelled join and showing the reason

- **Query**: how `UdpEngine.Connect` reports UnknownHost and how the UI shows `OnConnectFailed`; how a hook can abort
  the connect with its own reason and what state must be reset; the ping/QR paths through the same `Connect`; whether
  Zabric can cancel a void method at HEAD.
- **Scope**: internal (game bytecode via `javap -c -p -l`, vanilla Lua, `zabric/`, `zabric-processor/`)
- **Date**: 2026-10-18

Java citations are `Class.method (File.java:line)` from LineNumberTables of
`ProjectZomboid/projectzomboid.jar` (same build as the archived game-internals research). Lua paths are relative to
`ProjectZomboid/media/lua/`.

## 1. How `Connect` reports UnknownHost, and what the UI does

### `UdpEngine.Connect(String hostname, int port, String serverPassword, boolean useSteamRelay)` (UdpEngine.java:419-441)

IP branch (every non-coop-invite join):

```
432  InetAddress address = InetAddress.getByName(hostname); ip = address.getHostAddress();
434  catch (Exception e):
435    DebugType.Network.error(Translator.getText("UI_OnConnectFailed_UnknownHost"));
436    LuaEventManager.triggerEvent("OnConnectFailed", Translator.getText("UI_OnConnectFailed_UnknownHost"));
437    return;
439  peer.Connect(ip, port, hashServerPassword(serverPassword), useSteamRelay);
```

The Steam-ID branch (`port == 0 && SteamUtils.isSteamModeEnabled()`, lines 419-429) does the same on a bad Steam ID:
`printException`, `triggerEvent("OnConnectFailed", getText("UI_OnConnectFailed_UnknownHost"))`, `return` (425-426).

- Event name `OnConnectFailed`, **one argument**: the already-translated text (Java calls `Translator.getText` before
  firing; the overload used is `triggerEvent(String, Object)`).
- It returns early: `peer.Connect` is never called, so no RakNet packet ever comes back for this attempt.
- `LuaEventManager.triggerEvent` overloads exist for 1-4 args (`triggerEvent(String, Object, Object)` etc.), so a
  second `detail` argument can be passed too.
- Runs synchronously on the game thread (see game-internals §1 "Thread").

### What runs after `Connect` returns (caller side)

1. `GameClient.startClient` (GameClient.java:237-253): after the `Connect` call (245/247) it sets
   `clientStarted = true` (249) regardless of the early return. Exceptions inside are caught and logged (250-251).
2. `GameClient.init` (GameClient.java:211-234) just returns after `startClient()` (233).
3. `GameClient.doConnect` (GameClient.java:1811-1829): after `init()` (1821) it only sets `Core.gameSaveWorld` and
   renames the save dir (1822-1827). Nothing there depends on a connection.
4. `ConnectionManager.doServerConnect` (ConnectionManager.java:189-205) had already set `GameClient.client = true`,
   `clientSave = true`, `coopInvite = false`, cleaned MP saves, then calls `doConnect` (201/203) and returns.
5. `GameClient.connection` stays `null` (it is only set once RakNet accepts). `ConnectionManager.process()` returns
   immediately while `connection != null` (ConnectionManager.java:118-119), so a null connection lets later requests
   run.

### Other Java `OnConnectFailed` sources (for comparison of text style)

`GameClient.update` delayed-disconnect switch (GameClient.java:308-330), keyed on the RakNet message id:

| Line | Fires |
|---|---|
| 310 | `OnDisconnect` (no args) |
| 313 | `OnConnectFailed`, `getText("UI_OnConnectFailed_AlreadyConnected")` |
| 316 | `OnConnectFailed`, `getText("UI_OnConnectFailed_ConnectionLost")` |
| 319 | `OnConnectFailed`, `getText("UI_OnConnectFailed_Banned")` |
| 322-323 | connection attempt failed: non-Steam mode only, `OnConnectFailed` with `null` (Lua then shows the generic text) |
| 327 | `OnConnectFailed`, `getText("UI_OnConnectFailed_InvalidServerPassword")` |

`ServerWorkshopItemScreen.lua:236` fires it from Lua with two args: `triggerEvent("OnConnectFailed", error, detail)`.

### Lua listeners of `OnConnectFailed` (complete list from a grep of `media/lua`)

**`ConnectToServer`** (`client/OptionScreens/ConnectToServer.lua`): the "Connecting" screen every join uses
(saved-entry, `ServerConnectPopup`, Bootstrap/+connect, browser join all end in `ConnectToServer:connect`, 218-243).

- Registered for `OnConnectFailed` and also `OnDisconnect` (384-386); the local wrapper passes `(message, detail)` to
  the method (371-373).
- `ConnectToServer:OnConnectFailed(message, detail)` (302-335):
  - Hides the Google-auth popup (304-306); returns if the screen is not visible (307).
  - `message == "ServerWorkshopItemsCancelled"` triggers `onBackButton` (309-312).
  - Keeps the **first** message after Connect was clicked: `if not self.failMessage then self.failMessage = message`;
    a later nil message reuses it (317-318). `connect()` resets `failMessage = nil` (237).
  - Strips anything after `MODURL=` (319-322).
  - `message = message or getText("UI_servers_connectionfailed")` (324); appends `"\n" .. detail` when detail is
    non-empty (326-328).
  - Shows it: `self.connectLabel.name = message` (330); back button title becomes "Back" (332);
    `self.connecting = false` (334), which stops the `> > >` animation in `render` (151-174).
- **The text is shown raw.** It is not passed through `getText`. Translation must happen before firing (Java
  `Translator.getText(key, args...)` or Lua `getText`).
- `ConnectToServer:onBackButton` (197-216): `if self.connecting or isClient() then self.connecting = false;
  backToSinglePlayer() end`, hides itself and shows `previousScreen` (the server list) or the main menu for coop.

**`ISMPEditAccount.OnConnectFailed(message, detail)`** (`client/ISUI/ISMPEditAccount.lua:452-459`, registered 480-481
for `OnConnectFailed` and `OnDisconnect`): only acts when the account dialog exists; sets
`googleAuthPopup.statusLabel = getText("UI_servers_connectionfailed")` (ignores `message`) and calls
`stopSendSecretKey()` (sets `GameClient.sendQR = false`, LuaManager.java:7227).

**`CoopOptionsScreen.OnConnectFailed(message)`** (`client/OptionScreens/CoopOptionsScreen.lua:716-728`, registered
242): only when the coop-host screen is visible; shows `message` (or `UI_servers_connectionfailed`) as
`serverStatus`, same first-message rule.

`ConnectToServer:OnConnectionStateChanged` (337-365) is the RakNet-state path (`Failed`/`Disconnected`/...); it maps
state names via `getText('UI_servers_'..message)`, so it takes key suffixes, not raw text. Not used by UnknownHost.

### Translator details

- `Translator.getText(String, Object...)` (Translator.java:505): substitutes `%1`, `%2` ... (rewritten to
  `%1$s` and `String.formatted`), so a mod key like `"... %1"` can carry the failure detail.
- Mod translation keys load into the same Translator: `tcp-tunnel/src/main/lua/shared/Translate/{EN,CN}/UI.json`
  already exist (keys `UI_Dont39AllInOne_TcpTunnel_Port`, `..._PortTooltip`). The tooltip text currently promises
  "if the tunnel does not answer, the join goes over UDP as usual".
- Vanilla EN: `UI_OnConnectFailed_UnknownHost` = "Unknown host" (`shared/Translate/EN/UI.json:1088`),
  `UI_servers_connectionfailed` = "Connection Failed" (`:522`).

## 2. Aborting the connect from the hook, and the state afterwards

### Mechanism that mirrors vanilla UnknownHost

A cancellable `@Inject` at HEAD of `UdpEngine.Connect` that fires
`LuaEventManager.triggerEvent("OnConnectFailed", text[, detail])` and calls `ci.cancel()` leaves the game in exactly
the state vanilla UnknownHost leaves it (body skipped before `peer.Connect`, event fired with translated text, normal
return to `startClient`). Everything downstream in §1 is the same path vanilla already exercises.

### State after the abort, and how it is cleaned up

| State | Value after abort | Reset by |
|---|---|---|
| `GameClient.client` | `true` (doServerConnect, ConnectionManager.java:196) | Back button: `backToSinglePlayer` → sets `false` (LuaManager.java:3094-3097) |
| `GameClient.clientStarted` | `true` (GameClient.java:249) | `backToSinglePlayer` → `GameClient.doDisconnect` → `connection == null` so `GameClient.Shutdown()` (GameClient.java:1764-1777) → `udpEngine.Shutdown()` and `clientStarted = false` (GameClient.java:282-286) |
| `GameClient.connection` | `null` | nothing needed |
| client `UdpEngine` | started (RakNet peer + receive thread from the constructor), never connected | same `Shutdown` as above; `UdpEngine.Shutdown` (UdpEngine.java:109-119) sets `quit`, waits for the thread, `peer.Shutdown()`. The existing `ClientConnect.shutdown` hook on `UdpEngine.Shutdown` HEAD fires here. |
| ConnectToServer screen | label = our text, button "Back", `connecting = false` | player presses Back |
| `GameClient.ip/port/...`, `Core.gameSaveWorld` | set to the attempted server | overwritten by the next `doConnect` |

- The connecting screen does not get stuck: `OnConnectFailed` itself flips `connecting` and the button title, and the
  Back path calls `backToSinglePlayer` because `isClient()` is still true.
- If a new connect runs without Back (only possible from code paths other than ConnectToServer), `clientStarted` is
  true, so `startClient` reuses the engine and calls `Connect(GameClient.ip, ...)` again (GameClient.java:237-239);
  the hook runs again.

### Existing tcp-tunnel code this replaces

- `ClientConnect.host` (`tcp-tunnel/.../ClientConnect.kt:17-25`): `@ModifyVariable` at HEAD, ordinal 0; returns
  `127.0.0.1` when `TunnelClient.open` returns true, else the original host.
- `TunnelClient.open` (`TunnelClient.kt:20-37`): returns false for null host / Steam-ID branch (23-25) and for no
  stored port (26); on `ClientTunnel.Failed` it logs "...; connecting to host:port over UDP" and returns false
  (29-31). This is the UDP fallback.
- `ClientTunnel.Failed` reasons (`ClientTunnel.kt:39-66`): no tunnel at host:port, cannot check whether host is this
  machine, server is on this machine, cannot listen on 127.0.0.1:port, tunnel closed during hello.
- Generated glue (from the `tcp-connection-setup` worktree build,
  `tcp-tunnel/build/generated/ksp/main/java/com/dont39/pz/allinone/TcpTunnelGlue.java:38-60`): `@Patch.OnEnter` on
  `UdpEngine.Connect`, guarded by `MOD.active(TcpTunnel.INSTANCE)`; handler exceptions go to
  `MOD.failed(TcpTunnel.INSTANCE, ...)`.

### Distinguishing the request type at `Connect` time

`ConnectionManager.process` (ConnectionManager.java:128-168) sets three static flags before `doServerConnect`:

| Request (`RequestType` ordinal) | `askPing` | `askCustomizationData` | `sendQR` | Lua entry |
|---|---|---|---|---|
| `connect` (0) | false | false | false | `serverConnect` (ConnectToServer.lua:242) |
| `connectCoop` (1) | false | false | false | `serverConnectCoop` (Steam-ID branch) |
| `askPing` (2) | true | false | false | `ping(...)` global |
| `askCustomizationData` (3) | false | true | false | `getCustomizationData(...)` global |
| `sendQR` (4) | false | false | true | `sendSecretKey(...)` (ISMPEditAccount.lua:224, the "QR" button) |

`UdpEngine.connected()` reads `GameClient.askPing` / `sendQR` (UdpEngine.java bytecode near the `connected` method,
starting line ~456 in the javap dump) to decide what to send once connected.

### The "server-list ping" path

- In this B42 build **no vanilla Lua calls `ping(...)` or `getCustomizationData(...)`** (grep of `media/lua`); a jar
  scan finds `askPing`/`getCustomizationData` only in `ConnectionManager`, `GameClient`, `UdpEngine`, `GlobalObject`.
  `MultiplayerUI.ServerPinged` (MultiplayerUI.lua:1545-1547) is an empty handler.
- The server list's ping/player numbers come from Steam server queries (`steamRequestServerDetails`,
  MultiplayerUI.lua:702, 886; `OnSteamServerResponded`, 1549+), which do not touch `UdpEngine.Connect`.
- So the only vanilla paths through `Connect` are: the join (`connect`), coop host self-connect (`127.0.0.1`, coop
  port; GameClient.java:243-245), coop invite (Steam-ID branch), and the QR "send secret key" button.
- Failing the **QR path** with `OnConnectFailed`: `ConnectToServer` is hidden so it only resets its popup and returns
  (304-307); `ISMPEditAccount.OnConnectFailed` shows the generic "Connection Failed" status (our text is not shown)
  and clears `sendQR`. `GameClient.client` stays `true` after this path (doServerConnect set it, nothing in the QR UI
  calls `backToSinglePlayer`); vanilla UnknownHost on the QR path behaves the same.
- A mod calling `ping()` would see the same abort as a join; no vanilla UI reacts except the listeners above.

## 3. Zabric: cancelling a void method at HEAD

Supported.

- `@Inject(method, at, cancellable = false, always = false)` (`zabric/src/main/java/com/pzlauncher/zabric/hook/Inject.java:15-29`):
  "Lets the handler cancel (`CallbackInfo#cancel()`); not at `FINALLY`."
- `CallbackInfo.cancel()` (`CallbackInfo.java:28-34`): "Skips the target (at `HEAD`) and later handlers; throws unless
  `Inject#cancellable()`."
- Glue template (`zabric-processor/src/main/resources/com/pzlauncher/zabric/processor/glue/glue.ftl`): a patch with
  a cancellable HEAD handler gets `@Patch.OnEnter(skipOn = true)` returning boolean (lines ~95-100); after a
  cancellable call `if (ci.isCancelled()) { cancelled = true; return true; }` (lines ~51-60), i.e. it skips the target
  body and the remaining HEAD handlers of the patch.
- Handler order: within one `@Hooks` class, HEAD calls run in the order of `hookClass.handlers()`
  (`HookAnalyzer.java:131-147`, `555-565`; `GlueWriter.java:127-131`); different hook classes on one target run in
  binary-name order (`HookAnalyzer.java:60, 90`). So a cancel in an earlier handler means a later `@ModifyVariable` in
  the same patch does not run, and vice versa.
- Handler exceptions are caught by the glue and passed to `MOD.failed(gate, ...)` (glue.ftl ~62; spec
  `.trellis/spec/mod/zabric-hooks.md:36-37` "Handlers never catch or gate; failures go to the hook class's gate"). A
  throw is therefore not a way to abort `Connect`: it disables the module and the body still runs.
- Tests: `zabric/src/test/java/com/pzlauncher/zabric/hook/weave/GatedHooks.java:36-41` (`skipPing`, cancel on the
  void `Target.ping()`), verified by `WeaveTest.headCancelSkipsTheTargetAndFinallyStillRuns`
  (`WeaveTest.java:54-62`); `WeaveGlue.java:205-226, 314-326` is the reference glue with `skipOn`.
- In-repo usages: `missing-anim-folders/.../AnimFolderWalk.kt:15` (cancellable HEAD on a non-void target),
  `samples/zabric-java-hooks/.../GameTimeHooks.java:25-29` (cancellable HEAD on `GameTime.update`, `ci.cancel()`).
- `@ModifyVariable` (`ModifyVariable.java:86-109`) is HEAD-only, `argsOnly = true`; it cannot cancel. `@Share`
  (`LocalRef`) can pass a value between handlers of one hook class on the same call (GatedHooks.java:19-34).

## Related Specs

- `.trellis/spec/mod/zabric-hooks.md` — hook rules, handler failure goes to the gate.
- `.trellis/spec/mod/error-handling.md` — module disable on error.
- Archived `.trellis/tasks/archive/2026-10/10-17-tcp-connection/research/game-internals.md` §1 — connect path/thread.

## Caveats / Not Found

- When the tunnel module's gate is inactive (e.g. after `TcpTunnel.disable`), the glue skips the handlers
  (`MOD.active(...)` guard), so `Connect` runs unmodified over UDP for every entry.
- Not verified in game: how `ISLabel` lays out a long or multi-line `connectLabel.name`; vanilla itself joins
  `message .. "\n" .. detail` there.
- `hookClass.handlers()` order for Kotlin hook objects (KSP path) was not traced to source; the generated glue for
  the current single handler gives no evidence on ordering of two HEAD handlers.
- Line numbers for `UdpEngine.connected()` were not pinned; only the flag reads were seen.
