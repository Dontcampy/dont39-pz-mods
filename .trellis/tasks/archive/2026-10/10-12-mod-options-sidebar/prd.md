# Mod Options sidebar

Parent: `10-12-allinone-kotlin-sidebar`. Independent of `10-12-viewpoint-kotlin`.

## Goal

On Options > Mods, every mod's options are one long scrolling page, so finding one mod's settings is hard. A new
All-in-One feature, `mod-options-sidebar`, adds a right sidebar that lists each mod and jumps to its options.

## Decisions (user, 2026-10-12)

- **D1 Jump, not filter:** the page stays one list with every mod's options; the sidebar scrolls the list to the
  chosen mod's section header. Rejected: showing only the selected mod (re-lays out vanilla-built controls and
  controller navigation).
- **D2 Remember the last choice:** when the Mods tab is shown, it scrolls to the mod last chosen in the sidebar
  (matched by mod-options id; if that mod no longer registers options, the page stays at the top).
- **D3 Across restarts:** the last choice is saved to a file and survives a game restart.
- **D4 Restore once per page build (2026-10-12, after implementation):** the saved choice is applied the first time
  the Mods tab is shown after the page is built (i.e. after a game start). Within the same run, reopening Options
  keeps vanilla's scroll position instead of jumping back to the chosen mod.

## Background (game evidence, Build 42 Lua)

- The page is built by `MainOptions:addModOptionsPanel()`
  (`ProjectZomboid/media/lua/client/OptionScreens/MainOptions.lua:2795-3054`), called from `MainOptions:create()` only
  when `#PZAPI.ModOptions.Data ~= 0` (`:409`). `MainOptions` is created once by `MainScreen` (`MainScreen.lua:177`) and
  serves both the main menu and the in-game pause menu.
- It is one `addPage(...)` scroll panel (`:248`). For each `PZAPI.ModOptions.Data` entry (one per
  `PZAPI.ModOptions:create(id, name)`, `client/PZAPI/ModOptions.lua`) it calls `addHorizontalLine(y, getText(name))`
  once as the section header (`:2806`) and then lays out that mod's options; it ends with
  `self.mainPanel:setScrollHeight(y + self.addY + 20)` (`:3053`).
- Values go through `GameOption` objects (`<modOptionsID>.<optionId>`); save/apply is separate (`:3761`), so the
  sidebar never touches option values.
- Window resize re-runs `MainOptions:doLayout()` (`:3896-3918`), which does not rebuild the Mods page.

## Requirements

- R1 A right sidebar on the Mods tab lists every registered mod-options section by its display name, in the page's
  order, and is shown only while the Mods tab is active.
- R2 Clicking an entry scrolls the Mods page so that mod's section header is at the top (D1). The sidebar highlights
  the entry whose section is currently at the top of the view, also while the user scrolls with the wheel.
- R3 The last chosen mod-options id is saved to `~/Zomboid/Lua/Dont39AllInOne_ModOptionsSidebar.txt` and restored when
  the Mods tab is shown (D2, D3). A missing, empty, or unreadable file means "no choice".
- R4 The Mods page content is laid out for the narrower width so nothing is drawn under the sidebar; every vanilla
  option type and other mods' options keep working without changes on their side.
- R5 Saving, applying, cancelling, and option values behave exactly as in vanilla.
- R6 If the page is not what the feature expects (section count differs from `PZAPI.ModOptions.Data`, an error in the
  feature's code, another mod replaced `addModOptionsPanel`), the vanilla page is left as vanilla built it and the
  feature logs one warning line.

## Acceptance Criteria

- [ ] With 3+ mods that register options, the Mods tab shows a right sidebar with one entry per mod, in page order.
      (R1)
- [ ] Clicking an entry puts that mod's header at the top; scrolling with the wheel moves the highlight. (R2)
- [ ] Choose a mod, close Options, reopen: the page keeps its scroll position (D4). Restart the game: the page
      opens at the chosen mod. Delete the file: the
      page opens at the top, no error. (R3)
- [ ] Long descriptions and widest controls are not cut off or covered by the sidebar. (R4)
- [ ] Change a value, Accept, reopen: value kept; Cancel discards as in vanilla. (R5)
- [ ] Works from the main menu and from the in-game pause menu. (R1-R5)
- [ ] Forcing the R6 path (e.g. a temporary extra `addHorizontalLine` call in a test mod, or an error injected in
      dev) leaves the vanilla page intact with one warning in `console.txt`. (R6)
- [ ] `smokeTest` still passes; it checks the feature's load line if client Lua is loaded before ZombieBuddy's
      `exit_after_game_init` (see implement.md). (build)

## Out of Scope

- Filtering/showing only one mod, search box, collapsing sections, reordering mods.
- Other option tabs (Display, Audio, …).
- Controller (joypad) navigation of the sidebar itself; the vanilla page's controller navigation must keep working.
- An option to turn the sidebar off.
