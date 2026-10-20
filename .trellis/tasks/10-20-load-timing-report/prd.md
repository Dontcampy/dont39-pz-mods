# Loading Progress switches and load timing report

## Goal

Players can turn Loading Progress's display off, and a timing report of every load shows what slows it down (which
steps, mods, Java mods, Lua and script files, map cells, events), so slow loads can be traced later.

User request (2026-10-20): 这个功能有开关吗？可以不可以加一些打点，以后还可以用来找是哪些东西拖慢了加载速度。

## Background

- Loading Progress (`loading-progress/`, task `archive/2026-10/10-17-loading-progress`) has no switch. It is preloaded
  with launch-wide hooks (D7), so taking it out of the mod list does not turn it off.
- Its log already has one line per step and per item (D15); each line is a point where timing can be taken.
- The game ships a mod options API (`media/lua/client/PZAPI/ModOptions.lua`: `PZAPI.ModOptions:create`, `addTickBox`,
  `apply`). Lua loads after the launch flow has started, so options set there apply from the next launch.

## Decisions

- D1: two switches, "show the bar and log" (default on) and "write a timing report" (default on), in a settings file
  `Dont39LoadingProgress.ini` in the game's cache `Lua` folder, read before the launch flow starts. A missing file is
  written with the defaults and a comment per key.
- D2: the same two switches in the game's mod options; changing them rewrites the settings file and applies from the
  next load (stated in the option's tooltip).
- D3: per load (flow), a text summary and a CSV with one row per item, in `Lua/Dont39LoadingProgress/` of the cache
  folder; the last 10 loads are kept. `console.txt` gets one line with the summary's path.
- D4: timing never drives the bar (the 10-17 task's D2 stays: the bar fills by step count).
- D5: with the display off, the mod draws nothing and pushes no frames of its own, so loads run as vanilla; the timing
  report still works if it is on.

## Requirements

- R1: the settings file is read once per session before the first flow, created with defaults when missing; an
  unreadable or invalid file is reported (one line naming the file and key) and the defaults apply for that session.
- R2: the mod options page "Loading Progress" / "加载进度条" has the two tick boxes, EN and CN text, and writes the
  settings file on apply.
- R3: every log item and step gets a start time; an item ends when the next item or step begins (or the flow ends).
  Times come from `System.nanoTime`, taken in the existing hooks; no new per-item work beyond one timestamp.
- R4: the summary lists: flow, total time, per step time, per item kind the count, total and 20 slowest items, time per
  mod where items belong to a mod (mods, Java mods, Lua and script files by their mod), the frame-gap figures of the
  flow-end line, and the game version, mod count and the mod's version.
- R5: the CSV has a header and one row per item: flow, step, kind, index, total, name, mod (if known), start ms (from
  the flow's start), duration ms.
- R6: report files are written off the game thread or after the flow ends, never inside a load step; write failures
  are reported once and stop only the report, not the display.
- R7: the display switch off: no overlay, no own frames, no interim frames; the hooks still record timing when the
  report is on; both off: the hooks return at their first check.

## Acceptance Criteria

- AC1: unit tests for settings parsing (defaults, missing file written, invalid values reported), item timing
  (durations, step boundaries, flow end), summary ranking and per-mod totals, CSV format, keeping 10 reports.
- AC2: a launch to the menu and a single-player start each write a summary and a CSV; the summary's slowest items
  match the known slow points (tile comment stripping, the large Java mods, OnGameBoot callbacks).
- AC3: with the display off (settings file), a launch shows no bar and no own frames (frame count 0 in the flow line)
  and still writes the report; with both off, no report.
- AC4: the mod options page shows the two tick boxes in EN and CN; changing one rewrites the file; the next launch
  follows it.
- AC5: launch → menu with the report on is within 1 % of the time with it off (two runs each).
- AC6: build gate, `:loading-progress:smokeTest`.

## Out of Scope

- Changing what is hooked (no new targets just for timing).
- Uploading or sharing reports; a UI to browse them.
