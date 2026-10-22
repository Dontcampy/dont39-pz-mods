# GPU BC7 texture encoder

Parent: `.trellis/tasks/10-21-load-speed-optimization`. Follows `10-21-asset-wait-speedup` (R2), which ships CPU DXT5
compression at the texture upload behind an encoder interface (`faster-loading` `textures/Encoder`). **Planning
starts after R2 is verified in game.**

## Goal

Replace the game's texture compression with a compute-shader BC7 encoder: better image quality than DXT5 at the
same video memory, with the render thread only issuing commands, while the R2 DXT5 encoder stays the fallback where
OpenGL 4.3 compute or BPTC is missing.

## Known from R2 (`../10-21-asset-wait-speedup/research/upload-path.md`)

- The driver's own BC7 (`GL_COMPRESSED_RGBA_BPTC_UNORM`) on 24 real pages: RGB 42.7 dB, alpha 42.0 dB (DXT5 driver
  37.1 / 56.6); a good encoder should beat DXT5 on both.
- A GPU-only chain needs no readback: RGBA upload, compute dispatch into an RGBA32UI image (one texel per block),
  `glCopyImageSubData` into the BC7 texture. A cache would need a readback and only saves encode time (the PNG
  decode cannot be skipped from a preloaded mod), so it is built only if the encoder is slow.
- Candidate source: DirectXTex's BC7 compute shader (MIT), ported to GLSL with its license kept.

## To decide in planning

- Prototype first: quality (PSNR RGB/alpha on the R2 sample) and GPU time per 1024² page on the RTX 4090.
- GL state the dispatch touches and how it is restored inside the game's render thread.
- Capability selection (GL 4.3 compute + BPTC) as an explicit, logged choice between encoders.
- Whether a cache is needed, from the prototype's timings.
