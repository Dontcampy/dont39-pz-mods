# Implementation plan

Gradle runs on JDK 25: `JAVA_HOME=~/.jdks/graalvm-ce-25.0.2`. Code lives in `loading-progress/`; commits go to the open
PR's branch.

1. Settings: a small parser/writer for `Dont39LoadingProgress.ini` (two boolean keys), read at the first flow begin
   (game log up; cache folder from `ZomboidFileSystem`), defaults written when missing. Tests.
2. Switches in `Progress`/`Loading`/`FramePump`/`Overlay`/`Interim` per R7 (one volatile check per hook path).
3. Timing: a start time per log line (`Line` or a parallel record), durations at the next item/step/flow end; the
   item's mod where known (mods, Java mods; Lua/script files by their mod folder). Tests.
4. Report: summary text and CSV per flow, written after the flow ends on a background thread; keep the last 10; one
   console line. Tests for ranking, per-mod totals, CSV.
5. Mod options: `media/lua/client/` file with `PZAPI.ModOptions:create`, two tick boxes, EN/CN translations, `apply`
   rewrites the settings file through a JDK-only Lua-callable entry (or `getFileWriter`), tooltip "applies from the
   next load".
6. Docs: README, workshop.txt (EN + CN, ≤ 8,000 chars), design notes in this task.
7. Verify: build gate, smokeTest; launch to menu + single-player start with the report; display-off launch; options
   page screenshot; AC5 timing.
