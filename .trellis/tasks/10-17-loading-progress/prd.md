# Loading progress without a frozen window

## Goal

While Project Zomboid loads to the main menu and into a game, players see no progress and the window can stop
responding, so they think the game hung. Show a progress bar and status text during those loads, and keep the window
responsive.

User request (2026-10-17): 在加载进主界面、加载进游戏的地方加上进度条和进度信息，并且不要让游戏被挂起。

## Background

Evidence: `research/loading-flow.md` (threads, pumping, timeline) and `research/design-inputs.md` (Zabric flow, hook
targets, drawing, translations, build); javap of 42.21 and the 16:00 DebugLog of 2026-10-17.

- The JVM main thread renders, owns the GLFW window and GL context, and is the only thread that pumps OS messages.
  Loading runs on `MainThread` (`GameWindow.gameThread`), world loading also on `GameLoadingThread`. With
  `RenderThread.waitForRenderState` false the render loop pumps but draws nothing new; with it true it pumps only when
  `MainThread` hands over a frame (loading-flow §1).
- Launch → menu, 50.3 s: pumped, but blank, then static on the epilepsy-warning frame (black model-loading frames near
  the end). Vanilla `GameWindow.DoLoadingText` only logs (loading-flow §2a, §5; design-inputs Contradictions).
- Server join → first in-world frame, 78.9 s: `Core.ResetLua` ~25.8 s static "Reloading Lua" frame, its last ~2.6 s
  unpumped; `GameLoadingState.enter` ~2.2 s unpumped; the world-loading screen (~36 s) draws text and an animated
  figure but no progress; `IngameState.enter` 8.1 s unpumped, over Windows' 5 s "Not Responding" limit (loading-flow §5
  J3–J9). Single player runs `ResetLua` only when the ordered mod list changed, then the same world-load sequence.
- Zabric keeps every mod's hooks closed until the first loader pass, ~15 s after the window exists, and closes a mod's
  hooks on a pass whose game list lacks it, although ZombieBuddy loads preloaded jars in every pass
  (design-inputs A1; `HookRuntime.passProcessed`, `LoadStatus`). ZombieBuddy weaves a preloaded jar's glue at
  premain (`Loader.java:450,1021`).
- Since this plan was written (2026-10-17), main gained (re-checked 2026-10-19):
  - Zabric 0.4.1 runtime refactor: manifest entrypoints moved to `Entrypoints`, the reflective target check to
    `TargetCheck`. The research's Zabric and build line numbers predate it; the symbols they name still hold.
  - Build refactor: convention plugins `pz-bootstrap` (bootstrap-class check), `pz-mod` (id and package read from
    `mod/42/mod.info`), `widening/` (compile-jar widening, wired by `pz-feature` only), `smokeTest`/`serverSmokeTest`
    owned by `dont39-all-in-one`/`zb-kt`.
  - Dont39 All-in-One's tunnel (`tunnel/ClientConnect.kt`, `TunnelClient.kt`): for an address with a tunnel it takes
    over `UdpEngine.Connect` and opens the tunnel synchronously on the calling thread (TCP: ≤1 s connect + ≤1 s hello;
    KCP: ≤2 s handshake), then connects RakNet locally. A tunnel that cannot open stops the join through
    `OnConnectFailed`.
  - Dont39 All-in-One's network status icon: bottom left, created at `OnGameStart` (after the start flow).

## Decisions

- D1: a new standalone plain-Java Workshop mod (not an All-in-One feature, not part of Zabric), on Zabric's preLaunch
  entrypoint and registered as a ZombieBuddy preload mod; no Kotlin (ZabricKotlin is not preloaded). Its game hooks
  are Zabric `@Hooks` (D6). Active from the launch after its first registration.
- D2: the bar fills by step count ("step n / N" of a fixed list of named steps per flow); no timings are measured or
  stored.
- D3: one bar per flow: launch → main menu, and join (or single-player start) → first in-world frame, drawn over the
  vanilla world-loading screen too; the vanilla text and animated figure stay.
- D4: a second line shows the current item with its count (mod n / total, Lua file n / total, script file n / total),
  where the game has a per-item call.
- D5: a Workshop item for everyone: English and Chinese text, compatibility gating (a mismatch is reported and the
  mod's hooks stop, the game keeps loading), poster, preview and Workshop text; client only.
- D6: Zabric opens the hooks of the mods it keeps preloaded at preLaunch, with the weave check, in a new Zabric
  release (Maven Central + Workshop).
- D7: a per-mod manifest opt-in in Zabric keeps a preloaded mod's hooks open for the whole launch, also on passes
  whose list lacks it; mods without it keep today's behavior; Zabric's docs restrict it to client-side UI mods. The
  progress mod declares it.
- D8 (2026-10-19): a blocking call another mod makes on `MainThread`, inside which the progress mod has no hook, is a
  known gap in R8, not something the progress mod works around. Today that is Dont39 All-in-One's tunnel open in
  `UdpEngine.Connect` (≤2 s). Moving the tunnel open off the game thread is not part of this task.

## Requirements

### Zabric (R1–R4)

- R1 (D6): a preloaded mod's hooks open at preLaunch. Preload-time code still loads no game class: target members are
  checked from class bytes when each target loads, before any hook in it can run. A failed check stops that mod's
  hooks as today.
- R2 (D7): a manifest attribute opts a preloaded mod into launch-wide hooks; such a mod stays open on every pass, also
  ones whose list lacks it. The attribute on a mod that is not preloaded is reported as an error and ignored.
- R3: everything Zabric's hook runtime would log before the game log is up is held and logged once, in order, at the
  first pass. A lifecycle stop found there (Zabric denied, preLaunch hook failed) closes launch-wide hooks and is
  reported like today.
- R4: author contract, Javadoc and specs describe the new timing and the attribute (`zabric/mod/workshop/workshop.txt`,
  `ModHooks`, `Lifecycle`, `.trellis/spec/mod/access-widening.md`, `zabric-hooks.md`); Zabric's version goes from
  0.4.1 to 0.5.0. The hook listing format does not change.

### Progress mod (R5–R12)

- R5 (D2, D3): two flows with fixed, named step lists: launch (from fonts ready to the main menu) and game start (from
  a server join's connect, or a single-player `ResetLua` / world load, to the first in-world frame). Each frame shows
  a bar filled by step n / N and the line "n / N: <step name>". A step that does not run in a given start (e.g. no
  `ResetLua` in single player) is skipped and the bar jumps. A join that stops before the world (connect failure,
  including a tunnel that cannot open) ends the flow without an error and leaves no bar on the menu.
- R6 (D4): a second line names the current item with "i / total" for mods, Lua files and script files; other steps
  show the item the game names (texture pack, world-loading text) or nothing.
- R7 (D3): drawn on top of every frame `MainThread` hands over while a flow runs, including the vanilla world-loading
  screen, without covering its centered text, animated figure or ZombieBuddy's top-left watermark.
- R8: while a flow runs, `MainThread` hands over a frame of its own at least every 1 s whenever no other frame came,
  so the bar keeps moving and Windows never sees 5 s without a pump in the measured phases (incl. `ResetLua`'s
  post-reset events, `GameLoadingState.enter` and `IngameState.enter`). Frames come only from `MainThread`, never from
  inside another frame, at most ~20 per second. Known exception (D8): a blocking call of another mod with no hook of
  this mod inside, today Dont39 All-in-One's tunnel open (≤2 s); the frame-gap report names the step it fell in.
- R9 (D5): English and Chinese text from the mod's own strings, not the game's translation files (those load late and
  vanish on servers that do not list the mod). Chinese shows when the game language is Chinese (CN); every other
  language shows English.
- R10 (D5): client only: on a dedicated server (which may preload the jar through a shared ZombieBuddy config) the mod
  does nothing and logs one line saying so.
- R11 (D5): any mismatch with the game (missing target, unexpected state) is reported with context and stops the
  mod's hooks; loading continues as vanilla. No partial or silent degrade.
- R12 (D5): Workshop item: `mod.info` (`require=\ZombieBuddy,\Zabric`, `javaPreload=true`, `javaPkgName`, client jar path), poster,
  preview, `workshop.txt` in English and Chinese, module README, spec layout entry.

## Acceptance Criteria

- AC1 (R1–R3): Zabric unit tests cover opening at preLaunch, launch-wide vs. plain mods across a pass that lacks them,
  `closeAll` closing launch-wide mods, the attribute on a non-preloaded mod, the byte-based target check (missing
  member → stopped), and held log lines emitted once in order; all existing Zabric tests pass.
- AC2 (R1): no game class is loaded at premain or preLaunch because of the progress mod (its targets first load where
  vanilla loads them, per ZombieBuddy's `Transformed: … (new load)` lines), and its `hooks ready` line appears at the
  first pass.
- AC3 (R5–R7): on a launch with ~245 mods, the bar appears once fonts are ready and advances through every launch step
  to the main menu, with mod, script and Lua counts on the second line. A screen recording shows it moving at least
  once per second.
- AC4 (R5–R8): joining `myserver1`, once with the mod in the server's `Mods=` and once without, the bar runs from
  connect to the first in-world frame, over the world-loading screen without overlapping vanilla text, the figure or
  the watermark; Windows never shows "Not Responding" (recording); a debug line's frame gaps stay under 1 s in
  `ResetLua`, `GameLoadingState.enter` and `IngameState.enter`.
- AC4b (R5, R8, D8): with Dont39 All-in-One enabled, joining the `myserver2` test instance through its TCP tunnel and
  through its KCP tunnel, the bar runs to the first in-world frame; the only gap over 1 s is the tunnel open in the
  connecting step, under 2.5 s, named in the frame-gap line. A tunnel that cannot open (wrong tunnel port) ends the
  flow: the join screen shows the tunnel's reason, no bar remains, Back returns to the menu, no error is logged.
- AC5 (R5): a single-player start with a changed mod list and one without both reach the first in-world frame with
  the bar's steps in order.
- AC6 (R9): text is Chinese with the game in Chinese and English with the game in English.
- AC7 (R8): launch → menu and join → first in-world frame are each at most 5 % slower than without the mod (two runs
  each, same mod list, same session).
- AC8 (R10, R11): `serverSmokeTest` with the jar preloaded logs the client-only line and no error; a forced mismatch
  (test build with a wrong target) is reported once and the game reaches the menu.
- AC9: build gate passes (`./gradlew build assembleMod`, `smokeTest` and `serverSmokeTest` with the mod included);
  specs updated.

## Out of Scope

- The time from process start to the game window (ZombieBuddy premain; no window exists to draw on).
- Shortening any load phase; percentages or timing-based fill (D2).
- Drawing before fonts are ready (~3.5 s after the window opens).
- The first launch after installing (preload not registered yet): progress starts at the first pass, as for any
  Zabric mod; Zabric's restart notice asks for a restart.
- Publishing: the Maven Central tag, the `maven-central` approval and both Workshop uploads are the user's steps after
  merge.
- Languages other than English and Chinese (CN).
- Moving Dont39 All-in-One's tunnel open off the game thread (D8).
