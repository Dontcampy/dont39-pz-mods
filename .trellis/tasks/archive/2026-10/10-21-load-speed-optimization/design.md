# Design: load speed optimization

Requirements and evidence: `prd.md`. Research under `research/`: `asset-wait.md`; `launch-items.md` indexes `zabric-hook-model.md`, `strip-comments.md`, `lua-loading.md`, `deferred-items.md`.

## Shape

Two places change:

| Where | What | Child task |
|---|---|---|
| `loading-progress` (existing, measurement only) | Asset-wait entry and file-task breakdown in the start report | `10-21-asset-wait-breakdown` (R1) |
| New module `faster-loading`, mod `Dont39FasterLoading` (proposed name) | Every speed-up, one switch each | `10-21-faster-loading-strip-comments` (mod + R3), `10-21-lua-folder-walk` (R4), `10-21-asset-wait-speedup` (R2) |

Measurement and behaviour change stay in separate mods so a switch can be flipped and the next Loading Progress
report shows the difference.

## New mod

- Built like `loading-progress` (`loading-progress/build.gradle.kts`): `pz-java` + `pz-mod` + `pz-bootstrap` +
  `pz-widening` + `pz-junit`; jar in `media/java/client`; manifest `ZB-Preload`, `Zabric-PreLaunch`, `Zabric-Main`,
  `Zabric-Hooks: launch`; `checkJdkGlue` on. Reason: a client-only mod is absent from a server's mod list, and only
  preloaded launch-wide hooks are open during a join and a Lua reload (`prd.md` Hook model).
- Java, not Kotlin: preloaded hooks open before ZabricKotlin installs Kotlin.
- Glue binds JDK types only; implementation classes outside the glue package may use game types and widened members.
- Switches: `Dont39FasterLoading.ini` in the game cache's `Lua` folder, read once per session (pattern:
  `loading-progress/.../progress/Settings.java`), mirrored on a mod options page. Each handler checks its switch; off
  means the hook returns without cancelling, so the game's own code runs.
- Failure: the mod's default gate stops all its hooks on a handler failure; the mod logs one error line with context
  (`.trellis/spec/mod/error-handling.md`). No per-call fallback to the original once a fix has started, since a
  partial fix could leave the game half in our state.
- Client only at runtime too: handlers do nothing on a dedicated server (as Loading Progress checks
  `GameServer.server`).

## Per fix

- **R3 stripComments**: cancellable HEAD on `ScriptParser.stripComments(String)String`, return the linear result.
  The implementation is a single pass that reproduces the original's quirks; the unit test runs the real 42.21 method
  from the game jar as the oracle over real files and generated cases. Loading Progress brackets the same call as a
  known call; the child checks that its timing still sees the call when ours cancels it (advice order).
- **R4 Lua folder walk**: profile first (timing hooks or JFR) to confirm where `searchFolders(URI, File)` spends its
  time; then a cancellable HEAD that builds the same entries without the per-entry canonical-path round trips, still
  calling `ZomboidFileSystem.getRelativeFile` so Viewpoint's patch applies. Equivalence: `loadList` dump on vs off.
  Precompile only on a measured ≥ 1 s compile share with a preloadable point.
- **R1 breakdown**: hooks per `research/asset-wait.md` §3 (wait markers from `DebugType.println(String)`, per-frame
  queue snapshot in the existing `isLoadingAnimations()Z` hook or `hasWork()Z`, HEAD/FINALLY on file tasks'
  `call()`, names from `getRawFileName()` / `validatePrefix(String)` / `createTileset(String,Z)`), widened fields as
  listed there. New item kind and summary block in `report/Report.java`; `Progress` stays game-free and unit-tested.
- **R2**: designed in its child after R1 data.

## Compatibility

- Viewpoint patches `getRelativeFile` and `FMODManager.loadSound` and caps the loading-screen frame rate; Loading
  Progress hooks `stripComments`, `LoadDirBase`/`RunLua`; ModSync hooks `Core.ResetLua`. Nothing else patches
  `stripComments`, `searchFolders` or `LuaCompiler` (DebugLog 2026-10-21_20-55).
- Target build check: the mod refuses to act on a game revision other than 42.21 `4a0e9546ec`, reported as an error.
- MP: no file content or Lua checksum changes; joins verified on the Linux test server (memory: test server recipe).

## Rollback

Each fix is one switch; the whole mod can be removed from the mod list. Loading Progress changes are additive to its
report.
