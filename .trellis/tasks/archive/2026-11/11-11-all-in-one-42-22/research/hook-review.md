# Changed game members against the hooks, 2026-11-11

Old jar 42.21 `4a0e9546ec`, new jar 42.22.2 `3d39148846`. `javap -p -c` of each method from both jars, compared after
constant pool indices and offsets were removed. Static evidence; the in-game runs are in [in-game.md](in-game.md).

## network-status: `PacketTypes.packetTypes` is gone (R1)

- 42.21: `PacketTypes.<clinit>` filled the map `packetTypes` from `PacketType.values()`, keyed by `getId()`.
  `getId()` returned the ordinal.
- 42.22: no map. `PacketType.<clinit>` stores `values()` in the private `VALUES`; the public static
  `PacketType.getById(short)` returns `VALUES[id]`, and null for a negative id or one past the end. `getId()` still
  returns the ordinal.
- So `getById(id)?.name ?: "unknown packet type $id"` in `PacketTraffic.name` gives the text the map gave, the
  unknown case included. `getById` is public: no classtweaker entry.

## appearance-override: 2 of 13 hook targets changed (R2)

Both hooks open a render scope at `HEAD` and close it at `FINALLY` (`RenderScope.kt`); what they need is that the
target reads the worn items' visuals between the two.

| Target | Change | Hook | Holds |
|---|---|---|---|
| `AnimatedModel.setCharacter(IsoGameCharacter)` | A character that is no `IHumanVisual` now gets `character` and `setGrappleable` set before the early return, where 42.21 returned without them. The `IHumanVisual` branch is the same code in both jars. `setGrappleable` itself is unchanged (one field write) | `PreviewScope.character` / `characterDone` | Yes. The only read of visuals, `IsoGameCharacter.getItemVisuals(ItemVisuals)`, is still inside the method, after `HEAD`; the new early return leaves through `FINALLY` like the old one |
| `FBORenderCorpses$BodyParams.init(IsoMannequin)` | The loop over the square's objects reads `IsoGridSquare.getObjects()` as a `List` (`size()`, `get(i)`) instead of `PZArrayList.getElements()` | `ChunkCorpseScope.mannequin` / `mannequinDone` | Yes. `IsoMannequin.getItemVisuals(ItemVisuals)` is still the second call of the method and still goes to `WornItems.getItemVisuals` (same code in both jars); the changed loop only looks for a table surface |

No hook was changed.

## mod-sync: `IngameState.exit()`

The only difference is the removed `Core.lastStand = false`. `GameExit.exit` runs at `HEAD` and reads nothing of the
method. Holds.

## Limits

A method the mods never name is not compared, as in the parent research. For the two render targets the methods the
scopes depend on were compared by hand: `AnimatedModel.setGrappleable`, `IsoMannequin.getItemVisuals`.
