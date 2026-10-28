# Asset-wait speed-up (R2): texture compression off the render thread

Parent: `.trellis/tasks/10-21-load-speed-optimization` (goal, decisions, mod basics). Mod: `faster-loading/`
(Dont39 Faster Loading: client-only, preloaded, Java hooks with JDK-only glue, one switch per fix, on by default; a
game or target mismatch is reported and disables the mod, never a silent fallback). Technical design: `design.md`;
steps: `implement.md`.

## Goal

Cut the MP join's asset wait (`bWaitForAssetLoadingToFinish2`), 48-74 s on the live server's mod list, toward the
~14 s it takes with the game's texture compression off, while keeping compressed textures (same format, same video
memory) and at least the driver's image quality.

## Background (evidence)

Sources: Loading Progress reports of R1 (7 joins) and JFR joins (raw reports not kept), `research/upload-path.md` (code path,
JFR profile, compression on/off joins, encoder benches, hook points), parent `research/asset-wait.md`.

- **The bound is the render thread compressing textures.** Mod packs get the compress flag whenever the game option
  `textureCompression` is on (the default; the user's setting). Each page level then goes up as
  `glTexImage2D(..., GL_COMPRESSED_RGBA, ...)` and the driver compresses it inside the call: ~20 ms per 1024² page,
  about one page per frame. The 4 workers' sleep on the 50 MB budget is a symptom of that.
- Same join, same PC, `textureCompression=false`: asset wait 66.1 → 13.8 s, "Loading the world" 99.5 → 33.6 s, join
  151.5 → 82.3 s; launch unchanged.
- The NVIDIA driver picks DXT5 for `GL_COMPRESSED_RGBA`. On 24 real pages, stb_dxt (LWJGL `STBDXT`, in the game jar)
  is +1.8 dB colour / −2.2 dB alpha against it (its alpha fit is plain min/max); the driver's BC7 loses 14 dB of
  alpha. DXT blocks are independent: one page level splits across threads (1.8 ms on 14 threads vs 13.9 ms on one).
- Hook points (preloaded: JDK-only signatures, no `@Self`): the worker side cannot tell which page it decodes, so
  neither a decode-skipping cache nor moving depth maps out of their lock is reachable. The upload,
  `GL11.glTexImage2D(IIIIIIIILjava/nio/ByteBuffer;)V`, is: it receives each level's pixels, size and requested
  format, and no other mod patches `GL11` on the live list.

## Decisions (user, 2026-10-22)

- Compression moves to our own encoder ("our encoder compress"), behind an encoder abstraction that can be replaced
  later, possibly by a GPU-accelerated encoder.
- A compressed-texture cache stays a slot behind that interface, built when a slower encoder (GPU or BC7) needs it;
  this round ships the parallel DXT5 encoder.
- GPU compute-shader BC7 (with a cache if it needs one) is the next step, as its own task
  (`10-22-gpu-bc7-encoder`) behind this round's encoder interface; this round ships CPU DXT5 ("dxt 5 then bc7"),
  which also stays the fallback where OpenGL 4.3 compute or BPTC is missing.
- Better encoders than stb_dxt considered: colour stays stb_dxt high quality (already above the driver); alpha gets
  our own DXT5 alpha-block encoder so both channels reach the driver's quality. BC7 is not used (alpha loss, slow
  encoders, not in the game jar, no BPTC on macOS OpenGL 4.1).

## Requirements

- **R2.1 Texture compression fix** (switch `textureCompression`, on by default). Every upload the game asks the
  driver to compress (`GL_COMPRESSED_RGBA` 2D level from tightly packed RGBA bytes) is compressed by the mod's
  encoder to DXT5 and uploaded as such; the driver no longer compresses it. Other uploads are untouched. With the
  switch off, the game's call runs unchanged.
- **R2.2 Encoder abstraction.** The fix talks to an encoder interface (format, identity, encode one level on the GL
  thread); the DXT5 encoder is one implementation, parallel across a pool of the mod's own threads. A future encoder
  (GPU, BC7) or a caching decorator plugs in behind it without touching the hook.
- **R2.3 Quality.** Our DXT5 output is at or above the driver's quality on both colour and alpha.
- **R2.4 Mismatch handling.** A GL without `GL_EXT_texture_compression_s3tc`, or a GL error from our upload, is
  reported with context and disables the mod (default gate); the game's call then runs as without the hook.
- **R2.5 Worker pool (conditional).** Only if a profile with R2.1 on shows the file workers bound on texture decode
  during the wait (≥ 90 % busy on pack images for ≥ 5 s of it): widen the game's file pool during the world load
  (its own switch), keeping its output identical. Otherwise recorded in `verification.md` as not needed.

## Acceptance Criteria

- [x] AC1 (R2.3): on the 24-page sample (melos 1/2, `Tiles2x`, `tikitown_tiles.floor`; 6 each, or all of a smaller pack: 21 pages, as the floor pack has 3), the GPU-decoded
  level 0 of our encoder's output has PSNR (vs the premultiplied source) ≥ the driver's `GL_COMPRESSED_RGBA` result on
  both RGB and alpha, mean over the sample; recorded in `verification.md`.
- [x] AC2 (R2.2): unit tests: a Java DXT5 decoder round-trip meets fixed PSNR floors on generated images; sizes not
  divisible by 4 and the 2×2 / 1×1 mip tails encode to the size GL expects; parallel output is byte-identical to
  single-threaded output; the alpha encoder reproduces all-0, all-255 and two-value blocks exactly.
- [x] AC3 (R2.1): in-game, local nosteam server with the live mod list, `textureCompression=true` in the game: median
  of 3 joins with the switch on vs 3 off: asset wait ≤ 25 s on, and the join total drops by ≥ 35 s.
- [x] AC4 (no missing textures, no worse frames): in those runs, no new errors in `console.txt` (no GL errors, no
  texture exceptions, no mod disable); a screenshot of the same in-world spot on vs off shows the same textures; the
  largest frame gap of "Loading the world" and "Entering the world" with the switch on is no larger than off's
  median + 100 ms.
- [x] AC5 (R2.1 off): with the switch off the hook makes no GL call and cancels nothing (unit-tested decision code
  plus the off runs of AC3 matching the 2026-10-22 baseline within the ±12 s spread).
- [ ] AC6: `./gradlew :faster-loading:check` (unit tests, `checkJdkGlue`) and `:faster-loading:smokeTest` pass.
- [x] AC7 (R2.5): either the profile shows the workers are not the bound (recorded), or the pool fix meets AC3-AC5
  on its own on/off runs with a median wait gain ≥ 2 s.

Results: `verification.md` (join median 141.7 → 69.8 s, asset wait 70.4 → 12.5 s; quality above the driver;
the pool fix of R2.5 not needed).

## Out of Scope

- A compressed-texture disk cache (slot only), GPU or BC7 encoders.
- Depth maps out of their lock, decode-skipping, anything needing `@Self` in a preloaded mod.
- Uploads the game does not ask to compress, and the game's `textureCompression=false` path.
- Loading Progress changes (it already reports the wait and the budget sleep).
