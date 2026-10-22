# Research: where the Lua folder walk spends its time (offline measurement)

- **Date**: 2026-10-22. Game jar 42.21 `4a0e9546ec`; the game's JRE is Zulu 25.0.1 (`jre64`), the harness ran on
  GraalVM 25.0.2 on the same PC.
- Harness (scratchpad, not in the repo): calls the game's own `LuaManager.searchFolders(URI, File)` from
  `projectzomboid.jar` in a plain JVM (class init works without the game running) over the game's `media/lua/<sub>`
  plus `common` and `42` of every mod folder the live server's mod list maps to (372 roots), 3 rounds, warm OS cache.
  Version dirs are approximated by `42`; the in-game list has 3,138 files, the harness 2,536.

## The walk (`searchFolders`, bytecode in `../10-21-load-speed-optimization/research/lua-loading.md`)

| Variant | `shared` | `client` | Same list as the game's |
|---|---|---|---|
| Game's `searchFolders` | 3,096-3,376 ms | 791-894 ms | (reference) |
| `getCanonicalFile()` once per directory instead of per entry | 827-867 ms | 192-197 ms | yes, 833 / 1,703 files |
| No `getCanonicalFile()` (path built from the parent's path and `File.list()` names) | 104-118 ms | 35-36 ms | yes, on these folders |

Instrumented game walk (`shared`): `getCanonicalFile()` 7,067 calls, 2,978 ms (0.42 ms each); `isDirectory()` 7,439
calls, 49 ms; `File.list()` 1,560 calls, 75 ms; `ZomboidFileSystem.getRelativeFile(URI,String)` 833 calls, 6 ms.
`client`: canonical 1,982 calls 744 ms, `getRelativeFile` 1,703 calls 10 ms.

**Cause**: `searchFolders` L1269 canonicalizes the *parent* directory once per entry it lists, so a folder of n
entries pays n canonicalizations of the same path (~95 % of the walk). `getRelativeFile` is negligible.

In game the same span is 4.8 s (launch) / 3.8 s (join) before the first `shared` file; the rest is `LoadDirBase`'s
per-mod work outside `searchFolders` (one `getCanonicalFile()` and three `ZomboidFileSystem.getCanonicalFile(File,
String)` directory listings per mod dir, L1172-1198), a colder cache and a busier JVM. Only `searchFolders` is a
hookable JDK-only method there.

## Canonicalization on the game's JRE (Windows)

`File.getCanonicalPath()` on Zulu 25.0.1 resolves a junction to its target, and corrects case
(`junc/sub/A.LUA` → `real\Sub\a.lua`). So a path built from a canonical parent and a `File.list()` name equals its
canonical form unless the entry is a reparse point (junction, symlink, cloud placeholder) or its name is one Windows
path normalization changes (trailing dot or space). Linux: canonicalization resolves symlinks only.

## Other mods on this path

- **Viewpoint** `Patch_RelativeFile` (`3809306528/.../Viewpoint.jar`, `viewpoint/Patch_RelativeFile.class`): a
  ZombieBuddy `@Patch(className="zombie.ZomboidFileSystem", methodName="getRelativeFile")` whose advice binds
  `@Argument(0) String`. ZombieBuddy's signature-aware matching (`PatchEngine.java` ~L500-545, commit `9a08a54`) then
  applies it to `getRelativeFile(String)` only, **not** to `getRelativeFile(URI,String)`, which `searchFolders` calls.
  The earlier research's "Viewpoint patches the latter" does not hold for the walk.
- Loading Progress hooks `LoadDirBase()` and `LoadDirBase(String,boolean)` (`LoadHooks.java:130-141`), not
  `searchFolders`. Nothing on the live list patches `searchFolders` (DebugLog 2026-10-21).

## Lua compile share (precompile decision input)

Same roots, `LexState.compile` on every file's text (read beforehand): 2,536 files, round 0 (cold JIT) 938 ms,
rounds 1-3 730-771 ms; ≈ 1.15 s scaled to the in-game 3,138 files. Reading the files (`Files.readString`, first
read in the JVM) took 1,112 ms. The in-game per-file total is ~2.9 s (compile + read + run).

The compile share is at the 1 s threshold, but no preloadable hook point exists: the hand-in point
`LuaCompiler.loadis(Reader,String,KahluaTable)` names Kahlua types (rejected by `checkJdkGlue` in a preloaded mod),
and the JDK-only `RunLua`/`RunLuaInternal` would mean reimplementing ~60 lines of game logic
(`../10-21-load-speed-optimization/research/lua-loading.md`). Per the PRD rule, precompile is not done.
