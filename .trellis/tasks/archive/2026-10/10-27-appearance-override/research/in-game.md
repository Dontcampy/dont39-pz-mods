# In-game check, 2026-10-27

Test instance `myserver2` on the Linux test server (`-nosteam`, game port 48261, own RCON port 48015, fresh save,
`Map=Muldraugh, KY`; mods ZombieBuddy, Zabric, ZabricKotlin, Dont39AllInOne (this branch, uncommitted), SGTestDriver).
The Windows client `-nosteam` with `-Dappearanceoverride.debug=true`, driven by `harness/` (the archived Server Reborn
harness plus the driver tests `appearance`, `appearanceCheck`, `appearanceClear`), at Rosewood prison. myserver2's
`Server`, `mods`, `zombie_buddy`, `Saves`, `db`, `Lua` and `options.ini` were backed up first
(`backups-2026-10-27-appearance/state.tgz` there) and restored afterwards (7429 entries, the ini's RCON port back);
the client by `cleanup.ps1` (0 differences) and the Loading Progress reports, `default.txt` and the ZombieBuddy config
compared with a backup taken before `smokeTest` (no change). Lines of each run in `in-game-2026-10-27/`.

Before the game: `./gradlew build assembleMod` and `:dont39-all-in-one:smokeTest` passed (`appearance-override:
active`, hooks transformed, no feature disabled).

## Tests

- `appearance`: the server gives a `KnapsackSprayer` and a `Jacket_Padded`, the client wears both, the server puts 2 L
  of water in the sprayer, then `setHidden` on the sprayer and `setLook` (`Jacket_Black`, texture 0, white) on the
  jacket. Client and server report each item's id, weight, fluid, defenses, insulation and stored override.
- `appearanceCheck`: the same report after a rejoin.
- `appearanceClear`: `clear` on the jacket; then a second sprayer is worn instead of the hidden one.

## Results

| Run | What | Seen |
|---|---|---|
| ao2 | first build, set | both overrides stored on both sides; gameplay values unchanged (fluid 2/16, bite 10, scratch 20, insulation 0.95, weights); client debug: model, attached items and textures without the sprayer and with the jacket as `Jacket_Black` |
| ao3 | first build, restart + rejoin | items and overrides kept on both sides, but **no set reached the client** (nothing hidden): the server's set is built from the worn list, which a joined player's items fill without `OnClothingUpdated`, and the join's snapshot request was dropped |
| ao4 | first build, rejoin + clear | first set lacked the jacket's look (same cause) |
| ao5-ao7 | server checks worn items once a second | set and clear fine; restart + rejoin now drawn (set from the check); a rejoin without restart still got no snapshot |
| ao8 | + snapshot request repeated until the server's `snapshotDone` | set: revision 1 sprayer hidden, revision 2 + jacket look; drawn |
| ao9 | rejoin, same server session (only the snapshot can deliver) | full set at the join's first frames; drawn |
| ao10 | server restart + rejoin | full set at the join; drawn; items, water and overrides kept |
| ao11 | clear + second sprayer | jacket drawn as itself (set without it); the second sprayer drawn normally (empty set), the first keeps `hidden` in the inventory |

No `disabled`, `hooks stopped` or error line from All-in-One, Zabric or ZombieBuddy on either side in any run; the
client's error lines are the game's own (fonts, fluid container names, map data).

## Not covered in game

- Another player's view (`IsoPlayer.getItemVisuals` remote branch): needs a second client; to do with the SCTD
  takeover's run.
- Single player (sets keyed by player slot), UI previews built from a `SurvivorDesc`, corpses and mannequins.
- Screenshots: the debug lines name what each model build drew; no image was taken.

After the runs, the last check made three small Lua changes, not run in game since: the once-a-second server check is
registered only when `isServer()` at load (the same test that logged `server part ready (server)` in every run), and
two retry-stop paths in the client file. `build assembleMod` and the Kahlua syntax check passed after them.
