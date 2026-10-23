# Research: Deferred and dropped items: R5 FMOD, R6a Item.DoParam, R6b texture pack index

- **Date**: 2026-10-21. Split from the launch-items research; index and per-item verdict: `launch-items.md`.
- Jar: `E:/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid.jar`, 42.21.0 `4a0e9546ec`. Citations look
  like `Class.method Lnnn`, with source lines taken from the LineNumberTable.

## R5: FMOD at launch

**Sequence** (`GameWindow.mainThreadInit L590-662`):

| Line | Call |
|---|---|
| L600 | `Core.debug` from the `debug` system property |
| L609 | `if (!Core.soundDisabled) FMODManager.instance.init()` |
| L612-613 | `DebugOptions.init`, `GameProfiler.init` |
| L615 | `SoundManager.instance = soundDisabled ? new DummySoundManager() : new SoundManager()` |
| L616 | `AmbientStreamManager.instance = …` |
| L617 | `BaseSoundBank.instance = soundDisabled ? new DummySoundBank() : new FMODSoundBank()` |
| L619 | `VoiceManager.instance.loadConfig()` |
| L627-630 | `SoundManager.instance.set{Sound,Music,Ambient,VehicleEngine}Volume(...)` |
| L633 | `ZomboidFileSystem.instance.init()` |
| L660 | `init()` → … `loadMods` (`GameWindow.init L1111`) |

**What `FMODManager.init()` does** (`FMODManager.init L133-172`):
- `javafmodJNI.init()` (native libraries), then `FMOD_System_Create`. On failure it sets `Core.soundDisabled = true`
  and returns (L137).
- `FMOD_System_Init(1024, studioFlags, coreFlags)` with debug-only profiling flags chosen from `Core.debug`. On
  failure it sets `soundDisabled` and returns (L148).
- `Set3DSettings`; `loadBank` ×12 (`ZomboidSound.strings`, `ZomboidMusic`, `ZomboidSound`, `ZomboidSoundMP`, `Animal`,
  `Broadcast`, `Character`, `Object`, `Vehicle`, `Weapon`, …) at L154-165.
- `CreateChannelGroup`, `initGlobalParameters` (L169), `initEvents` (L170), `initVCA` (L172).
- Bank paths are plain relative `File` canonical paths (`bankPath L177`), with no `ZomboidFileSystem` dependency.

**What depends on it**:
- Mid-method code right after it: `Core.soundDisabled` (L615-617, choosing the Dummy managers), the banks (FMOD
  global-parameter descriptions resolved in the `SoundManager`/`AmbientStreamManager` field initializers), and the
  VCAs (the volume setters, L627-630).
- PZ_Optimization's issue #3: managers built before the banks loaded silently lost their parameters, so the music and
  ambience broke (`docs/override-edits.md:431-450`).
- They moved those constructions to the join, right before `ScriptManager.Load` (`initShared` L217), whose last step
  `GameSounds.ScriptsLoaded` is the first FMOD consumer. That needs body edits.

**Feasibility with hooks only**:
- Starting FMOD inside `mainThreadInit` gains nothing. The dependent constructions are 6 source lines later, in the
  same method body, and cannot be moved or deferred by HEAD/RETURN/FINALLY hooks (constructors cannot be cancelled).
- **What hooks can do: start earlier, join at the original point.**
  - `MainScreenState.main(String[])` parses the arguments (`Core.soundDisabled` L243, `Core.debug` L252-263). It then
    calls `RenderThread.init()` (L324), which runs `GameWindow.InitDisplay()` (L78) and `Controllers.create()`, and
    then `GameWindow.InitGameThread()` (L338) → `mainThreadStart` → `mainThreadInit`.
  - A HEAD hook on `RenderThread.init()V` (static, no args, JDK-only) could start `FMODManager.instance.init()` on a
    thread when `!Core.soundDisabled`.
  - A HEAD `cancellable` hook on `FMODManager.init()V` would then join that thread and cancel, unless it runs on the
    FMOD thread itself, which must pass through.
  - `Core.soundDisabled` is then set before L615 reads it, the same as in vanilla.
  - Both signatures are JDK-only (`FMODManager.instance` is a static field, so no `@Self` is needed), so this works in
    a preloaded mod with the default gate.
  - It cannot be a preLaunch entrypoint: no game classes are allowed there (`zabric/README.md:17`).
- **Overlap window** in `2026-10-21_20-55_DebugLog.txt`:
  - The display starts around 21.6 s (`onDisplayCreate` 20:55:22.375, `VSync: OFF` 22.408).
  - FMOD's first line is `fmod: Java loging: OK` at 20:55:23.043; `Studio::System::Initialize()` OK at 23.157.
  - The next line is at 27.704, so the log cannot separate the bank loading from what follows.
  - **The window before L609 is about 1.4 s.** PZ_Optimization measured FMOD init at 1.4-1.6 s
    (`plan-instant-load.md` B2; `docs/guide/how-it-works.md:150`).
- **Risks**:
  - FMOD init would run concurrently with GLFW window creation.
  - Thread affinity of FMOD Studio: the core flags `131840` (0x20300) and studio flags 0 (or the debug profile flags)
    include no synchronous-update or thread-unsafe flag, so the Studio API should be thread-safe. This is from FMOD
    flag semantics, not verified in the docs here.
  - The Windows COM apartment of the init thread is unverified.
  - `Core.debug` may change at `mainThreadInit` L600 from the `debug` property. That affects only the profiling
    flags.
  - **The saving would not show in Loading Progress reports**: FMOD runs before the launch flow begins
    (`initFonts` RETURN), in the uncovered "process start → fonts" span.

## R6a: `Item.DoParam(String,String)`

- **Method**: `Item.DoParam(String param, String value)` L1969-3008.
  - It first runs `Attribute.TypeFromName(param.trim())` (L1970).
  - Then a chain of `param.trim().equalsIgnoreCase("…")`: **440 `equalsIgnoreCase` and 540 `trim()` calls** in the
    bytecode. Some are value comparisons; PZ_Optimization counts 361-367 keys, with `SwingAnim` appearing twice and
    the first branch winning.
  - The unknown-key and `GameEntityScript` tail and `InvalidParameterException` follow (L3006).
- **Callers**: `DoParam(String)` L1951-1960 (`split("=")`, trimming both parts) and `Item.Load(String,String)` L1433-1449
  (`ScriptParser.parse`, then `DoParam` per value).
- **Cost**:
  - PZ_Optimization measured 0.85-0.9 s of boot for vanilla items (`plan-instant-load.md` B3;
    `docs/guide/how-it-works.md:165`).
  - Here it falls inside "Script file 3137 / 3137 … and the game's processing of all script files after it" = 2,332 ms
    at launch, and inside the join's scripts step of 2,688 ms. It is not separated.
- **Hook point**:
  - The descriptor `(Ljava/lang/String;Ljava/lang/String;)V` is JDK-only, but a replacement must write the `Item`'s
    fields, so it needs `@Self Item`. That is **not allowed in a preloaded mod** (`zabric/README.md:28`).
  - A non-preloaded mod could cancel at HEAD and dispatch through a `switch`. That means reimplementing all ~365
    branch bodies, which is game logic tied to the exact build.
  - PZ_Optimization generated theirs mechanically from a decompilation and validated it with a field dump of every
    item (`docs/override-edits.md:793-807`).

## R6b: `TexturePackDevice` page scan

- **Path**: `GameWindow.LoadTexturePack(String,int,String)` L1029 → `FileSystem.mountTexturePack` → `TexturePackDevice`
  → `initMetaData()` L190-218 → `readPage(PositionInputStream)` L222-259.
- **The scan**: for version-0 packs (`version == 0`), L247-254 loops `TexturePackPage.readIntByte(in)` until it reads
  `0xDEADBEEF`.
  - Each iteration reads one byte through the `synchronized` `PositionInputStream.read()`.
  - `readIntByte` keeps its sliding window in **static** fields `chl1..chl4` (`TexturePackPage.readIntByte L81-93`),
    so it is single-threaded only.
  - Version 1+ packs read a length and skip (L255-256).
- **Cost**:
  - PZ_Optimization measured 0.5-0.6 s for 526 MB of packs (`docs/override-edits.md:779-791`).
  - Here, the launch report's "texture packs: 15, 447 ms" (JumboTrees2x 176 ms, WeatherFx 111 ms, …) is the
    **upper bound** for the whole mount, at launch only. The join report has no texture-pack rows.
- **Hook points**:
  - `readPage` names `TexturePackDevice$PositionInputStream` and `$Page`.
  - `initMetaData()V` needs `@Self`.
  - Both are non-JDK, so a non-preloaded mod is required. That is enough, since `initShared` runs after the launch's
    first mod list.
  - `readIntByte(InputStream)I` is JDK-only but called once per byte. A hook there adds a gate check per byte instead
    of removing the loop.
  - A cache of page end offsets keyed by pack size and mtime, as PZ_Optimization did, would cancel `readPage` and
    reimplement its header parse (L222-245, about 20 lines).

---

