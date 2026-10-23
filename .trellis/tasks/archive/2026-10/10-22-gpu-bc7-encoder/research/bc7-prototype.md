# Research: GPU BC7 encoder prototype (DirectXTex compute shader in GLSL)

- **Query**: how good and how fast is DirectXTex's BC7 compute shader, ported to GLSL, on the R2 sample of real tile
  pages, against the shipped CPU DXT5 encoder and the driver; what it costs the GL thread; what GL state it touches.
- **Date**: 2026-10-23
- **PC**: Ryzen 7 9800X3D (16 threads), RTX 4090, driver 617.42 (OpenGL 4.6 compatibility context, GL 4.3 and
  `GL_ARB_texture_compression_bptc` present).
- **Prototype** (outside the repo):
  `C:\Users\dontc\AppData\Roaming\Claude\side-session-notes\local_9ffcb414-601a-457d-a248-510acda488a3\bc7proto\`:
  `bc7_dxtex.glsl` (the port, every variant behind defines), `bc7_tables.glsl`, `Bc7Proto.java` (hidden-window
  harness on the game's LWJGL, same page sample and PSNR as `QualityCheck`), `BC7Encode.hlsl.orig`,
  `DirectXTex-LICENSE.txt`. Raw outputs (`run*.txt`, `median-ring.txt`) stayed in the planning session's scratchpad. Source: `DirectXTex/Shaders/BC7Encode.hlsl` at `ec66b78b37389e8373a8e14ccc3cd29be94146b3`
  (MIT, Copyright (c) Microsoft Corporation), host sequence from `DirectXTex/BCDirectCompute.cpp`.

## The port

DirectXTex runs four kernels per level, 64 threads a group: `TryMode456` (4 blocks per group: modes 4, 5 with rotations
and index selector, mode 6 with p-bits), `TryMode137` (one block per group, one partition per thread, per mode 1, 3,
7), `TryMode02` (modes 0, 2; only with `BC_FLAGS_USE_3SUBSETS`) and `EncodeBlock`. Each try pass keeps the lowest
squared error per block (`uvec4` per block: error, mode, partition, rotation/p-bits) in a ping-pong buffer.
Endpoints are the per-channel bounding box of the subset, quantized with p-bits; indices are the projection onto the
endpoint line through a step table.

Port changes, none of which changes the output (PSNR identical to the first faithful port on every page):

| Change | Why |
|---|---|
| `partition` → `part` | reserved word in GLSL |
| Input from an SSBO of packed RGBA bytes, edges clamped (a partial block repeats its last column and row) | no texture unit state to touch; D3D's `Load` returns 0 outside, GL's `texelFetch` is undefined there |
| Per-subset values of `TryMode137`/`TryMode02` in named variables instead of dynamically indexed arrays | the arrays spilled to local memory: 340 ms a 1024² page, 8 ms after |
| No uniforms: width, height, blocks per row, block count in a 16-byte header of the input buffer; mode and alpha weight compiled in (7 programs) | setting a uniform waited for every dispatch in flight that used the program: 5-12 ms a page of GL-thread time, ~0.2 ms after |
| A barrier at the end of each `EncodeBlock` reduction pass | the HLSL relied on lockstep execution there |
| Bounds checks on block id and partition tables | out-of-range SSBO writes and table reads are undefined in GL |

Lookup tables as `const` arrays or in an SSBO made no measurable difference. The shader cache makes later compiles
~1 ms (first compile of all programs ~300 ms).

## Quality (21 pages: 6 each of melos 1/2 and `Tiles2x`, the 3 of `tikitown_tiles.floor`; level 0; GPU-decoded)

"Over white/grey" composites the premultiplied page over a flat background (what the screen shows, colour and alpha
errors together). Lossless counts as 100 dB. Alpha weight is the weight of alpha in the encoder's squared error.

| Encoder | RGB / alpha dB | over white / grey dB | Alpha px off > 8 (max) | Over-white px off > 8 (max) | Pages below shipped DXT5 over white |
|---|---|---|---|---|---|
| Driver `GL_COMPRESSED_RGBA` (DXT5) | 37.40 / 56.12 | 37.06 / 37.31 | 159,061 (25) | 1,636,372 (199) | 21 |
| **Shipped CPU DXT5** | **39.26 / 58.15** | **38.95 / 39.18** | 98,250 (23) | 1,198,871 (181) | — |
| Driver BC7 (`GL_COMPRESSED_RGBA_BPTC_UNORM`) | 43.14 / 42.61 | 39.57 / 41.77 | 393,323 (43) | 833,271 (182) | 9 |
| GPU modes 4/5/6, alpha weight 1 | 42.96 / 48.04 | 41.12 / 42.33 | 240,371 (108) | 760,521 (206) | 3 |
| GPU modes 4-7, alpha weight 1 | 46.17 / 49.01 | 43.98 / 45.49 | 184,045 (125) | 474,800 (175) | 2 |
| GPU modes 0-7, alpha weight 1 | 46.22 / 49.01 | 44.01 / 45.53 | 184,041 (169) | 463,299 (175) | 2 |
| GPU modes 4-7, alpha weight 2 | 45.77 / 50.82 | 44.19 / 45.31 | | | |
| GPU modes 4-7, alpha weight 4 | 45.32 / 52.99 | 44.22 / 45.01 | 147,638 (100) | 486,023 (169) | 2 |
| GPU modes 4-7, alpha weight 4, nearest index | 45.32 / 53.70 | 44.36 / 45.05 | 121,278 (100) | 471,912 (169) | 1 |

- The port beats shipped DXT5 on colour by 6-7 dB and on screen (over white/grey) by 5-6 dB, and loses 4.5-9 dB of
  alpha. Weighting alpha trades colour for alpha; at weight 4 the screen composite is best over white.
- "Nearest index" (each pixel's index by the smallest actual error, then the anchor fixed by swapping endpoints and
  mirroring indices) adds +0.7 dB alpha and removes one page below DXT5.
- The pages below DXT5 on screen are alpha-heavy: melos2 #3 (31.6 vs 33.0 dB) and tikitown #1 (36.1 vs 36.5).
- The largest alpha errors (100-125 levels, DXT5 at most 23) stay with every variant: they come from the
  bounding-box endpoints and the mode choice (e.g. an opaque mode 1/3 block over a few translucent pixels), not from
  the index choice. Fixing them needs endpoint fitting (least squares / principal axis) and a better mode search,
  i.e. a stronger encoder than DirectXTex's, such as Intel ISPC Texture Compressor's BC7 (MIT). BC7 gives alpha fewer
  bits than DXT5 (mode 5: 2-bit alpha indices; mode 4: 3-bit indices with 6-bit endpoints; DXT5: 3-bit indices with
  8-bit endpoints in 64 bits of their own), so alpha ≥ DXT5 on these pages is not certain with any BC7 encoder.
- Modes 0/2 (3 subsets) add +0.05 dB for +60 % GPU time: not worth it.

## Time (all mip levels, a new texture per page as the game does, 21 pages back to back, median of 7 interleaved runs)

| Path | Until the GPU is done, per page | GL thread per page |
|---|---|---|
| Driver DXT5 | 21.1 ms | 21.0 ms |
| Shipped CPU DXT5 (16 threads) | 3.6 ms | 3.6 ms (blocked, all cores busy) |
| GPU modes 4/5/6 | 1.7 ms | 0.26 ms |
| GPU modes 4-7 | 5.0 ms | 0.21 ms |
| GPU modes 0-7 | 7.9 ms | 0.21 ms |

- Level 0 of a 1024² page back to back: modes 4/5/6 0.9 ms, 4-7 4.0 ms, 0-7 5.8 ms. Per pass (one page, finish in
  between): 456 0.22 ms, 137 mode 1/3/7 0.71/1.27/1.31 ms, 02 mode 0/2 1.82/0.58 ms, encode 0.08 ms.
- Small levels cost a fixed ~0.1-0.3 ms of GPU time each (dispatches, barriers, the upload from the pixel buffer).
- The GL thread only issues: one input upload (`glBufferSubData` from the game's buffer, which the game frees after the
  call), 3-5 dispatches, one `glCompressedTexImage2D` from a pixel unpack buffer. Input in a ring of 8 buffers beats
  orphaning (5.0 vs 5.1 ms GPU for 4-7, 1.7 vs 2.0 for 4/5/6).
- **No cache is needed**: the GL thread already spends ~0.2 ms a page; a cache could only save GPU time and would need
  a readback.

## Effect on the join (reasoning from R2's data, to be measured)

- With CPU DXT5 the pack pages no longer bound the asset wait (R2 `verification.md`; depth-map task's timeline: pack
  images 1.3-4.1 s at the wait's start, then animations and depth maps). Page decode on the 4 file workers runs at
  ~6 ms a page or slower.
- GPU modes 4-7 at 5.0 ms a page stay below that rate; in a burst the GPU becomes the pace (5.0 ms a page vs CPU
  DXT5's 3.6 ms). The GL thread gives back ~3.4 ms a page and the 16 encoder threads no longer compete with the file
  workers. Expected join effect: neutral to slightly faster; the quality gain is the reason for the change.

## GL state on the game's render thread

- The game uses SSBOs itself: `zombie.core.rendering.ShaderBuffer` binds `GL_SHADER_STORAGE_BUFFER` (37074) and calls
  `glBindBufferBase` before it draws; programs change all the time (`ShaderProgram`, `ShaderHelper`, `VBORenderer`,
  ImGui). It never uses `GL_PIXEL_UNPACK_BUFFER`, `glMemoryBarrier` or compute (class scan of `projectzomboid.jar`).
- The encoder changes: current program; generic `GL_SHADER_STORAGE_BUFFER` binding and the indexed bindings it uses
  (4: input, two error buffers, output; tables if kept in a buffer); `GL_PIXEL_UNPACK_BUFFER` (must be 0 already,
  `Upload.packedRows`). It touches no texture unit, no uniform of the game's programs, no framebuffer.
- Restore: program and generic binding by `glGetInteger`; an indexed binding by `glGetIntegeri_v` of
  `GL_SHADER_STORAGE_BUFFER_BINDING`/`_START`/`_SIZE` (range or base); unpack buffer back to 0.
- Every level of a texture must have the same internal format (else the texture is incomplete): once BC7 is chosen,
  every level the game asks to compress must go through it.
