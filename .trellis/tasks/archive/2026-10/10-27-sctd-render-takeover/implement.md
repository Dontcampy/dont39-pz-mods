# Implement: SCTD render takeover

Read first: `prd.md`, `design.md`, `../10-27-sctd-render-transmog/research/sctd-integration.md`, the appearance API
(`appearance-override/src/main/lua/shared/Dont39AllInOne_AppearanceOverrideShared.lua`), and SCTD's source at
`E:/SteamLibrary/steamapps/workshop/content/108600/3689462578/mods/ShadowCatTacticsDispatch/common/media/lua/`.
The removed `sctd-transmog-flush-fix` (`git show d57f9dc:sctd-transmog-flush-fix/src/main/lua/client/Dont39AllInOne_SctdTransmogFlushFix.lua`)
is the model for reading SCTD's file, closure identity and idle/disabled lines.

## Steps

1. **Module.** `sctd-render-takeover/build.gradle.kts` (`pz-bundled-feature`, like `mod-options-sidebar/`);
   `include` in `settings.gradle.kts`; add to `features` in `dont39-all-in-one/build.gradle.kts` after
   `:appearance-override`; `luaTargeted = listOf("sctd-render-takeover")`; one line in `workshop.txt` (what a player
   gets with SCTD; nothing without it). No version bump (the parent's last child does it).
2. **Reviewed blocks.** Extract every block in design.md's list from the installed SCTD into
   `research/sctd-blocks/<file>__<name>.lua` exactly as the gate will cut it (header line to the indent + `end` line,
   `\n` joined, no trailing newline). Record each block's length and hash (computed by the same Lua function, run
   through `LuaCheck`-style Kahlua or a throwaway Lua script under GraalVM's java with `projectzomboid.jar`) in a table
   in the feature's Lua. Re-read each block while writing the replacement that depends on it.
3. **Record module** (`shared/Dont39AllInOne_SctdTakeoverRecord.lua`): record constructor and readers, `isOurs`,
   `isHidden(item)`, `lookOf(item)` (confirm with `javap` on `zombie.core.skinnedmodel.visual.ItemVisual` whether an
   inventory item's visual can still hold an unpicked value; handle as design.md says).
4. **Gate** in `server/Dont39AllInOne_SctdTakeover.lua`: idle / disabled / ready as design.md, all blocks on every
   side, nothing installed on any failure.
5. **Authoritative replacements** (where `not isClient()`): `HideItem_Server`, `Apply` (normal, cross-slot, invert),
   `Restore`, `AutoHide_Reconcile` with the copied `CanAutoHide`; the `lookSkin`/`lookColor` handler; the R7a wrapper,
   installed even when the gate failed (only needs `SCTD_Transmog_Restore` to exist and `Appearance.problem()` nil).
6. **UI replacements** (where `not isServer()`): filters, hide button, click, preview skip, panel row closure via the
   `createChildren` wrapper, skin list wrapper, `ChangeSkin`/`ChangeColor` wrappers.
7. **Runtime guard**: one helper that runs a replacement in `pcall`, and on error logs, puts every replaced global back
   (keeps the R7a wrapper), sets the status and returns `false`.

Comments: short, only why (memory rule). Errors: never a neutral fallback; a mismatch is an error line and
`disabled`.

## Checks

- `JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew build assembleMod` and the Kahlua syntax check on the new Lua files.
- `:dont39-all-in-one:smokeTest` (SCTD not in its mod list → `idle`; if it is, `ready`).
- Gate paths in the game (single player is enough): ready with SCTD; one expected hash changed temporarily →
  `disabled` and SCTD's own hide swaps to a placeholder; SCTD disabled → idle.

## In game (myserver2, harness from `archive/2026-10/10-27-appearance-override/research/harness/`)

Back up myserver2 and the client as in that task's `in-game.md`; copy SCTD's mod folder to myserver2 and add
`ShadowCatTacticsDispatch` to its mod list; restore everything afterwards. Driver tests (server commands call SCTD's
own globals, client tests call SCTD's client functions and read the panel helpers):

1. Hide (`SCTD_Transmog_HideItem` on the server) the water-filled knapsack sprayer and a jacket; show the jacket
   (`ShowItem`). Item ids, type, fluid, weights, defenses unchanged on both sides; records and overrides as designed;
   client debug lines: drawn hidden; `MatchesBaseFilter`(hidden) and `DrawHideButton` label agree.
2. Transmog jacket A with B (`Apply`): A worn, B still in the inventory, A drawn as B; cross-slot; invert (B worn,
   drawn as A, A in the inventory); `Restore` each.
3. Auto-hide: wear an SCTD body form (a type from SCTD's `TransparentSkin.TriggerClothing` config), vanilla clothing
   hidden with `autoHidden`; take it off, shown again.
4. Skin panel: `SCTD_ChangeSkin`/`SCTD_ChangeColor` on a transmogged item from the client → the override's look
   changes on the server and in the drawn model; on a plain item → SCTD's own path.
5. Restart + rejoin: items, records, overrides kept; drawn the same.
6. Remote view: a second client (`-nosteam`, its own `-cachedir`) sees the first player's hidden sprayer and
   transmogged jacket (core debug lines for the remote player's model build). Not covered by the core's run.
7. Disabled path: a temporary wrong hash in the deployed copy → `disabled`; SCTD's Restore on one of our items clears
   record and override (R7a).

Record results in `research/in-game.md` with the lines under `research/in-game-<date>/`.

## Rollback

Remove the module from `settings.gradle.kts` and `features`, and its `luaTargeted` entry; items it touched keep a
record SCTD can clear and an override the core draws.
