# SCTD takeover: hide and transmog through the appearance override

Child of `10-27-sctd-render-transmog` (decisions there). Needs `10-27-appearance-override`'s API; design.md is written
against that API once it exists. Evidence: `../10-27-sctd-render-transmog/research/sctd-integration.md` (entry points,
record readers, takeover plan).

## Goal

SCTD's hide, show, transmog, restore, auto-hide, transmog panel and skin panel keep working for players, but never swap
the item: they set or clear the item's appearance override, and the real item stays worn.

## Requirements

- R1. Hide (panel button, server command, auto-hide while wearing a body form): the worn item stays; override `hidden`.
  Show / restore: override cleared. Auto-hide's restore loop finds items it hid (it keys on type today).
- R2. Transmog A → B: A stays worn, override = B's type with B's current texture choice, base texture and tint; B stays
  in the inventory (not consumed). Restore clears the override and returns nothing. Cross-slot transmog
  (`ignoreBodyLocation`) works the same (A stays in its own slot).
- R3. "Invert stats" A ↔ B: B is worn in its own slot in A's place, drawn as A (A's current look); A goes to the
  inventory. Restore clears the override (B stays worn).
- R4. SCTD's record (`modData.SCTD_Transmog`) is still written so its readers work (`IsTransmogged`, panel info,
  content filter, forbidden lists), in a form SCTD's own code never turns into an item swap or a stats copy (no
  `baseFullType`, no `baseStats`; marked as ours).
- R5. SCTD's UI shows the right state: hidden/transmogged filters, the row mark, the hide/show button, previews skipping
  hidden items.
- R6. The skin panel on a transmogged item changes the drawn look (texture choice, base texture, tint), in single player
  and multiplayer (own client command; SCTD's server handler would edit A's own visual).
- R7. Every replaced SCTD function is checked against its exact source text; any mismatch, missing global, or error →
  `disabled` with an error, every replacement put back, SCTD runs as shipped. Items already hidden/transmogged keep
  their look; SCTD's own Restore clears such a record (its legacy branch) and the override is cleared with it.
- R7a. Records this takeover writes stay manageable without it: SCTD's own Restore (shipped code, its legacy branch)
  clears them and returns nothing (no `srcFullType` in the record; the look lives in the override). A plain wrapper on
  `SCTD_Transmog_Restore`, not a replacement and not source-gated, stays installed even while the takeover is disabled
  and clears the override when SCTD clears our record, so such an item never stays hidden with no way to show it.
- R7b. Without All-in-One at all (removed, or an older version), such an item is a normal item drawn as itself and SCTD
  sees a record it can only clear: nothing is lost but the look.
- R8. Multiplayer: client actions still go through SCTD's commands; the server's replacements do the work; the acting
  client's panel reselects the item as today (`SCTD_TransmogSync.Notify`).
- R9. Status line in `smokeTest` (`luaTargeted`): `ready` with SCTD, `idle` without.

## Acceptance

- [ ] Gate and `smokeTest` (`ready` with SCTD, `idle` without; a temporary source edit shows `disabled`).
- [ ] myserver2, driven through SCTD's own functions/commands: hide, show, transmog (normal, cross-slot, invert),
      restore, auto-hide with a body form, skin panel change — item identity, stats, contents and fluid unchanged on
      both sides; drawn look as expected (debug output); panel state correct; survives restart and rejoin.
