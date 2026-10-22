# Implementation plan: texture compression off the render thread (R2)

Requirements `prd.md`, design `design.md`. Gradle: `JAVA_HOME=~/.jdks/graalvm-ce-25.0.2`.

## Checklist

1. **Encoder core (game-free, tested first).**
   - `textures/Encoder`, `Dxt5Alpha`, `Dxt5Encoder`, `EncoderPool`.
   - Tests: Java DXT5 decoder in test sources; alpha exact cases; alpha error ≤ brute force + tolerance on random
     blocks; PSNR floors on generated gradients/edges with alpha; odd sizes, 2×2 and 1×1; parallel == serial bytes.
2. **GL side.** `textures/Upload`: S3TC check once, unpack-state read, `glCompressedTexImage2D`, `glGetError` check.
   Call-shape decision as a pure static method, unit-tested.
3. **Hook and switch.** `hooks/TextureHooks` (cancellable HEAD on `GL11.glTexImage2D(...ByteBuffer)`), `Fix
   .TEXTURE_COMPRESSION`, options page `FIXES`, `Translate/EN|CN/UI.json`, `Settings` tests updated for the new key.
   README: fix row, the cancel exception in Constraints, Checks. Version 0.2.0 (`build.gradle.kts`, `mod.info`).
4. **Quality check (AC1).** `qualityCheck` task in `faster-loading/build.gradle.kts` (hidden GLFW window, 24 pages
   from installed packs, skipped when a pack is missing); record numbers in `verification.md`.
5. **Build gate.** `./gradlew :faster-loading:check :faster-loading:assembleMod`, then
   `:faster-loading:smokeTest` (twice after a fresh preload registration).
6. **In-game series (AC3-AC5).** Tooling copied from the R1 run into the session scratchpad (`setup.ps1`,
   `server.ps1`, `run.ps1` with `-ExtraVmArgs`, `series.ps1` switching `textureCompression`, `teardown.ps1`,
   `rcon.ps1`, LPTestDriver).
   - Back up first: `~/Zomboid/mods/default.txt`, `~/.zombie_buddy/config.json`, `~/Zomboid/options.ini`,
     `~/Zomboid/Workshop/Dont39LoadingProgress`, `~/Zomboid/Workshop/Dont39FasterLoading`,
     `~/Zomboid/Lua/Dont39LoadingProgress/`, `~/Zomboid/Lua/Dont39FasterLoading.ini`.
   - Stage Loading Progress 0.2.0 and Faster Loading 0.2.0 (`deployWorkshop`); Faster Loading in the ZombieBuddy
     preload list (one launch with it in the mod list registers it); LPTestDriver in the client mod list.
   - Warm-up join, then off/on alternating, 3 each; game `textureCompression=true`. Screenshot the same spot in the
     world on one on and one off run (run.ps1's shots, last frame in world).
   - Record per run: join total, Loading the world, asset wait, largest frame gaps, console errors; medians in
     `verification.md`.
7. **R2.5 gate.** One JFR join with the fix on: if the workers are ≥ 90 % busy on pack images for ≥ 5 s of the wait,
   plan the pool fix (back to `design.md`, user review), else record "not needed".
8. **Restore** everything from step 6's backups; teardown removes the copies.
9. **Check and spec.** `trellis-check`; spec update (Faster Loading's GL-level hook and the cancel exception in
   `zabric-hooks.md`/`zombiebuddy-patching.md` as applicable).

## Validation commands

```bash
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew :faster-loading:check :faster-loading:assembleMod
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew :faster-loading:qualityCheck
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew :faster-loading:smokeTest
```

## Risky points

- The first `GL_COMPRESSED_RGBA` call must come after the game log and cache folder exist (`Session`); smokeTest and
  the first in-game run's `console.txt` show the order. If it comes earlier, `Session` needs a not-yet-ready path
  (back to design).
- ZombieBuddy weaving `org.lwjgl.opengl.GL11` (loaded at display creation, before most game classes): smokeTest's
  hook listing shows it woven.
- Encoder pool vs game threads on CPUs with few cores: measured on 16 threads only; the pool size is
  `availableProcessors()`.

## Rollback points

- Commit after step 3 (fix complete, unit-tested), after step 6 (verified). The switch turns the fix off without a
  build.
