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
- D9 (2026-10-19): the generated glue declares advice parameters with the target's own types, which ZombieBuddy loads
  at premain for a preloaded jar. The progress mod therefore binds only JDK-typed arguments and never `@Self`; the
  generator is not changed. A second-line item that would need a game-typed argument shows its count without a name.
- D10 (2026-10-19, measured): a class that loads while a transformer of the same agent runs is shown to none of that
  agent's transformers, so Zabric never retransforms at preLaunch. Consequences accepted: ZombieBuddy's own premain
  weaving of already-loaded targets (`LuaManager`, `KahluaThread`, `ZomboidFileSystem`) loads ~50 game classes, and
  each mod's access-widener report is the guard for what a list names; hooks on targets ZombieBuddy loaded at premain
  without weaving (`ChooseGameInfo`, `LuaManager$GlobalObject`) are woven at the end of the first pass, so mod
  discovery gets no trigger from them (D11 covers it through `ZomboidFileSystem`).
- D11 (2026-10-19): mod discovery and ZombieBuddy's Java-mod pass are covered (options A + B + C of
  `research/zb-pass-gap.md`): discovery triggers on `ZomboidFileSystem` (woven at premain); a Zabric loader-progress
  API (per Java mod jar, JDK-only listener) drives own frames and "Java mod i / n: id"; inside one jar's patching, the
  render thread shows interim frames: the last `MainThread` frame with the bar redrawn from a Zabric heartbeat count.
- D12 (2026-10-19, `research/other-gaps.md`): triggers on `TextManager.isAnyFontLoading` (font pages, ~2.2 s in
  translations), `ScriptBucket.getCurrentScriptObject` (script objects, ~1.7 s) and `ChooseGameInfo.readModInfo`
  (mods that reread mod.info in their boot code). Known gaps like D8, since no hook can split them: one
  `ScriptParser.stripComments` call per tile-geometry file (~2 s for vanilla's, every launch, start and reload), the
  Steam installed-items query at discovery (9 ms measured with a warm cache; bracketed for a cold one), and other
  mods' long Lua callbacks. Discovery's measured ~1.8 s was `DebugFileWatcher.registerDir` per folder of a large mod,
  covered by a trigger, as is `LuaManager$Exposer.setExposed` (~1 s of first class loads in `LuaManager.init`).
- D13 (2026-10-19): a thin sub-bar under line 2 shows the current item's progress (i / total of mods, script files,
  Lua files, Java mods; inside a jar, the heartbeat-paced moves within that jar's share). The main bar stays step
  n / N (D2).
- D14 (2026-10-19): interim frames work without OpenGL 4.5 direct state access (bind-based copy with saved and restored
  bindings); no capability switches them off. A GL error is a mismatch and stops the mod with its report.
- D15 (2026-10-20, replaces D13's sub-bar and lines 1 and 2): under the main bar (step n / N), a log of as many
  lines as fit between the bar and the vanilla loading screen's own content (~12 at 1080p, ~20 at 4K): one per step change and one per item (mod, script file, Lua file, Java mod, texture pack, event), the newest
  at the bottom, older lines fading by position and dropped by count, never by time. Interim frames animate trailing
  dots on the newest line while the heartbeat moves.

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
  `ResetLua` in single player) is skipped and the bar jumps. After its Lua reset a game start's bar is hidden over
  the menu's interactive screens (character creation, spawn selection) and resumes at the login queue or the world
  load. A join that stops before the world (connect failure, including a tunnel that cannot open) ends the flow
  without an error and leaves no bar on the menu.
- R6 (D4): a second line names the current item with "i / total" for mods, Lua files and script files; other steps
  show the item the game names (texture pack, world-loading text) or nothing.
- R7 (D3): drawn on top of every frame `MainThread` hands over while a flow runs, including the vanilla world-loading
  screen, without covering its centered text, animated figure or ZombieBuddy's top-left watermark.
- R8: while a flow runs, `MainThread` hands over a frame of its own at least every 1 s whenever no other frame came,
  so the bar keeps moving and Windows never sees 5 s without a pump in the measured phases (incl. `ResetLua`'s
  post-reset events, `GameLoadingState.enter` and `IngameState.enter`). Own frames come only from `MainThread`, never from
  inside another frame, at most ~20 per second (also for runs of jars that return at once). Known exceptions (D8,
  D12): a blocking call of another mod with no hook of this mod inside (today Dont39 All-in-One's tunnel open, ≤2 s),
  other mods' long Lua callbacks, tile-geometry comment stripping and the Steam installed-items query; the frame-gap
  report names the step and item each gap fell in, the share of it the last two took, the Java-mod pass's largest
  visible gap and the gap with the most time outside them. ZombieBuddy's Java-mod pass (D11, D13): own frames at each jar;
  between them the render thread presents interim frames (the last captured frame, sub-bar advanced, text
  unchanged) while the heartbeat moves, and none when it stops, so a real hang still shows a still bar. Interim
  frames come only from the render thread and restore all GL state they touch.
- R9 (D5): English and Chinese text from the mod's own strings, not the game's translation files (those load late and
  vanish on servers that do not list the mod). Chinese shows when the game language is Chinese (CN); every other
  language shows English.
- R10 (D5): client only: on a dedicated server (which may preload the jar through a shared ZombieBuddy config) the mod
  does nothing and logs one line saying so.
- R11 (D5): any mismatch with the game (missing target, unexpected state) is reported with context and stops the
  mod's hooks; loading continues as vanilla. No partial or silent degrade.
- R12 (D5): Workshop item: `mod.info` (`require=\ZombieBuddy,\Zabric`, `javaPreload=true`, `javaPkgName`, client jar path), poster,
  preview, `workshop.txt` in English and Chinese, module README, spec layout entry. `poster.png` and `preview.png` are
  placeholders; real art is the user's step before upload.

## Acceptance Criteria

- AC1 (R1–R3): Zabric unit tests cover opening at preLaunch, launch-wide vs. plain mods across a pass that lacks them,
  `closeAll` closing launch-wide mods, the attribute on a non-preloaded mod, the byte-based target check (missing
  member → stopped), and held log lines emitted once in order; all existing Zabric tests pass.
- AC2 (R1, D9, D10): a build check fails if the mod's glue names a non-JDK type in an advice signature; neither Zabric
  nor the mod's own code loads a game class at preLaunch (class-load log: 0 game classes loaded from Zabric or mod
  frames there), ZombieBuddy's premain weaving of already-loaded targets excepted; the mod's `access widener: ok` and
  `hooks ready` lines appear at the first pass.
- AC3b (D11–D14): on the ~245-mod launch, the frame-gap line reports the largest visible gap (counting interim
  frames) of the Java-mod pass separately, under 1 s; every other visible gap over 1 s is one of R8's known
  exceptions; at 4K the bar and sub-bar do not cross ZombieBuddy's watermark (R7); Zabric
  tests cover the loader-progress listener and heartbeat; with Viewpoint enabled and disabled the screen shows no
  flicker or garbage (recording).
- AC3 (R5–R7): on a launch with ~245 mods, the bar appears once fonts are ready and advances through every launch step
  to the main menu, with mod, script and Lua counts on the second line. A screen recording shows it moving at least
  once per second.
- AC4 (R5–R8): joining `myserver1`, once with the mod in the server's `Mods=` and once without, the bar runs from
  connect to the first in-world frame, hidden only over character creation and spawn selection after the Lua reset
  and back at the login queue or world load, over the world-loading screen without overlapping vanilla text, the
  figure or the watermark; Windows never shows "Not Responding" (recording); a debug line's frame gaps stay under 1 s
  in `ResetLua`, `GameLoadingState.enter` and `IngameState.enter`. A join with a client/server version mismatch ends
  the flow: Back leaves no bar on the menu.
- AC4b (R5, R8, D8): with Dont39 All-in-One enabled, joining the `myserver2` test instance through its TCP tunnel and
  through its KCP tunnel, the bar runs to the first in-world frame; the only gap over 1 s is the tunnel open in the
  connecting step, under 2.5 s, named in the frame-gap line. A tunnel that cannot open (wrong tunnel port) ends the
  flow: the join screen shows the tunnel's reason, no bar remains, Back returns to the menu, no error is logged.
- AC5 (R5): a single-player start with a changed mod list and one without both reach the first in-world frame with
  the bar's steps in order.
- AC6 (R9): text is Chinese with the game in Chinese and English with the game in English.
- AC7 (R8): launch → menu and join → first in-world frame are each at most 5 % slower than without the mod (two runs
  each, same mod list, same session).
- AC8 (R11): a forced mismatch (test build with a wrong target) is reported once and the game reaches the menu. R10
  has no server test (2026-10-19: client-only mod); review checks that every hook and the drawing sit behind the
  client-only gate.
- AC9: build gate passes (`./gradlew build assembleMod`, the mod's `smokeTest`); specs updated.

## Out of Scope

- The time from process start to the game window (ZombieBuddy premain; no window exists to draw on).
- Shortening any load phase; percentages or timing-based fill (D2).
- Drawing before fonts are ready (~3.5 s after the window opens).
- The first launch after installing (preload not registered yet): progress starts at the first pass, as for any
  Zabric mod; Zabric's restart notice asks for a restart.
- Publishing: the Maven Central tag, the `maven-central` approval and both Workshop uploads are the user's steps after
  merge.
- Final poster and preview art: the committed `poster.png` and `preview.png` are placeholders; the user replaces them
  before upload.
- Languages other than English and Chinese (CN).
- Moving Dont39 All-in-One's tunnel open off the game thread (D8).

## Outcome (2026-10-20)

Evidence (consoles, DebugLogs, screenshots, timing tables) stayed in the session scratchpad; results below.

| AC | Result |
|---|---|
| AC1 | Passed: Zabric unit tests (preLaunch opening, launch-wide, closeAll, byte check, held log, loader progress, heartbeat) |
| AC2 | Passed as reworded (D10): `checkJdkGlue`; 0 game classes loaded from Zabric or mod code at preLaunch |
| AC3, AC3b | Passed: 246-mod launch, Java-mod pass largest visible gap 534–645 ms; only gaps over 1 s are tile comment stripping (known call) |
| AC4 | Passed against a local dedicated server (nosteam), with and without the mod in `Mods=`; failed join then a good one clean |
| AC4b | Wrong tunnel port passed; successful TCP and KCP tunnel joins not run (the tunnel refuses a server on this machine) |
| AC5 | Passed: single player with and without a mod-list change |
| AC6 | Passed; a CN launch shows English until the CN fonts load (~16 s, by design) |
| AC7 | Passed: launch → menu +4.4 %, join → first in-world frame +2.3 % (local server) |
| AC8 | Passed: forced mismatch reported once, menu reached (found and fixed a second error from exit handlers); server line once, listed or only preloaded |
| AC9 | Passed: build gate, `:loading-progress:smokeTest`, `:dont39-all-in-one:smokeTest` |

Open items for the user:
- Successful TCP and KCP tunnel joins on `myserver2` (needs the user's account there).
- A join with a client/server version mismatch (Back must leave no bar on the menu).
- Real art for `loading-progress/mod/42/poster.png` and `mod/workshop/preview.png` (placeholders now).
- Release: Zabric 0.5.0 tag (Maven Central) and Workshop upload, then the sample's `zabricVersion` bump; Loading Progress Workshop upload. Consider a sentence in Zabric's author contract that exit handlers run after the hooks close (spec rule in `zabric-hooks.md`).
- Joins with Steam on to a server on the same machine hang at "GettingServerInfo" here, without the mod too (environment).
