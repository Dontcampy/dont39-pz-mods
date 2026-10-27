# SCTD hide and transmog without swapping items (parent)

## Goal

Shadow Cat Tactics: Dispatch (Workshop 3689462578, `ShadowCatTacticsDispatch`) hides a worn item by replacing it with
an invisible placeholder item (`Base.SCTD_Invis_*`), and transmogs item A into B's look by replacing A with a new
instance of B's type (consuming B). Every swap copies part of A's state and loses the rest. Seen live:

- **Stats on the server after a load.** Copied instance stats (weight, defenses, insulation, container capacity…) are
  not saved; SCTD re-applies them only on the client, so after a restart or rejoin the server keeps the script values
  (invisible bags weigh 0 on the server, 1.0 / 0.1 on the client).
- **Water containers.** A knapsack sprayer hidden by SCTD became `SCTD_Invis_back_f684b5d5`, which has no fluid
  container: it cannot hold water, and the liquid it held was gone.
- Also from the same investigation: the placeholder keeps the name the server read in its own language, and a
  taken-off placeholder never turns back (SCTD restores only worn items).

Replace the item swap with a render-only appearance: the real item stays worn and unchanged, and the game only draws
it hidden or as another item. All patched in Dont39 All-in-One, without changing SCTD's files (multiplayer checks mod
Lua files, so players cannot patch them).

## Decisions (user, 2026-10-27)

- Java/Kotlin render hook rather than fixing the swap (the first, Lua-only design is in
  `research/superseded-lua-design.md`).
- Both hide and transmog become render-only.
- SCTD's own entry points are taken over: its panel, hide/show, auto-hide while wearing a body form, and skin panel keep
  working for players.
- Existing placeholders and transmogged items are converted back to real items, keeping the hidden/transmog state.
- The appearance source is no longer consumed; it stays in the inventory.
- "Invert stats" becomes "wear B, drawn as A" (the same mechanism as a normal transmog, roles swapped).
- The skin panel can change the transmogged look's texture and color.
- The takeover checks the exact source of every SCTD function it replaces; on a mismatch it turns itself off with an
  error and SCTD runs as shipped until All-in-One is updated. Items already hidden or transmogged keep their look.

## Task map

| Child | Delivers | Needs |
|---|---|---|
| `10-27-appearance-override` | All-in-One Kotlin feature, independent of SCTD: per worn item "hidden" or "looks like type X with texture/tint", drawn only in rendering, stored on the server, sent to every client; Lua API on the authoritative side | — |
| `10-27-sctd-render-takeover` | Lua feature targeting SCTD: hide, show, transmog, restore, auto-hide, panel and skin panel go through the override | appearance-override's API |
| `10-27-sctd-legacy-migration` | Converts SCTD placeholders and type-transmogged items in player inventories to real items with the override | both above (API, record format) |

Each child is planned (design.md + implement.md), reviewed, started, checked and verified in game on its own, in this
order. Research shared by all: `research/engine-appearance.md`, `research/sctd-integration.md`.

## Acceptance (across children)

- [ ] A worn knapsack sprayer with water, hidden through SCTD's panel, stays a knapsack sprayer: same amount on server
      and client, accepts more, not drawn on the wearer, on other players' screens, or in previews; shown again, it is
      drawn as before. Survives a server restart and a rejoin.
- [ ] A transmog through SCTD's panel keeps the real item (stats, contents, fluid, name) and draws it as the source on
      every client; the source stays in the inventory; the skin panel changes the drawn look; restore draws the item
      as itself.
- [ ] Auto-hide while wearing an SCTD body form hides and later shows vanilla clothing through the override.
- [ ] Existing placeholders and transmogged items in a player's inventory become the real items, with the same
      hidden/transmogged look, on the first load with the new version.
- [ ] No `weight{...}` or other Server Reborn field diff for these items after a restart and rejoin.
- [ ] With SCTD changed so a replaced function's source no longer matches, the takeover logs `disabled` and SCTD's
      own swap works as shipped; items already converted keep their look.
- [ ] Build gate and `:dont39-all-in-one:smokeTest` pass; All-in-One version bumped once for the release.
