# GPU BC7 texture encoder

Parent: `.trellis/tasks/10-21-load-speed-optimization`. Follows `archive/2026-10/10-21-asset-wait-speedup` (R2), which
ships CPU DXT5 compression at the texture upload behind an encoder interface (`faster-loading` `textures/Encoder`),
verified in game (join 141.7 → 69.8 s). Mod: `faster-loading/` (client-only, preloaded, JDK-only hook glue, one switch
per fix, on by default; a game or target mismatch is reported and disables the mod, never a silent fallback; an
explicit, logged capability choice between encoders is allowed). Technical design: `design.md`; steps: `implement.md`.

## Goal

Better texture quality at the same video memory: the textures the game asks the driver to compress go to BC7, encoded
by a compute shader on the GPU, where the GL has OpenGL 4.3. The render thread only issues the GPU's work. The
shipped CPU DXT5 encoder stays where OpenGL 4.3 is missing or the new switch is off.

## Background (evidence: `research/bc7-prototype.md`)

- Prototype: DirectXTex's BC7 compute shader (MIT, commit `ec66b78b`) ported to GLSL, on R2's 21-page sample (6 each
  of melos 1/2 and `Tiles2x`, the 3 of `tikitown_tiles.floor`), RTX 4090, driver 617.42.
- Chosen variant (modes 4-7, alpha weight 4, nearest index), mean of the 21 pages: RGB 45.32 dB vs shipped DXT5 39.26;
  over white 44.36 vs 38.95; over grey 45.05 vs 39.18; alpha 53.70 vs 58.15. One alpha-heavy page is below DXT5 on
  screen; isolated alpha errors reach 100 levels (DXT5: 23).
- Time per page with all mip levels (median of 7): GL thread ~0.2 ms (CPU DXT5 3.6 ms with all cores busy, driver
  21 ms); GPU 5.0 ms. Pages decode on the game's 4 file workers at ~6 ms a page or slower, so the GPU keeps up; the
  expected load-time effect is neutral to slightly positive. The reason for the change is image quality.
- No cache: the GL thread already spends ~0.2 ms a page, and a cache would need a readback.
- The game binds its own SSBOs (`zombie.core.rendering.ShaderBuffer`) and switches programs constantly; it never uses
  a pixel unpack buffer or compute.

## Decisions (user, 2026-10-23)

- Quality bar: RGB PSNR and the on-screen composite over white and over grey above the shipped CPU DXT5, means over the
  sample. Lower alpha PSNR than DXT5 is accepted.
- Encoder: the DirectXTex port with modes 4-7 (no 3-subset modes 0/2), alpha weight 4. Revised 2026-10-23 after
  implementation measured the nearest-index variant at ~9.6 ms GPU a page (the research's 5.0 ms was the projection
  variant): nearest index only in the final encode pass, DirectXTex's projection index in the mode search (RGB 45.33,
  alpha 53.43, over white 44.34, over grey 45.06 dB; ~4.6 ms GPU a page).
- Switch: BC7 gets its own switch, on by default, used only when `textureCompression` is on; off means the CPU DXT5
  encoder.
- Failure policy (standing decision): a GL without OpenGL 4.3 is a capability, so the encoder choice falls to DXT5
  with a logged reason; on a GL that has it, a shader that does not compile or link, or a GL error from the encoder,
  is reported and disables the mod (default gate).

## Requirements

- **R1 BC7 encoder.** With `textureCompression` and the new switch `textureBc7` on and OpenGL 4.3 present, every level
  that R2's fix takes (`Upload.requested` and tightly packed rows) is encoded to BC7 (`GL_COMPRESSED_RGBA_BPTC_UNORM`)
  on the GPU and defined in the bound texture without a readback. Every level of a texture gets the same format.
- **R2 Render thread only issues.** Per level the GL thread uploads the game's pixels once, dispatches the encoder and
  defines the level from a GPU buffer; no wait for the GPU, no uniform updates.
- **R3 GL state.** After each level the GL state is as the game left it: current program, the generic and every
  indexed `GL_SHADER_STORAGE_BUFFER` binding the encoder used (base or range), `GL_PIXEL_UNPACK_BUFFER`. No texture
  unit, framebuffer or game program uniform is touched. A failure while restoring still restores the rest, then
  throws.
- **R4 Encoder choice.** Decided once per session at the first taken level, on the GL thread: BC7 if `textureBc7` is
  on and the GL has OpenGL 4.3, else CPU DXT5 (which keeps R2's `GL_EXT_texture_compression_s3tc` check). One log
  line names the encoder and why (the switch, or the GL's renderer and version when 4.3 is missing).
- **R5 Errors.** Compile or link failure (with the GL's log), a GL error after a level, or a level the BC7 choice
  cannot take once BC7 is chosen (the rows not tightly packed) throws with context; the mod's default gate stops its
  hooks and that call runs the game's own `glTexImage2D`.
- **R6 Switch.** `textureBc7` in `Fix`, the ini (an older ini gets the key at its default), the options page and
  `Translate/{EN,CN}`; with it off the fix behaves as Faster Loading 0.4.0.
- **R7 License.** The ported shader keeps DirectXTex's copyright and MIT notice and the source commit; the license
  text ships in the jar under `META-INF/licenses/`.

## Acceptance Criteria

- [x] AC1 (R1, quality bar): `:faster-loading:qualityCheck` on the 21-page sample: BC7's mean RGB PSNR, mean PSNR over
  white and mean PSNR over grey are each above the shipped CPU DXT5's; it prints alpha PSNR and the worst alpha error
  too. It still checks DXT5 against the driver as in R2.
- [x] AC2 (R1, R3): `qualityCheck` also uploads full mip chains of odd sizes (down to 1×1) and one 2048² level through
  the BC7 encoder: every level's internal format is BPTC, the texture is complete, each level decodes within a PSNR
  floor of its source; GL state set before the upload (a program, a range-bound and a base-bound SSBO at the encoder's
  binding points, the generic SSBO binding) reads back the same after.
- [x] AC3 (R2): `qualityCheck` reports the GL-thread time per page with all mip levels (median) for BC7 and DXT5 and
  BC7's is ≤ 40 % of DXT5's in the same run, and reports the GPU time per page. (User, 2026-10-23: first ≤ 1 ms, then
  ≤ 1.5 ms after ~1 ms of it proved to be the driver copying the game's pixels, then relative to DXT5 because the
  absolute time follows the machine's load: 1.33-1.42 ms quiet, 1.54-1.55 ms busy, DXT5 alike.)
- [x] AC4 (R4, R6): unit tests: the encoder choice for each combination of switch and OpenGL 4.3 with its reason;
  `Settings` with the new key (default on, an ini without it gets it, other values kept); the group-count arithmetic
  for level sizes from 1×1 to 4096².
- [x] AC5 (in game, R1-R5; accepted by the user 2026-10-23: join 2.3 s faster; the asset wait +1.04 s, 40 ms over
  the bound, is the larger page backlog at its start, worked through faster with BC7; `verification.md`): local nosteam server with the live mod list, game `textureCompression=true`, after a
  menu stay of ≥ 30 s: 3 joins with `textureBc7` on and 3 off (DXT5), alternating. On: the log names the BC7 encoder;
  no new errors in `console.txt` (no GL error, no mod disable, no texture exception); median join total ≤ off's + 2 s
  and median asset wait ≤ off's + 1 s; the largest frame gap no larger than off's median + 100 ms; screenshots of the
  same spot on vs off show the same textures, edges of sprites without visible holes or specks.
- [x] AC7 (video memory; user, 2026-10-23: the mod must suit 8 GB cards, not this PC's 24 GB): one join each with
  `textureBc7` on and off on the AC5 setup, GPU memory in use sampled with `nvidia-smi` from launch to quit: BC7's peak
  ≤ DXT5's peak + 64 MB; both peaks and the baseline before launch recorded in `verification.md`.
- [x] AC6: `./gradlew :faster-loading:check :faster-loading:assembleMod` and `:faster-loading:smokeTest` pass; the
  jar holds the shader resource and `META-INF/licenses/DirectXTex.txt`.

## Out of Scope

- A compressed-texture cache; the 3-subset modes 0/2; a stronger BC7 encoder (endpoint fitting) to remove the
  remaining alpha outliers.
- Other GPUs and drivers (AMD, Intel, macOS's OpenGL 4.1, which takes DXT5 by the capability rule): not tested here.
- Uploads the game does not ask to compress; the game's `textureCompression=false` path; depth maps.
- Loading Progress changes.
