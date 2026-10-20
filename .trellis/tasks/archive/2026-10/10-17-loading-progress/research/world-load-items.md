# Research: per-item lines in "Loading the world"

- **Query**: inside step 11 (`GameLoadingState` running `GameLoadingState$1.runInner` on its `GameLoadingThread`), which
  sub-phases have a per-item call the mod can hook (D9: whole descriptor JDK-only, no `@Self`, no overload prefix
  clash; D10: woven before use), with a name and an i / total from statics.
- **Scope**: javap of 42.21 `projectzomboid.jar`; DebugLog timelines of two single-player loads of the LPTest save
  (246 mods); `Transformed:` lines of the same runs.
- **Date**: 2026-10-20

`runInner` order: wait for clothing items (`waitForAssetLoadingToFinish1`, released by `GameLoadingState.update` when
`OutfitManager.isLoadingClothingItems()` is false), `BaseVehicle.LoadAllVehicleTextures`, `IsoWorld.init`, then
`OnGameTimeLoaded`, global objects, wait for animations and file I/O (`waitForAssetLoadingToFinish2`:
`ModelManager.isLoadingAnimations()`, `FileSystem.hasWork()`), FBO, physics, `SendDone`. `IsoWorld.init` sets the
game's text: "" → `IGUI_MP_LoadTileDef` ("从硬盘加载区块定义文件…" = tile definitions) → "" → `IGUI_MP_InitMap` →
`IGUI_MP_LoadWorld` → "" (MP adds `IGUI_MP_Checksum`; `IsoCell.LoadPlayer` and `RequestDataManager` set their own).

## Summary table

Times: run 2 (`scratchpad/world-items/runs/w2-sp`), step 11 from 64.7 s to 157.1 s.

| Sub-phase (time) | Target, descriptor | Name / total | Fire rate | Woven in time |
|---|---|---|---|---|
| Clothing wait (≈65-79 s; ~100 loaded for several seconds, then +16 per frame) | `OutfitManager.isLoadingClothingItems()Z` HEAD, main thread, only `GameLoadingState.update` calls it | count only: non-empty of `OutfitManager.instance.cachedClothingItems` (widened) / its size | per frame while waiting | yes: new load after the first pass |
| Lot headers (79.1-85.9 s, 8 loader threads, parallel to the next rows) | `IsoMetaGrid$MetaGridLoaderThread.loadCell(II)V` HEAD (`targets =`; the other overload starts with `MapFiles`) | `x_y` when some `IsoLot.MapFiles` entry `hasCell(x, y)` / `IsoLot.InfoHeaders.size()` (4109) | ~251k calls, 4109 items | yes: first loads at the world load (no ZB log line names it; its items show) |
| Tile definitions, game files (81.8-84.3 s, each file twice) | `ZomboidFileSystem.getMediaPath(Ljava/lang/String;)Ljava/lang/String;` HEAD, path ending `.tiles` | file name / no total (the 7 names are constants in `IsoWorld.init`) | 14 | yes: premain |
| Tile definitions, mods' files | `ZomboidFileSystem.loadModTileDefPropertyStrings()V`, `loadModTileDefs()V` HEAD | one unnamed item per pass | 2 | yes: premain |
| Lua events of the loading thread (whole step) | existing `LuaEventManager.checkEvent` HEAD, off `GameWindow.gameThread` → `Item.WORLD_EVENT` | event name | ~50 (OnWeatherPeriodStart/Stop alternate ~25 times) | yes |
| Chunks (`IGUI_MP_LoadWorld`, 91.6-93.1 s) | `IsoChunkMap.LoadChunk(IIII)V` HEAD counts queued; `WorldStreamer.isBusy()Z` HEAD polls `IsoChunk.loadGridSquare.size()` | count only / LoadChunk calls (361 = 19²) | poll every 100 ms | yes: IsoChunkMap retransformed at the first pass, WorldStreamer new load |
| Animation wait (94.7-155.1 s, 28 loaded for ~30 s) | `ModelManager.isLoadingAnimations()Z` HEAD, only `GameLoadingState` calls it | count only: non-empty of `AnimationAssetManager.instance.getAssetTable()` / its size (2291) | per frame while waiting | yes: retransformed at the first pass |

## Rejected and gaps

- `ModelManager.animationAssets` cannot be widened: `ModelManager` loads at premain, before the widener (smoke test
  reported `not applied`); the asset manager's public table is used instead.
- Mods' tile definition files per name: their only JDK-only per-file call, `ZomboidFileSystem.getRelativeFile(String)`,
  is skipped whole by Viewpoint's `Patch_RelativeFile` (`@OnEnter(skipOn = true)`), HEAD advice of other mods
  included (run 1: no line); `IsoWorld.LoadTileDefinitions*` take `IsoSpriteManager`.
- MP chunks: a join queues them through `IsoChunkMap.LoadChunkForLater(IIII)Lzombie/iso/IsoChunk;` (game-typed
  return), so a join has no chunk count; its checksum and server data show the game's own text.
- No per-item call: `BaseVehicle.LoadAllVehicleTextures`, `IsoMetaGrid.CreateStep2` tail, `IsoMetaGrid.load`
  (map_meta.bin, zones: two files), `BuildingRoomsEditor.load`, player load, `MetaTracker` (≈1-5 s each, the game's
  text and the thread's Lua events show there). `WorldDictionary.init` (0.2 s), `VehiclesDB2.init` (6 ms) are short.
