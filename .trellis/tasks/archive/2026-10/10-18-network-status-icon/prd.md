# Multiplayer network status icon

## Goal

In multiplayer, a player sees at a glance how good their connection to the server is, and on hover reads the details
(latency, packet loss, how they are connected, debug numbers), so they can tell a laggy server from a bad link and know
whether the Dont39 tunnel is in use and working.

Request (user, 2026-10-18): a signal icon in the bottom-left corner in multiplayer, ping and packet loss presented
separately, hover shows latency, packet loss, connection method, etc.

## Background (repository and game evidence)

- The game client's connection (`GameClient.connection`, a `UdpConnection`) provides ping (last / average / lowest)
  and RakNet statistics (`ZNetStatistics`: loss last second and total, byte rates, resend and send buffers, congestion
  and bandwidth limits). RakNet tells Steam from direct UDP connections. All public API (details: design.md).
- Vanilla shows ping only as text (`ISVersionWaterMark`, bottom-right). There is no signal icon.
- Bottom-left is free below the default chat window (`Chat/ISChat.lua:1167`: x=15, y=screenHeight−400, 500×200).
- Through the Dont39 tunnel (`TunnelClient.kt`, `TunnelStore.Mode` OFF / TCP / KCP), RakNet's ping still covers the
  full round trip, but RakNet's loss is ~0 because TCP / KCP resend underneath; link loss becomes latency. KCP and
  FEC already count retransmits and rebuilds (`Kcp.kt:75`, `FecDecoder.kt:31,35`); TCP link loss is not observable
  from Java.
- No All-in-One feature has mod options yet; vanilla's `PZAPI.ModOptions` (`client/PZAPI/ModOptions.lua`) is the API.

## Requirements

- **R1 Visibility.** Shown only while connected to a multiplayer server as a client; never in single player, on
  menus, or on the dedicated server. Mod option "Show network status icon" (tick box, default on) hides it.
- **R2 Placement.** Bottom-left corner of the screen, 10 px from the left and bottom edges, kept there across
  resolution changes. Not draggable.
- **R3 Ping on the icon.** Four signal bars. Bar count and bar color both show the ping tier, from the 5-second
  average ping:

  | Average ping | Bars | Color |
  |--------------|------|-------|
  | < 100 ms | 4 | green |
  | 100–199 ms | 3 | yellow-green |
  | 200–349 ms | 2 | orange |
  | ≥ 350 ms | 1 | red |
  | no data | 0, gray, with an × | gray |

- **R4 Loss on the icon.** A warning-triangle badge ("!") at the icon's lower-right corner. Absent below 1 %
  (5-second average); amber at 1–5 %; red at ≥ 5 %. Color alone is not used for loss.
- **R5 Loss source follows the connection method.**
  - Direct UDP / Steam: RakNet loss. If RakNet reports no statistics (possible on Steam), loss shows "not reported"
    and there is no badge.
  - KCP tunnel: link loss, the worse of inbound loss (missing FEC sequence numbers) and the outbound retransmit rate.
  - TCP tunnel: never a badge.
- **R6 Tooltip on hover.** Rows that do not apply to the connection method are omitted:
  1. Connection method: direct UDP / Steam / Dont39 TCP tunnel / Dont39 KCP tunnel, plus the tunnel port when
     tunneled.
  2. Ping: current, average, lowest (ms).
  3. Loss:
     - UDP / Steam: last second and session total.
     - KCP: inbound loss, outbound retransmit rate, FEC rebuilt, FEC could not rebuild, KCP smoothed RTT.
     - TCP: "Loss is resent by TCP and shows up as latency".
  4. Traffic: send / receive rate (KB/s).
  5. Server: name and address.
  6. Debug section: RakNet resend buffer (messages / bytes), whether congestion control or the bandwidth limit is
     throttling (and to what rate), and send-buffer bytes.
- **R7 Translations.** All text in EN and CN.
- **R8 Robustness.** A failure reading stats disables the feature with a logged error, without spamming every
  second. The icon then shows the no-data state. The icon works when the tunnel feature is absent or disabled
  (method is then UDP / Steam only).

## Acceptance criteria

- AC1 (R1) In single player and on menus no icon is drawn; after joining a server it appears; after disconnecting
  it is gone. Turning the mod option off hides it in game without a restart; turning it on shows it again.
- AC2 (R2) The icon sits at the bottom-left corner after joining, and still does after the resolution changes.
- AC3 (R3) With ~50 ms ping, 4 green bars. With added latency (netem delay) crossing 100 / 200 / 350 ms, the icon
  drops to 3 / 2 / 1 bars with the matching color within ~5 s.
- AC4 (R4, R5) Direct UDP with 5 % induced loss: red badge within ~5 s; it disappears within ~5 s after the loss
  stops. KCP with induced loss: badge appears. TCP with induced loss: no badge, ping rises.
- AC5 (R6) The tooltip shows the rows for each of UDP, TCP tunnel, and KCP tunnel joins, with values that change
  over time. The TCP tooltip shows the TCP loss note. The debug section is present.
- AC6 (R7) The text is in Chinese when the game language is CN.
- AC7 (R8) The build gate passes and `smokeTest` shows `network-status: ready`. A join with the tunnel feature
  disabled still shows the icon.

## Out of scope

- Dragging or saving the icon position.
- Server-side or admin views of other players' connections.
- Estimating TCP-tunnel loss (for example from jitter).
- Changing networking behavior from measured quality.

## Deferred / risks

- Steam connections: whether RakNet returns statistics is not known until tested. R5's "not reported" path covers
  the case where it doesn't. Verify if a Steam server is available, otherwise report it as not verified.
- KCP inbound loss counts FEC data sequence gaps. Very late reordering can briefly overcount. The 5-second window
  absorbs it.
