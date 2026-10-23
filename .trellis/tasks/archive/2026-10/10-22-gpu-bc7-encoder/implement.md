# Implementation plan: GPU BC7 texture encoder

Requirements `prd.md`, design `design.md`, evidence `research/bc7-prototype.md`. Gradle:
`JAVA_HOME=~/.jdks/graalvm-ce-25.0.2`. Worktrees get LFS pointer PNGs: `git lfs pull` before any staging.

## Checklist

1. **Encoder interface.** `Encoder.define(...)`; `Dxt5Encoder.define` (encode + `glCompressedTexImage2D`);
   `Upload.compressed` delegates and keeps the error checks; `Encoders` with the pure `choose` and the session choice
   (one log line); `TextureHooks` uses it; S3TC check moves into the DXT5 choice. With the BC7 switch absent this is
   behaviour-neutral: R2's tests and `qualityCheck` pass unchanged.
2. **Switch.** `Fix.TEXTURE_BC7` (`textureBc7`), `Settings` tests (default on, an ini without the key gets it),
   options page `FIXES`, `Translate/{EN,CN}/UI.json`.
3. **Shader resource.** `textures/bc7/BC7Encode.comp` from the prototype's `bc7_dxtex.glsl` with the chosen variant
   baked in (modes 4-7, alpha weight 4, nearest index), `ENTRY_02` and 3-subset packing dropped, 2D group index, MIT
   header + source commit + list of deltas. `META-INF/licenses/DirectXTex.txt` (DirectXTex `LICENSE`) via
   `src/main/resources`.
4. **`Bc7Encoder` + `GlState`.** Programs, buffers (input ring, error, output), `groups(n)` (unit-tested), `define`
   per `design.md`, state save/restore. `TextureHooks`: BC7 chosen and rows not packed → throw.
5. **`qualityCheck`.** Add BC7 vs shipped DXT5 (RGB, over white, over grey; alpha and worst error printed), odd-size
   mip chains and a 2048² level (format, completeness, PSNR floor), GL-state round trip, GL-thread and GPU time per
   page. Record the numbers in `verification.md` (AC1-AC3).
6. **Docs and version.** README (fix row, Switches, Constraints: GL state restore and no uniforms, license, Checks),
   `workshop.txt`, version 0.5.0.
7. **Build gate.** `./gradlew :faster-loading:check :faster-loading:assembleMod`; jar contents (shader,
   license); `:faster-loading:smokeTest` (twice after a fresh preload registration) shows the encoder line.
8. **In-game series (AC5).** Tooling from the R2/R4 runs (copy
   `...lua-folder-walk-143b68\...\scratchpad\fullsrv\tools` into this session's scratchpad; it copies ~29 GB of mods;
   the test server copies the live server's `DoLuaChecksum=false`).
   - Back up first: `~/Zomboid/mods/default.txt`, `~/.zombie_buddy/config.json`, `~/Zomboid/options.ini`,
     `~/Zomboid/Lua/*.ini`, `~/Zomboid/Workshop/Dont39LoadingProgress`, `~/Zomboid/Workshop/Dont39FasterLoading`,
     `~/Zomboid/Lua/Dont39LoadingProgress/` (Loading Progress keeps only 10 reports).
   - Stage Loading Progress 0.2.0 and Faster Loading 0.5.0 (`deployWorkshop`); check the mod list first (the launcher
     rewrites `default.txt`).
   - Warm-up join, then `textureBc7` off/on alternating, 3 each, each after a ≥ 30 s menu stay; game
     `textureCompression=true`. Screenshot the same spot on one on and one off run; crop sprite edges.
   - Record per run: join total, Loading the world, asset wait, the wait's phases (`timeline.py` from
     `archive/2026-10/10-22-depth-map-speedup/research/`), largest frame gap, console errors, the encoder line.
9. **Restore** everything from step 8's backups; teardown removes the copies.
10. **Check and spec.** `trellis-check`; spec update: ports of third-party code (`quality-guidelines.md`), GL work on
    the game's render thread (state restore, no uniforms on programs in flight; `error-handling.md` GL row or a
    new section).

## Validation commands

```bash
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew :faster-loading:check :faster-loading:assembleMod
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew :faster-loading:qualityCheck
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew :faster-loading:smokeTest
```

## Review gates

- After step 1: R2's tests and `qualityCheck` unchanged (interface change is neutral).
- After step 5: AC1-AC3 numbers before any in-game run; a miss goes back to design, not into the series.
- After step 8: AC5 table reviewed with the user before the spec update and commit.

## Risky points

- The game's GL context: the prototype ran on a GLFW default context (4.6 compatibility); the game's may differ.
  `smokeTest`'s encoder line shows the version, and the first in-game join shows whether BC7 was chosen.
- Driver work queued by a burst of pages could stretch a loading-screen frame; Loading Progress's largest frame gap
  (AC5) shows it.
- A level the GL defines as BC7 while an earlier level of the same texture went another way would leave the texture
  incomplete (black or missing tiles): the one-choice-per-session rule and the packed-rows throw prevent it; the
  screenshots check it.
- Shader compile on the first launch (~300 ms, cached by the driver later) lands in the first pack page's upload.

## Rollback points

- Commit after step 4 (encoder complete, unit-tested), after step 7 (build gate), after step 8 (verified).
  `textureBc7 = false` returns to 0.4.0's behaviour without a build.
