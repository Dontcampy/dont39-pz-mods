# Detect and recover lost incremental multiplayer sync

## Goal

Project Zomboid's own multiplayer data sync (Build 42, vanilla `zombie.network`) sends incremental updates without
versioning. When an update is lost, neither side notices, nothing is resent, and the client stays out of sync with
the server until a relog or chunk reload. A standalone mod makes such losses for container contents and item state
detectable, repairs them from the server's state, and tells the player and the log when it did.

## Background

Source: user report (2026-10-24): "数据同步没有做版本控制，增量型同步有时候丢包了也无法发现并重传", clarified as the
game's own multiplayer network data sync (not mod-sync, the tunnel, or SCTD's TransmogSync).

Evidence is bytecode via `javap` (no decompiler on this machine); client and dedicated-server jars are
byte-identical. Detail: `research/vanilla-sync.md` (packets, loss modes, hook points) and
`research/server-change-flow.md` (who changes what, fan-out, identity, threading).

### How updates get lost

- No state-sync packet carries a version, revision, sequence or checksum.
- Reliability comes from `@PacketSetting` or `PacketType` constructor args: 200 RELIABLE, 83 RELIABLE_ORDERED,
  11 UNRELIABLE, 4 UNRELIABLE_SEQUENCED. RakNet resends reliable packets until acked or disconnect, so **wire loss is
  not the main cause**.
- Real loss modes, all silent or log-only:
  - Receiver discards: client `isConsistent` failures go to `EventManager.report`, a no-op on clients
    (`PacketType.onClientPacket L1022-1024`, verified); many handlers return when the container/square/item is
    missing; the server reject callback `INetworkPacket.sync` is empty.
  - Ordering: `SyncItemFields`/`SyncItemModData`/`SyncHandWeaponFields` are unordered RELIABLE; `ItemStats` uses
    channel 0 while container add/remove use channel 1, so a field update can overtake its item's add and be dropped
    (inferred).
  - Index addressing: `ContainerID.findObject L501-513` makes a throwaway dummy container for an out-of-range object
    index.
  - Chunk-download race: a change can reach the client before the snapshotted chunk is placed, and is dropped
    (`PlayerDownloadServer.update L512-516`; inferred).
  - Client-applied changes the server rejects stay on the client: client `syncItemFields`, `ItemStats`, admin-only
    `SyncItemDelete` get no reply on reject.
  - Changes the server never sends: food age, heat, freezing; world-container item fields via `syncItemFields`; bags
    inside world containers; fire `removeAllItems`; thump-to-floor.
- Existing repair: none for explored containers (`RequestItemsForContainer` skips them, `ItemContainer.requestSync`
  returns on every branch, verified; `sendItemsInContainer` only adds).

### How the server changes and sends

- Transfers and timed actions with a server `complete()` (239 action files) are server-authoritative: the client
  moves items only when the server's Add/Remove arrive (`ISInventoryTransferAction.lua:501-503, 854-856`, verified).
- Fan-out: player inventories (and bags in them) go to the owner only; world/vehicle/corpse/floor containers to every
  relevant, fully connected client; relays of client-originated Remove, `SyncItemFields`, `ItemStats` skip the
  originator.
- Identity: containers have no stable server id (`ItemContainer.id` unsaved; instances recreated on chunk reload);
  they are addressed by parent (square + object + container index, vehicle + part, corpse, player, bag item id).
  `InventoryItem.id` is random, persisted, stable.
- Threading and timing (verified): everything in scope runs on the server main thread. The world update runs at
  10 Hz (`GameServer.main`: `UpdateLimit(100)`, `setLockFPS(10)`, offsets 2695-2709); between world ticks the loop
  drains the net queues roughly every 5 ms (offsets 3060-3554), and those packet handlers mutate and relay inline.
- Send path (verified): `sendToRelativeClients`/`sendToClients` re-run `write` per recipient into that connection's
  locked buffer on the main thread (`INetworkPacket.sendToConnection` → `UdpConnection.startPacket` →
  `flipSendUnlock` → `RakNetPeerInterface.Send`).

### Reference design: m5-universe-service `synchronizeMap`

Reviewed at the user's request (`E:/Dev/Project/pz/m5-universe-service/src/main/kotlin/m5/service/universe/gameplay/observer/`):
per-observer server state with the version last sent per entity (`component/SynchronizationState.kt:90-589`),
snapshot on entering interest then events since that version (`SerializationManager.kt:197-214`), one response per
observer per frame on one ordered gRPC stream, replay of cached responses on reconnect
(`ResponseSender.processAttachObserver :170-195`), and parallel serialization and sending
(`AsyncResponseSender.kt:45-87`). It never checks that the client applied an update, since one ordered stream makes
that unnecessary; PZ's losses are apply-side, so that check is what this mod adds.

## Decisions

- D1 (user): detection by server-assigned versions with the client actively requesting a resync; refined by D2.
- D2 (user): end-of-tick sync frame. During a world tick and the net-drain iterations before it, the server only
  records which connection was sent (or sent) which in-scope container or item. At the end of each world tick, with
  nothing mutating, it computes each touched object's digest once and sends each connection one sync frame. In-scope
  packets share one ordered channel with the mod's packets. On mismatch the client requests a snapshot. Opening a
  container checks once more.
- D3: vanilla's own sends stay inline; the vanilla tick is not split into phases. Deferring vanilla sends would
  change what `write` serializes and touch every send site, including third-party mods, and would not address
  apply-side discards.
- D4 (user): a standalone mod with its own Workshop item, not an All-in-One feature; Java on `zabric-processor` like
  `loading-progress`; runs on client and dedicated server; a client turns it on only when the server advertised it
  at join (`.trellis/spec/mod/zombiebuddy-patching.md#extending-the-join`, reference `mod-sync`). The user uploads
  to the Workshop.
- D5 (user): the player's own inventory and the bags in it are in scope. Snapshots reconcile in place by item id;
  an extra item that is held or worn is taken off through vanilla's unequip/unwear path first.
- D6 (user): on a repair the player sees a small notice and the log gets a line.
- D7 (user, 2026-10-24, after `research/modded-transfer-path.md`): the server also records a container as touched
  when its contents change (items added or removed), however the change reaches clients, including mods' own Lua
  sync (on `myserver1`, "Multiplayer Weight Limit Breaker" replaces vanilla transfers with unversioned
  `sendServerCommand` broadcasts). Frames carry these entries to every active connection the container is relevant
  to, marked as change-recorded; the client waits a short grace period before comparing them, so a mod's own
  unordered sync packet can land first.
- D8 (user, 2026-10-26): bags nested inside a world, corpse or floor container (which vanilla's `ContainerID` cannot
  address, so vanilla never syncs them) get their own Sync Guard address: the outer container's address plus the chain
  of bag item ids. Generic, not specific to a mod; on `myserver1` RPGInventory moves items into and out of such bags
  through its own `sendServerCommand('RPGInventoryNestedTransfer','moved')` broadcast, limited to players within 50
  tiles, with no gap detection or resend.

## Scope

In scope:

1. Container contents: world containers, vehicle part containers, corpses, and the player's own inventory with its
   bags, as changed by `AddInventoryItemToContainer`, `RemoveInventoryItemFromContainer`,
   `ReplaceInventoryItemInContainer`, `SyncItemDelete`.
2. Per-item fields, stats and mod data, as changed by `SyncItemFields`, `SyncItemModData`, `SyncHandWeaponFields`,
   `ItemStats`.

Out of scope (later tasks): square object lists (`AddItemToMap`, `RemoveItemFromSquare`, `ObjectChange`), object
and square mod data, client rate-limit drops (`MaxPacketsPerSecond`), the server tick rate (evaluated in its own
session), sharing vanilla's per-recipient serialization. A container whose object index has drifted can be detected
but not repaired by this task (its object list is out of scope); it is reported, not retried forever.

## Requirements

- R1. Server keeps per-connection sync state: for each connection, the in-scope containers (keyed by parent) and
  items (keyed by `InventoryItem.id`) it was sent or that it changed, with a per-connection version. Entries go when
  the object leaves that connection's relevance or the connection closes.
- R2. The in-scope packet types are sent RELIABLE_ORDERED on one channel shared with the mod's own packets, so a
  field update cannot overtake its item's add and a sync frame arrives after the deltas it covers.
- R3. The server records (connection, object) as touched when it sends an in-scope packet to a connection, and when
  it processes an in-scope packet from a client (accepted or rejected), for that sender. Sends from net-drain
  iterations count. It also records a container as touched, for every active connection it is relevant to, when its
  contents change on the server's main thread by any route (D7).
- R4. At the end of each world tick the server computes one digest per touched object over the state vanilla syncs
  (excluding fields vanilla never sends, such as `age`, `heat`, `itemHeat`, `freezingTime`) and sends each connection
  with touched objects one sync frame: object key, version, digest.
- R5. The client compares each sync-frame entry with its own state; an entry recorded from a content change is
  compared only after a grace period, against the newest frame entry for that object. On a digest mismatch it requests one snapshot of
  that object (deduplicated while pending). An entry whose object it cannot resolve yet is retried for a bounded
  time, then checked or dropped with a log line.
- R6. A snapshot carries the object's full state with version and digest. The client reconciles in place by item id:
  update existing instances, add missing items, remove extras; an extra item the player holds or wears is taken off
  through vanilla's unequip/unwear path first. The client ignores sync-frame entries older than an applied snapshot.
  The server budgets snapshots per connection per tick. If a snapshot leaves the digest still mismatched, the object
  is reported as unrepairable and not requested again for that session.
- R7. Opening a container sends the client's digest for it; on mismatch the server answers with a snapshot.
- R8. Activation: the server advertises the protocol in a versioned connection-details trailer; a client activates
  only when advertised and then announces itself; the server sends the mod's packets only to clients that announced.
  A vanilla client on a modded server and a modded client on a vanilla server see no mod packets and no errors.
- R10. A bag inside a world, corpse or floor container (at any depth, including bags inside such bags) is addressed
  by its outer container's address and its bag item id chain. Frames, snapshot requests, snapshots and open checks
  work for it as for any other container; content changes in it are recorded (R3) for the peers its outer container
  is relevant to.
- R9. On a repair that changed something on the client, the player sees a short notice (rate-limited, counting
  repairs since the last one), and both sides log the object, what differed and the outcome. Each side keeps
  counters (checks, mismatches, snapshots, repaired, unrepairable) and logs a summary periodically and at disconnect.

## Acceptance Criteria

- [ ] AC1 (R3-R6). With a debug switch that drops one in-scope delta on the client, the divergence is detected at
  the next sync frame and repaired; client and server digests then match. Run for a world container and the
  player's inventory.
- [ ] AC2 (R2). Under induced packet loss, no `SyncItemFields`/`ItemStats` is discarded for "item not found" after
  its item's add.
- [ ] AC3 (R3, R6). A client-side change the server rejects (a non-admin client's Lua `sendRemoveItemFromContainer`)
  is undone on the client at the next sync frame.
- [ ] AC4 (R7). A container emptied by fire on the server is corrected on the client when opened.
- [ ] AC5 (R6, D5). A snapshot that removes an item the player holds and one the player wears unequips them through
  vanilla's path, with no errors in either console.
- [ ] AC6 (R4). Normal play on the modded local server (two clients, transfers, crafting, eating, looting, 30
  minutes) produces no mismatch other than injected ones.
- [ ] AC7 (R4, R6). Load scenario on the modded local server (user, 2026-10-24): a client in god mode at a dense spot
  (Rosewood prison or a military base on the server's map) with many spawned zombies, looting and moving items.
  Run the same script with the mod off and on. The end-of-tick phase stays under 1 ms per tick (p99), snapshots stay
  within the per-tick budget, and the report gives, off vs on: server outbound and inbound bytes/s (and the mod's own
  share), server process CPU, JVM heap used and process memory, and the mod's per-connection state size.
- [ ] AC8 (R8). A vanilla client on a modded server and a modded client on a vanilla server join and play with no
  mod packets sent and no errors; the modded side logs that it is inactive and why.
- [ ] AC9 (R9). An injected loss shows one notice on screen, a log line on both sides, and the counters in the
  summary; ten injected losses within the rate limit show one notice with the count.
- [ ] AC11 (D7). On `myserver1`'s mod list (transfers through "Multiplayer Weight Limit Breaker"), a transfer whose
  mod broadcast the test drops on the client is detected and repaired; normal transfers cause no mismatch after the
  grace period.
- [x] AC12 (D8, R10). On `myserver1`'s list, an RPGInventory nested transfer into and out of a bag inside a world
  container whose `moved` broadcast the test drops on the client is detected and repaired; normal nested transfers
  cause no mismatch after the grace period.
- [ ] AC10 (D4). `./gradlew build` passes; `assembleMod` yields the mod folder with `mod.info`, jar and translations.
  The smoke run uses the local dedicated server with the user's modded server config (`myserver1`, its full mod
  list plus Sync Guard) and a client joining it, to check compatibility with those mods: both sides log Sync Guard
  ready and active, and neither console has errors that the same run without Sync Guard does not have.

All in-game tests run on this machine's local dedicated server (`pzServerDir`), not the Linux test server
(user, 2026-10-24).
