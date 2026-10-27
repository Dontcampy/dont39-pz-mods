# Design: appearance-override

Evidence: `../10-27-sctd-render-transmog/research/engine-appearance.md` (bytecode, B42.21). Section numbers below
refer to it.

## Shape

Kotlin feature module `appearance-override` (package `com.dont39.pz.appearanceoverride`, facade
`AppearanceOverride.kt`), with Lua under `src/main/lua/`:

| Part | Side | Does |
|---|---|---|
| Render hooks (Kotlin) | client, single player | edit the visual list only inside render consumers |
| Client store (Kotlin) | client, single player | per player: `(bodyLocation, fullType) → Override`, plus revision |
| `@LuaGlobal`s (Kotlin) | client, single player | fill/replace a player's set in the store, which also resets that player's model |
| `shared/Dont39AllInOne_AppearanceOverrideShared.lua` | all | the public Lua API (module table) and the override's table format |
| `server/Dont39AllInOne_AppearanceOverrideServer.lua` | server, single player | per-player sets from worn items, broadcast, snapshot on request |
| `client/Dont39AllInOne_AppearanceOverrideClient.lua` | MP client | receive sets, request a snapshot after the join |

## Data

Item mod data key `Dont39AllInOne_Appearance`: `{ hidden = true }` or
`{ type = "Base.X", textureChoice = n, baseTexture = n, tint = {r, g, b, a}, decal = s|nil, hue = f|nil }`. Saved with
the item; it is the only persistent copy (R1).

A player's **set** is derived from the items worn now: for each worn item with the key, `(location id, A's fullType) →
override`. Keyed by location and type, not mod data or item id, because a remote player's visuals on another client
carry neither (§4a: `SyncClothing` makes client-side items from the type; join visuals have no item), and SCTD's own
render swap puts mod-data-less copies into the local worn list (`sctd-integration.md` §3, `A.Prepare`).

## API (shared module, authoritative side only; an MP client call is an error)

- `setHidden(player, item)`, `setLook(player, item, look)`, `clear(player, item)`: validate (`look.type` is a
  clothing script item with a `ClothingItem`; all look fields present, so no getter picks a random value, §2b), write
  the key, then publish the player's set.
- `get(item)`: the override table or nil.
- `publish(player)`: recompute the set from worn items; when it changed, bump the player's revision and send it.

## Sync (Lua commands, option (iv) in §4b)

- Server: `OnClothingUpdated(player)` (fires on the server for every wear change; SCTD relies on it too) → `publish`.
  A change is sent with `sendServerCommand(module, "set", {user, revision, entries})` to all clients; a whole set per
  message, so a lost delta cannot leave stale entries, and the client drops a revision older than the one it has
  (commands are reliable but unordered).
- Client: after `OnGameStart` (MP) send `snapshot`; the server answers that client with every online player's set.
  `OnServerCommand` → `@LuaGlobal` `Dont39AllInOne_putAppearanceOverrideSet(user, revision, entries)`.
- Single player: `publish` calls the `@LuaGlobal` directly.

Why not an own packet (§4b (iii), Server Reborn's mechanism): that plumbing lives in another mod and would have to be
copied (new reserved id, hello, queue). The state is small and changes only on player action, so ordering by revision
over vanilla commands is enough. Why not player mod data (ii): not in the join, sent only to nearby clients, and no
rebuild on receipt.

## Render hooks (§2c)

- **Scope**: `HEAD` sets and `FINALLY` clears a thread-local depth on `PopTemplateManager.populateCharacterModelSlot`,
  `ModelManager.DoCharacterModelEquipped` (private), `ModelInstanceTextureCreator.init(IsoGameCharacter)`,
  `AnimatedModel.setCharacter`, `AnimatedModel.setSurvivorDesc`, `DeadBodyAtlas$BodyParams.init` (dead body, zombie,
  mannequin), `FBORenderCorpses$BodyParams.init` (dead body, mannequin), `IsoMannequin.syncModel`. The scope also
  records the character (or desc) being drawn.
- **Edit**: `RETURN` on `WornItems.getItemVisuals(ItemVisuals)` and on `IsoPlayer.getItemVisuals(ItemVisuals)` (only the
  remote branch: `isRemote` and not the server), only while the depth is > 0: for each visual find its override (below);
  hidden → remove the entry; look → replace it with the substitute. The list is the caller's; the item's own visual is
  never touched (R3).
- **Lookup**: drawing an `IsoPlayer` (or a desc built for one) → client store by username, `(location of the visual's
  script item, its fullType)`. Anything else (corpse, mannequin) → the item's mod data via `visual.getInventoryItem()`.
- **Substitute**: `ItemVisual` with `setItemType(look.type)` and every look field set explicitly; cached per
  `(player, key, revision)`; A's blood, dirt, holes and patches copied in each time it is handed out (`copyBlood`,
  `copyDirt`, `copyHoles`, `copyPatches`, §2b).
- **Invalidate**: a store change for a player → `resetModelNextFrame()` on that `IsoPlayer` when it exists (looked up
  by username); a player not yet known is drawn from the store when it appears. UI previews rebuild on their own next
  rebuild; SCTD's panel refreshes on its events (the takeover child handles SCTD's UI). Corpses: `invalidateCorpse()` is
  not needed (their override rides on the item and does not change after death).

## Gating and errors

`Feature` base as `ime-keyboard-fix`; hooks gated by the facade. Any exception in a hook → the feature disables itself
(Zabric gate), the game draws vanilla. Lua side: the API throws on bad input (caller's error, `sctd-render-takeover`
reports it); a failure in the server/client Lua plumbing logs `disabled after an error while …` and stops sending.
Status: `ready` on clients/SP (hooks installed), `ready` on the server (sync), `disabled …`.

## Risks (from §2c, kept here as checks)

- A render consumer not in the scope list draws the real item (after a game update, re-check the list).
- Two worn items with the same type at the same location share a key (multi-item locations); they get the same look.
- `findModelInstance` collides when the substitute's `ClothingItem` is also worn for real; that model is drawn once.
- Other mods that draw characters outside the scoped methods see real items.

## Deviations (implementation)

- **Key is the worn item's type only**, not `(location, type)`. A remote visual carries only its type; its location
  would come from the type's script, which names two candidates (`bodyLocation`, `canBeEquipped`: a bag worn on Back
  has no body location), so the server's real location and the client's derived one could disagree. A type is worn in
  one location, so the location adds nothing. The server sends the first worn item of a type.
- **Validation in Kotlin**: `Dont39AllInOne_checkAppearanceOverride(table)` parses with the same parser the clients use
  and checks the look against the scripts (type exists, has a `ClothingItem`, texture indices in range, a decal given
  when the type has a decal group). "Not clothing" means no `ClothingItem`: bags have one and are accepted. A nil
  `hue` is 0.
- **Two put globals**: `Dont39AllInOne_putAppearanceOverrideSet(user, …)` (MP client, by user name) and
  `Dont39AllInOne_putLocalAppearanceOverrideSet(player, …)` (single player, by the player object): every single-player
  `IsoPlayer` is named "Bob", so the store keys it by the player's index there.
- **Session reset from Lua**: `Dont39AllInOne_clearAppearanceOverrideSets()` at the client's `OnGameStart` (before the
  snapshot request) and when the server file loads in single player, rather than a Kotlin hook on leaving the game.
- **Previews from a `SurvivorDesc`** are drawn from the items' mod data (the desc holds the player's real items), not
  from the store; the server API calls `syncItemModData` after writing, so the owner's copy has it.
- **Stored overrides that fail the check** (a look type whose mod was removed) are stored data, not a mismatch: the
  server leaves them out of the set and the item-mod-data path draws the item as itself, each with one error line. A
  set message that fails on a client is a mismatch and disables the feature.
- **No message for a player who never had an override**: a set is first sent when it is not empty.
- **One handler per target method** (Zabric requires it): the scope is a HEAD/FINALLY pair per consumer overload.
- Debug switch: `-Dappearanceoverride.debug=true` logs, per consumer and player, what was drawn instead whenever it
  changes, and each set a client takes.
- **Lua stops with the Java side**: `Appearance.problem()` asks `Dont39AllInOne_getAppearanceOverrideProblem()` (the
  facade's `whyNotActive`), so a disabled feature stops the sync instead of reporting each stored override as invalid.
- **Server checks worn items once a second** (found in game, `research/in-game.md` ao3/ao4): a joined player's worn
  list is filled from the client's packets without `OnClothingUpdated`, so a set built on that event alone can miss
  items. `publish` still sends only a set that changed.
- **Snapshot request repeated until `snapshotDone`** (found in game, ao5-ao7): a client command sent at `OnGameStart`
  can arrive before the server has bound the player to the connection and is dropped. The client asks every 2 s,
  the server ends each answer with `snapshotDone`; 30 unanswered requests stop the sync with an error.
