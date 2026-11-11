# Design: Server Reborn on game 42.22

Evidence and finding numbers (F1-F4): [research/r2-assumptions-42.22.md](research/r2-assumptions-42.22.md).
Mechanism of the sync part, unchanged here: the archived tasks `10-24-mp-sync-loss-recovery` and `10-26-server-reborn`.

## What changes

| # | Finding | Change | Files |
|---|---|---|---|
| 1 | F1 | Player-owned containers are addressed and found by the mod itself, by online id, on both sides | `Containers.kt`, `ContainerWire.kt`, their tests |
| 2 | F2 | The pinned container may replace `SyncItemFieldsPacket.containerId` | `server-reborn.classtweaker` |
| 3 | F4 | The debug drop reads the field packets' player as the game now writes it | `DeltaDrop.kt` |
| 4 | F3, R6 | A `map` digest group for a map item's annotations | `ItemDigests.kt` |
| 5 | R3 | `Protocol.VERSION` 2 to 3 | `Protocol.kt` |
| 6 | R5, F4 | Mod version 0.3.0, `versionMin=42.22.0`, docs and comments that name 42.21 facts | `build.gradle.kts`, `mod.info`, `README.md`, `workshop.txt`, comments |
| 7 | R7 | Added during the in-game runs: the field diff lists the client's values of an item that differs only from the server's own item, so the field can be named ([research/in-game.md](research/in-game.md), "Items that stay different") | `FieldDiff.kt`, `ItemDigests.kt`, `Snapshots.kt` |

Nothing else changes: frames, snapshots, reconcile, the ledger, the tick rate and every hook keep their code.

Open after the in-game runs, the user's decision: whether a snapshot leaves alone the items whose digest already
matches the server's ([research/in-game.md](research/in-game.md), "Map annotations"). Until then `Reconcile` is unchanged.

## 1. Player-owned containers (F1)

### Rule

One byte form for a player inventory and for a bag in one, in every message of the mod and in both directions: the
form the server's `ContainerID.write` produces in 42.22.

```
player inventory: [byte 7][short online id]
bag in one:       [byte 8][short online id][int bag item id]
```

`ContainerKey.player` / `bag` keep `owner` = online id. All other kinds (objects, vehicles, corpses, bags on the
floor, nested addresses) keep the game's bytes, which mean the same on both sides.

### Writing an address (`Containers.current`)

For a player-owned container the mod builds key and bytes itself and does not call `ContainerID.set`, which on a
client writes the local index and logs an error for a remote player. Player-owned, in the order of the game's `set`:

1. the container's parent is an `IsoPlayer`: player inventory of that player;
2. else its containing item's outermost container has an `IsoPlayer` parent: bag of that player, by the containing
   item's id.

A player without an online id yet (below 0) has no address. Everything else goes through `ContainerID.set` as today.
`ContainerWire` gets the two encoders (JDK only, unit-tested).

### Finding a container (`Containers.find`)

For a `PLAYER` or `BAG` key the mod looks the player up by online id in `GameServer.IDToPlayerMap` on the server and
`GameClient.IDToPlayerMap` on a client, then takes the inventory, or the bag by `getItemWithIDRecursiv` as the game's
`findObject` does. No player or no such bag: not there (null), as the game's lookup answered before. `ContainerID.parse`
is not used for these two kinds: on the server it reads the id as an index on the connection. Other kinds keep the
game's `parse`.

`parentThere`'s `BAG` branch and `ContainerWire.ownerInventory` go: the lookup above is the check they made.

### Addresses taken from a game packet

- Server, a packet it sent or read (`Containers.of`): the packet's `ContainerID` holds the online id in both cases
  (`set` on the server; the server's `parse` replaces the index with it), so `ContainerID.write` gives the rule's
  bytes. This path stays on the game's bytes on purpose: `ContainerWire.decode` of them is the run-time check that the
  game still writes the layout the mod reads.
- Client, `DeltaDrop.target`: the id was parsed from the server's bytes, online id.

### Rejected

- Keep `set` / `parse` and translate index and online id per direction: two byte forms of one key, and every message
  handler must know its direction.
- Patch the id after a client's `ContainerID.set`: still needs the server's own lookup, and keeps the game's error
  line for a remote player's container.

## 2. Pinned container (F2)

`mutable field zombie/network/packets/SyncItemFieldsPacket containerId` in the classtweaker, as
`AddInventoryItemToContainerPacket` has. `PinnedContainerID` keeps its code. No reflection (R1): the widener removes
`final` when the class loads, and the build compiles against the widened jar.

## 3. Debug drop (F4)

`DeltaDrop.target` skips the field packets' player with `ConnectionPlayer().parse`, the class the packets use.

## 4. Map item annotations (F3)

Decided (PRD D3): the digest covers them.

- A `map` group in `ItemDigests.write` for a `MapItem`, hashing what `SyncItemFields` carries: the bytes of
  `MapItem.saveData` (map id, then the user-made symbols). The scratch buffer pattern is `fluids`'.
- A new `ItemDigests.Checked` entry gates it, so the in-game field diff run can drop it (section 7). Its open point:
  the symbols' save starts with a font table whose equality on both sides was not settled from the bytecode.
- The field diff shows it as `map.saved` (length and hash), like `fluids.saved`.

Why cover it: a snapshot's `SyncItemFields` payload carries the server's annotations and the client's `parse` applies
them, whatever the digest covers. A snapshot written before the server got a client's edit puts the client back to the
old annotations; only a digest that covers them finds that at the next frame.

## 5. What both sides agree on, and `Protocol.VERSION`

| Shared | Changed | |
|---|---|---|
| A key's bytes (`WireKey`, `ContainerWire`) | yes | player kinds lose the player index byte and are always the online id |
| Digest fields (`ItemDigests`) | yes | the `map` group (section 4) |
| Messages (`Codec`), the trailer, snapshot layout (`SnapshotWriter`) | no | |
| Snapshot payloads | the game's, both sides 42.22 | `versionMin` keeps the mod off 42.21 |

`Protocol.VERSION` goes from 2 to 3, once, for all of it. A 0.2.0 client loads on 42.22 (its one broken statement
runs only for a nested bag's snapshot), so it can meet a 0.3.0 server: the join's trailer check
(`ClientSync.playerConnectSent`) leaves it inactive with the "update Server Reborn on the side that is older" line,
and the server never checks a client that sent no Hello. Unchanged code.

## 6. A mismatch still turns the mod off, with the reason (R4)

No new fallback is added. The existing checks cover the new code:

- Start: the widener's report (now with the new `mutable` line), the hook targets, `PacketScope.resolve`,
  `Containers.check`. Any failure: off, with the reason.
- Run time: bytes `ContainerWire` cannot read throw inside a hook, and the gate turns the mod off and undoes it. The
  42.21 byte form now fails this way ("bytes after a container id" / "ends early"), covered by a unit test.
- The own lookup returns "not there" only where the game's lookup did (a player or bag that is gone). It does not
  catch anything.

## 7. Digest fields the run decides

`ItemDigests.Checked` goes in with every entry it has plus the map one. If the field diff run on 42.22 shows a field
differing in normal play (the map annotations, `AMMO`, `CONDITION`, `BLOOD`, `SHARPNESS` are the candidates, see the
research), that field is dropped as the enum's contract says, inside this task and under the same `VERSION` 3. Each
drop is reported with its log lines.

## Tests

- `ContainerWireTest`: the 2-byte player forms decode; the encoders round-trip; a 42.21 form is refused;
  `ownerInventory`'s cases go with it.
- In the game: [implement.md](implement.md). F1 needs a checked player whose online id is not 0.

## Specs and docs

`server-reborn/README.md` and `workshop.txt` (EN and CN): 42.22. Spec pass in Phase 3.3; expected: nothing, unless
the in-game runs teach a rule.
