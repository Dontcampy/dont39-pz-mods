# Research: how worn items become the character's appearance (render-only hide / transmog)

- **Query**: All paths from worn items to the drawn character (local, remote, UI, corpses/zombies, mannequins); where
  to omit or substitute an item's visual for rendering only; invalidation; how a per-item override can reach every
  client (incl. late joiners); gameplay readers of visuals; which side renders.
- **Scope**: internal (B42.21 `projectzomboid.jar`, `javap -c -p -l -constants`, JDK 25; SCTD Lua; repo specs)
- **Date**: 2026-10-27

Evidence is `Class.method Lnnn` (LineNumberTable) and `@offset` where useful. "Inferred" marks conclusions not
directly visible in one method body. Packet reliability/channel facts are from
`.trellis/tasks/archive/2026-10/10-24-mp-sync-loss-recovery/research/vanilla-sync.md` (not redone).

## 1. The source list: `getItemVisuals(ItemVisuals)`

| Implementation | Returns | Evidence |
|---|---|---|
| `WornItems.getItemVisuals(ItemVisuals)` | clears the list, then for each worn item `item.getVisual()`, `visual.setInventoryItem(item)`, add (null visual skipped) | `WornItems.getItemVisuals` @0-69 |
| `IsoGameCharacter.getItemVisuals(ItemVisuals)` | `getWornItems().getItemVisuals(list)` | `IsoGameCharacter.getItemVisuals L3092` |
| `IsoPlayer.getItemVisuals(ItemVisuals)` | if `!remote || GameServer.server`: worn items; **else (remote player on an MP client): `list.clear(); list.addAll(remotePlayerItemVisuals)`** | `IsoPlayer.getItemVisuals L8287-8293` |
| `IsoPlayer.getItemVisuals()` (no arg) | the field `remotePlayerItemVisuals` itself (packets fill it) | `IsoPlayer.getItemVisuals L8282` |
| `IsoZombie.getItemVisuals(ItemVisuals)` | worn items if `isUsingWornItems()`, else copies its `itemVisuals` field | `IsoZombie.getItemVisuals L4605-4613` |
| `IsoDeadBody.getItemVisuals(ItemVisuals)` | its own `wornItems` (real items from the corpse container) | `IsoDeadBody.getItemVisuals L518` |
| `IsoMannequin.getItemVisuals(ItemVisuals)` | its `wornItems` | `IsoMannequin.getItemVisuals L217` |
| `SurvivorDesc.getItemVisuals(ItemVisuals)` | its `wornItems` | `SurvivorDesc.getItemVisuals L79` |

The `ItemVisual` returned is the **item's own object** (`InventoryItem.getVisual()`), not a copy. Anything a consumer
writes into it lands on the item.

### 1a. Render consumers

| Path | Who | Reads visuals at | Notes |
|---|---|---|---|
| World model build | local player, SP, remote players on a client, zombies | `ModelManager.update L1066-1068` (main thread, from `IngameState.updateInternal L1641`) → `Reset L583-616` → `DoCharacterModelParts L1056` → `PopTemplateManager.populateCharacterModelSlot L188` | one `ModelInstance` per visual via `addClothingItem L214/L216` (`ClothingItem.getModel`, `getTextureChoice/getTint`, `ModelInstance.setItemVisual L111`); body mask `HumanVisual.GetMask(list) L189`; hair under hat `findHat/findMask L195-196` → `addHeadHair` uses `ClothingItem.hatCategory L144/L157/L169`; hidden/alt rules `isItemModelHidden L209`, `isItemModelAlt L213`; ends with `postUpdateModelTextures L234` |
| Attached-item hiding | same | `DoCharacterModelParts L1058` → `ModelManager.DoCharacterModelEquipped L776` (also via `ResetCharacterEquippedHands L743`) | visuals only for `shouldHideModel(list, location) L784` (hides belt/back attachments when a worn location hides them) |
| World texture composite | same | `IsoGameCharacter.render` → `checkUpdateModelTextures L7065-7068` (flag `updateModelTextures`) → `ModelInstanceTextureCreator.init(IsoGameCharacter) L99-100` | per visual: `findModelInstance(sub, visual) L197` matches by **`ClothingItem` identity** (`L349`), not visual identity; `addClothingItem L358-445` reads base texture, texture choice, tint, hue, decal, and per-part blood/dirt/basic/denim/leather patches/holes from the visual; holes open `holeMask` `L210-223`; `ClothingItem.masks`/combined mask hide body parts `L219-233` |
| UI preview (character screen, SCTD viewer "more" mode, make-up UI) | any `IsoGameCharacter` | `UI3DModel.setCharacter L149` → `AnimatedModel.setCharacter L448` copies `character.getItemVisuals(own list)` → `setModelData L491-549` → own `populateCharacterModelSlot`/`DoCharacterModelEquipped`; textures `AnimatedModel.renderMain L1089` → `ModelInstanceTextureCreator.init(BaseVisual, ItemVisuals, ModelInstance)` on the copied list | snapshot at `setCharacter` time; `setModelData` always rebuilds (`resetModelInstanceRecurse L533`) |
| UI preview from a `SurvivorDesc` (SCTD viewer filtered mode) | desc with real worn items | `UI3DModel.setSurvivorDesc L157` → `AnimatedModel.setSurvivorDesc L473` → `WornItems.getItemVisuals` | SCTD `LingLi_SCTD_3DViewer.lua:821-942` builds a temp `SurvivorDesc` with the player's real worn items (`setWornItem(location, wornItem)`), or calls `setCharacter(player)` when nothing is filtered (L848) |
| Corpses | `IsoDeadBody` | `FBORenderCorpses.render L437` → `FBORenderCorpses$BodyParams.init(IsoDeadBody) L127`; `DeadBodyAtlas.getBodyTexture L627`, `checkLights L708`, `invalidateBodyTexture L685` → `DeadBodyAtlas$BodyParams.init(IsoDeadBody) L287` | params copy the list (`BodyParams.init(BodyParams) L87-88`), then a `RenderJob` → `AnimatedModel.setModelData` (`DeadBodyAtlas$RenderJob.init L1059`, `FBORenderCorpses$RenderJob.init L303`) |
| Zombie atlas | `IsoZombie` | `DeadBodyAtlas$BodyParams.init(IsoZombie) L343` | |
| Mannequins | `IsoMannequin` | `IsoMannequin.syncModel L960` (`WornItems.getItemVisuals`) → `AnimatedModel.setModelData L980`; `IsoMannequin.render L629`; atlas `DeadBodyAtlas$BodyParams.init(IsoMannequin) L374`, `FBORenderCorpses$BodyParams.init(IsoMannequin) L191` | |
| Reanimated player | server copies the corpse's visuals into a shared descriptor | `IsoDeadBody.reanimate L2170` → `SharedDescriptors.createPlayerZombieDescriptor L162-165` (`new ItemVisual(copy)`) → sent to every connection (`L171-177`) | clients draw that zombie from these copied `ItemVisual`s (real `fullType`), not from items (Inferred from `IsoZombie.getItemVisuals L4605-4611`) |

### 1b. Non-render consumers (all via the same virtual `getItemVisuals`)

| Caller | Purpose | What it reads from the visual |
|---|---|---|
| `IsoGameCharacter.addBlood L13543`, `addDirt L13463`, `addLotsOfDirt L13496`, `addHole L13435`, `addBasicPatch L13407`, `addHoleFromZombieAttacks L15025` | write blood/dirt/holes/patches into worn items' visuals | `BloodClothingType.add*` over the list; coverage from `getScriptItem().getBloodClothingType()` (`L15030-15035`); then `updateModelTextures=true` (`addBlood L13560`, etc.) and Lua `OnClothingUpdated` (`addBlood L13563`) |
| `getBodyPartClothingDefense L13617` | bite/scratch/bullet defense per part | `getScriptItem`, `getBloodClothingType`, `getHole`, `getInventoryItem` → `Clothing.getDefForPart L13649` |
| `bodyPartHasTag L13568` | tag coverage | script item, holes, inventory item |
| `playWeaponHitArmourSound L12058` | hit sound | script item coverage |
| `updateWornItemsVisionModifier L16415`, `updateWornItemsHearingModifier L16453` | vision/hearing (called from Java `OnClothingUpdated L16799-16800`) | `getScriptItem` |
| `hasDirtyClothing L16537`, `hasBloodyClothing L16579` | moodle/state | script item coverage, dirt/blood |
| `updateDisguisedState L16756` | zombie disguise | `getScriptItem` |
| `IsoZombie.cantBite L5479` | bite-proof gear | `getScriptItem().isBodyLocation/hasTag` |
| `IsoZombie.helmetFallFromVisuals L5936`, `PersistentOutfits.removeFallenHat L362` | hat falls | |
| `ParameterShoeType.getShoeType L47` | footstep sound | script item name/location |
| `Thermoregulator.updateClothing L1121`, `ClothingWetness.calculateExposedItems L44`, `updateWetness L81` | temperature/wetness | inventory item |
| `SyncVisualsPacket.write L170` / `parse L64` | blood/dirt/holes/patches sync **by list index**; `isConsistent` requires `itemVisualsSize == list.size()` (parse `@36-75` logs an error on mismatch) | |
| `ConnectedPacket.write L282` | join-time appearance of a player | `ItemVisuals.save` |
| `SharedDescriptors.createPlayerZombieDescriptor L162` | reanimated player look | copies visuals |

So `getItemVisuals` is **not** render-only. Changing its result globally changes defense, coverage, bite checks,
vision/hearing, disguise, sounds and breaks `SyncVisuals` (size check).

## 2. Hook points for a render-only change

### 2a. What decides the drawn item

- `ItemVisual.getClothingItem()` resolves from **`fullType`** (`getScriptItem()` → `ScriptManager.getItem(fullType)`,
  `L83-87`) → `Item.getClothingItemAsset()` (`L100-104`), or `replaceSecondHand/replacePrimaryHand/WithoutDrainable`
  by `alternateModelName` (`L96-106`). `clothingItemName` is **not** read there (only by `HumanVisual.
  addBodyVisualFromClothingItemName L781` and item code).
- Model: `ClothingItem.getModel(female)`/`getAltModel` (`PopTemplateManager.addClothingItem L80-83`); textures:
  `getBaseTexture(ClothingItem)`, `getTextureChoice(ClothingItem)`, `getTint(ClothingItem)`, `getHue(ClothingItem)`,
  `getDecal(ClothingItem)`; masks: `ClothingItem.masks`, `masksFolder`, `underlayMasksFolder`
  (`ModelInstanceTextureCreator.init L219-233`, `addClothingItem L367-410`); hair-under-hat: `hatCategory`.
- Each getter that finds an unset value **picks a random one and stores it in the visual**: base texture/texture choice
  `< 0` → `OutfitRNG.Next` (`L171-178`, `L182-189`), hue `Infinity` → random when `allowRandomHue` (`L139-145`), tint
  null → random when `allowRandomTint`, else white (`L157-163`), decal null → random from `decalGroup` (`L197-203`).
- `getTint(ClothingItem)` first returns the **inventory item's custom colour** if `inventoryItem != null &&
  isCustomColor()` (`L153-155`).

### 2b. A substitute visual for type B

- `new ItemVisual()` sets `hue=Infinity, baseTexture=-1, textureChoice=-1` (`ItemVisual.<init> L31-34`); then
  `setItemType("Module.B")` (asserts a `.`, `L52-54`), optionally `setClothingItemName(...)`, `setTint(ImmutableColor)`,
  `setBaseTexture(int)`, `setTextureChoice(int)`, `setHue(float)`, `setDecal(String)`.
- Rendering then uses B's `ClothingItem` everywhere above (model, textures, masks, hat hair, `isItemModelHidden` rules).
- To keep A's wear on the look: `copyBlood/copyDirt/copyHoles/copyPatches(aVisual)` exist (`L576…`); the texture creator
  reads them per `BloodBodyPartType` (`addClothingItem L438-445`), holes also open the body mask (`init L210-223`).
  A fresh substitute without copies draws clean; whether A's per-part values look right on B's model depends on B's
  masks (Inferred).
- Leave `inventoryItem` null (or set it to A only if A's custom colour should win, `getTint L153`).
- Set every random-picked field explicitly (or keep one substitute object per item): the texture pass and the model pass
  call the getters separately, and a substitute with `-1`/null would get a new random pick per call/rebuild
  (Inferred from the getters above).
- Edge case: `findModelInstance` matches by `ClothingItem` identity (`L349`); a transmog to B while a real B (same
  `ClothingItem`) is also worn makes both visuals hit the first model instance (Inferred).

### 2c. Choke points and the Zabric constraint

Zabric injects only at `HEAD`, `RETURN` (normal), `FINALLY` (`zabric/.../hook/At.java`); no `INVOKE` point. The list in
each render consumer is a local (`populateCharacterModelSlot L187`, `DoCharacterModelEquipped L775`) or a field
(`ModelInstanceTextureCreator.itemVisuals`, `AnimatedModel.itemVisuals`).

| Candidate | Covers | Problem |
|---|---|---|
| RETURN of `WornItems.getItemVisuals(ItemVisuals)` unconditionally | local player, SP, corpses, mannequins, `SurvivorDesc` previews, zombies using worn items | also every gameplay reader of §1b and `SyncVisuals`/`ConnectedPacket` |
| RETURN of `IsoPlayer.getItemVisuals(ItemVisuals)` | remote players on a client (the `remotePlayerItemVisuals` branch) | same gameplay problem; the local branch already went through `WornItems` |
| `ItemVisual.getClothingItem()` RETURN | everything that draws | also hat/mask lookups, atlas keys, `clothingItemChanged`; called many times per frame; can't express "hidden" |
| **Render scope**: `HEAD`/`FINALLY` on the render consumers set a flag; the two RETURN hooks above edit the list only while the flag is set | all render paths in §1a, nothing in §1b | must list every render consumer (below) |

Render consumers to scope (all call `getItemVisuals` once inside):
`PopTemplateManager.populateCharacterModelSlot(IsoGameCharacter, ModelSlot)`,
`ModelManager.DoCharacterModelEquipped(IsoGameCharacter, ModelSlot)` (private),
`ModelInstanceTextureCreator.init(IsoGameCharacter)`,
`AnimatedModel.setCharacter(IsoGameCharacter)`, `AnimatedModel.setSurvivorDesc(SurvivorDesc)`,
`DeadBodyAtlas$BodyParams.init(IsoDeadBody|IsoZombie|IsoMannequin)`, `FBORenderCorpses$BodyParams.init(IsoDeadBody|
IsoMannequin)`, `IsoMannequin.syncModel`. The AnimatedModel texture pass works on its already-copied list, so scoping
`setCharacter`/`setSurvivorDesc` covers the UI fully. Thread: `ModelManager.update` and `IsoGameCharacter.render` run on
the main thread; Lua UI calls too (Inferred for the corpse paths). Hooks on private/nested targets are supported
(`mod-sync` `ClientHooks.kt`, spec `zabric-hooks.md`).

Edit in scope: hidden → remove the entry; transmog → replace the entry with the B substitute. The list handed out is a
consumer-owned container; replacing entries never touches the item's own `ItemVisual`. Effects: removing a visual also
removes its body mask (body/underwear shows) and its hat-hair alternate (hair shows), as when the item is not worn.

Key for the lookup: local/SP/corpse/mannequin/desc paths see real items (`visual.getInventoryItem()` set by
`WornItems.getItemVisuals @53`, item id/modData available). Remote players on a client do **not** (see §4): the
remote list holds visuals of client-created items (from `SyncClothing`) or visuals with `inventoryItem == null` (from
the join's `ConnectedPacket`), so the key there must be per player + body location (from `visual.getScriptItem()`) or
fullType, not the item's modData.

## 3. Invalidation

| Viewer | Trigger | Evidence |
|---|---|---|
| World model (any character) | `chr.resetModelNextFrame()` → `ModelManager.ResetNextFrame` queues (dedup) → next `ModelManager.update` → `Reset` → full rebuild incl. `postUpdateModelTextures` | `IsoGameCharacter.resetModelNextFrame L1630`, `ModelManager.ResetNextFrame L1217-1223`, `update L1066-1070`, `DoCharacterModelParts L1054-1062`, `populateCharacterModelSlot L234` |
| Textures only (blood etc.) | `updateModelTextures = true` (`postUpdateModelTextures L13854`) → `checkUpdateModelTextures L7065-7068` | used by `addBlood` etc. — re-runs the texture pass (and the hook) without a model rebuild |
| Immediate | `resetModel()` → `ModelManager.Reset` | `IsoGameCharacter.resetModel L1625` |
| Character screen preview | Lua `OnClothingUpdated(chr)` for a local player sets `charScreen.refreshNeeded` → `setCharacter` | `ISCharacterInfoWindow.lua:345-351`, `ISCharacterScreen.lua:664-666` |
| SCTD viewer | its own `refreshAvatarFromPlayer` (`LingLi_SCTD_3DViewer.lua:821`) | |
| Corpses | `IsoDeadBody.invalidateCorpse()` (used by `ObjectModDataPacket.parse L90`) | atlas re-renders with a new `BodyParams` |
| Remote player on a client, vanilla | `SyncClothingPacket.processClient`: `process()`, `onWornItemsChanged()`, `resetModelNextFrame()`, Lua `OnClothingUpdated` (`L269-275`); `SyncVisualsPacket.processClient` → `resetModelNextFrame`; `HumanVisualPacket` likewise | so a remote rebuild needs a received message plus `resetModelNextFrame()` on that `IsoPlayer` |

`onWornItemsChanged()` only updates the ragdoll flag and, on the server, Java `OnClothingUpdated` (`L3392-3398`); it
does not rebuild the model. Firing the Lua event `OnClothingUpdated` also runs SCTD's own handlers (Inferred from the
SCTD design notes in `design.md`: its `OnClothingUpdated` branch does auto-hide/resnap work).

## 4. Getting the override to every client

### 4a. What vanilla carries

| Packet / store | Fields | Delivery | Evidence |
|---|---|---|---|
| `SyncClothing` (R_ORD ch0) | player id; per worn item `itemId, itemType (getFullType), location, textureChoice, baseTexture, tint(rgba)`; per item patches (`tailorLvl, fabricType, hasHole` per part) | sender → server `process()` + `sendToClients` (`processServer L279-283`) | `SyncClothingPacket$ItemDescription.<init> L55-63`, `write L67-78`, `writeClothing` |
| — on a client receiving it | for a remote player: clears `remotePlayerItemVisuals`, removes worn items not listed, takes each item from `getInventory().getItemWithID` **or creates it with `InventoryItemFactory.CreateItem(itemType)` + `setID`**, wears it, sets tint/baseTexture/textureChoice on its visual, adds the visual to `remotePlayerItemVisuals` | | `SyncClothingPacket.process L225-265` |
| `SyncVisuals` (R_ORD ch0) | per visual by index: patches, holes, dirt, blood; clothing dirtiness/blood/condition; primary-hand blood | | `SyncVisualsPacket.write`, `parse`, `isConsistent` |
| `ConnectedPlayer` (`ConnectedPacket`), at join | `SurvivorDesc`, `HumanVisual.save`, **`ItemVisuals.save`** (each visual: `fullType, alternateModelName, clothingItemName, tint, baseTexture, textureChoice, hue, decal`, blood/dirt/holes/patch arrays), attached items by type+id, … | server sends every alive player to the joiner and the joiner to everyone (`GameServer.receivePlayerConnect L2870-2882`); parsed into `remotePlayerItemVisuals` with no items (`ConnectedPacket.parse L140 @340-350`) | `ItemVisual.save`, `ConnectedPacket.write L282` |
| `WornItems.save` | per item: location, `getType()`, texture name, `col`, baseTexture, textureChoice, tint | callers not identified in the classes read | `WornItems.save L224-235` |

No item mod data in any of these. A remote player's worn items on another client are the client's own objects created
from `itemType` (or found by id in the remote inventory, which other clients are not sent — Inferred: no inventory
packet targets non-owners in the prior packet table), so **an item's `modData` does not reach other clients for a
remote player's worn items**. Corpses and mannequins are different: their containers are sent with full items
(`IsoDeadBody.writeInventory L2681` → `ItemContainer.save`; `readInventory L2395` → `ItemContainer.load`), so item mod
data rides along there (Inferred that `ItemContainer.save` includes mod data, as for saves).

### 4b. Options

| Option | How | Ordering / loss | Join | Pros | Cons |
|---|---|---|---|---|---|
| (i) Existing fields, server rewrites what it sends | server-side hooks on `SyncClothingPacket$ItemDescription.write` / `ConnectedPacket.write`'s visual list to send B's type/visual for a transmogged item | rides vanilla packets, R_ORD ch0 | automatic (`ConnectedPacket`) | no new channel | receivers create real B items in the remote player's `WornItems` (Lua/tooltips/inspect see B); "hidden" cannot be expressed without dropping an entry, which breaks `SyncVisuals` index/size matching (`SyncVisualsPacket.isConsistent`); the owner's client still sends A in `SyncClothing`, so every relay must be rewritten; texture/tint ints only |
| (ii) Player mod data + `transmitModData()` | per-player table `{location → hidden/B+texture+tint}` | `ObjectModData` R_ORD **ch1** (not ordered with ch0 clothing packets); whole table each time, wiped+loaded on receipt; server relays only to clients `isRelevantTo(x,y)` (`INetworkPacket.sendToRelativeClients L213-216`, `ObjectModDataPacket.processServer L101`) | **not** in `ConnectedPacket`; a joiner or a far client gets nothing until the next transmit | vanilla, saved with the player (Inferred), Lua-visible | relevance filter and join gap need a re-send scheme; no rebuild trigger on receipt (needs a hook on `ObjectModDataPacket.parse` to call `resetModelNextFrame`); table shared with other mods (SCTD already uses player mod data, Inferred) |
| (iii) Own packet (`server-reborn` mechanism) | header 134 + reserved short id, first byte protocol version; client `ZomboidNetData.read` HEAD + dispatch at `mainLoopDealWithNetData`; server `GameServer.addIncoming` HEAD into own queue; `Net.send` RELIABLE_ORDERED on a chosen channel (`server-reborn/.../network/game/Net.kt:16-28`; spec `zombiebuddy-patching.md` "Own Network Packets") | ordered on its own channel; reliable until disconnect | server sends a full snapshot (all players' overrides) after the joiner's hello; deltas afterwards | exact fields (hidden, B fullType, textureChoice, baseTexture, tint, keyed by player + location), all clients regardless of distance, rebuild on receipt | most plumbing: hello per peer, never send to vanilla peers, send only from own tick/handler, must handle a message for a player not yet known (store by username/onlineID, render hook looks up at draw time) and clear on disconnect / onlineID reuse |
| (iv) Lua `sendClientCommand` / `sendServerCommand` | SCTD-side or our Lua keeps a server table, broadcasts, clients pass it to the Kotlin render store (spec "Lua Bridge") | `ClientCommand` is RELIABLE unordered (prior research) | client asks for a snapshot at `OnGameStart` | no Java net hooks | unordered; Lua↔Kotlin bridge; strings/tables |
| (v) Item mod data on the server as the source of truth | the override lives in `item:getModData()` (persists with the item, server-authoritative) and the server derives (ii)/(iii) messages from it | — | — | survives restarts; corpses/mannequins/own player get it with the items | still needs (ii)/(iii)/(iv) for remote viewers |

## 5. Gameplay vs ClothingItem

- Every gameplay reader in §1b resolves through **script fields** (`ItemVisual.getScriptItem()` from `fullType`,
  `Item.getBloodClothingType`, `Clothing.getDefForPart`, tags, body location) or the inventory item, never through
  `ClothingItem`. `IsoZombie.cantBite` also reads `getWornItem(location).hasTag` (`L5462-5474`).
- Readers of `ClothingItem` in the jar are render/creation code only: `PopTemplateManager`, `AnimatedModel`,
  `ModelInstanceTextureCreator`, `ItemVisuals.findHat/findMask`, `ModelManager.addMaskingModel/addEquippedModelInstance`,
  `DeadBodyAtlas.getBodyKey`, `WorldItemAtlas`, `ItemModelRenderer`, `HumanVisual`, item creation/outfits,
  `IsoGameCharacter.clothingItemChanged` (debug reload), Lua `getAllItemsForBodyLocation/getAllDecalNamesForItem`.
- So a substitution confined to the render scope (§2c) changes nothing in defense, coverage, bites, disguise,
  vision/hearing, footsteps, temperature, wetness, saves or packets. A substitution in `getItemVisuals` itself would
  change all of them to B's script values (B's coverage/defense/tags), and hiding would remove the item from them.
- Note for comparison: SCTD's current item swap changes these to the placeholder's/B's script values (the item really
  is another type), compensated by its `baseStats` copy for stats; coverage/tags/location follow the new type.

## 6. Which side renders

- Dedicated server: `GameServer.doMinimumInit L1495-1496` sets `ModelManager.noOpenGL` and creates the manager;
  `ModelManager.isUpdateDisabled L1213` is true on a server without `ServerGUI`, so `ResetNextFrame` is a no-op
  (`L1217`); `SyncClothingPacket.processServer` resets the model only with `ServerGUI` (`processServer L280-281`); no UI, no
  `IsoGameCharacter.render`. Render hooks never need to run there.
- MP client: renders its own player from real worn items (`IsoPlayer.getItemVisuals`, `remote=false`) and every remote
  player from `remotePlayerItemVisuals` (`remote=true`). Corpses/mannequins from their containers' items.
- Single player: everything from real items; `remote=false`.
- So: render hooks + render state on clients and SP; the server only stores/broadcasts the state (if (ii)/(iii)/(iv)).

## Recommended hook points and sync option

- **Hooks (client + SP)**: a render-scope flag set at `HEAD` and cleared at `FINALLY` on the render consumers listed in
  §2c; `RETURN` hooks on `WornItems.getItemVisuals(ItemVisuals)` and `IsoPlayer.getItemVisuals(ItemVisuals)` that,
  only inside the scope, remove hidden entries and replace transmogged ones with a cached substitute `ItemVisual`
  (B `fullType`, explicit texture choice/base texture/tint/hue/decal, A's blood/dirt/holes/patches copied in). For
  `IsoPlayer` act only on the remote branch (`remote && !GameServer.server`), since the local branch already passed
  through `WornItems`.
- **Invalidate**: on any override change for a character, `resetModelNextFrame()` on that character; for the local
  player also refresh UI previews (Lua `OnClothingUpdated` or the specific panels); corpses `invalidateCorpse()`.
- **Sync**: the override stored server-side (item mod data as the persistent source), and delivered to clients with an
  own packet per `server-reborn`'s mechanism (iii): full snapshot to each client after its hello, deltas to all hello'd
  peers, keyed by player (username/onlineID) + body location, carrying hidden flag or B fullType + textureChoice +
  baseTexture + tint. Clients look the key up at render time, so a message arriving before `ConnectedPlayer` /
  `SyncClothing` still applies once the player exists. (ii) is the vanilla fallback but needs its own re-send for
  joiners and distant clients and a hook to rebuild on receipt.
- **Risks**:
  - Missing a render consumer leaves one view unchanged (e.g. corpse atlas, mannequin, UI); scope list must be checked
    after game updates.
  - Random-pick getters on an incomplete substitute cause flicker/different looks per pass.
  - `findModelInstance` collisions when the same `ClothingItem` appears twice.
  - Remote-player keying can't use item mod data or item ids at join (`ConnectedPacket` visuals have no item).
  - Reanimated-player zombies on clients are drawn from server-copied visuals (`SharedDescriptors`), unaffected unless
    the server substitutes there (that zombie is cosmetic; its look would then show B).
  - Firing Lua `OnClothingUpdated` to refresh UI also runs SCTD's own handlers.
  - Other mods that draw the character by reading `getItemVisuals` outside the scoped methods see the real items.

## Caveats / Not Found

- `WornItems.save` callers were not traced (only classes referencing the searched names were disassembled).
- Whether `IsoPlayer` mod data is written in the player save and whether `ItemContainer.save` writes item mod data were
  not re-read here (Inferred from normal save behaviour).
- Threading of the corpse atlas `BodyParams.init` paths was not traced to the thread that runs them (Inferred main).
- No in-game test; ordering statements about channels rely on the prior research's RakNet semantics.
