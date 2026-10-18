# Design: Multiplayer network status icon

## Boundaries

Two modules, composed in Lua, with no Gradle dependency between features:

| Module | Owns | Exposes to Lua |
|--------|------|----------------|
| `network-status` (new Kotlin feature, `pz-feature`) | reading the game client's RakNet connection; the HUD icon, tooltip and mod option (Lua) | `Dont39AllInOne_getNetworkStatus()` |
| `tunnel` (existing) | the open tunnel's mode, port and KCP/FEC counters | `Dont39AllInOne_getTunnelStatus()` |

Why not one module: the status icon is not part of the tunnel, and a `network-status` → `tunnel` Kotlin dependency
would be the first between features (facades are `internal`). Lua already composes features by checking that a
global exists ([ZombieBuddy Patching § Lua Bridge](../../spec/mod/zombiebuddy-patching.md#lua-bridge)), so the icon
works without the tunnel global (connection method then reads direct UDP / Steam only).

## Data sources (all public game API; no widening, no reflection)

- `GameClient.connection: UdpConnection` (null when not connected).
  - Ping: `connection.updatePing()` then `getLastPing()` / `getAveragePing()` / `getLowestPing()`. On the client,
    only `PingManager` calls `updatePing`, and that may be server-only, so the feature calls it itself before reading.
    `getMPStatus().lastPing` reads the same field.
  - RakNet stats: `connection.getStatistics()` → `ZNetStatistics` (null when RakNet throws): `packetlossLastSecond`,
    `packetlossTotal`, `lastActualBytesSent/Received` (per-second rates), `messagesInResendBuffer`,
    `bytesInResendBuffer`, `isLimitedByCongestionControl` / `bpsLimitByCongestionControl`,
    `isLimitedByOutgoingBandwidthLimit` / `bpsLimitByOutgoingBandwidthLimit`, `bytesInSendBuffer*`.
  - Steam vs RakNet: `GameClient.instance.udpEngine.getPeer().GetConnectionType(connection.getConnectedGUID())` →
    `ConnectionType_UDPRakNet` / `ConnectionType_Steam` (`RakNetPeerInterface`). `udpEngine` is a public instance
    field, so no `.classtweaker` is needed.
- Tunnel: `TunnelClient` holds the open `OpenTunnel` (`Transport.kt`). Add a `stats()` snapshot to `OpenTunnel`:
  - TCP (`ClientTunnel`): mode + port only.
  - KCP (`KcpClient` → `KcpSession`): under the session lock, copy `Kcp.timeoutRetransmits`, `Kcp.fastRetransmits`,
    a new `Kcp` counter of segments sent (first transmissions), `rxSrtt`, `FecDecoder.recovered` / `unrecoverable`,
    and a new inbound-loss count: FEC data sequence numbers skipped (gaps in `seq` of received data shards) vs
    expected. `KcpSession.counts()` already formats these for the close log; reuse the same fields.

## Loss metric per connection method (PRD Q2)

| Method | Badge input | Tooltip |
|--------|-------------|---------|
| Direct UDP (RakNet) | `packetlossLastSecond` | last second, session total |
| Steam | `packetlossLastSecond` if `getStatistics()` is non-null, else "not reported" and no badge | same |
| KCP tunnel | worse of inbound loss (FEC seq gaps / expected, over the window) and outbound retransmit rate (retransmits / segments sent, over the window) | inbound loss, outbound retransmit rate, FEC rebuilt, FEC could not rebuild, KCP SRTT |
| TCP tunnel | none (never shows a badge) | "loss is resent by TCP and shows up as latency" |

Windows: the Lua side samples once per second and keeps the last 5 samples; the badge uses the 5-second average of
the loss input and the icon uses the 5-second average ping, so one bad second does not flash the badge. Counters are
cumulative in Kotlin; Lua takes deltas between samples.

## Contracts

`Dont39AllInOne_getNetworkStatus()` → `KahluaTable` or nil when `GameClient.connection` is null. Keys (numbers are
Lua doubles): `method` ("udp" | "steam"), `pingLast`, `pingAvg`, `pingLow`, `stats` (bool: RakNet returned
statistics), and when `stats`: `lossLastSecond`, `lossTotal`, `bytesSentPerSec`, `bytesRecvPerSec`,
`resendMessages`, `resendBytes`, `congestionLimited`, `congestionBps`, `bandwidthLimited`, `bandwidthBps`,
`sendBufferBytes` (sum of the four priorities).

As built: `fecExpected` / `fecMissing` count every FEC sequence number (data and parity share one sequence), so
their ratio is inbound datagram loss, also with FEC off. Mode and port come from `TunnelClient`'s stored entry;
`OpenTunnel.stats()` returns only KCP link counters (null for TCP). While connected but with the feature not running
(disabled after a read error), `Dont39AllInOne_getNetworkStatus()` returns `{ disabled = true }` instead of nil, so the
tooltip can say it stopped; the icon shows the no-data state. A connection type of `Disconnected` (RakNet dropped it,
the game has not cleared `GameClient.connection` yet) returns nil.

`Dont39AllInOne_getTunnelStatus()` → nil when no tunnel is open or the tunnel feature is not active, else `mode` ("tcp" | "kcp"), `port`, and for KCP the
cumulative counters `segmentsSent`, `retransmitsTimeout`, `retransmitsFast`, `fecExpected`, `fecMissing`,
`fecRecovered`, `fecUnrecoverable`, `srtt`.

Both are called from the render loop at most once per second; each builds a fresh table
(`LuaManager.platform.newTable()`). A throw disables that feature (`Feature.disable`) and returns nil; the icon then
shows the no-data state rather than erroring every second
([Error Handling](../../spec/mod/error-handling.md)).

## UI (Lua, `network-status/src/main/lua/client/Dont39AllInOne_NetworkStatus.lua`)

- One `ISUIElement` added on `OnGameStart` when `isClient()`, removed on disconnect / main menu. Anchored 10 px from
  the left and bottom of the screen, re-anchored on resolution change (`OnResolutionChange`).
- Icon (~24×20): four bars drawn with `drawRect`; bar count and color from the ping tier; unfilled bars as outlines.
  Badge: a warning-triangle PNG (`media/ui/Dont39AllInOne_NetworkStatus_Warning.png`, white triangle with a cut-out
  "!", tinted at draw time) at the icon's lower-right; amber for moderate, red for heavy loss.
- Tooltip on hover: a panel drawn above the icon (it is at the screen bottom) with the PRD rows; rows that do not
  apply are omitted. Text through `getText` with `UI_Dont39AllInOne_NetworkStatus_*` keys in `UI.json` (EN + CN).
- Mod option: `PZAPI.ModOptions:create("Dont39AllInOne_NetworkStatus", ...)` with tick box `show` (default true);
  the element checks it each render and hides itself when off.

## Compatibility and rollback

- New module, new Lua globals, one new PNG; `tunnel` gains read-only counters and a stats accessor. No stored formats
  change. Rollback: drop the module from `settings.gradle.kts` and the feature list; revert the tunnel stats commit.
- The PNG is LFS-tracked (`.gitattributes`); a worktree needs `git lfs pull` before `deployWorkshop`.
