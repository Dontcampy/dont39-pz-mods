# Verification (2026-10-20, 42.21.0, 247 mods, 14 Java mods, 4K)

- AC1: `:loading-progress:test` 100 tests (Settings, Progress timing incl. loop returns, Report, Reports).
- AC2: launch → menu → single-player continue (LPTest save) wrote `<time>_launch.txt/.csv` and `<time>_start.txt/.csv`
  with one `load timing report:` line each. Launch: Java mods ShootToThrillMultiplayer 2.4 s and Viewpoint 1.5 s top
  the per-mod list; OnGameBoot 2.1 s; "Loading tiles and furniture" 6.6 s with the flow line's gap "mostly script
  comment stripping". Start: world-load Lua events (OnLoadMapZones 3.4 s), OnGameStart 6.2 s, the animation wait.
  The first run showed the last mod / script / Lua file taking the loop's tail (1.2–1.9 s); fixed (R3 amended), and
  the last script file row is named as holding the game's processing of all script files (no hook between).
- AC3: display off: `launch: done ... 0 own frames, 0 interim frames`, vanilla screen only, report written. Both off:
  only the `settings:` line, no flow line, no report.
- AC4: options page "加载进度条" (CN) and "Loading Progress" (EN) with both tick boxes; boxes followed the ini
  (`isSelected` false/true after the CN run turned display off); apply rewrote the ini; the next launch read it.
  (PrintWindow captures show no tick marks for any mod's boxes, the All-in-One's included.)
- AC5: eight launches to the menu, report on vs off alternating: 55.47 s vs 55.62 s (−0.27 %; runs vary ±1.5 s).
- AC6: `./gradlew build assembleMod` and `:loading-progress:smokeTest` pass.
