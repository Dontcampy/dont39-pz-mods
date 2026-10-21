# Verification: Faster Loading 0.1.0, comment stripping (R3)

Date: 2026-10-21/22. Build: this worktree (main c5a9100 + uncommitted `faster-loading/`).

## Offline

- `./gradlew :faster-loading:check :faster-loading:assembleMod`: pass (checkJdkGlue, checkLoaderRefs,
  checkBootstrapClasses, 8 + 3 + 11 tests).
- The replacement equals the real 42.21 `ScriptParser.stripComments` on all strings of `/ * x` up to length 12
  (797,161), 250,000 random texts/nestings, the quirk cases, and 6,077 real files (game media 1,016; Workshop 5,061);
  the game's method returns our output unchanged on all of them.
- Vanilla `tileGeometry.txt`: game's method 1,890 ms, hooked call 9.6 ms.

## In game

- `:faster-loading:smokeTest`: pass on the second launch (the first registers the preload, as designed). Log:
  `active: comment stripping on`, `hooks ready (1 hook class)`, `0.1.0 loaded`.
- `GameSmokeTest` gained `widener` (default true); Faster Loading sets it false since it widens nothing.
- Local nosteam dedicated server with the live server's full list (246 mods incl. the test driver, 16 Java mods), one
  warm-up join, then off/on alternating, 3 each; each run is a fresh client launch + join. Reports copied to
  `../10-21-asset-wait-speedup/research/join-*.txt`.

| Run | Launch total | Launch "Tiles and furniture" | Join total | Join "Clothing, tiles and furniture" |
|---|---|---|---|---|
| off1 | 80,422 | 7,565 | 147,412 | 8,732 |
| on1 | 54,272 | 863 | 127,032 | 1,634 |
| off2 | 47,887 | 7,181 | 132,951 | 7,756 |
| on2 | 35,277 | 726 | 111,974 | 1,493 |
| off3 | 42,144 | 6,626 | 117,099 | 7,479 |
| on3 | 35,596 | 752 | 145,190 | 1,429 |
| **median off** | 47,887 | **7,181** | 132,951 | **7,756** |
| **median on** | 35,596 | **752** | 127,032 | **1,493** |

- **AC (≥ 1.5 s per launch and per join): met.** The step that holds the stripping drops by 6.4 s at launch and 6.3 s
  per join (median), far above the ~2 s estimated for vanilla's file: the mods' tile-geometry files add the rest.
- Join totals are dominated by the asset wait's ±12 s run-to-run spread (R2), so they are not the measure here.
- No errors from Faster Loading or Loading Progress in any run's `console.txt`; every join reached the world.
- Not exercised: the Lua checksum (the live server's ini has `DoLuaChecksum=false`, kept for fidelity); the options
  page (not opened); a join through the tunnel (refused on this machine by design).

## Open

- Workshop images (`mod/42/poster.png`, `mod/workshop/preview.png`) for the user to supply before staging.
- Specs do not list the new module yet (spec-update step).
