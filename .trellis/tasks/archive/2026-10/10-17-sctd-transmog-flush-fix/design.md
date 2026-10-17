# Design: sctd-transmog-flush-fix

## Shape

Lua-only feature module `sctd-transmog-flush-fix/` (applies `pz-kotlin`, like `mod-options-sidebar/`), one file
`src/main/lua/client/Dont39AllInOne_SctdTransmogFlushFix.lua`. Client Lua loads after every mod's shared Lua, so
`SCTD_TransmogSync` exists when it runs; dedicated servers do not load it, and `Flush` does nothing there anyway.

Why Lua and not a Java hook: the bug is in another mod's Lua function; a runtime replacement leaves the files (and the
multiplayer checksum) untouched, and needs no Kotlin facade.

Why replace instead of wrap (the Wrapping Vanilla Lua UI rule says wrap): the faulty call is inside the body, so no
wrapper can avoid it. The replacement is allowed only while the original's source is byte-for-byte the verified text,
so it reproduces exactly that function with one expression changed.

## Gate (at file load, inside `pcall`)

1. `getModInfoByID("ShadowCatTacticsDispatch")` + `isModActive(info)`; not active → `idle: ShadowCatTacticsDispatch
   is not enabled`.
2. `SCTD_TransmogSync` is a table with function `Flush`, function `FlushPlayer`, table `players`; else error.
3. Read `media/lua/shared/LingLi_SCTD_Skins.lua` with `getModFileReader(id, path, false)` (resolves the mod's
   `42/` then `common/` dir, UTF-8). Nil reader → error. Close the reader on every path.
4. Find the line `function SCTD_TransmogSync.Flush()` (exactly once, else error) and the lines up to the first line
   that is exactly `end`. That block vs the expected text (constant in the file, copied from version 131):
   - equal → continue;
   - block has no `next(` → `idle: ShadowCatTacticsDispatch's SCTD_TransmogSync.Flush no longer calls next(), so this
     fix is not needed`;
   - else error naming the file and line.
5. The live function belongs to that block: `getFilenameOfClosure(Flush)` ends with `LingLi_SCTD_Skins.lua` and
   `getFirstLineOfClosure(Flush)` lies within the block's lines (exact first-line semantics of Kahlua not relied on).
   Else error ("another mod replaced SCTD_TransmogSync.Flush").

## Apply

```lua
Events.OnContainerUpdate.Remove(original); Events.OnTickEvenPaused.Remove(original)
SCTD_TransmogSync.Flush = fixed
Events.OnContainerUpdate.Add(fixed); Events.OnTickEvenPaused.Add(fixed)
```

`fixed` is the original body with `next(state.pending)` → a local `hasEntry(state.pending)` (`for _ in pairs(t) do
return true end`). It calls `SCTD_TransmogSync.FlushPlayer` outside any `pcall` (R3). If an apply step throws, undo
what was done (remove `fixed`, re-add `original`, restore the field), then report `disabled`. Log `ready` last.

Every Lua reload (joining a server with another mod list) re-runs the target file and this one, so the gate is
re-decided per load; nothing persists across reloads.

## Logging

`DebugType.Mod:println` / `:error` with prefix `[Dont39AllInOne] sctd-transmog-flush-fix: `, helpers as in the
sidebar file. One status line per load: `ready`, `idle: <reason>` or `disabled after an error while <what>: <err>` +
Lua stack. Nothing per tick.

## Build / packaging

- `settings.gradle.kts` include; `features` list in `dont39-all-in-one/build.gradle.kts` (Lua is copied into
  `42/media/lua` by `assembleMod`).
- `mod.info`: no `loadModAfter` entry for the target (decided during implementation). The game reads `loadModAfter`
  only in the mod-selector Lua; its auto-sort (`ModLoadOrderPanel` lines 206-253, 42.21) inserts every listed id and
  maps ones that are not enabled to `nil`, leaving a hole in the list for players without SCTD. Client Lua loads after
  every mod's shared Lua, so the order is not needed; the smoke test confirms (`ready`, not "SCTD_TransmogSync
  missing").
- `smokeTest`: besides the sidebar's exact `ready`, a Lua feature with a target mod must have a status line and must
  not be `disabled` (`idle` is fine, as for generated features). With the user's list it is expected `ready` (AC1).
- Version 0.8.0 → 0.9.0. Workshop: new section; the intro sentence "They change only the game itself and need no mods
  besides the requirements below" is adjusted, since this fix targets another mod (and does nothing without it).

## Rollback / retirement

When the author fixes `Flush`, the feature logs idle by itself (gate step 4). Remove the module in a later release
once the fixed SCTD version is out.

## Risks

- `isModActive` / `getModInfoByID` behaviour with the leading `\` of B42 ids: verified by AC1 (expects `ready`).
- `loadModAfter` naming a mod that is not enabled: breaks the mod selector's auto-sort (see above), so not used.
