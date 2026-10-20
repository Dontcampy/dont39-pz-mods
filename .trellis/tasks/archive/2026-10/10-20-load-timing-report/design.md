# Design: Loading Progress switches and load timing report

Builds on the 10-17 design (`archive/2026-10/10-17-loading-progress/design.md`): flows, steps, the log, own and
interim frames. Plain Java, preloaded, JDK-only glue; nothing here adds a hook target (Out of Scope).

## Settings (D1, R1)

- `progress.Settings`: a record `(boolean display, boolean report)` plus a game-free parser/writer over a `Path`.
  Format: `key = true|false` lines, `#` comments, keys `display` and `report`; unknown keys, a missing key or another
  value are problems (one line naming the file and key); the defaults apply for the whole session then, the file is
  left as it is. A missing file is written with the defaults and one comment per key.
- Path: `ZomboidFileSystem.instance.getCacheDir()` + `/Lua/Dont39LoadingProgress.ini`, the folder Lua's
  `getFileWriter`/`getFileReader` use, so the options page reads and writes the same file.
- Read once per session, at the first flow's begin (the launch at fonts ready, or the first flow of a launch whose
  hooks opened later), before the flow's state is set: `Progress` takes the settings from a supplier the hooks layer
  gives it (`Loading`), called once, under the lock. The game log is up there.
- Both off: no flow begins, so `active` stays false and every hook returns at its first check (R7).

## Display switch (D5, R7)

- `Progress` keeps running flows for timing and the frame-gap figures; with the display off `ownFrames` stays false
  (no own frames, no jar frames), `loadingJar` stays 0 (no interim capture or frames), and the overlay draws nothing.
- `Overlay.frameEnds` still feeds `worldText` and `frameHandedOver` (the report's items and gaps), but skips `draw`
  and `Interim.mark`. The flow-end line then shows 0 own and 0 interim frames (AC3).

## Timing (R3, D4)

- Each new log line (step line or item line, the same condition that adds it to `Log`) also becomes a timed record
  with `System.nanoTime()` taken at that point; it ends when the next record begins, at a pause (a start waiting in
  the menu after its Lua reset) or at the flow's end. The pause itself is not an item or step time: the summary shows
  it as one "waiting in the menu" figure.
- A run of items also ends where the game's loop over them returns (`modsLoaded`, `scriptsLoaded`, `luaLoaded`, a
  jar's `jarLoaded`): from there the step's own record runs again, so the last item does not take the loop's tail (the
  first launch report gave the last mod, script and Lua file 1.2–1.9 s each).
- `ScriptManager.Load` parses each file inside `LoadFile`, then runs `loadScripts` and its post-processing for all
  files before it returns; no JDK-only point lies between, so the last script file's record holds that processing
  (~1.6 s). The summary names that row so and credits it to no mod (`Report.lastScript`); no hook is added for it.
- Records live in a per-flow list (all of them, unlike the 128-line `Log`), only while the report is on. For Lua and
  script files the record keeps the full path, for the mod lookup at report time. Nothing else is computed per item.
- Several world-loading threads overlap (map cells): an item's time is until the next record, whichever thread; the
  summary says item times of the world load are wall-clock spans between lines.
- The bar is unchanged: it fills by step (10-17 D2).

## Report (D3, R4–R6)

- At a flow's end (`Progress.End`, under the lock), `Progress` hands the records, the step times and the pause time
  with the `End` to the ended consumer. `Loading.ended` builds the flow-end line once (also into the summary), takes
  on the game thread what needs the game (game version, the loaded mods' folders for the mod lookup, the mod count),
  and starts a daemon thread that writes both files. No file I/O inside a load step.
- `report.Report` (game-free, tested): summary text and CSV from the records and those inputs.
  - Summary: flow, outcome, total, per step time, waiting in the menu, per item kind count / total / 20 slowest, time
    per mod (mods, Java mods, Lua and script files by the mod whose folder holds them; the game's own media is not a
    mod), the flow-end line's frame-gap figures, game version, mod count, Java mod count, this mod's version.
  - CSV (R5): header `flow,step,kind,index,total,name,mod,start_ms,duration_ms`, one row per item record; fields with
    a comma, quote or line break quoted.
- `report.Reports` (game-free, tested): folder `<cache>/Lua/Dont39LoadingProgress/`, files
  `<yyyy-MM-dd_HH-mm-ss>_<flow>.txt` / `.csv` (a counter suffix on a clash), keeps the newest 10 loads (pairs) and
  deletes older ones; one console line with the summary's path.
- A write failure: one error line, and the report stays off for the session; the display goes on (R6).

## Mod options (D2, R2)

- `media/lua/client/` file: `PZAPI.ModOptions:create("Dont39LoadingProgress", <name>)`, two `addTickBox` (display,
  report), tooltip "applies from the next launch of the game"; `apply` rewrites the ini through `getFileWriter`
  (same format as `Settings` writes). The ini is the source of truth: the tick boxes are set from it when the page is
  built, over whatever the options API saved itself.
- EN/CN text the way the repo's other Lua mods do it.

## Trade-offs

- An invalid settings file falls back to the defaults for the session instead of stopping the mod (PRD R1): a
  player's hand edit should not turn off a working display; the line names file and key.
- A report write failure stops only the report (PRD R6): the files are diagnostics, the display is the mod's job.
