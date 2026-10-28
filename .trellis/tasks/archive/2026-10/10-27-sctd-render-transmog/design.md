# Design: sctd-transmog-fix

One Lua-only All-in-One feature, `sctd-transmog-fix`, with two parts that share one target check and one switch:
**resnap** (PRD problem 1) and **fluids** (problem 2). No Kotlin, no hooks. SCTD source read: `LingLi_SCTD_Skins.lua`
(file dated 2026-10-19, sha256 `ba9931c0…`); game code read with `javap` on B42.21 `projectzomboid.jar`.

## Where the code runs

| Side | Loads | What it needs |
|---|---|---|
| Dedicated server | `shared`, `server` (`GameServer` loads `shared`, `client` for checksum only, `server`, then fires `OnGameBoot`) | resnap + fluids |
| Single player | `shared`, `client` at boot, `server` at game start | fluids |
| MP client | `shared`, `client` (and `server`, whose file returns at once on a client) | nothing acts; SCTD's functions only send commands there |
| `smokeTest` (main menu, exits via `System.exit` at the end of `GameWindow.init`, before `OnGameBoot`) | `shared`, `client` | a status line |

Seen on the live server's console: client-only Lua (`mod-options-sidebar`) logs nothing there, so the dedicated server
does not run `client/` files.

Load order inside `shared` across mods is not ours to rely on, so nothing runs at `shared` load. Files:

- `shared/Dont39AllInOne_SctdTransmogFixShared.lua`: a module table (`require`d, as `Dont39AllInOne_TunnelShared.lua`
  is): logging, the target check, both parts, `disable`. Holds the state, so a second entry in the same Lua state is a
  no-op.
- `client/Dont39AllInOne_SctdTransmogFix.lua`: entry on clients and in single player. Client Lua runs after every
  mod's shared Lua, so SCTD's globals exist. Checks, installs fluids, logs the status.
- `server/Dont39AllInOne_SctdTransmogFixServer.lua`: entry on the dedicated server and in single player; returns on an
  MP client. Checks, installs fluids (already done in single player), and on a dedicated server (`isServer()`) the
  resnap; logs the status.

## Target check

`getModInfoByID("ShadowCatTacticsDispatch")` not active → `idle: ShadowCatTacticsDispatch is not enabled`. Otherwise
these must be functions, else an error and `disabled`: `SCTD_Transmog_Apply`, `SCTD_Transmog_Restore`,
`SCTD_Transmog_HideItem_Server`, `SCTD_Transmog_ResnapPlayer`, `SCTD_Transmog_GetData`, `instanceItem`,
`getOnlinePlayers`, plus `GameEntityFactory.AddComponent` and `ComponentType.FluidContainer`. No source-text gate:
unlike the flush fix this replaces nothing of SCTD's, and SCTD updates often; the runtime self-check below catches a
changed structure instead.

Status lines, prefix `[Dont39AllInOne] sctd-transmog-fix: `: `idle: …`, `ready` (client entry: fluids installed;
server entry: fluids, and the resnap on a dedicated server), `disabled after an error while <what>: <err>` + trace.
One line per entry; single player logs twice (client, then server entry).

## Part resnap (dedicated server)

Decision: (a), a Lua scan, every tick. `Events.OnTick`: for each player in `getOnlinePlayers()` not in a weak-keyed
`done` table, mark it and call `SCTD_Transmog_ResnapPlayer(player)` in `pcall`; an error disables the feature.

Why the inventory is there by then (bytecode, `GameServer.receivePlayerConnect`): the player is loaded by
`ServerPlayerDB.serverLoadNetworkCharacter` → `IsoPlayer.load` → `IsoGameCharacter.load` → `ItemContainer.load`
(@184/@202), synchronously, before `GameServer.Players.add` (@373) and `connection.setPlayerAt` (@387).
`getOnlinePlayers()` on the server is `GameServer.getPlayers()`, which reads the connections' player arrays. Packet
handling and `OnTick` both run on the server's main thread, so the first tick that sees the player sees the loaded
inventory. The in-game run confirms it (the weights match on the second join).

Every tick, not a timer: the resnap then lands before the client's Server Reborn `Hello` (a later packet) can start
comparisons, so no join shows a `weight{...}` diff. Cost: one small list per tick and a weak-table lookup per player.

On the server, `ResnapPlayer` does `ResnapContainer(inventory)` plus `RepairWornSlots`; `FlushPlayer` and
`ReconcileWorn` return at once there (client-only state). `RepairWornSlots` is SCTD's own server work, the same its
server `OnClothingUpdated` branch already runs.

Rejected (b), a Kotlin hook after `receivePlayerConnect`: the same moment as the first tick, for a Java hook plus a Lua
call from Java.

## Part fluids (single player and server)

Facts (bytecode):

- An item saves every component it has, also one added at runtime (`InventoryItem.save` → `saveEntity`), and loading
  recreates the saved ones (`GameEntity.loadEntity` → `ComponentType.CreateComponent`). So an added fluid container
  survives save, load and rejoin.
- The item packet `AddInventoryItemToContainer` carries the full item, components included. A later
  `item:sendSyncEntity(nil)` would also add a missing component on the client (`receiveSyncEntity`), but it goes on
  ordering channel 0 and the item add on channel 1, so it can arrive first and be dropped. So the container must be on
  the new item **before** SCTD sends it.
- `FluidContainer:copy()` clones capacity and contents; `GameEntityFactory.AddComponent(item, true, c)` replaces an
  existing component of that type.

SCTD makes the new item with `instanceItem` inside `SCTD_Transmog_HideItem_Server`, `SCTD_Transmog_Apply` and
`SCTD_Transmog_Restore`, and sends it later in the same call. Every caller (SCTD's server commands, its menu in single
player, auto-hide `SCTD_AutoHide_Reconcile`, `SCTD_Transmog_ShowItem`, the content filter) looks these up as globals at
call time, and none keeps a local copy. So:

1. Replace the three globals with wrappers. A wrapper on a client (`isClient()`), or for a call that does not replace
   the item (`Apply` with `invertStats`; `Restore` of an `inverted` record or one without `baseFullType`), or when the
   item has no fluid container, calls SCTD's function unchanged.
2. Otherwise it pushes a context `{old, source = old:getFluidContainer(), kind}` and, for the call, sets the global
   `instanceItem` to an interceptor; the previous value is put back afterwards, also on error. The interceptor calls the
   real `instanceItem`; for the first non-nil item of the innermost context it applies the rule below. Nested wrapped
   calls (SCTD's wear sync fires `OnClothingUpdated`, whose server branch runs auto-hide) get their own context.
3. Rule: Hide and Apply: always `AddComponent(new, true, source:copy())`. Restore: only when the new item already has a
   container from its script (R5: a type that never had one gets none).
4. Self-check after SCTD returns `true, newItem`: `newItem` must be the item the interceptor handled. Otherwise (SCTD
   changed how it builds the item) → error, `disabled`.

Copy, not move: the original keeps its own container until SCTD drops it, so when SCTD gives up after making the item
(its `CanWear` check), nothing is lost. SCTD's own errors pass through unchanged; an error inside the interceptor is
recorded (SCTD calls `instanceItem` inside its own `pcall`) and disables the feature after the call returns.

Gaps left as they are:

- `SCTD_Transmog_RefitInvis_Server` (local) rebuilds a hidden placeholder when SCTD's variant table names a different
  placeholder, without the container. It runs only after such an SCTD update; showing the item still gives the base
  item an empty container from its script.
- Placeholders already made before this fix have no container; nothing adds one back (see the review question). Wearing
  one and choosing Show gives the original item back with an empty container.

## Disable

Puts back SCTD's three functions if they are still the wrappers, `instanceItem` if it is still the interceptor, and
removes the `OnTick` handler. SCTD then runs as without this fix.

## Plumbing

Module `sctd-transmog-fix/` (`pz-bundled-feature`), `settings.gradle.kts`, the feature list and `luaTargeted` in
`dont39-all-in-one/build.gradle.kts`, a Workshop section, All-in-One version bump. Not in `loadModAfter` (as the flush
fix). Rollback: drop the module from the feature list.
