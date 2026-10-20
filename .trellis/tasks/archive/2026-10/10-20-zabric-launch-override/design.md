# Design: Zabric mod folder override for one launch

## Decision: Zabric applies the override itself, from a file read at premain

The PRD's open choice was between (a) letting a preloaded mod's hooks open from preLaunch, with the feature becoming
a preload mod, and (b) a Zabric-owned transform of the folder resolution driven by a file read at premain. (b):

- The consumer is Kotlin (`mod-sync` in All-in-One). PreLaunch code is "plain Java, no Kotlin, no game classes"
  (`zabric/mod/workshop/workshop.txt`), and any `kotlin.*` class loaded before ZabricKotlin's `Main` beats its swap
  ([kotlin-runtime.md](../../spec/mod/kotlin-runtime.md#library-zb-kt)). Hooks of a Kotlin mod cannot open that early.
- (a) would also make All-in-One a preload mod, with a restart after every install or update for every player.
- (b) needs no mod code before the startup pass, and the request is plain data.

Rule changed on purpose: Zabric gets a second advice on a game method next to its preLaunch hook on `main`
([access-widening.md](../../spec/mod/access-widening.md#zabric), "no patches of its own"). Like that hook it names the
class by string at premain, loads no game class, touches no game type in its advice, logs nothing there, and reports
at MAIN. It is installed only on a launch that has a request.

## Granularity: a Workshop item's `mods` folder

The override replaces an installed Workshop item's `<item>/mods` in `ZomboidFileSystem.getInstalledItemModsFolders`
with another folder holding mod folders, the same point and unit as the core child's swap
([Swap.kt](../../../mod-sync/src/main/kotlin/com/dont39/pz/modsync/client/swap/Swap.kt)):

- Every lookup (id → folder, the mod list UI, `require=`, ZombieBuddy's jar resolution) goes through that list
  ([mod-discovery.md §1-2](../10-20-server-mod-sync/research/mod-discovery.md)), so Lua and Java of all mods in the item
  come from one folder for the whole launch (R2), with no duplicate ids.
- The game derives `workshopId` from `<x>/<item id>/mods/<mod>`, so the core child's trees
  (`.../<item id>/<timestamp>/<item id>/mods`) keep the item id.
- The PRD's "mod ids → folders" becomes "Workshop item ids → `mods` folders"; it covers all `mod-sync-java` needs.
  Server-only mods (a later follow-up of the parent) would add a folder, which this contract can grow into.

## Public API

New package `com.pzlauncher.zabric.mods` (the loader package stays internal to Zabric and `zb-kt`,
[zombiebuddy-patching.md](../../spec/mod/zombiebuddy-patching.md#loader-seam)):

```java
LaunchOverride.request(Map<String, Path> modsByItem)   // for the next launch; replaces a pending request
LaunchOverride.pending()                               // the request the next launch will apply, or empty
LaunchOverride.current()                               // what this launch applies (item id → folder), or empty

JavaMods.loaded()             // every jar the loader loaded this JVM: mod id, path, SHA-256, LoadPhase
JavaMods.lastPass()           // the jars of the last pass over the mod list
JavaMods.servingJar(String pkg)   // the jar the system class loader serves that package from, or null

WidenRoots.add(Path modsFolder)   // widen the mods in it from the next launch on, while it exists
WidenRoots.scanned(Path modsFolder)   // whether this launch's premain read its lists
```

`LoadPhase`: `PRELOAD` (ZombieBuddy's premain), `STARTUP` (the first pass), `LATER` (a join or leaving). "Loaded by
the startup pass" in `mod-sync-java` R3 is `PRELOAD` or `STARTUP`.

Calls are main-thread, on a launch where Zabric ran at game start and its main entrypoint ran without stopping; they throw on misuse (no main yet, a relative path, an
item id that is not digits) and on loader mismatch, naming the loader version. Javadoc carries the contract; the
author contract in `workshop.txt` gains one line per class.

## Request file

- `<cache>/Zabric/launch-override-client.properties` and `-server.properties` (the game's `-cachedir`): key = Workshop
  item id, value = absolute path of a folder holding mod folders. `java.util.Properties`, written to a temp file and
  moved into place. One file per side, because a dedicated server on the same machine shares the cache folder and
  would otherwise consume the client's request: `request` writes its own side's file, premain reads the file of the
  side its main class names (`sun.java.command`, `PreLaunchHook.TARGETS`); an unknown main class reads none, an error at
  MAIN only when a request file of either side exists (R6). `request(Map.of())` withdraws a pending request.
- `request` checks before writing: each folder exists and holds at least one mod folder (`common/mod.info` or a
  version folder's `mod.info`); no mod in the folder, nor in the client's installed copy of that item, has the id
  `ZombieBuddy`, `Zabric` or `ZabricKotlin` (R5). Failing items throw and nothing is written.
- Premain, in order: read the request, `AccessWidener.install` (so that it scans the override folders, R4), install the
  advice, `Lifecycle.install`. The request is read and the file deleted before anything else, so a crash later in this launch cannot carry it over (R1). Then the same checks as `request` with
  plain file IO. Any failure discards the whole request (nothing applied) and is reported at MAIN as an error naming
  the item; there is no partial apply.
- The advice is installed only when a valid request was read.

## Advice

`getInstalledItemModsFolders(List<String>)` RETURN (signature checked with `javap`): of the entries this call added,
one whose normalized path is `<x>/<id>/mods` for a requested id is replaced by the requested folder; entries already in
the list (other roots) are left alone. `ZomboidFileSystem` is already loaded at Zabric's premain (ZombieBuddy patches it
first; [spike.md](../archive/2026-10/10-20-mod-sync-core/research/spike.md), `zombie.ZomboidFileSystem`), so the advice is
installed by retransformation, like `ModsLoadedHook` on ZombieBuddy's `Loader`: class described from its bytes
(`POOL_ONLY`) so no game class loads, no class format changes, fail-fast. The transformer stays registered, so a later
ZombieBuddy retransformation (a mod's hooks on `ZomboidFileSystem`, such as the core child's `FolderSwap`) gets the
advice again beneath its own: their RETURN advice sees the list after the override
([ItemFoldersHook.java](../../../zabric/src/main/java/com/pzlauncher/zabric/mods/impl/ItemFoldersHook.java)). With the core swap on for the
same item and the same tree, `Swap.apply` finds no install folder to map and `Swap.verify` finds the tree, so both
agree; `mod-sync-java` requests the tree the core child built.

A requested item not in the list on the first call (unsubscribed, or Steam off) is an error at MAIN naming it; the
other items still apply, since each item's folder is independent and the consumer checks `current()` against what it
asked for.

## Reporting

At MAIN (`Main.main`, beside `AccessWidener.report`), in Zabric's log prefix:

- `launch override: Workshop item <id> uses <folder> this launch` per applied item.
- Errors: the discarded request with the reason; an item the game did not list; an advice that failed to install, to
  weave (also on a later retransformation) or threw, or a method the game never called before MAIN. Any of these stops
  the override for the rest of the launch, and `current()` then throws.
- On a server: the same lines (a dedicated server has the same method; nothing in `mod-sync` uses it there).

## Accessors (R3)

- `JavaModLoadState` has `sha256`; `LoadStatus` reads it next to `id`, `jarPath`, `reason`
  ([LoadStatus.java](../../../zabric/src/main/java/com/pzlauncher/zabric/loader/LoadStatus.java)).
- The phase comes from Zabric's own pass count (`Lifecycle.modsLoaded`): jars first seen as `loaded` in the first
  pass are `STARTUP`, later ones `LATER`; `preloaded` ones `PRELOAD`.
- `servingJar(pkg)`: the record has no package, and the class path decides which copy runs
  ([java-mod-restart.md §2](../10-20-server-mod-sync/research/java-mod-restart.md)). Every `.class` of the package in
  the loaded jars is looked up through the system class loader; the jar the URLs name is the answer. Null when no loaded
  jar holds the package; throws when the classes come from several jars or from a jar the loader did not record.
- `lastPass()`: the pass's jars as Zabric records them at `modsLoaded`. ZombieBuddy never drops a record, so a mod whose
  jar changed between passes lists both; `servingJar` tells which one runs.
- A loaded jar without a hash (ZombieBuddy could not read it) makes `loaded` and `lastPass` throw.

## Widening (R4)

- `ModFolders.roots()` gains: this launch's override folders, and the folders in `<cache>/Zabric/widen-roots.txt`
  that still exist (one absolute path per line; missing ones are dropped from the file at MAIN).
- Both are plain `mods` folders (`Root(dir, null)`). `scanned` answers from the premain scan's roots.
- A folder added this launch is widened from the next launch; `mod-sync-java` decides what that means for a join.

## Compatibility

- Growth only: new package, new classes, new loader-seam methods; nothing removed or renamed
  ([zabric-hooks.md](../../spec/mod/zabric-hooks.md#publishing-and-compatibility)).
- A launch without the files behaves exactly as today (no advice installed, roots as before).
- Released as the next minor Zabric version (new API): 0.5.0 if it ships before the loading-progress branch's Zabric
  changes (`claude/trellis-loading-progress-tasks-67a636`, also 0.5.0), else the one after; whichever lands second
  rebases and resolves `Lifecycle.java`, `LoadStatus.java`, `Loader.java`. `mod-sync-java` requires it.

## Risks

- Advice on a game method from Zabric: a game update renaming `getInstalledItemModsFolders` breaks it; the weave
  error is reported at MAIN and the override does not apply (the startup pass then loads the client's copy, and
  `mod-sync-java` sees it through `JavaMods`).
- The weave test covers retransformation on a stand-in class with a ZombieBuddy patch on top; the real
  `ZomboidFileSystem` described with `POOL_ONLY` is proven only in game (implement step 8; smokeTest writes no request).
- `servingJar` assumes the system class loader serves mod classes (ZombieBuddy's `appendToSystemClassLoaderSearch`).
- Two requests in one launch: the last wins (one launch can hold one version per package anyway).
