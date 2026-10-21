# Lua folder walk speed-up (R4)

Parent: `.trellis/tasks/10-21-load-speed-optimization` (Goal, evidence and decisions live there).

Depends on `10-21-faster-loading-strip-comments` (the mod, its switches and failure handling).

## Goal

Cut the ~4.8 s (launch) / ~3.8 s (join) between `LuaManager.LoadDirBase` and its first Lua file, which goes to
building the file list, not to running Lua.

## Requirements

- First measure where the walk's time goes (canonical-path lookups per entry, `getRelativeFile` per file and
  Viewpoint's patch on it, sorting), since the cause is inferred from bytecode only
  (`../10-21-load-speed-optimization/research/lua-loading.md`).
- Then replace or speed up `searchFolders(URI, File)` (JDK-only signature, so preloadable) with the same result:
  identical `loadList` content and order, and other mods' patches on `getRelativeFile` still applied.
- Its own switch in the mod's ini, on by default.
- Lua precompile: measure the compile share of Lua file time. Implement only if it is ≥ 1 s and a preloadable hook
  point exists; otherwise record the numbers and close it as not worth doing.

## Acceptance Criteria

- [ ] `loadList` dumped with the switch on vs off is identical on launch and join.
- [ ] `LoadDirBase` → first file time drops by ≥ 50 % (median of 3 runs) in Loading Progress reports.
- [ ] Launch and an MP join: no new errors, no checksum mismatch; Viewpoint's `getRelativeFile` patch still logs as
  applied.
- [ ] Precompile decision recorded with its measurement.
- [ ] Build gate passes.

## Out of Scope

- Speeding up the mods' own Lua code or events.
