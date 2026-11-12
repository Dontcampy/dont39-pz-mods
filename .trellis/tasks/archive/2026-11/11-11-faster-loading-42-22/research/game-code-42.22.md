# The game code behind each fix, 42.21 against 42.22

Static evidence, gathered 2026-11-11. It answers R1: no fix needs a change for 42.22.

## Method

Every method of `zombie/**`, `se/krka/kahlua/**`, `org/lwjgl/opengl/GL*` and `org/lwjgl/stb/STBDXT*` in both jars
(5,288 classes in 42.21, 5,318 in 42.22), disassembled with `javap -p -c -s` and normalized as in the
[parent research](../../11-11-game-42-22-update/research/impact-42.22.md) (constant pool indices, offsets and jump
targets removed). A method counts as changed when its normalized code or its access flags differ; lambdas are compared
as a group per enclosing method and descriptor.

From that, per fix:

1. The method the fix replaces or reproduces, and every method that calls it.
2. Its call closure: each `invoke*` target resolved to its declaring class, plus the targets of the class's method
   handles (lambdas and method references) for a method with an `invokedynamic`. Followed to any depth without
   overrides, and to depth 4-5 with every override in the jar. The walk does not expand through logging, Lua, UI, the
   render thread's draw loop, networking and the game states: `ExceptionLogger` alone reaches most of the game.
3. Whole-class comparison of the classes the fix reads or depends on, inner classes included.

Limits: natives and data files are not compared; a call made by reflection is not seen.

## Result

| Fix | Game code it stands for | 42.22 |
|---|---|---|
| `stripComments` | `ScriptParser.stripComments(String)` (no game callee); its 11 callers | all unchanged |
| `luaFolderWalk` | `LuaManager.searchFolders(URI, File)`, its caller `LuaManager.LoadDirBase(String, boolean)`, `ZomboidFileSystem.getRelativeFile(URI, String)`, and their closure (103 methods) | unchanged, apart from the list helper below |
| `depthMapOrder` | `TileDepthTextureManager.init()` and its closure (306 methods); `TileDepthTextures$LoadTask` and its closure (314); all of `FileSystemImpl`, `FileTask` and their closure (130); every class of `zombie/fileSystem` (26) | one changed method, below; nothing in how tasks are queued, pumped, locked or prioritized |
| `textureCompression`, `textureBc7` | `TextureID.generateHwId(boolean)` and its closure (57 methods); every game caller of `GL11.glTexImage2D` (13 methods), `glTexSubImage2D`, `glPixelStorei`, `glGenerateMipmap`; all of `TextureID`, `ImageData`, `MipMapLevel`, `PZGLUtil`, `RenderThread`; the LWJGL GL and stb classes (105) | all unchanged, apart from the PNG decoder below |

What did change near the fixes, and why it does not matter to them:

- `PZArrayUtil.addAll(List, List)`, which `LoadDirBase` calls to copy the finished `loadList` on: its branch for a
  `PZArrayList` destination is gone with that class. The walk only fills `loadList`, still a `java.util.List`.
- `TileGeometryUtils.getDepthOnPlaneAt` (reached from a depth map's GPU update): `Core.tileScale` is gone and the two
  factors it scaled are now the constant they had at 2x. Same value as on a 2x game; the fix only orders the tasks.
- `PNGDecoder.decode`, `copy`, `copyRGBtoRGBA`, new `markOpaquePixels` (reached from `ImageData.<init>`): the opaque
  pixel mask is now built in a pass of its own instead of while copying. It produces the pixels the game later hands to
  `glTexImage2D`; the fix takes that call as it comes and reproduces nothing of the decoder.
- `GameWindow.enter()`, one of the four callers of `TileDepthTextureManager.init()`: the 1x texture packs and
  `Core.tileScale` are gone, the 2x packs load with the same flags as before. `IngameState.exit()`, another caller, no
  longer clears `Core.lastStand`. The other two callers (`Core.ResetLua`, `GameServer.doMinimumInit`) are unchanged,
  and so are the 8 methods that pump the file queue (`FileSystem.updateAsyncTransactions`), none of them inside
  `init`.
- The game's GL call sites for programs, buffer bindings, pixel store and `glGetError` are the same set of 183 in
  both jars, so the state the BC7 encoder saves and restores is still all the state it shares with the game.
- No fix names `Core.tileScale` or a 1x pack. `QualityCheck` reads `Tiles2x.pack`, which 42.22 still ships.

## The gate's own calls

- `Core.getGitRevisionString()` changed: it now returns a static field, `3d39148846` in this build, instead of a
  constant behind a dev check. Same meaning for `Session`.
- `Core.getVersion()` changed: it now ends in the game's integrity summary (`IntegrityReport.currentSummary()`,
  new in 42.22: `integrity=pending`, later a list such as `agents=<n>`). `Session` used it only in the mismatch
  message; the message is now built from `getGameAndBuildVersion()` (unchanged) and the revision.
- `GameServer.server`, `ZomboidFileSystem.getCacheDir()`: unchanged.

## Lua

The options page (`Dont39FasterLoading_Options.lua` on `mod-settings/Switches.lua`) uses `PZAPI.ModOptions`,
`getFileReader`, `getFileWriter` and `DebugType.Mod`. Vanilla `client/PZAPI/ModOptions.lua` is byte-identical in both
versions; the two global functions are unchanged.

## Unit tests on the 42.22 jar

`./gradlew :faster-loading:build :faster-loading:assembleMod`: pass, 74 tests, 1 skipped (the symbolic-link case, which
needs a user who may create one). `CommentsTest` and `RealFilesTest` compare with 42.22's own `stripComments`,
`FolderWalkTest` with 42.22's own `searchFolders`.
