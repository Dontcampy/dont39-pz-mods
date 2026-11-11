# Static review: Loading Progress against game 42.22

2026-11-11. `javap -c -p` of the 42.21 jar (`4a0e9546ec`) against the 42.22 jar (`3d39148846`), per method, with
constant pool indices, offsets, jump targets and local slots removed. Nothing here ran in the game; see
[in-game.md](in-game.md).

## The 5 changed hook targets

| Target | Hooks on it | What the mod does there | Change in 42.22 | Result |
|---|---|---|---|---|
| `GameWindow.enter()` | `LaunchHooks.Window.enter` (HEAD) | begins the launch's `TEXTURES` step | `Core.tileScale` is no longer set; the four 1x packs are gone, the eight 2x packs load unconditionally | No change needed. Packs are log lines without a count (`Progress.texturePack`, `item(TEXTURE_PACK, 0, 0, pack)`), from `LoadTexturePack(String, int, String)`, which is unchanged. A 1x player's report has four pack lines fewer |
| `GameLoadingState.enter()` | `StartHooks.World.prepare` (HEAD), `.load` (RETURN) | begins `PREPARING_WORLD`, then `LOADING_WORLD` | `GameMode.MULTIPLAYER` instead of the string; `Core.isLastStand()` gone from the condition around its file write of the world's name and game mode | No change needed: both hooks sit at the method's ends and read nothing of it |
| `IngameState.enter()` | `StartHooks.InGame.enter` (HEAD) | begins `ENTERING_WORLD` | `Core.isTutorial()` picks the music state | No change needed |
| `IngameState.exit()` | `StartHooks.InGame.exit` (HEAD) | ends a start that has not drawn the world | `Core.lastStand` is no longer cleared | No change needed |
| `LuaManager$Exposer.setExposed(Class)` | `TriggerHooks.Exposer.exposed` (HEAD) | a tick for an own frame | an enum class also goes into `exposedEnums` / `enumToId` | No change needed: the hook counts nothing |

## Around the targets

The [parent's comparison](../../11-11-game-42-22-update/research/impact-42.22.md#method) only covers members the mod
names. The methods that call the hooked ones decide the order of the boundaries, so these were compared too.

| Game code | Change in 42.22 | Effect on the mod |
|---|---|---|
| `GameWindow` (all other methods), `Core.ResetLua`, `LuaManager` (but its `Exposer`), `ScriptManager`, `Translator`, `ModelManager`, `SpriteModelManager`, `TileGeometryManager`, `ClothingDecals`, `OutfitManager`, `LoadingQueueState`, `LoadingQueueUI`, `TextManager`, `FileSystemImpl`, `TextureIDAssetManager`, `DebugLogStream`, `CoopMaster`, `RakNetPeerInterface` | none (`GameWindow.save`: world version 251) | The launch's and the Lua reset's boundaries come in the same order |
| `GameLoadingState$1.runInner` (the world's loading thread) | returns right after `IsoWorld.init` when a client lost its connection during it | None: the flow then ends as any lost join does. The `bWaitForAssetLoadingToFinish2 start` / `end` lines that bracket the asset wait are still there |
| `IsoWorld.init` | the vanilla tile definitions are asked for as `tile_definitions/<file>.tiles`; a second early return for a client that lost its connection; `StashSystem.init` also on a client | None: `WorldHooks.TileDefinitions.game` matches on `.tiles` and `Progress.fileName` drops the folder |
| Subclasses of `zombie.fileSystem.FileTask` | the same 16 classes | `AssetWaitHooks` still has a hook for every class that declares a task's call |
| `LuaEventManager` | one new event (`OnElectricityChanged`) | None: `OnGameBoot`, `OnConnectFailed` and `OnDisconnect` are still fired from the same classes |
| `ConnectToServer:OnConnected` (Lua) | `forceDisconnect()` when the player profile was not received | None: the same path as its existing `forceDisconnect()` for a debug client |
| `MainScreen.lua`, `LoadingQueueUI` | the sizes `Overlay` copies (`w <= 1024`: 95 %; inset 288) are unchanged | None |
| `PZAPI/ModOptions.lua` | unchanged | The options page needs no change |
| `Core.getVersion()` | ends with `IntegrityReport.currentSummary()` when that is not empty: `integrity=pending` until the game's background check is done, then a list such as `agents=1` (ZombieBuddy is a Java agent) | **Changed.** The report's `Game` line took this text. It now takes `getGameAndBuildVersion()` and `getGitRevisionString()`, which is what `getVersion()` was on 42.21 |

## Not covered by this review

- Data files, natives and Lua the mod does not name.
- Whether ZombieBuddy 2.3.4 weaves the hooks on 42.22: in-game.
