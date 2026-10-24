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

Implemented deviations (phase A, 2026-10-24):

- The client reads the trailer at `ConnectToServerState.exit` HEAD from the join's `connectionDetails` copy, not at
  `ConnectionDetails.parse`: the server's mod list (and so this mod's hooks) loads in `receiveServerOptions` after
  `parse`, so a client that enables the mod only through the server's list would never activate on its first join.
- The trailer coexists with mod-sync's: ours is written before a trailing mod-sync record, and our reader steps over
  one written after ours.
- Item keys are (container key, item id); a moved item shows as gone in the old container and changed in the new.
- The version is one frame number per SyncFrame; snapshots carry the peer's latest frame number.
- Floor containers are not tracked (built as throwaways from square objects on both sides). Item names enter the
  digest only when `isCustomName` (each side translates names). Mod data hashes only pairs the game would send.
- Malformed or out-of-scope client input stops checks for that connection or refuses the request; it never
  disables the server side. Snapshots and digests come only from a container the game still addresses under the
  same key, so an out-of-range index never becomes an emptied container on the client.
- Per-connection entries also go 3000 frames after the last frame that covered them (check, 2026-10-24): the
  player's own items never leave relevance, so entries of items that left the inventory would only grow.
- The digest leaves out food stats whose getters scale them by age, heat, freezing or cooking (thirst, unhappiness,
  boredom, stress, food sickness; `ItemStats` stores the server's scaled value as the client's base) and clothing
  weight (follows wetness) (check, 2026-10-24).
- The receive hooks (server `GameServer.addIncoming`, client `ZomboidNetData.read`) run while the gate is shut too
  and swallow the mod's id: a peer that failed still gets packets until the other side stops, which the game would
  log or count toward a packet-anticheat kick.

Implemented deviations (phase B, client, 2026-10-24):

- Open check: the game's Lua calls `requestServerItemsForContainer` only for a container this client never explored,
  and the server answers that request only for one it never explored either. So its HEAD hook checks the container
  3 s later, once the game's own fill is in; containers already explored are reported by a client Lua file
  (`Dont39SyncGuard_OpenCheck.lua`, the loot window's shown container) through a Lua global from `javaPkgName`.
- Unresolved frame entries are retried every 500 ms, not each frame: the game's lookup logs an error for a missing
  bag or vehicle on every try.
- A `gone` item entry the client still has is checked as its whole container (an `OpenCheck`): the server cannot
  snapshot an item it no longer has there. A `gone` container entry is left to its parent's entry.
- A game call that throws while a snapshot is applied fails that key only (logged with the stack, not asked again);
  the session goes on, as the game itself drops a packet whose handler throws.
- `Bye` (C→S, no body): the client stops for the session (a failure, or leaving); the server stops sending to it.

Implemented deviations (hardening, 2026-10-24):

- Less access widening (`research/widening-during-weave.md`: in the first in-game run, members of classes first
  loaded while ZombieBuddy wove a hook were not widened). Container keys are read from the bytes the public
  `ContainerID.write` produces (`protocol/ContainerWire`, unit-tested on byte fixtures); the game's `ContainerType`
  order is checked at the main entrypoint. No main-loop queue is widened any more (see Own packets).
- Widening that stays, as no public route exists: `UdpConnection.bufferLock` (nothing public tells whether this
  thread is inside a game send); `ConnectToServerState.connectionDetails` (private, no getter; the public
  `ConnectionDetails.parse` runs before a mod enabled by the server's list is loaded); the in-scope packets'
  `containerId` / `itemId` / `id` and `AddInventoryItemToContainerPacket.items` (private or package-private, no
  getters; reading them from the written bytes would repeat each packet's layout).
- `ConnectToServerState` is still woven on the server too (no per-side hooks), which can stop other mods' widening of
  its nest members until Zabric loads a target's member types before ZombieBuddy weaves it.

Implemented deviations (first in-game run, 2026-10-24):

- The client reads the trailer at `GameClient.sendPlayerConnect` RETURN from `ConnectToServerState.instance` (public,
  set by each join's `enter()`), not at `ConnectToServerState.exit`: `MainScreenState.update` calls `exit` only while
  the main screen still updates, which stops once the world loads, so the join never activated. `ConnectToServerState`
  is no longer woven at all, which also stops Sync Guard from disturbing other mods' widening of its nested classes.

## Own packets

- Carried as raw packets with a reserved id outside the `PacketTypes` enum, sent with
  `UdpConnection.startPacket` / `endPacket(priority, RELIABLE_ORDERED, channel)` on the shared channel (R2).
- Settled by S1 (`research/spike-s1-s5.md`): header 134 + reserved short id `0x5347`.
- Client: a cancellable HEAD hook on `ZomboidNetData.read` (network thread, `always`) does what `read` does for our
  id minus its unknown-id error line and marks the instance (identity set; the pool reuses instances, so the mark is
  removed at dispatch and cleared per join and on failure). Vanilla `GameClient.addIncoming` then queues it with
  `time` set, in order with the in-scope packets; the `mainLoopDealWithNetData` HEAD hook dispatches marked ones and
  vanilla drops the typeless entry. While the gate is shut the hook only swallows the id.
- Server: a cancellable HEAD hook on `GameServer.addIncoming` (`always`) takes our id before vanilla routes an unknown
  type to `AntiCheat.PacketType.act`, onto the mod's own queue. A HEAD hook on `PoolCaps.updateServerCaps`, which
  `GameServer.main` calls first in every pass of its loop (ticking or not) and nothing else calls, runs what arrived
  before the previous pass started: vanilla ran every packet that arrived before those during that pass, so a
  `Hello` is handled after its client's `PlayerConnect` (sent first, on the same reliable-ordered channel).
- Send with `startPacket()` / `endPacket(1, 3, 1)` (HIGH, RELIABLE_ORDERED, channel 1), only from our own handlers or
  the end-of-tick hook, never inside a vanilla send (`bufferLock` is reentrant and a nested send corrupts the vanilla
  packet). `Hello` goes from a RETURN hook on `GameClient.sendPlayerConnect`.
- Messages: `Hello` (C→S), `SyncFrame` (S→C), `SnapshotRequest` (C→S, keys), `Snapshot` (S→C), `OpenCheck`
  (C→S, key + digest), `Bye` (C→S). Each starts with a protocol version byte.

## Channel change (R2)

At server start, and on an active client, set `packetReliability = RELIABLE_ORDERED` and the ordering channel to the
shared channel (channel 1, where container add/remove already are) for `SyncItemFields`, `SyncItemModData`,
`SyncHandWeaponFields`, `ItemStats`. `packetPriority`, `packetReliability`, `orderingChannel` are public non-final
fields read on every `PacketType.send` (S5): reliability 3 for types 147-149, channel 1 for `ItemStats`. Reliability
and channel are chosen by the sender, so a vanilla peer receives them unchanged. Cost: under loss, field updates wait
behind earlier channel-1 messages.

## Keys and digests (R1, R4)

- Keys are our own value types, not `ContainerID` (its `equals` ignores x,y,z for player inventories while
  `hashCode` includes them). On the wire a container is written as vanilla's `ContainerID` bytes, so the client
  resolves it with `parse` → `findObject` → `getContainer()`. Floor containers and out-of-range object indexes
  resolve to throwaway containers: check-only, never repaired; bags inside world or corpse containers have no
  address and are skipped (S3).
- Container key, computed from the parent the way `ContainerID.set` does: player inventory (online id), bag in a
  player inventory (owner id + bag item id), object container (x, y, z, object index, container index), vehicle
  part (vehicle id, part index), corpse (x, y, z, static-moving index), floor/world item (x, y, z, world item id).
- Item key: `InventoryItem.id`.
- Container digest: 64-bit hash over the sorted `(item id, full type)` pairs plus each item's item digest.
- Item digest: 64-bit hash over the fields vanilla's `SyncItemFields`/`ItemStats`/`SyncItemModData` write, minus any
  field the client simulates locally. Static part settled by S2 (`research/spike-s2-s4.md`, "Decisions"): excluded
  heat, age, freezingTime, frozen, fertilized, cookingTime, wetCooldown, clothing wetness/dirtiness, attached-slot
  fields, taintedWater and save-only fields; about 14 fields (condition, headCondition, uses, ammo/chamber, blood and
  visuals, weight, pages, sharpness, item mod data, cooked/burnt, isWet, food name) stay in only if the in-game
  per-field diff run (debug option, AC6) shows no false mismatches. The final list is written in the code with the
  reason for each exclusion. Known false-mismatch sources to watch: a field changed after its send in the same tick
  (`Food.update` L379/L387), client-applied fields in flight, client-side replacements that get a new random id,
  mods writing item mod data on the client.
- One implementation in `protocol`, used by both sides, so both hash the same fields the same way.

## Server flow (R1, R3, R4, R6, R7)

- Touch recording (S3, `research/spike-s2-s4.md`): the packet object is not reachable at `PacketType.send`, and the
  Remove relay calls `write`/`send` itself. So RETURN hooks on each in-scope packet class's `write` remember the
  packet, and `PacketType.send` HEAD pairs it with the connection and adds (connection, key) to this tick's touched
  set. Our own snapshot writes reuse those classes and are excluded by a guard. Client-originated packets:
  `onServerPacket` HEAD + FINALLY; if the reader moved, vanilla parsed it and `connection.getPacket(type)` gives the
  target (accepted, rejected or thrown); if not, the packet was unauthorized and only its `ContainerID` is re-parsed
  (a full `SyncItemFields`/`SyncItemModData` parse mutates the item).
- Per-connection state: `Map<Connection, Map<Key, Long version>>`, version bumped when touched. Entries whose
  position is no longer `isRelevantTo` the connection are swept once per second; player-inventory entries stay
  while the player is connected.
- End of tick: HEAD hook on `ServerMap.postupdate()V`, called only by `GameServer.main` (offset 4470), once per
  world tick after every in-tick send. Zabric has no INVOKE hooks; `VehicleManager.serverUpdate` also runs inside
  `IsoWorld.updateInternal`, and `ObjectIDManager.checkForSaveDataFile` is also called by `ServerMap.QueuedQuit`
  (verified 2026-10-24). Compute each touched key's digest once (resolve the key on the server; a key
  that no longer resolves becomes a "gone" entry), then write one `SyncFrame` per active connection with its touched
  keys. Clear the touched set.
- Snapshots: requests queue per connection; each tick sends at most N snapshots or B bytes per connection (initial
  N = 8, B = 64 KiB, tuned in AC7). A snapshot holds the key, the version, the digest, and the items serialized with
  vanilla's own packet classes: the item list as `AddInventoryItemToContainer` writes it, and per item the
  `SyncItemFields`, `SyncItemModData`, `ItemStats` and `SyncHandWeaponFields` payloads (the last two carry food/
  drainable stats and weapon state, S2). Reusing vanilla's classes keeps field semantics identical.
- Open check: compare the client's digest with the server's; mismatch → queue a snapshot.

## Client flow (R5, R6, R7, R9)

- `SyncFrame`: for each entry, resolve the key; unresolved → keep pending for up to 10 s and retry each frame, then
  log and drop. Resolved → compute the digest; equal → done; different → `SnapshotRequest` once (pending set). Entries
  with a version at or below the last applied snapshot for that key are ignored.
- `Snapshot`, reconciled in place on the main thread:
  - ids in both: apply the snapshot's per-item payloads to the existing instance with vanilla's packet classes
    (`parse` + `processClient`);
  - ids only in the snapshot: add the same way `AddInventoryItemToContainer` does (its `parse` + `processClient`);
  - ids only on the client (S4): if worn, `WornItems.remove`, `onWornItemsChanged`, `resetModelNextFrame`, then the
    Lua `OnClothingUpdated` event, in `SyncClothingPacket.processClient`'s order; then `removeItemWithID` (which
    clears hands through `removeFromHands`), `setExplored`, and the vehicle-part content amount. Hotbar items are
    left to `ISHotbar:update`. Nothing here sends a request to the server (`removeWornItem` and
    `removeAttachedItem` would);
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
