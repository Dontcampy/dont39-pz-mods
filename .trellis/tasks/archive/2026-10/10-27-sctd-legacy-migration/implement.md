# Implement: SCTD legacy migration

Read first: `prd.md`, `design.md`, the takeover's code (`sctd-render-takeover/src/main/lua/`) and its archived task
(`archive/2026-10/10-27-sctd-render-takeover/`: design.md with its Deviations, `research/sctd-blocks/BlockHashes.java`,
`research/in-game.md` and `research/harness/`), and SCTD's source at
`E:/SteamLibrary/steamapps/workshop/content/108600/3689462578/mods/ShadowCatTacticsDispatch/common/media/lua/`.

## Steps

1. **Gate blocks.** Add `CopyState`, `applyAuthoritativeTint`, `SCTD_Transmog_EmptyContainer`,
   `SCTD_Transmog_IsInvisVariant`, `SCTD_Transmog_RemoveWorn`, `SCTD_Transmog_WearNoSync` to the takeover's block table
   (values from `BlockHashes.java`, reviewed copies into the takeover task's `research/sctd-blocks/`, which moved to the
   archive: put the new ones in this task's `research/sctd-blocks/` and say so in the README line of BlockHashes).
   Globals among them also get the identity check.
2. **Migration module** (`shared/Dont39AllInOne_SctdRenderTakeoverMigration.lua`): walk, classify, convert one item
   (rebuild, swap, source, invert), convert a player (busy flag, one sync, one log line), `stopped` flag. Reuse the
   takeover's helpers (record, look, the invert's take-off/wear code) by moving what both need into the shared
   module rather than copying it.
3. **Fluid**: find the engine call that copies one item's fluid into another's container (`javap` on
   `zombie.entity.components.fluids.FluidContainer`), use it for a type transmog when both items have one.
4. **Wiring** in the takeover's server file: after `installAuthoritative()` succeeded, register the server tick check,
   single player `OnGameStart`/`OnCreatePlayer`, and `OnClothingUpdated` (worn items only).
5. **Workshop text**: extend the takeover's line in `workshop.txt` with the one-way conversion and "back up the save
   before the first start with this version".

No version bump here: the parent does it once, after this child.

## Checks

- `JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew build assembleMod`, the Kahlua syntax check, BlockHashes (every block
  `ok`).
- `:dont39-all-in-one:smokeTest` (back up the LP reports first): `sctd-render-takeover: ready`.

## In game (myserver2, the takeover's harness)

Back up myserver2 and the client as before; SCTD and SCTG on myserver2; restore everything afterwards.

1. **Make old-style items**: deploy this build with one block hash wrong (the takeover then logs `disabled` and SCTD
   swaps as shipped, as in the takeover's test 8), and through SCTD's own functions on the server make: a hidden
   knapsack sprayer with water (worn), a hidden backpack with items inside (worn), a type transmog (worn), a
   cross-slot transmog (worn), an inverted transmog (worn), a hidden item taken off into a bag, an auto-hidden item
   (body form worn). Record each item's type, id, record, stats, contents on server and client.
2. **First start with the migration**: correct hash, restart, join. Server: one `converted` line with the counts, no
   error. Each item is its real type, worn where it was, same look on the own client (core debug lines), our record
   and override, contents in place, a fresh source in the inventory for the transmogs and the invert, the inverted pair
   as B worn drawn as A; stats equal on client and server (no Server Reborn diff).
3. **Second join**: nothing converted, no line.
4. **Picked up later**: an old-style item made in step 1 and dropped on the ground before step 2, picked up and worn
   after it → converted on the clothing event.
5. **Takeover disabled**: with a wrong hash again, nothing is converted; SCTD's own items keep working.

Record results in `research/in-game.md`, with the proving lines quoted (no raw logs).

## Rollback

Remove the migration's wiring line and module file; converted items stay converted (one way).
