# On `myserver1`'s mod list, item transfers bypass the vanilla packets

Found in debug runs `func-dbg1` / `func-dbg2` on the local `sgtest` server (2026-10-24), server started with
`-Dsyncguard.debugTouches=true` (counts every packet type the server sends, per 30 s).

## Evidence

- While the test driver took 60 items out of prison containers and moved 39 (`ISInventoryTransferUtil.
  newInventoryTransferAction`, the vanilla factory the loot UI uses), the server sent no `ItemTransaction`, no
  `AddInventoryItemToContainer` and no `RemoveInventoryItemFromContainer`. It sent 119-207 `ClientCommand` and 28-68
  `ObjectChange` per 30 s. Sync Guard recorded 5 touches in 2 minutes, all correct.
- "Multiplayer Weight Limit Breaker" (`vac_mod_b42_13`, in `myserver1`'s list; client log line `FastTransfer true`)
  replaces the global `createItemTransaction` (`client/vac_mod_b42_13_client_default.lua:236-258`): the client sends
  `sendClientCommand('VacMod13', 'CreateTransaction', ...)` (`:222`); the server's Lua moves the item itself
  (`server/vac_mod_b42_13_server_default.lua:224` `src_container:Remove(item)`, `:245` `dest_container:AddItem(item)`)
  and announces it with the 3-argument `sendServerCommand('VacMod13', 'RemoveItem' | 'AddItem', {...})` (`:225`,
  `:246`), which goes to every client; client Lua applies it with `container:AddItem` (`client/...:57, 94`).
- `sendServerCommand` is a `ClientCommand` packet: RELIABLE, unordered, channel 0 (`research/vanilla-sync.md`). These
  per-item deltas carry no version, are broadcast to all players, and can overtake each other.
- RPGInventory additionally takes over transfers into or out of nested containers
  (`client/RPGInventory/NestedTransfer.lua:105-116`, server `NestedTransferServer.lua:71-103`), also via
  `sendServerCommand`.

## Consequence

On this server, container contents change through mod Lua deltas that Sync Guard's send-point recording never sees.
Sync Guard still checks what goes through vanilla packets (item fields, stats, mod data, the vanilla paths other
actions use) and, when a container is opened, compares digests (R7). But the main incremental path on this server, the
one most likely behind the reported losses, is the mod's own unversioned broadcast.

## Other findings in these runs

- Food items (`Base.BouillonCube`): digest fields `food` and `spices` differ between client and server and still
  differ after a snapshot, so the item is reported unrepairable. Those fields need to leave the digest (S2 tuning).
- `openChecks=0`: the driver moves items with timed actions and never opens the loot window, which is where the
  open check hooks in. A test of R7 needs the driver to open the window.
