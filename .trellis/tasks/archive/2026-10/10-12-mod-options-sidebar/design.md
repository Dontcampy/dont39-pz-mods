# Design: Mod Options sidebar

## Shape

A Lua-only feature module `mod-options-sidebar/` with one client file
`src/main/lua/client/Dont39AllInOne_ModOptionsSidebar.lua`. No Java/Kotlin, no patches, no Lua bridge.
`dont39-all-in-one/build.gradle.kts` already copies each feature's `src/main/lua` into the mod folder.

The module still has a `build.gradle.kts` applying `buildsrc.convention.pz-kotlin` (spec "Adding a Feature" step 1);
with no sources its compile tasks are NO-SOURCE and it adds nothing to the jar.

## Hook: wrap, don't replace

```
local vanilla = MainOptions.addModOptionsPanel
function MainOptions:addModOptionsPanel()
    -- 1. narrow the Lua-side width so vanilla lays the page out for (width - sidebar)
    -- 2. record each section header's y by wrapping self.addHorizontalLine for the duration
    -- 3. call vanilla (always, and outside our pcall — vanilla errors must surface as they do today)
    -- 4. restore self.width / self.addHorizontalLine
    -- 5. pcall(attach sidebar); on failure or mismatch: log once, leave the page as built
end
```

- Wrapping keeps every option type, `GameOption` wiring, save/apply, and controller rows exactly vanilla (R4, R5).
- If another mod replaces `addModOptionsPanel` after us, our wrapper is simply not called: vanilla-or-theirs page, no
  sidebar (R6). If they wrap us, we still run.
- **Width trick:** vanilla sizes headers (`addHorizontalLine`, `self.width`) and descriptions (`addDescription`,
  `self.width - x - 100`) from the Lua field `self.width`, while `addPage` and `splitpoint` use `self:getWidth()`
  (the Java object, `ISUIElement.lua:259`). Setting the Lua field `self.width = w - sidebarW` for the call narrows
  everything that reaches the right edge without moving the label column. After the call, the Mods `mainPanel` is set
  to `tabs.width - sidebarW` (anchors keep the gap on window resize). Verify in game that no control overlaps; if
  the trick turns out insufficient, fall back to clipping by the narrowed panel's stencil and note it.
- **Section mapping:** the k-th recorded `addHorizontalLine` call is `PZAPI.ModOptions.Data[k]`
  (`MainOptions.lua:2805-2806`; no option type calls it). Record `{ y = self.addY + y, id = Data[k].modOptionsID,
  name = text }`. If the count differs from `#Data`, do not attach (R6).

## Sidebar

- `ISScrollingListBox` child of `MainOptions` (not of the scrolling `mainPanel`, so it does not scroll with the page),
  placed at the right edge over the tab view area, anchored right/top/bottom.
- Visible only when the Mods view is active: `ISTabPanel` has no hook we own, so the sidebar's visibility is synced
  from a small `prerender` on the Mods `mainPanel` (which only runs while that view is visible) plus hiding when the
  options screen hides. Exact mechanism chosen during implementation; must not change other tabs.
- Click → `mainPanel:setYScroll(-section.y)` (clamped to scroll height), save id (R3).
- Highlight: in the same prerender, pick the last section with `y <= -mainPanel:getYScroll() + small margin` and
  set `list.selected` (R2). O(sections) per frame while the tab is visible — trivial.
- Restore: on first activation of the Mods view after the page is built, scroll to the saved id if present.

## Persistence

- `getFileReader("Dont39AllInOne_ModOptionsSidebar.txt", false)` / `getFileWriter(..., true, false)` — relative to
  `~/Zomboid/Lua/`, the same API vanilla uses for `keysB42.ini` (`MainOptions.lua:417`, `:3526`).
- Content: one line, the mod-options id. Read once when the page is built; written on click only (not per frame).
- All IO in `pcall`; failure → no choice / not saved, one log line.

## Logging

Prefix `[Dont39AllInOne] mod-options-sidebar: ` via `print` (the Lua side; check `logging-guidelines.md` for the
existing Lua convention). One line at file load, one at attach (`N sections`), one on any R6 fallback.

## Rollback

Feature is one Lua file plus build wiring; removing the module from the feature list restores vanilla.
