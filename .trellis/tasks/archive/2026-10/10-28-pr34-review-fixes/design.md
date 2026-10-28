# Design: PR 34 review fixes

Three independent areas; the takeover goes last because it uses the new shared helpers and appearance API.

## Zabric (R3, R10)

- `Loader` (seam) gets the class list by reflection on `Exposer.getClassesWithGlobalLuaMethod` (`getDeclaredMethod` +
  `setAccessible`): it is public in the installed ZombieBuddy and package-private upstream, so one reflective path
  works with both; the comment says why (last-resort rule). Still throws naming the loader version when absent.
- `Loader.newGlobalLuaInstance(Class)` holds "as ZombieBuddy instantiates it" (declared no-arg constructor,
  concrete-class check like ZombieBuddy's message). `LuaGlobals` keeps the presence check and the post-check.
- Considered and rejected: `Exposer.afterExposeAll()` (public upstream) re-exposes every global class on a new
  instance, splitting state of classes already exposed at this Lua state's init, and loses the per-class error.
- `Lifecycle`: `exposeMissing` before the `stopped` early return, gated on `zabricLoaded`.
- Tests through a package-private `exposeMissing(env, exposer, source)` seam.

## appearance-override (R2, R4–R7)

- `allinone-common/src/main/lua/shared/Dont39AllInOne_CommonShared.lua` (or move `Common` there): `log`, `guard`.
- API: `Appearance.put(player, item, override)` writes (validated, synced) without publishing; `setHidden`/`setLook`/
  `clear` = put + publish; `Appearance.lookOf(item)`, `copyLook` move here from the takeover.
- Kotlin owns the look's canonical form: a Lua global returning (problem or nil, canonical string / normalised table);
  `describe` and the stored copy use it.
- Server: cache override table → canonical text, pruned each pass to the online players' items.
- Client `luaClear` resets the dropped players' models.

## sctd-render-takeover (R1, R2, R8, R9)

- Migration state: `scanned` rebuilt each tick from the online list (by player object, but only those online);
  `left` keyed by item id, pruned with `scanned`; `converting` dropped; `Takeover.busy` a plain table cleared on
  disconnect and through `Takeover.whileBusy(player, fn)`.
- Auto-hide / restore / `hideItemServer` with `deferSync` use `Appearance.put`, one publish at the end.
- Files: `shared/...Shared.lua` (record, body moves), `shared/...SourceGate.lua` (generic gate),
  `server/...Takeover.lua` (entry: gate + runtime + install), authoritative and UI parts as modules.
- UI: wrap SCTD's originals with a temporary `SCTD_Transmog_IsInvisVariant` answering for the current item; keep a
  copy only where SCTD's code makes the swap unsafe (documented).

## Deviations (implementation)

Zabric:

- **Session state on an instance**: `LuaGlobals` keeps `failed`/`unavailable` on one session object; the test seam is
  its `exposeMissing(env, exposer, source)`, so each test gets a fresh session. `synchronized` dropped: the only caller
  holds `Lifecycle`'s lock.
- **The exposure runs after the main phase's try**, so it also runs on a pass whose entrypoints failed (`stop`), not
  only on one that was stopped before. A failure reading `LuaManager`'s fields latches `unavailable` like a failing
  class list.
- **`Loader.globalLuaClasses` checks the result** is a list of classes and throws naming the loader version otherwise.
- **`GlobalLuaClassesTest`** (loader package) reads the list from the installed `ZombieBuddy.jar` through the
  reflective path.

appearance-override and allinone-common:

- **Common moved, not copied**: `allinone-common/src/main/lua/shared/Dont39AllInOne_CommonShared.lua` holds `log`,
  `logError`, `guard` (now with `guard.reason` and `guard.run(what, fn, ...)`), `isPlayer` (the animal check) and
  `every(ms, fn)` (the time-paced ticker); the client `Dont39AllInOne_Common` copies its functions, so its requires are
  unchanged.
- **Kotlin owns the look's form** through `Dont39AllInOne_readAppearanceOverride(table)` →
  `{ override = <table to store>, text = <signature part> }` or `{ problem = … }` (`Override.toTable`/`text`), and
  `Dont39AllInOne_readAppearanceOverrideLook(item)` (`Looks.of`, the takeover's `lookOf` ported to typed Kotlin). The
  stored copy holds each value as drawn: tint and hue as the float the visual gets, a missing hue as 0.
  `Appearance.copy` is a generic deep copy, so Lua lists no look field. `Dont39AllInOne_checkAppearanceOverride` stays
  for the takeover until it moves to `put`.
- **`Appearance.write(item, override)`** besides `put`: checked, no sync, no publish, allowed on an MP client, for the
  migration's new item before its add packet and the client's own copy during a skin edit.
- **`Appearance.KEY` stays duplicated** with `Override.KEY`: Lua needs it before (or without) the Java side, e.g. in
  `Appearance.get`.
- **Read cache** per player key, rebuilt from the worn tables on each publish (so pruned to them), and dropped for
  players no longer online by `Appearance.publishOnline` (the server's 1 Hz check); the sent revisions stay, as a
  rejoin must replace the set the clients hold.
- **`ClientSets.find`** uses `GameClient.getPlayerFromUsername` in multiplayer: a client's local players are in
  `IDToPlayerMap` too (`ConnectedPacket.parse`, javap); single player matches the slot key. `luaClear` resets the
  models of the dropped players that had overrides, and is gated like the other puts.

sctd-render-takeover:

- **Files**: `shared/...SourceGate.lua` (generic gate: `SourceGate.new(modId, files, blocks)` with `cut(visit,
  readLines)` and `check(identity)`, plus `hash`, `digits`, `isFrom`, `describe`), `shared/...Shared.lua` (record,
  busy, worn items, looks, SCTD's `FILES`/`BLOCKS`, `Takeover.gate`, `Takeover.check`), `shared/...Runtime.lua`
  (stopped/idle, `fail` = `Common.guard`'s, `replace` and put-back, `guard`, `takeOrPass`, `refuse`),
  `shared/...Authoritative.lua` (hide, apply, restore and its wrapper, auto-hide, look edits), `shared/...Ui.lua`
  (panel wrappers, skin panel), `shared/...Migration.lua`; `server/...Takeover.lua` is the entry (gate, install,
  status line). The modules sit in `shared/` so `require` finds them on every side; Ui is required only when
  `not isServer()`.
- **Gate**: each file is read once and indexed by the headers its blocks name; per block, in the table's order, the
  same not-found / more-than-once / no-closing / not-reviewed / identity errors as before (Kahlua comparison of old
  and new gate on SCTD's real files with 7 injected faults: identical results). `BlockHashes.java` (archived task)
  now runs `Takeover.gate.cut` and loads the modules next to the given one through `require`.
- **Check failure through `Runtime.fail`**: the "disabled after an error while checking SCTD's code" line is the same,
  and a later error of the Restore wrapper then logs its "stopped clearing the looks" line instead of a second
  "disabled".
- **`Takeover.whileBusy`** raises an error again with the stack where it was first raised appended (Kahlua's `error`
  keeps no stack), also for SCTD's error out of its own Restore.
- **Look check**: `Takeover.drawableLook(item)` (`Appearance.lookOf` + `Dont39AllInOne_checkAppearanceOverride`) for
  the three places that turn a look the override cannot draw into an error line or a left item; `put`/`write` still
  check again.
- **deferSync**: hide and restore with `deferSync` `put` without publishing; `SCTD_AutoHide_Reconcile` publishes once
  when a step changed something; SCTD's `MigrateLegacy` ends with `BatchSync`, whose clothing event publishes. The
  Restore wrapper's clear after SCTD's own Restore honours it the same way.
- **Migration**: `scanned[player] = { left = ids }`, rebuilt each 1 Hz pass from `getOnlinePlayers()` (single player:
  the local players, at `OnGameStart` and `OnCreatePlayer`); `converting` dropped (the busy flag covers it). An error
  in the conversions disables the migration before `finish` (its clothing event would try the rest again); a second
  error in `finish` gets its own "also failed finishing" line.
- **UI wrapped, not copied (R9)**: `MatchesBaseFilter`, `DrawHideButton`, `ClickHide` and the panel's row closure call
  SCTD's own code; for an item with this feature's `hidden` record, `SCTD_Transmog_IsInvisVariant` answers true for
  that item's type during the call and is put back after (also on error, and only if still ours; a wrapper another
  mod built on it meanwhile keeps only SCTD's answer after the call). Nothing else they call reads it
  (`IsForbidden`, `IsProtected`, `HideBounds`, the theme and list helpers). In `ClickHide` the answer
  stays in effect while SCTD's `ShowItem` runs for that item: on a client it only sends a command; in single player
  it calls `SCTD_Transmog_Restore`, which for this feature's item is `restoreOurs` (no placeholder check, no clothing
  event). Kahlua comparison with the old copies over 7 item kinds: same draws, filters and clicks. SCTD's errors in
  these calls now go on as SCTD's instead of disabling the takeover. `Takeover.isHidden` is gone (no caller left).
