# Research: launch items R3-R6 and Zabric hook capabilities

- **Query**: Part A: what Zabric hooks can do and the constraints on a client-only mod built on them. Part B: for R3
  (`ScriptParser.stripComments`), R4 (Lua compile), R5 (FMOD), R6 (`Item.DoParam`, `TexturePackDevice`): the 42.21
  method, its cost, whether it is safe to change, the hook point, and what other Java mods already patch there.
- **Scope**: internal (this repo, ZombieBuddy source, the 42.21 jar via `javap -c -p -l`, logs and Loading Progress
  reports). PZ_Optimization docs were used only for measurements and pitfalls.
- **Date**: 2026-10-21
- Jar: `E:/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid.jar`, 42.21.0 `4a0e9546ec`. Citations look
  like `Class.method Lnnn`, with source lines taken from the LineNumberTable.

---

## Files

| File | Contents |
|---|---|
| `zabric-hook-model.md` | Zabric handler kinds, hook declaration, manifest (preload, client-only), the JDK-only glue constraint, gates and switches, how ZombieBuddy stacks several mods' patches |
| `strip-comments.md` | R3: `ScriptParser.stripComments`, cost, exact quirks a replacement must keep, hook point |
| `lua-loading.md` | R4: `LoadDirBase` folder walk and Lua compile, hook points, checksum |
| `deferred-items.md` | R5 FMOD (deferred), R6a `Item.DoParam` (dropped), R6b texture pack index (deferred) |

## Other Java mods' patches on these methods (DebugLog `2026-10-21_20-55_DebugLog.txt`)

Every `[ZB] patching <class>.<method>` line was collected (274 distinct). For the methods of R3-R6:

| Method | Patched by | Evidence |
|---|---|---|
| `ScriptParser.stripComments` | Loading Progress only (1 advice: HEAD + FINALLY known-call bracket) | log L~127 `patching zombie.scripting.ScriptParser.stripComments with 1 advice(s)` at 20:55:27.744; `TriggerHooks.java:109-123` |
| `ScriptManager.Load` / `LoadFile` | Load: Loading Progress; LoadFile: none | `LoadHooks.java:86-101` |
| `LuaManager.LoadDirBase` (2 advices), `RunLua` | Loading Progress only | `LoadHooks.java:125-157` |
| `LuaCompiler.*`, `LexState.*`, `FuncState.*` | none | — |
| `KahluaThread.pcall` | Loading Progress | `TriggerHooks.java:30-40` |
| `Core.ResetLua` | Loading Progress + `Dont39AllInOne` ModSync (`ModSyncGlue$Core_ResetLua`) | log L210 |
| `ZomboidFileSystem.getRelativeFile` | **Viewpoint** (`viewpoint.Patch_RelativeFile`) | log L634, L1794. It is called once per Lua file in `LuaManager.searchFolders` L1273 (see the R4 caveat) |
| `FMODManager.init`, `GameWindow.mainThreadInit`, `RenderThread.init` | none | — |
| `FMODManager.loadSound` | Viewpoint (`viewpoint.Patch_LoadSound`) | log L478; not on the init path |
| `GameWindow.init/initShared/initFonts/enter/LoadTexturePack/mainThreadStart/...` | Loading Progress only | `LaunchHooks.java:20-60` |
| `Item.DoParam`, `Item.Load`, `TexturePackDevice.*`, `TexturePackPage.*`, `FileSystem.mountTexturePack` | none | — |

ShootToThrillMultiplayer's patch classes (`iseethem.Patch_*`) target none of these methods.

## Per-item verdict

| Item | Feasible with Zabric hooks? | Hook kind / point | Needs preload (JDK-only glue)? | Expected saving (source) | Risk |
|---|---|---|---|---|---|
| R3 stripComments | **Yes** | HEAD cancellable + `setReturnValue` on `ScriptParser.stripComments(String)String` (JDK-only) | Yes, if it must act on joins and reloads (`Zabric-Hooks: launch`); a launch-only version works without preload | Up to about 2 s per flow for vanilla `tileGeometry.txt`, plus mod tile files (archived `other-gaps.md:133-140`: 2.0 / 0.85 / 0.65 / 0.77 / 2.1 s). Bounded by the tiles steps: 4.3 s launch, 6.4 s join. PZ_O: 1.56 s → about 0 | Medium: exact quirks (depth-3, stray `*/`, `/*/`, seams, markers in strings) must match or fall back. The real method can be called in unit tests as the reference. Loading Progress hooks the same method |
| R4 Lua precompile | **Partly** | HEAD cancellable on `LuaCompiler.loadis(Reader,String,KahluaTable)` returning `new LuaClosure(proto, env)`; prototypes compiled on a pool, `Prototype.file/filename` restamped | Not JDK-only: needs a non-preloaded mod (off on joins to servers without it) or a `CheckJdkGlue` exception for Kahlua types | At most the compile share of 2.9 s (launch) / 2.75 s (join) of Lua file time; the share is unmeasured (PZ_O vanilla: 0.37 of 0.85 s). **About 4.8-6.2 s of the Lua step is the folder walk before the first file, which precompiling does not touch** | Medium: static `FuncState.currentFile` races, `rewriteEvents`/`reroute` must stay on the main thread at the original call. No checksum impact |
| R5 FMOD async | **Yes, limited** | HEAD on `RenderThread.init()V` starts `FMODManager.instance.init()` on a thread; HEAD cancellable on `FMODManager.init()V` joins | Yes (before the first mod list; default gate only) | Up to about 1.4 s, the window between `RenderThread.init` and `mainThreadInit` L609 in this log. PZ_O got about 1.6 s by moving the dependent constructions, which hooks cannot do. **Invisible in Loading Progress reports** | Medium-high: concurrent with window creation; COM/thread affinity unverified; `Core.debug` may change at L600 |
| R6a Item.DoParam switch | Only by reimplementation | HEAD cancellable with `@Self Item` on `DoParam(String,String)` | Needs `@Self`: non-preloaded mod only | Up to about 0.9 s per scripts load (PZ_O). Here inside the 2.3 s "after the last script file" row; unmeasured | High: about 365 copied branch bodies tied to 42.21; needs an equivalence check (field dump of every item) |
| R6b Texture pack index | **Yes** | HEAD cancellable on `TexturePackDevice.readPage(PositionInputStream)` with `@Self`, plus a cache file | Non-JDK: non-preloaded mod; launch only, which suffices | ≤ 0.45 s at launch (report "texture packs: 15, 447 ms"); PZ_O 0.5-0.6 s | Low-medium: cache invalidation by size and mtime; header parse reimplemented |

## Caveats / not found

- The relative order of stacked advices when one mod cancels at HEAD and another (Loading Progress) brackets the same
  method was not verified (`PatchEngine.java:280-300`).
- The FMOD duration on this machine could not be read from the log; only its start and the about 1.4 s window before
  it are visible.
- The compile versus execute split of Lua file time was not measured. The 4.8 s / 3.8 s gaps before the first Lua
  file come from the Loading Progress CSVs. Their cause (`getCanonicalFile` per entry, Viewpoint's `getRelativeFile`
  patch) is inferred from the bytecode, not profiled.
- The thread-safety of FMOD init beside GLFW and the COM apartment are reasoned from FMOD flag semantics, not checked
  in the FMOD docs.
- `external-repos/ZombieBuddy` is empty in this worktree; ZombieBuddy was read from the main checkout.
- The probe sources are in the scratchpad (`.../scratchpad/probe/`), not in the repo.
