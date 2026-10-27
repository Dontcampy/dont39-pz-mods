# SCTD migration: existing placeholders and transmogged items back to real items

Child of `10-27-sctd-render-transmog`. Needs the appearance override API and the takeover's record format. Evidence:
`../10-27-sctd-render-transmog/research/sctd-integration.md` §6 (what each record holds), and
`../10-27-sctd-render-transmog/research/superseded-lua-design.md` "Part resnap" (bytecode: a player's inventory is
loaded before the player is in the online list; a per-tick scan sees it on the first tick).

## Goal

Items made by SCTD's old item swap become the real items again, keeping the look the player had, the first time the
player loads with the new version.

## Requirements

- R1. Scope: every item in a player's inventory, bags included, worn or not, on the authoritative side, once per loaded
  player (server: per-tick scan of online players; single player: game start), and again when SCTD's `OnClothingUpdated`
  shows a newly worn old-style item. Items elsewhere (world containers, ground, vehicles) are converted when they reach
  a player's inventory this way; until then SCTD's own Show still works on them when worn.
- R2. Hidden placeholder (`Base.SCTD_Invis_*`, `hidden` record) → a new item of `baseFullType` with the placeholder's
  current state (condition, blood, dirt, holes, patches, wetness, favorite, mod data minus SCTD's swap fields), the
  record's stats, textures, tint and name, the bag contents; worn where the placeholder was; override `hidden` (and
  `autoHidden` kept). The fluid is already lost; the item gets its script's empty container.
- R3. Type-transmogged item (type B', record with `baseFullType` A and `srcFullType` B) → a new A built the same way,
  override = B with B''s current texture choice, base texture and tint; a fresh B (`srcCondition`, `srcFavorite`) into
  the inventory, since sources are no longer consumed.
- R4. Inverted record (A carrying B's stats) → the new invert form: a fresh B worn in its slot, drawn as A; A back to the
  inventory with `origStats`.
- R5. Clients get the change through vanilla item packets and the override sync; the acting client's SCTD panel state
  follows (`SCTD_TransmogSync.Notify`).
- R6. One log line per converted player (counts), errors → `disabled` for the migration only, items left as they are.
- R7. Runs only while the takeover is active (its source check passed). With the takeover disabled, SCTD runs as
  shipped and its old-style items are left for it; converting them then would leave records SCTD's own UI can only
  clear, not manage.
- R8. One-way: nothing turns a converted item back into a placeholder. The Workshop text asks server owners to back up
  the save before the first start with this version.
- R9. Offline players are converted on their next join (their inventory loads then); nothing scans the whole players
  database.

## Acceptance

- [ ] Gate and `smokeTest`.
- [ ] myserver2: a save made with SCTD's old swap (a hidden sprayer, a hidden bag with contents, a type transmog, an
      inverted one, one placeholder in a bag not worn) on the first join with the new version: real items, same look,
      contents kept, a fresh source in the inventory; no Server Reborn diff; second join changes nothing.
