# Verification: Faster Loading 0.2.0, texture compression off the render thread (R2)

Date: 2026-10-22. Build: this worktree (`claude/asset-wait-speedup-2d74f7`, uncommitted). PC: Ryzen 7 9800X3D (16
threads), RTX 4090 24 GB, driver 617.42, game 42.21 `4a0e9546ec`, game option `textureCompression=true`.

## Offline

- `./gradlew :faster-loading:check :faster-loading:assembleMod`: pass (36 tests: comments 8 + 3, settings 12,
  `Dxt5AlphaTest` 6, `Dxt5EncoderTest` 5, `UploadTest` 2; `checkJdkGlue`, `checkLoaderRefs`,
  `checkBootstrapClasses`).
- **AC2 met.** The alpha encoder is never worse than stb's BC4 on 2,000 random blocks, within the brute-force bounds on
  ramps/edges (per block) and noise (total ≤ 1.1×), exact on flat, two-value and 0/255 blocks; parallel output equals
  serial bytes; odd sizes and mip tails give the block count GL expects and the same blocks as the padded image; the
  test decoder has its own palette and bit unpacking.
- **AC1 met** (`./gradlew :faster-loading:qualityCheck`, 21 pages: 6 each from melos 1/2 and `Tiles2x`, the 3 of
  `tikitown_tiles.floor`; level 0, GPU-decoded, PSNR vs the premultiplied source):

  | Encoder | RGB / alpha | GL thread per page |
  |---|---|---|
  | Driver `GL_COMPRESSED_RGBA` (picks DXT5, `0x83F3`) | 37.40 / 56.12 dB | ~15 ms |
  | Ours (stb_dxt colour + own alpha), 16 threads | 39.26 / 58.15 dB | ~2.7 ms (15.5 ms on one thread) |

  Ours is at or above the driver on every page, both channels. One `Tiles2x` page has exact alpha from both
  (counted as 100 dB in the means).
- Encoder speed: the first in-game profile (below) put ~90 % of the encoder threads in the alpha search; its rewrite
  (nearest palette step by fixed point, distinct values, exact early exits, pruning) cut alpha from ~24 to ~7.4 ms
  per page single-threaded at +0.25 % total squared error (alpha mean 58.16 → 58.15 dB). `PARALLEL_BLOCKS` 1024 →
  256 from a size sweep.

## In game

- `:faster-loading:smokeTest`: pass (second launch; the first registered the preload). ZombieBuddy wove
  `org.lwjgl.opengl.GL11.glTexImage2D` ("Applied advice ... signature-aware matching"); `active: comment stripping on,
  texture compression on`; the first taken upload logged `texture compression: dxt5 (...) on 16 threads`.
- The 5 `AngelCodeFont failed to load page 0 texture` errors in every run predate this change (same count in the
  morning's runs without it, compression on and off).
- Series: local nosteam server with the live server's full mod list, Loading Progress 0.2.0, each run a fresh client
  launch + join; Faster Loading's comment stripping on in all runs.

| Run | Join total | Loading the world | Asset wait | Budget sleep | Largest frame gap |
|---|---|---|---|---|---|
| off1 | 144,312 | 106,360 | 73,046 | | 1,320 (Loading mods) |
| off2 | 139,656 | 102,469 | 69,553 | 191,501 | 1,301 (Loading mods) |
| off3 | 141,682 | 103,840 | 70,422 | | 1,325 (Loading mods) |
| on4 | 69,308 | 32,267 | 12,479 | 807 | 1,322 (Loading mods) |
| on5 | 69,849 | 32,194 | 12,034 | 400 | 1,351 (Loading mods) |
| on6 | 71,100 | 33,650 | 12,841 | 332 | 1,371 (Loading mods) |
| **median off** | **141,682** | **103,840** | **70,422** | | 1,320 |
| **median on** | **69,849** | **32,267** | **12,479** | | 1,351 |

  (ms). on1-on3 ran the first encoder (before the alpha rewrite): wait 29.2-30.6 s, join 94.0-97.3 s.

- **AC3 met:** asset wait median 12.5 s ≤ 25 s; join total drops by 71.8 s (≥ 35 s). The wait is below the
  morning's compression-off join (13.8 s).
- **AC4 met, with these limits:**
  - No new errors in any run's `console.txt`: no mod disable, no GL error, no texture exception.
  - The run script's "Not Responding" sampler saw 0 hung samples in every run.
  - Loading Progress reports only the largest frame gap of the whole load, not one per step. In every run it falls in
    "Loading mods", outside this fix: 1,351 ms on vs 1,320 ms off (median), within the +100 ms bound. Per-step gaps of
    "Loading the world" and "Entering the world" are not measured.
  - Screenshots: one clean join each (`shot-off`, `shot-on`): survival guide off, inventory hidden, 20 s in the world.
    The same spot differs by a mean of 0.34/255 over the world area (0.95 on a brick wall, 1.16 on the roofs), with
    ≤ 1 % of pixels off by more than 15. That fits a different DXT5 compressor plus JPEG noise. No texture is missing.
  - It was night in game, so much of the view is dark.
- **AC5:** the off runs make no GL call from the hook. The argument check fails first or the switch is off, and the
  ordering is in `TextureHooks`. Their numbers match the baseline: the off waits of 69.6-73.0 s sit inside the R1
  runs' 48-74 s spread.
- **AC6 met:** check and smokeTest pass.
- **AC7 (R2.5 gate):** profile and reports with the fix on show the decoders' budget sleep gone (0.3-0.8 s). The file
  workers are 98 % busy, but pack images are 9.4 s of their 47 s; the rest is depth maps (22.6 s, one at a time under
  their lock) and animations (14.8 s). Below the ≥ 90 %-on-pack-images gate: the pool fix is **not needed**.

## Profiles (JFR, `settings=profile`; files in the session scratchpad)

- Compression on, before the fix: render thread 52 % `glTexImage2D` (driver compression), 26 % `glfwSwapBuffers`.
- First encoder in game (wait 35.5 s): encoder threads ~90 % in `Dxt5Alpha`, stb colour ~2 %;
  the render thread encoded small levels inline. Fixed by the alpha rewrite above.

## Restored after the runs

Mod list, ZombieBuddy config (without Faster Loading's preload), `options.ini`, both mods' ini files, the Workshop
staging of Loading Progress / Faster Loading / Zabric (0.1.0 / 0.1.0 / 0.6.0 as before), Loading Progress's reports
(20), and the 494 mod copies removed.

## Not done

- Other GPUs/drivers (AMD, Intel, macOS): untested. The fix uses DXT5 explicitly; NVIDIA picks DXT5 for
  `GL_COMPRESSED_RGBA` too.
- A join with the game's `textureCompression=false` and the fix on (the hook takes no call then by its argument check;
  not run).
- Remaining wait: depth maps under their lock and animations; out of reach for a preloaded mod (`research/upload-path.md`).
