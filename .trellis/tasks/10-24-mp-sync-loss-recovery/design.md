# Design: Sync Guard

Requirements and decisions: `prd.md`. Evidence: `research/vanilla-sync.md`, `research/server-change-flow.md`.

## Module and packaging (D4)

- New Gradle module `sync-guard`, registered in `settings.gradle.kts`. Mod id `Dont39SyncGuard`, name "Sync Guard",
  package `com.dont39.pz.syncguard` (proposed; the user may rename before release).
- Java, built like `loading-progress/build.gradle.kts`: `pz-java`, `pz-mod`, `pz-bootstrap`, `pz-widening`,
  `pz-junit`, `pz-switches`; hooks through `zabric-processor`; `checkJdkGlue`; widening list in
  `META-INF/zabric`. Differences: the jar stays in `media/java` (client and server), the manifest sets
  `Zabric-Main` only (no preload, no pre-launch hooks), and `smokeTest` runs as for Loading Progress.
- `mod/42/mod.info`: `require=\ZombieBuddy,\Zabric`, `loadModAfter=\Zabric`, `javaJarFile=media/java/Dont39SyncGuard.jar`,
  `javaPkgName=com.dont39.pz.syncguard`; Workshop text and poster under `mod/workshop/`; translations EN and CN
  under `src/main/lua/shared/Translate/`.
- Package layout, layered like `mod-sync`: `protocol` (wire format, keys, digests; used by both), `server`, `client`;
  neither of the last two uses the other.

## Activation (R8)

- Server: at `ConnectionDetails.write` RETURN, append a versioned trailer (protocol version 1), as
  `ModSyncServer.appendTrailer` does with `protocol/Trailer.kt`. Dedicated servers only; a co-op host logs that it
  is off.
- Client: reads the trailer at `ConnectionDetails.parse` HEAD (as `ModSyncClient.joinStarts`). No trailer → inactive
  for this join, one log line. Trailer present → after `PlayerConnect` completes, send `Hello(protocol)`.
- Server marks a connection active on `Hello`; only active connections get sync frames and snapshots, and only
  their packets are recorded. Connection close drops all its state.

## Own packets

- Carried as raw packets with a reserved id outside the `PacketTypes` enum, sent with
  `UdpConnection.startPacket` / `endPacket(priority, RELIABLE_ORDERED, channel)` on the shared channel (R2).
- Received at the point where vanilla queues incoming data, so they are processed on the main thread in arrival
  order with vanilla packets (`GameClient.addIncoming` / `GameServer.addIncoming`, then
  `mainLoopDealWithNetData`). Spike S1 settles the exact hooks: whether `ZomboidNetData` keeps an unknown id, and how
  to keep the server's `AntiCheat.PacketType.act` from firing for the reserved id.
- Messages: `Hello` (C→S), `SyncFrame` (S→C), `SnapshotRequest` (C→S, keys), `Snapshot` (S→C), `OpenCheck`
  (C→S, key + digest). Each starts with a protocol version byte.

## Channel change (R2)

At server start, and on an active client, set `packetReliability = RELIABLE_ORDERED` and the ordering channel to the
shared channel (channel 1, where container add/remove already are) for `SyncItemFields`, `SyncItemModData`,
`SyncHandWeaponFields`, `ItemStats`. Fields of the `PacketTypes$PacketType` constants; widened if final. Reliability
and channel are chosen by the sender, so a vanilla peer receives them unchanged. Cost: under loss, field updates wait
behind earlier channel-1 messages.

## Keys and digests (R1, R4)

- Container key, computed from the parent the way `ContainerID.set` does: player inventory (online id), bag in a
  player inventory (owner id + bag item id), object container (x, y, z, object index, container index), vehicle
  part (vehicle id, part index), corpse (x, y, z, static-moving index), floor/world item (x, y, z, world item id).
- Item key: `InventoryItem.id`.
- Container digest: 64-bit hash over the sorted `(item id, full type)` pairs plus each item's item digest.
- Item digest: 64-bit hash over the fields vanilla's `SyncItemFields`/`ItemStats`/`SyncItemModData` write, minus any
  field the client simulates locally (never `age`, `heat`, `itemHeat`, `freezingTime`). The field list is fixed by
  spike S2, which logs per-field differences in normal play until AC6 holds, and is written down in the code with the
  reason for each exclusion.
- One implementation in `protocol`, used by both sides, so both hash the same fields the same way.

## Server flow (R1, R3, R4, R6, R7)

- Touch recording: hook `PacketType.send` HEAD (one choke point for every game packet) and keep the in-scope types;
  resolve the packet's container/item from the packet object being sent (spike S3 confirms the packet object is
  reachable there, else per-type `write` RETURN hooks), and add (connection, key) to this tick's touched set. Hook
  `PacketType.onServerPacket` RETURN for in-scope types from clients to touch (sender, key) whether or not vanilla
  accepted it.
- Per-connection state: `Map<Connection, Map<Key, Long version>>`, version bumped when touched. Entries whose
  position is no longer `isRelevantTo` the connection are swept once per second; player-inventory entries stay
  while the player is connected.
- End of tick: hook in `GameServer.main` after `VehicleManager.serverUpdate`, before `PlayerDownloadServer.update`
  (an INVOKE hook on one of those calls). Compute each touched key's digest once (resolve the key on the server; a key
  that no longer resolves becomes a "gone" entry), then write one `SyncFrame` per active connection with its touched
  keys. Clear the touched set.
- Snapshots: requests queue per connection; each tick sends at most N snapshots or B bytes per connection (initial
  N = 8, B = 64 KiB, tuned in AC7). A snapshot holds the key, the version, the digest, and the items serialized with
  vanilla's own writers: the item list as `AddInventoryItemToContainer` writes it, and per item the
  `SyncItemFields` and `SyncItemModData` payloads. Reusing vanilla's writers keeps field semantics identical.
- Open check: compare the client's digest with the server's; mismatch → queue a snapshot.

## Client flow (R5, R6, R7, R9)

- `SyncFrame`: for each entry, resolve the key; unresolved → keep pending for up to 10 s and retry each frame, then
  log and drop. Resolved → compute the digest; equal → done; different → `SnapshotRequest` once (pending set). Entries
  with a version at or below the last applied snapshot for that key are ignored.
- `Snapshot`, reconciled in place on the main thread:
  - ids in both: apply the snapshot's `SyncItemFields`/`SyncItemModData` payload to the existing instance with
    vanilla's readers;
  - ids only in the snapshot: add with vanilla's item reader, as `AddInventoryItemToContainer.processClient` does;
  - ids only on the client: if held, unequip through vanilla's client path; if worn, remove from worn items through
    vanilla's path (spike S4 finds the exact calls vanilla runs when a server Remove hits an equipped/worn item);
    then remove from the container;
  - recompute the digest; still different → report unrepairable for this key, stop requesting it this session.
- Open check: hook `ItemContainer.requestServerItemsForContainer` HEAD (called by the Lua that opens containers) and
  send `OpenCheck` with the key and digest.
- Notice: when a snapshot changed anything, show `HaloTextHelper` text over the local player (translated), at most
  once per 10 s; later repairs in that window are counted into the next notice.

## Logging and counters (R9)

Lines per `.trellis/spec/mod/logging-guidelines.md`, prefix `[Dont39SyncGuard]`. A repair logs the key, item ids
added/removed/changed and the outcome. Nothing logs per frame. Counters per side (and per connection on the
server): checks, mismatches, snapshots sent/applied, repaired, unrepairable; summary every 10 minutes and at
disconnect.

## Errors (`.trellis/spec/mod/error-handling.md`)

A hook target or widened member that does not match disables the mod with an error at load. A runtime exception in
the mod's code disables the mod for the session with the stack, never vanilla's handling: vanilla packet handlers
run unchanged and outside the mod's catch.

## Compatibility and rollback

- Vanilla packet bytes are unchanged; only the four packet types' reliability/channel change, and only when sent by
  a peer with the mod active.
- Removing the mod from a server returns everything to vanilla at the next restart; nothing is saved to the world.

## Spikes (first steps of implementation)

- S1: raw packet id path on both sides (queueing, anticheat).
- S2: item digest field set (normal-play diff logging).
- S3: reaching the packet object and its target at `PacketType.send`.
- S4: vanilla's client calls for removing a held or worn item.
- S5: confirm the `PacketType` reliability/channel fields and that changing them at runtime takes effect.

Each spike's result goes into `research/` before the code that depends on it.
