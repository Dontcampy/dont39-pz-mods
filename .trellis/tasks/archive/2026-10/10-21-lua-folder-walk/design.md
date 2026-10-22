# Design: Lua folder walk (R4)

Requirements: `prd.md`. Evidence: `research/walk-measurement.md`; the game's code:
`../10-21-load-speed-optimization/research/lua-loading.md`.

## Shape

| Where | What |
|---|---|
| `faster-loading/.../luafiles/FolderWalk.java` (new) | The walk: the same `.lua` relative paths, in the same order, as `LuaManager.searchFolders(URI, File)`, with `getCanonicalFile()` only where the game's result could differ from the path it built |
| `faster-loading/.../hooks/LuaHooks.java` (new) | `@Hooks(LuaManager.class)`, cancellable `@Inject` at HEAD of `searchFolders(Ljava/net/URI;Ljava/io/File;)V` |
| `settings/Fix.java`, the options page `FIXES`, `Translate/EN|CN/UI.json` | Switch `luaFolderWalk`, on by default |
| `README.md`, `mod/workshop/workshop.txt`, `build.gradle.kts` | Fix row, cancel exception stated, version 0.3.0 → 0.4.0 |

## The walk

The game's `searchFolders(base, fo)` (L1266-1279), per node:

- `fo.isDirectory()`: for each name of `fo.list()`, recurse on `new File(fo.getCanonicalFile().getAbsolutePath() +
  File.separator + name)`. The canonical call repeats once per entry (the cost).
- else, when `fo.getAbsolutePath().toLowerCase()` (default locale) ends with `.lua`: append
  `ZomboidFileSystem.instance.getRelativeFile(base, fo.getAbsolutePath()).toLowerCase(Locale.ENGLISH)` to
  `LuaManager.loadList`.

`FolderWalk` keeps every one of those steps and calls, in the same order, except the parent's path used for its
children:

- A directory with no entries is never canonicalized (as in the game, where the loop body never runs).
- Otherwise its children's parent path is `fo.getAbsolutePath()` when the path is known canonical, else
  `fo.getCanonicalFile().getAbsolutePath()`, computed **once** per directory.
- **Known canonical**: a directory reached as `<canonical parent> + separator + <name from File.list()>`, whose name
  does not end in `.` or a space, and whose no-follow attributes say neither `isSymbolicLink()` nor `isOther()`
  (a junction, a symlink, a cloud placeholder: any reparse point reads as one of them on Windows, a symlink on Linux).
  The root of each call is never known canonical. A failed attribute read counts as not known canonical, so that
  directory takes the game's call. The name test comes first: a name ending in `.` fails the attribute read
  (`walk-measurement.md`).
- Why that is the game's result: canonicalization only changes a path through links, case, short names and Windows
  name normalization (trailing dot/space). A listed name has the on-disk case and long name; links and odd names take
  the game's call. Verified on the game's JRE (Zulu 25.0.1): a junction canonicalizes to its target and reads
  `isOther`.
- `getRelativeFile(URI,String)` is still called on `ZomboidFileSystem.instance` per `.lua` file with the same
  arguments, so any mod's advice on that overload still applies (none today: Viewpoint's binds `getRelativeFile(String)`).
- `FolderWalk` takes the relative-path function as a parameter (the hook passes the game's), so the test can run it
  next to the game's method.

## The hook

- The game's method is recursive; the outermost call's handler walks the whole tree into a local list, appends it to
  `LuaManager.loadList` and cancels. The inner calls never happen. With the switch off it returns and the game's
  recursion runs (each inner call passes through the same switch check).
- **Cancel, not a rewrite**: no argument or return change can skip the per-entry canonical calls inside the method.
  Allowed by the spec's exception (`.trellis/spec/mod/zabric-hooks.md`, cancelling): the replacement covers the
  whole call, it cancels only after the walk succeeded, and nothing on the live mod list patches `searchFolders`
  (Loading Progress hooks `LoadDirBase`, not this). The README states it, like `glTexImage2D`.
- **Failure**: an `IOException` from a canonical call or attribute read the game would also make, or `File.list()`
  returning null (the game then throws a `NullPointerException`), makes the handler throw before it appends anything.
  The default gate stops all the mod's hooks and that call runs the game's own walk, which fails or succeeds as
  vanilla. Nothing partial reaches `loadList`.
- Callers: `LoadDirBase(String,boolean)` only (launch: shared, client; join/SP start/reload: `Core.ResetLua`; server
  Lua in SP `GameLoadingState.enter`). The game's other `searchFolders` methods (scripts, animations, texture packs,
  `ZomboidFileSystem`) are different methods without the per-entry canonical call.
- Client only, build check and switch read through `Session.on(Fix.LUA_FOLDER_WALK)` like the other fixes.

## Tests (`FolderWalkTest`, game jar on the test classpath as for comment stripping)

The game's `LuaManager.searchFolders` is the reference (it runs in a plain JVM, `walk-measurement.md`); each case
compares the game's list with `FolderWalk`'s, content and order:

- The game's `media/lua/{shared,client,server}`; every installed Workshop mod's `media/lua/{shared,client,server}`
  under each of its version folders (skipped when a folder is not there).
- Generated trees in a temp folder: nesting, mixed-case names, `.LUA`, non-Lua files, empty folders, a root that is
  a file or missing; a junction (Windows, `mklink /J`); a symlink (skipped without the privilege); a folder named with
  a trailing dot or space and one with its twin without it (Windows, created through `\\?\`).
- Canonical calls: on the real folders `FolderWalk` canonicalizes only the roots (count through a test seam or a
  timing bound), to keep the speed-up from regressing silently.
- A folder whose `list()` fails throws and returns nothing.

## Verification in game

Local full server with the live mod list (`fullsrv` tooling), nosteam, real copies; switch on vs off, 3 launches and
3 joins each:

- `loadList` identity: Loading Progress's CSV has a row per Lua file run, in order; the rows are identical on vs off
  (launch and join). A join with a different list would also fail the server's Lua checksum.
- Time: `LoadDirBase` HEAD → first `shared` file and → first `client` file, from the CSV; medians on vs off.
- `console.txt`: no new errors, `[ZB] patching zombie.Lua.LuaManager.searchFolders` shows only our advice,
  `active: ... lua folder walk on`.

## Rollback

`luaFolderWalk=false` in `Dont39FasterLoading.ini` (or the options page) restores the game's walk from the next launch.
