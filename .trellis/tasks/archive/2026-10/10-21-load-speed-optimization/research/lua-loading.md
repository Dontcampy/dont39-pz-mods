# Research: R4 Lua folder walk and compile

- **Date**: 2026-10-21. Split from the launch-items research; index and per-item verdict: `launch-items.md`.
- Jar: `E:/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid.jar`, 42.21.0 `4a0e9546ec`. Citations look
  like `Class.method Lnnn`, with source lines taken from the LineNumberTable.

## R4: Lua compile (`LuaManager.LoadDirBase` → `RunLua` → `LuaCompiler.loadis`)

**Call path**:

- **Launch**: `GameWindow.init` L1146 calls `LuaManager.LoadDirBase()`, which runs `initChecksum()`, then
  `LoadDirBase("shared")`, then `LoadDirBase("client")` (`LoadDirBase() L1260-1263`).
- **Join / SP start / reload**: `Core.ResetLua` L4169 calls `LoadDirBase()`. Server Lua is loaded by
  `GameLoadingState.enter` L157 calling `LoadDirBase("server")`. Also `IngameState.exit` L1082.
- **`LoadDirBase(String,boolean)` L1144-1234**:
  1. Builds `loadList` through `searchFolders(URI, File)` (L1266-1279, recursive).
     - Each entry does `new File(dir.getCanonicalFile().getAbsolutePath() + sep + name)`.
     - Each `.lua` file does `ZomboidFileSystem.getRelativeFile(URI, String)`, lower-cased.
  2. Walks the mods' common and version dirs (L1164-1198) and sorts with `CASE_INSENSITIVE_ORDER` (L1201-1207).
  3. Then, per file (L1211-1232): dedups with a `HashSet`, runs `RunLua(absPath)` (L1225) unless `bNoRun`, then
     `NetChecksum.checksummer.addFile(rel, abs)` when `!checksumDone && !contains("SandboxVars.lua") && (server ||
     client)` (L1228-1229), then `CoopMaster.update()`.
- **`RunLuaInternal(String,boolean)` L1352-1409**:
  - Normalizes slashes and returns `loadedReturn` if the file is already in `loaded`.
  - Sets the **static** `FuncState.currentFile = name after the last '/'` and `FuncState.currentfullFile = path`
    (bytecode 31-47).
  - Opens `IndieFileLoader.getStreamReader(path)`, a UTF-8 `InputStreamReader` with no BOM handling
    (`IndieFileLoader.getStreamReader L21-31`).
  - Sets the **static** `LuaCompiler.rewriteEvents = bRewriteEvents` (bytecode 86-87).
  - Calls `LuaCompiler.loadis(new BufferedReader(isr), fileName, LuaManager.env)` (L1372).
  - Runs the closure with `LuaCaller.protectedCall` (L1394), records `loaded`/`loadedReturn` and calls
    `CraftRecipe.onLuaFileReloaded` (L1387).

**The compiler**:
- `LuaCompiler.loadis(Reader,String,KahluaTable)` L117 calls the private `loadis(reader, name, null, env)` L125:
  `new LuaClosure(LexState.compile(reader.read(), reader, name, source), env)`.
- `LexState.compile(int, Reader, String, String)` (L181-) creates a fresh `LexState` and `FuncState`. It has no
  `putstatic`, and outside `<clinit>` it reads only `Core.debug`.
- **But `FuncState.code` L1074-1089 stamps `Prototype.file = FuncState.currentFile` and `Prototype.filename =
  FuncState.currentfullFile` (global statics) on every emitted instruction.** A compile on another thread races with
  the main thread's statics. Its prototypes, including nested `Prototype.prototypes`, would need these two fields
  set afterwards to what the main thread would have set.
- `LuaClosure.<init>(Prototype, KahluaTable)` L45-53 calls `LuaEventManager.reroute(proto, closure)` and
  `MapObjects.reroute(...)` when `LuaCompiler.rewriteEvents` is set.
  - `reroute` (`LuaEventManager.reroute L935-946`) matches existing event callbacks by `Prototype.filename` and
    `Prototype.name`. It is the hot-reload path.
  - So the closure must still be created on the main thread at the original call, with the correct `filename`.
- `LuaCompiler.rewriteEvents` is also read at run time in `KahluaThread.luaMainloop` L387 and `Event$Add.call` L76.

**Can compile run ahead on a pool?**
- Thread-wise yes. The compiler is per-instance except for the two `FuncState` statics, which only affect
  `Prototype.file`/`filename`.
- A handoff point is `LuaCompiler.loadis(Reader,String,KahluaTable)` HEAD, cancellable:
  - Read the reader's characters and look up a prototype compiled ahead for (path, content).
  - Set `Prototype.file`/`filename` recursively.
  - `cir.setReturnValue(new LuaClosure(proto, env))`.
  - On a miss, compile the same characters from a `StringReader`.
  - PZ_Optimization edited this same overload the same way (`docs/override-edits.md:729-740`); their pitfall note is
    that the precompiler must stamp `file`/`filename`.
- The file list is available as `LuaManager.loadList` (public static; Loading Progress reads it in
  `LoadHooks.java:147-151`) from the first `RunLua` of a folder on. To start earlier, the list has to be rebuilt by
  the same rules (`searchFolders` + mod dirs + sort).

**MP checksum interaction**: none from precompiling.
- `NetChecksum.Checksummer.addFile(String,String)` reads the file itself (`Files.readAllBytes`, L39) and hashes the
  bytes (L47-52). Compile output is not involved.
- PZ_Optimization's "join fix" was about **its own** Lua files installed into `media/lua/{shared,client}/pzopt/`,
  which the server did not have (`File doesn't exist on the server`). They exempted those paths in `addFile`
  (`docs/override-edits.md:2017-2045`; `docs/guide/limitations.md:15-23`).
- A Zabric mod's own Lua (an options page) is loaded only when the mod is in the active mod list, and a join uses the
  server's list. Loading Progress ships such a page and joins work. **It is not a precompile issue.**

**Hook point and constraint**:
- The signature of `loadis(Reader,String,KahluaTable)LuaClosure` names `se.krka.kahlua.vm.KahluaTable` and
  `LuaClosure`, so it is **not JDK-only**. `checkJdkGlue` rejects it in a preloaded mod.
- It works in a non-preloaded mod: hooks open at the launch's first mod list, which comes before the launch's
  `LoadDirBase` (`Flow.java` LAUNCH order MODS → … → LUA). But then it is off during joins to servers that do not
  list the mod.
- No JDK-only method in the path can hand a prototype in.
  - `RunLua(String,boolean)Object` and the private `RunLuaInternal(String,boolean)Object` are JDK-only, but
    replacing either means reimplementing about 60 lines of game logic: `loaded`, `loadedReturn`, error logging,
    `CraftRecipe.onLuaFileReloaded`.

**Cost (important caveat)**:
- Launch report `2026-10-21_20-56-19_launch`: Lua step 9,116 ms. All 3,138 Lua files took **2,887 ms** in total
  (compile + execute).
  - The CSV shows **4,847 ms from `LoadDirBase()` HEAD to the first `shared` file** and **1,378 ms before the first
    `client` file**.
- Join report `2026-10-21_21-11-04_start`: Lua 6,651 ms. Files took 2,753 ms; there were 3,824 ms and 1,016 ms
  before the first file of each folder.
- So **about two thirds of the Lua step is the folder walk and list building in `LoadDirBase(String,boolean)`, not
  compile.**
  - Candidates seen in the bytecode: a `getCanonicalFile()` per directory entry (`searchFolders` L1269) and
    `getRelativeFile` per Lua file (L1273), which Viewpoint patches.
  - Not measured further here.
- The compile share of the 2.8-2.9 s is unknown for this mod set. PZ_Optimization measured 0.37 s compile of 0.85 s
  for vanilla `LoadDirBase` (`plan-instant-load.md` B4), and about 1.7 s of Kahlua compile across boot and load.

