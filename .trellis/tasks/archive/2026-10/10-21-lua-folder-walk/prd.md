# Lua folder walk speed-up (R4)

Parent: `.trellis/tasks/10-21-load-speed-optimization` (goal, evidence and decisions live there). Builds on the
Faster Loading mod from `10-21-faster-loading-strip-comments` (its switches, `Session` and failure handling).

## Goal

Cut the ~4.8 s (launch) / ~3.8 s (join) between `LuaManager.LoadDirBase` and its first Lua file, which goes to
building the file list, not to running Lua.

## Background (2026-10-22, `research/walk-measurement.md`)

- ~95 % of `searchFolders(URI, File)` is `getCanonicalFile()` of the parent directory, repeated once per entry it lists
  (`shared` offline: 7,067 calls, ~3.0 s of 3.1-3.4 s). `getRelativeFile(URI,String)` costs ~6 ms in total.
- Canonicalizing once per directory: 0.83-0.87 s; only where it can change the path: 0.10-0.12 s (`shared`). Same list
  as the game's on all 372 real roots.
- Viewpoint's patch applies to `getRelativeFile(String)` only, not the `(URI,String)` overload the walk calls. Nothing
  on the live list hooks `searchFolders`.
- Lua compile share ≈ 1.15 s of the ~2.9 s per-file time, but no preloadable hook point exists to hand a compiled
  prototype in (`LuaCompiler.loadis` names Kahlua types).

## Decisions

- (user, 2026-10-22) Walk variant B: skip the canonical call for directories whose path is canonical by construction;
  directories that are links (any reparse point) or have a name ending in a dot or space, and each walk's root, take
  the game's call, once per directory.
- Precompile is not done: the PRD rule needs both a ≥ 1 s share and a preloadable hook point; the second fails.

## Requirements

- Replace `searchFolders(URI, File)` (JDK-only signature, so preloadable) with a walk that produces the identical
  `loadList` content and order, still calling `ZomboidFileSystem.getRelativeFile(URI,String)` per `.lua` file so other
  mods' patches on it apply.
- Its own switch `luaFolderWalk` in `Dont39FasterLoading.ini` and on the options page, on by default; off means the
  game's own walk.
- A failure (I/O error, unreadable folder) reports through the mod's gate, stops its hooks and leaves the call to the
  game's walk; nothing partial reaches `loadList`.

## Acceptance Criteria

- [x] Unit tests: the walk equals the game's `searchFolders` (content and order) on the game's and installed Workshop
  mods' Lua folders and on generated trees with a junction, a symlink (where creatable), names ending in a dot or space,
  mixed case and empty folders.
- [x] In game, Loading Progress's Lua file rows are identical with the switch on vs off, on launch and join.
- [x] `LoadDirBase` → first file time drops by ≥ 50 % (median of 3 runs, launch and join) in Loading Progress reports.
- [x] Launch and an MP join on the local full server: no new errors, no checksum mismatch (the server does not check
  Lua checksums, `DoLuaChecksum=false`; covered by the identical lists, `verification.md`).
- [x] Precompile decision recorded with its measurement in `verification.md`.
- [x] `:faster-loading:check` and `:faster-loading:smokeTest` pass.

## Out of Scope

- Lua precompile (decided above) and prefetching Lua file reads.
- `LoadDirBase`'s per-mod work outside `searchFolders` (not hookable without reimplementing the method).
- Speeding up the mods' own Lua code or events.
