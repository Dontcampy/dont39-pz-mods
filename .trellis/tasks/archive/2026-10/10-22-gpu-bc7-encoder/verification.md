# Verification: Faster Loading 0.5.0, GPU BC7 texture encoder

Date: 2026-10-23. Build: this worktree (`feat/gpu-bc7-encoder-2b7d0a`, uncommitted). PC: Ryzen 7 9800X3D (16
threads), RTX 4090 24 GB, driver 617.42 (`4.6.0 NVIDIA 617.42`), game 42.21 `4a0e9546ec`.

## Offline

- `./gradlew :faster-loading:check :faster-loading:assembleMod`: pass. 71 tests, 1 skipped (`FolderWalkTest`'s symbolic
  link, as before): new `EncodersTest` 3 (each switch × OpenGL 4.3 combination with its reason), `Bc7EncoderTest` 4
  (blocks of partial sizes; the dispatch grid on every width 1-4096 × 13 heights up to 4096, one and four blocks a
  group, around one grid row, 4096² = 65,535 × 17), `SettingsTest` 15 (+1: a 0.4.0 ini gets `textureBc7 = true`, the
  others kept); `checkJdkGlue`, `checkLoaderRefs`, `checkBootstrapClasses`.
- **AC6 (offline part) met.** The jar holds `com/dont39/pz/fasterloading/textures/bc7/BC7Encode.comp` and
  `META-INF/licenses/DirectXTex.txt`; manifest and `mod.info` say 0.5.0. `smokeTest` not run here (it launches the
  game; the parent session runs it with backups).
- `./gradlew :faster-loading:qualityCheck`: pass (final build: the encoder variant and AC3 limit the user revised on
  2026-10-23, below). Full output at the end.

### AC1 met: quality on the 21-page sample (level 0, GPU-decoded, means, lossless = 100 dB)

| Encoder | RGB / alpha dB | Over white / over grey dB | Alpha px off > 8 (max) | Over-white px off > 8 (max) |
|---|---|---|---|---|
| Driver `GL_COMPRESSED_RGBA` (DXT5) | 37.40 / 56.12 | 37.06 / 37.31 | 159,061 (25) | 1,636,372 (199) |
| Shipped CPU DXT5 | 39.26 / 58.15 | 38.95 / 39.18 | 98,250 (23) | 1,198,871 (181) |
| **BC7 (modes 4-7, alpha weight 4, nearest index when encoding)** | **45.33 / 53.43** | **44.34 / 45.06** | 132,272 (100) | 475,620 (169) |
| BC7, nearest index in every pass (first build) | 45.32 / 53.70 | 44.36 / 45.05 | 121,277 (100) | 471,906 (169) |

- The first build (nearest index in every pass) matched the research's variant to 0.01 dB; the final build matches
  the measured hybrid exactly.
- BC7 above DXT5 on RGB, over white and over grey (means); pages below DXT5: over white 1, over grey 1 (melos2 #3),
  RGB 0, alpha 20. DXT5 still at or above the driver on both channels (R2's gate).

### AC2 met: mip chains, a 2048² level, GL state

- Chains of 1023×1019 (10 levels), 37×5, 5×37, 13×2, 3×1, 1×1 (box-filtered halves of a real page's crop): every
  level `0x8E8C` (BPTC) of its size down to 1×1, so the texture is complete. Lowest level PSNR 25.38 dB (rgb of the
  31×31 level of the 1023×1019 chain; DXT5 on the same level 26.79), others ≥ 37.85. The floor is 20 dB (blocks
  decoded wrong land far below it): the first floor of 30 dB failed on that 31×31 level, which DXT5 does not reach
  either.
- Review fix: a texture whose min filter does not mipmap (the game's "noMips" path sends level 0 alone with
  `GL_NEAREST`/`GL_LINEAR`, set before the upload: `TextureID.generateHwId` @277 before its upload loop @442) gets only
  its level, not a chain, so it uses DXT5's video memory. The 2048×2048 check runs with `GL_LINEAR`: "level 1 not
  allocated".
- 2048×2048 level (4 pages tiled; 65,536 groups of four blocks and 262,144 of one, so both grids span two or more
  rows): `0x8E8C`, 43.95 / 48.81 dB. Since the fixed buffers (AC7) it goes in four strips of 512 rows: 16,384 groups
  of four and 65,536 of one a strip, so only the one-block grid spans two rows; 43.97 / 48.50 dB.
- GL state: program, generic SSBO, SSBO 0 range-bound (`@16+256`), SSBO 1 base-bound, SSBO 2 unbound, unpack buffer
  0, a texture bound on unit 3: the same after a BC7 level.

### AC3 met: BC7's GL thread a page is 31-33 % of DXT5's in the same run (limit ≤ 40 %)

The limit went from ≤ 1 ms to ≤ 1.5 ms (user, after the pixel copy below proved to be ~1 ms) and then to a share of
DXT5's time in the same run (user, 2026-10-23): on the same code BC7 measured 1.33-1.42 ms with the machine quiet and
1.51-1.59 ms with other programs busy, DXT5 4.1-4.3 vs 4.85-5.0 ms, the same share. Final run: BC7 1.34 ms, DXT5
4.25 ms, 32 %.

21 pages with all levels, a new texture each, medians of 7 interleaved runs, per page:

| Encoder | GL thread | Until the GPU is done | GPU time (`GL_TIME_ELAPSED`) |
|---|---|---|---|
| Shipped CPU DXT5, 16 threads | 4.12 ms (runs vary 4.1-4.6) | 4.14 ms | |
| BC7, final build (after the review) | **1.33 ms** | 4.55 ms | **4.51 ms** |
| BC7, first build (nearest index in every pass) | 1.28 ms | 9.69 ms | 9.65 ms |

The research's 0.2 ms GL-thread and 5.0 ms GPU figures do not hold for this sequence, for three reasons:

1. **Syncs.** NVIDIA's driver runs GL commands on its own thread (threaded optimization); a `glGet*` or `glGetError`
   waits until that thread has caught up. The prototype made none; the hook makes several a level (R2's
   `Upload.packedRows` and error check, R3's state save). Every GL-thread figure above includes them.
2. **The level's definition waited for the GPU** (first build: 10.25 ms GL thread a page, equal to the GPU time).
   Probes (scratchpad `probe/Probe2..15.java`): with any `glGet*` per level, `glCompressedTexImage2D` from the output
   buffer made the driver thread wait for the compute passes; so did `glCompressedTexSubImage2D` into a freshly
   defined level and `glCopyImageSubData` from an `RGBA32UI` staging texture (which also rejects partial edge blocks
   and incomplete textures). Without compute work pending the same upload did not wait. Defining all levels of the
   chain up front (no data) and then filling each one made it async: the driver reallocates a texture's storage when a
   level is added, and that waits for the GPU writes pending into the texture. `Bc7Encoder` now allocates the chain
   from the first level that is not BPTC of its size down to 1×1, then fills each level with
   `glCompressedTexSubImage2D` from the output buffer. Output is identical (hash of all levels), no GL errors.
3. **The remaining ~1 ms is the pixel upload.** Without `glBufferSubData` of the pixels: 0.4-0.6 ms; pixel upload
   alone: 1.2-1.4 ms (random 1024² chains). The sync exposes the driver thread's copy of the game's ~5.6 MB a page.
   Usage hints (`STREAM/DYNAMIC/STATIC_DRAW`, `STREAM_COPY`) do not change it (`STREAM_READ` is far worse). A
   persistently mapped ring of 8 slots (GL 4.4) waits on its fences, because in a burst the GPU is more than 8
   levels behind; `glBufferSubData` lets the driver queue as far ahead as it needs. No change made.

GPU time: the chosen variant costs ~9.6 ms a page, not 5.0. The research's time table was the projection-index
variant: the prototype itself, timed now with `-Dbest` (nearest index) and alpha weight 4, gives 10.56 ms a page
(projection index: 4.77 ms). So in a burst the GPU, not the file workers (~6 ms a page), sets the pace, and the
driver holds the queued pixel copies (≈ 5.6 MB a page) until the GPU reaches them.

The user chose (2026-10-23) nearest index in `EncodeBlock` only, the try passes back to DirectXTex's projection index
(scratchpad `hybrid.py` applied to the shipped `.comp`), and raised AC3's limit to 1.5 ms:

| BC7 variant | RGB / alpha dB | Over white / grey dB | Alpha px off > 8 | GL thread | GPU a page |
|---|---|---|---|---|---|
| First build: nearest index in all passes | 45.32 / 53.70 | 44.36 / 45.05 | 121,277 | 1.28 ms | 9.65 ms |
| **Shipped**: nearest index in `EncodeBlock` only | 45.33 / 53.43 | 44.34 / 45.06 | 132,272 | 1.38 ms | 4.64 ms |

### `qualityCheck` output (final build)

```
GL: NVIDIA GeForce RTX 4090/PCIe/SSE2 / 4.6.0 NVIDIA 617.42
bc7 (DirectXTex modes 4-7, alpha weight 4, nearest index when encoding) 2: programs built in 26 ms
level 0, PSNR dB rgb / alpha and over white / over grey; driver, then DXT5, then BC7:
  (the driver picks internal format 0x83f3 for GL_COMPRESSED_RGBA)
  melos_tiles_for_miles1.pack #0 1024x1019   34.65 /  47.91   34.47 /  34.61    36.56 /  50.18   36.39 /  36.52    43.75 /  48.06   42.64 /  43.52
  melos_tiles_for_miles1.pack #1 1023x1020   42.40 /  47.27   41.21 /  42.08    43.79 /  50.12   42.92 /  43.56    48.29 /  49.40   45.77 /  47.49
  melos_tiles_for_miles1.pack #2 1024x1017   34.06 /  45.11   33.74 /  33.98    35.61 /  46.86   35.31 /  35.53    40.22 /  45.35   39.10 /  39.93
  melos_tiles_for_miles1.pack #3 1020x1016   40.59 /  63.73   40.57 /  40.58    43.43 /  66.05   43.40 /  43.42    50.18 /  62.76   50.00 /  50.15
  melos_tiles_for_miles1.pack #4 1020x1022   36.05 /  63.34   36.04 /  36.05    37.85 /  65.25   37.84 /  37.85    45.50 /  60.98   45.36 /  45.46
  melos_tiles_for_miles1.pack #5  992x914    37.45 /  50.64   37.28 /  37.41    40.10 /  53.19   39.89 /  40.04    45.61 /  48.44   43.76 /  45.06
  melos_tiles_for_miles2.pack #0 1018x1022   41.48 /  62.42   41.45 /  41.47    42.89 /  64.60   42.86 /  42.88    51.01 /  65.56   50.87 /  50.98
  melos_tiles_for_miles2.pack #1 1016x1020   43.03 /  73.57   43.03 /  43.03    45.34 /  75.66   45.34 /  45.34    52.98 /  67.94   52.74 /  52.89
  melos_tiles_for_miles2.pack #2 1017x1020   34.56 /  64.70   34.56 /  34.56    36.35 /  66.50   36.35 /  36.35    43.13 /  57.73   42.83 /  43.01
  melos_tiles_for_miles2.pack #3  990x900    32.82 /  37.53   31.55 /  32.46    34.22 /  39.08   32.99 /  33.88    34.48 /  36.19   32.15 /  33.73
  melos_tiles_for_miles2.pack #4 1005x1017   36.89 /  59.28   36.87 /  36.89    38.67 /  61.41   38.64 /  38.66    46.42 /  55.67   45.97 /  46.31
  melos_tiles_for_miles2.pack #5 1024x1022   40.84 /  65.96   40.84 /  40.84    42.40 /  68.09   42.39 /  42.40    49.50 /  64.79   49.39 /  49.47
  Tiles2x.pack #0                1024x1014   33.14 /  42.32   32.73 /  33.06    34.87 /  44.43   34.55 /  34.82    38.75 /  41.77   37.40 /  38.51
  Tiles2x.pack #1                1018x1021   42.33 /  68.62   42.32 /  42.33    44.65 /  71.40   44.64 /  44.64    52.81 /  64.43   52.34 /  52.64
  Tiles2x.pack #2                1024x1022   39.38 /  47.78   38.86 /  39.26    41.40 /  49.80   40.82 /  41.25    46.10 /  48.63   44.63 /  45.84
  Tiles2x.pack #3                1024x1022   33.29 /  46.91   33.13 /  33.25    34.83 /  49.29   34.69 /  34.79    43.63 /  47.16   42.36 /  43.38
  Tiles2x.pack #4                1015x914    40.11 /  50.20   39.78 /  40.05    42.17 /  52.49   41.74 /  42.04    46.60 /  49.66   44.86 /  46.09
  Tiles2x.pack #5                1020x912    37.31 / 100.00   37.31 /  37.31    39.71 / 100.00   39.71 /  39.71    50.09 /  67.14   49.92 /  50.03
  tikitown_tiles.floor.pack #0   1022x1013   30.10 /  45.10   29.99 /  30.08    31.53 /  46.89   31.43 /  31.51    37.01 /  43.52   36.40 /  36.92
  tikitown_tiles.floor.pack #1   1024x1022   37.27 /  38.91   35.08 /  36.63    38.62 /  40.57   36.52 /  38.01    39.20 /  39.99   36.64 /  38.43
  tikitown_tiles.floor.pack #2    855x663    37.60 /  57.15   37.56 /  37.59    39.56 /  59.24   39.51 /  39.55    46.56 /  56.78   46.04 /  46.39
21 pages; means in dB (a lossless page counts as 100 dB), level 0 until the GPU is done:
  driver GL_COMPRESSED_RGBA                                    rgb / alpha  37.40 /  56.12, over white / grey  37.06 /  37.31,  14.7 ms
  dxt5 (stb_dxt high quality colour, own alpha) 1 on 16 threads rgb / alpha  39.26 /  58.15, over white / grey  38.95 /  39.18,   2.2 ms (15.4 ms on one thread)
  bc7 (DirectXTex modes 4-7, alpha weight 4, nearest index when encoding) 2 rgb / alpha  45.33 /  53.43, over white / grey  44.34 /  45.06,   4.4 ms
alpha pixels off by more than 8 (sum over the pages), the largest alpha error; the same over white:
  driver                                                       alpha  159061 px, max  25; over white 1636372 px, max 199
  DXT5                                                         alpha   98250 px, max  23; over white 1198871 px, max 181
  BC7                                                          alpha  132272 px, max 100; over white  475620 px, max 169
pages where BC7 is below DXT5: over white 1, over grey 1, rgb 0, alpha 20
BC7 mip chains (from melos_tiles_for_miles1.pack #0), every level down to 1x1:
  1023x1019 10 levels: all BPTC of their sizes, complete; lowest PSNR 25.47 dB, rgb of level 5 (31x31; DXT5 26.79)
    37x5     6 levels: all BPTC of their sizes, complete; lowest PSNR 42.08 dB, alpha of level 1 (18x2; DXT5 Infinity)
     5x37    6 levels: all BPTC of their sizes, complete; lowest PSNR 39.33 dB, rgb of level 3 (1x4; DXT5 36.96)
    13x2     4 levels: all BPTC of their sizes, complete; lowest PSNR 37.85 dB, rgb of level 2 (3x1; DXT5 33.51)
     3x1     2 levels: all BPTC of their sizes, complete; lowest PSNR 52.90 dB, rgb of level 1 (1x1; DXT5 52.90)
     1x1     1 levels: all BPTC of their sizes, complete; lowest PSNR Infinity dB, every level lossless
BC7 2048x2048 level (real pages, no mipmaps, strips of 512 rows): format 0x8e8c, PSNR 43.97 / 48.50 dB, lowest strip 42.44 dB, level 1 not allocated
BC7 1501x1099 level (real pages, no mipmaps, strips of 696 rows): format 0x8e8c, PSNR 44.29 / 48.55 dB, lowest strip 44.27 dB, level 1 not allocated
GL state around a BC7 level: the same, program 7, generic SSBO 14, SSBO 0 12 @16+256, SSBO 1 13 @0+0, SSBO 2 0 @0+0, unpack buffer 0, texture unit 3 texture 1
21 pages with all their levels, a new texture each; medians of 7 interleaved runs, per page:
  dxt5 (stb_dxt high quality colour, own alpha) 1              GL thread  4.25 ms, until the GPU is done  4.27 ms
  bc7 (DirectXTex modes 4-7, alpha weight 4, nearest index when encoding) 2 GL thread  1.34 ms, until the GPU is done  4.53 ms, GPU time  4.48 ms
  BC7's GL thread a page is 32% of DXT5's (limit 40%)
```

The BC7 "level 0 until the GPU is done" mean is not a cost of the game's path: in that loop each page redefines
level 0 of one texture that the step before made DXT5, so every BC7 level reallocates a texture with pending GPU writes
(AC3 point 2). The timing table is the game's shape.

## In game

Date 2026-10-23. Build: this worktree after the review (Faster Loading 0.5.0 from `deployWorkshop`, Zabric 0.6.0,
Loading Progress 0.2.0 as staged). Game option `textureCompression=true`; Faster Loading's other fixes on in every run;
only `textureBc7` changes.

- `:faster-loading:smokeTest`: pass. `0.5.0 loaded`, `active: comment stripping on, texture compression on, texture
  bc7 on, depth map order on, lua folder walk on`, `access widener: ok (16 directives)`, and in the game's own context:
  `texture compression: bc7 (DirectXTex modes 4-7, alpha weight 4, nearest index when encoding) 2 on the GPU
  (textureBc7 on; NVIDIA GeForce RTX 4090/PCIe/SSE2, 4.6.0 NVIDIA 617.42)`. The user's 0.4.0 ini got `textureBc7 =
  true` with its other values kept. The 3 `AngelCodeFont failed to load page 0 texture` errors predate this work (R2).
- Series: local nosteam server `fulltest` with the live server's mod list (245 mods + LPTestDriver, real copies),
  R2's kit with two changes: the client waits 30 s in the menu before it joins (quality guideline 10; the launch's
  depth maps finish first) and the screenshot pair stays 20 s in the world. One warm-up join discarded; off/on
  alternating; `offshot`/`onshot` a fourth pair. Table `research/series-table.txt`.

| Run | Encoder | Launch | Join total | Loading the world | Entering the world | Asset wait | Budget sleep | Pack pages in the wait | Largest frame gap |
|---|---|---|---|---|---|---|---|---|---|
| off1 | dxt5 | 32,654 | 56,903 | 22,361 | 10,455 | 2,679 | 285 | 253 | 1,312 (Loading mods) |
| on1 | bc7 | 30,876 | 54,942 | 20,466 | 9,950 | 3,872 | 1,882 | 423 | 1,343 (Loading mods) |
| off2 | dxt5 | 31,206 | 57,368 | 22,540 | 10,492 | 3,942 | 814 | 409 | 1,325 (Loading mods) |
| on2 | bc7 | 30,689 | 55,396 | 20,715 | 10,277 | 3,778 | 1,913 | 422 | 1,333 (Loading mods) |
| off3 | dxt5 | 30,357 | 57,628 | 22,479 | 10,571 | 2,738 | 379 | 268 | 1,327 (Loading mods) |
| on3 | bc7 | 30,892 | 55,101 | 19,835 | 10,753 | 3,487 | 2,500 | 388 | 1,317 (Loading mods) |
| **median off** | | 31,206 | **57,368** | **22,479** | 10,492 | **2,738** | 379 | 268 | 1,325 |
| **median on** | | 30,876 | **55,101** | **20,466** | 10,277 | **3,778** | 1,913 | 422 | 1,333 |
| offshot | dxt5 | 31,031 | 57,561 | 22,774 | 10,531 | 4,154 | 918 | 442 | 1,335 (Loading mods) |
| onshot | bc7 | 30,230 | 55,307 | 20,714 | 10,254 | 4,250 | 2,622 | 466 | 1,363 (Loading mods) |

(ms.) Medians of 4 pairs with the screenshot runs: join 57,465 off vs 55,204 on; asset wait 3,340 off vs 3,825 on.

- **Join total met:** 2.3 s shorter with BC7 (median 55.1 vs 57.4 s; every on run below every off run). "Loading the
  world" is 2.0 s shorter: the render thread no longer spends ~4 ms a page, all cores busy, on DXT5.
- **Asset wait: 1.04 s longer (median of 3), AC5's +1 s bound missed by 40 ms; with the fourth pair +0.49 s.** The wait
  holds only pack pages now (no depth maps or animations left after the 30 s menu stay; `timeline.py`), and its
  length follows the backlog at its start: the on runs reach the wait 2 s earlier with 388-466 pages queued against
  253-442. Pages per second in the wait are higher with BC7: 106-108 (on) against 90-100 (off) (`timeline.py`: on1
  423 in 4.0 s, on2 422 in 3.9, on3 388 in 3.6, onshot 466 in 4.4; off1 253 in 2.8, off2 409 in 4.1, off3 268 in
  2.9, offshot 442 in 4.4). The decoders sleep more on the upload budget with BC7 (1.9-2.6 s against 0.3-0.9 s of
  worker time): the GPU (~4.5 ms a page) holds the render thread's uploads back in a burst, while CPU DXT5 never got
  that far ahead.
- **Largest frame gap met:** 1,333 ms on vs 1,325 off (median), +8 ms; always in "Loading mods", outside this fix.
- **Errors met:** every run logs its encoder (`bc7 ... on the GPU` on, `dxt5 ... on 16 threads (textureBc7 off; ...)`
  off); no GL error, no texture exception, no hook stop or mod disable; the `console.txt` errors (949 a run, the live
  mod list's own, e.g. `FluidContainerScript` names) are the same set on and off. The "Not Responding" sampler saw 0
  hung samples in every run.
- **Screenshots: limited.** It was night in game and the survival guide was open, as in R2's runs: the in-world frames
  (`offshot` 154,771 ms, `onshot` 151,340 ms) show the same roofs on both, no missing tile, but little else is lit;
  sprite edges cannot be judged from them. Edge quality rests on `qualityCheck` (AC1, worst alpha errors listed).

## AC7: video memory (user, 2026-10-23: the mod must suit 8 GB cards)

One join each, `textureBc7` off then on, the AC5 setup (30 s menu stay, 20 s in the world), GPU memory in use sampled
by `nvidia-smi` every 250 ms from before the launch to after the quit. "Game" is the peak minus the baseline just
before the launch (the desktop and other programs: ~3.2 GB). Window 3840x2054.

| Build | Encoder | Baseline | Peak (in the world) | Game | At the menu (game) |
|---|---|---|---|---|---|
| first (buffers grow) | DXT5 | 3,186 MiB | 10,552 MiB | 7,366 MiB | ~2.3 GB |
| first (buffers grow) | BC7 | 3,181 MiB | 11,469 MiB | **8,288 MiB (+922)** | ~3.0 GB |
| **final (fixed buffers, strips)** | DXT5 | 3,188 MiB | 10,569 MiB | 7,381 MiB | 2,292 MiB |
| **final (fixed buffers, strips)** | BC7 | 3,178 MiB | 10,598 MiB | **7,420 MiB (+39)** | 2,303 MiB (+11) |

- **AC7 met with the final build:** BC7's peak is 39 MiB above DXT5's (bound: +64 MB).
- The first build failed it by +922 MiB, already at the menu, so not a join backlog. The cause, found with a probe
  (scratchpad `vram/VramProbe.java`: game-shaped textures, 1024² pages padded like `widthHw`, full chains, video memory
  from `GL_NVX_gpu_memory_info`):
  - Textures are not it: 400 and 1,000 pages take the same memory through BC7 and DXT5 (533 / 1,333 MB of blocks),
    plus the encoder's ~35 MB.
  - The encoder's buffers were: the 8 input slots and 3 block buffers grew with the largest level and never shrank;
    after eight 4096² levels BC7 held 640 MB (DXT5 17 MB). Buffers of their own for large levels, deleted after,
    still left ~110 MB in the driver's cache.
  - Fix: fixed buffers for a 1024² level (~35 MB), larger levels encoded in strips of whole block rows through them
    (`Bc7Encoder.stripRows`, unit-tested; `qualityCheck` checks every strip of a 2048² and a 1500x1100 level on real
    pixels). Quality, GL-thread and GPU time unchanged.
  - Check review: the second large level is now 1501x1099, so a multi-strip level also has a partial block column and
    a last strip with a partial block row: 44.29 / 48.55 dB, lowest strip 44.27 dB, level 1 not allocated. In that
    run the GL thread took 1.51-1.57 ms a page (limit 1.5) with DXT5 at 4.85-4.99 ms (4.31 above), the same with
    and without the review's edit: the machine was busier, not the encoder slower.
  - Left in the probe: deleting large BC7 textures leaves ~16 MB each in the driver's pool (DXT5's are freed). The
    game does not delete textures while it loads; the join measurement above includes whatever it does.
- The game's own peak with the live mod list is ~7.4 GB on this 3840x2054 window, with DXT5 or BC7 alike: tight for an
  8 GB card at this resolution. That is the mod list and the window size, not this change; texture compression (on in
  both) is what keeps the tile pages at a quarter of their uncompressed size.

## Restored after the runs

After the AC5 series and again after the AC7 runs (the same backups):

Mod list (without LPTestDriver), ZombieBuddy config, `options.ini`, every `Lua/*.ini` (Faster Loading's 0.4.0 ini
without `textureBc7`), the Workshop staging of Faster Loading / Loading Progress / Zabric (0.4.0 / 0.2.0 / 0.6.0),
Loading Progress's 20 reports, all byte-identical to the backups; the 494 mod copies removed.
