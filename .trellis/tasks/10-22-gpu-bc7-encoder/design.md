# Design: GPU BC7 texture encoder

Requirements: `prd.md`. Evidence and measurements: `research/bc7-prototype.md` (prototype sources: its "Prototype"
line). R2's design for the hook and upload path:
`archive/2026-10/10-21-asset-wait-speedup/design.md`.

## Shape

```
render thread (GL context current)
GL11.glTexImage2D HEAD  (hooks/TextureHooks, unchanged call-shape checks and Session.on(TEXTURE_COMPRESSION))
  └ Encoders.session()            first taken level: choose once, log one line
  │    textureBc7 on + OpenGL 4.3 ──> Bc7Encoder (compile 5 programs, allocate buffers)
  │    else                       ──> Dxt5Encoder (R2; S3TC check)
  └ Upload.compressed(encoder, target, level, w, h, pixels)
       clear pending GL errors; encoder.define(...); glGetError != 0 ──> throw
  └ ci.cancel()

Bc7Encoder.define(target, level, w, h, rgba)            GPU (in order, no CPU wait)
  stripRows(w, h)                                        whole block rows that fit the fixed buffers (a 1024² level: one strip)
  GlState.save()
  level not yet BPTC of its size: glCompressedTexImage2D(..., null) for it, and for the chain below when mipmapped
  for each strip of rows [y, y + rows):
    input ring[i] ← glBufferSubData(header w,rows,bx,blocks | the strip's rows)   copy of the game's buffer
    dispatch TryMode456   input → err A                  4 blocks a group
    dispatch TryMode137 ×3 (modes 1, 3, 7)  A → B → A → B  1 block a group, 1 partition a thread
    dispatch EncodeBlock  B → out                        4 blocks a group
    glMemoryBarrier(PIXEL_BUFFER); bind out as PIXEL_UNPACK_BUFFER
    glCompressedTexSubImage2D(target, level, 0, y, w, rows, BPTC, blocks*16, offset 0)
  GlState.restore()  (finally; restores every item even after one fails, then throws the first failure)
```

## Encoder interface (change to R2's)

R2's `Encoder.encode(...) → Blocks` hands blocks in client memory to `Upload`, which a GPU encoder could only meet with
a readback. The interface becomes "define the bound texture's level":

| Member | Role |
|---|---|
| `int glInternalFormat()` | as now |
| `String id()` | as now (name, variant, version) |
| `void define(int target, int level, int width, int height, ByteBuffer rgba)` | GL thread, context current; defines the level of the bound texture from tightly packed RGBA |

- `Dxt5Encoder.define` = its `encode` (kept public, the unit tests use it) + `glCompressedTexImage2D` from client
  memory, the code now in `Upload.compressed`.
- `Upload.compressed` keeps R2's error clearing and the `glGetError` check around `define`; the S3TC check moves to the
  choice (`Encoders`), which runs it once for DXT5.
- `TextureHooks` asks `Encoders.session()` instead of holding a static `Dxt5Encoder`; its first-upload log line moves to
  the choice.

## Encoder choice (`textures/Encoders`)

- Pure part, unit-tested: `choose(boolean bc7Switch, boolean openGl43) → Choice(kind, reason)`.
- Session part (GL thread, first taken level): reads `Session.on(Fix.TEXTURE_BC7)` and
  `GL.getCapabilities().OpenGL43` (BPTC is core since 4.2), builds the encoder, logs once:
  `texture compression: <id> (<reason>; <GL_RENDERER>, <GL_VERSION>)`, e.g. "bc7 on the GPU (...)" or "dxt5 on 16
  threads (textureBc7 off)" / "(OpenGL 4.3 missing)". The choice holds for the session: every level of every texture
  gets one format.
- Construction failures (compile, link, a GL error while allocating) throw from the first hook call: default gate,
  mod disabled with that message (prd failure policy).

## `textures/bc7/Bc7Encoder`

- Sources: `src/main/resources/com/dont39/pz/fasterloading/textures/bc7/BC7Encode.comp` (the port, one file with
  `ENTRY_456`, `ENTRY_137`, `ENTRY_ENCODE` sections; `ENTRY_02` and the 3-subset packing paths dropped) read with
  `getResourceAsStream`; each program is `#version 430`, its entry and mode define, the tables, the body.
- Programs: 456; 137 with `g_mode_id` 1, 3, 7; encode → 5. Mode and alpha weight (4.0) are compile-time constants.
  **No uniforms**: setting one waits for the dispatches in flight that use the program (prototype: 5-12 ms a page).
  Width, height, blocks per row and block count are a 16-byte header of the input buffer.
- Port deltas from the HLSL, all output-neutral or chosen (research table): `partition` renamed; SSBO input with
  clamped edges; named per-subset variables in 137 (no dynamically indexed arrays: 340 → 8 ms a page); a barrier after
  each reduction in `EncodeBlock`; bounds checks on block ids and partition tables; nearest index per pixel in the
  encode pass, the anchor fixed afterwards by swapping endpoints and mirroring indices; the try passes keep the
  projection index (nearest index there too doubled the GPU time for no gain; user, 2026-10-23).
  Tables stay `const` arrays (an SSBO made no difference).
- Buffers, created at construction with a fixed size and kept for the session: input ring of 8 SSBOs
  (`GL_STREAM_DRAW`; a ring beat orphaning in the prototype) of 16 + 1024² × 4 bytes, two error buffers and one output
  buffer of 1024² / 16 blocks × 16 bytes (`GL_DYNAMIC_COPY`): ~35 MB. A larger level is encoded in strips of whole
  block rows that fit them (`stripRows`), each filled with `glCompressedTexSubImage2D` at its row offset. (First built
  growing with the largest level and never shrinking: +922 MiB VRAM against DXT5 in the AC7 join, 640 MB held after
  eight 4096² levels in a probe; buffers of its own per large level still left ~110 MB in the driver's cache.
  `verification.md` AC7.)
- Dispatch sizes: one linear group index over a 2D grid, at most 65,535 groups per dimension (the GL minimum of
  `GL_MAX_COMPUTE_WORK_GROUP_COUNT`): `groups(n) → (x, y)`, the shader's group index `y * 65535 + x`, out-of-range
  groups write nothing. Pure arithmetic in Java, unit-tested; the shader side is exercised by the 2048² case of
  `qualityCheck`.
- Binding points: input 0, error in 1, out 2. `glBindBufferBase` also sets the generic binding.
- Levels down to 1×1 are whole blocks (`ceil(w/4) × ceil(h/4)`), edge pixels repeated (the input clamp).
- A level arriving with rows not tightly packed after BC7 was chosen cannot be left to the driver (it would pick DXT5
  and leave the texture incomplete): `TextureHooks` throws instead of returning when the encoder is BC7 and
  `Upload.packedRows` is false. The game's path never does it (R2: every taken level is packed).

## `textures/bc7/GlState`

Save before, restore in `finally`, game-free apart from LWJGL:

| State | Save | Restore |
|---|---|---|
| Current program | `glGetInteger(GL_CURRENT_PROGRAM)` | `glUseProgram` |
| Generic SSBO binding | `glGetInteger(GL_SHADER_STORAGE_BUFFER_BINDING)` | `glBindBuffer` |
| Indexed SSBO bindings 0-2 | `glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING / _START / _SIZE, i)` | size 0: `glBindBufferBase` (0 or the buffer); else `glBindBufferRange` |
| `GL_PIXEL_UNPACK_BUFFER` | is 0 (`Upload.packedRows` checked it) | `glBindBuffer(..., 0)` |

The order of restoring: indexed bindings first (each also sets the generic binding), the generic binding last.
Errors while restoring: keep restoring, then throw the first (`.trellis/spec/mod/error-handling.md` restore paths).

## Threads and frames

`define` is GL calls only: ~0.2 ms of GL-thread time a page with all mip levels (prototype), against R2's 3.6 ms with
16 threads busy. The GPU spends ~5 ms a page; a burst of pages in one `flushInvokeQueue` pass queues GPU work that the
driver works through while the next frame is drawn (the loading screen draws little). The CPU DXT5 pool is never
started when BC7 is chosen (it is lazy).

## Testing

- Unit (`check`, no GPU): `Encoders.choose`; `Bc7Encoder.groups`; `Settings` with `textureBc7`; R2's DXT5 tests
  unchanged (through `encode`).
- `qualityCheck` (GPU, not in `check`): R2's driver-vs-DXT5 comparison plus BC7 vs shipped DXT5 (RGB, over white, over
  grey must be higher; alpha and worst alpha error printed), mip chains of odd sizes and a 2048² level (format,
  completeness, PSNR floor per level), the GL-state round trip, GL-thread and GPU time per page (`GL_TIME_ELAPSED`).
- In game: `smokeTest`, then the AC5 series (`implement.md`).

## Compatibility and rollback

- `textureBc7 = false` gives Faster Loading 0.4.0's DXT5 path from the next launch; `textureCompression = false` the
  driver's.
- Video memory: BC7 and DXT5 both 1 byte a pixel. Game shaders sample the texture as before (the format is invisible
  to them).
- Other mods: nothing on the live list patches `GL11`; the game's own SSBO users rebind before drawing and are restored
  anyway.
- A GL without 4.3 (macOS 4.1) gets DXT5 with a logged reason.
- License: `META-INF/licenses/DirectXTex.txt` in the jar; the shader file keeps the MIT header and the source commit.
  `.trellis/spec/mod/quality-guidelines.md` asks copied third-party code to keep its language: a GLSL port of an HLSL
  shader cannot, so the spec update records ports (header, commit, license, a list of deltas in the file).
- Version: Faster Loading 0.5.0.
