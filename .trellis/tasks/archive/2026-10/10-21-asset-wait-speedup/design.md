# Design: texture compression off the render thread (R2)

Requirements: `prd.md`. Evidence: `research/upload-path.md`.

## Shape

```
render thread                                   mod's encoder pool (N daemon threads)
TextureID.generateHwId
  └ GL11.glTexImage2D(target, level, GL_COMPRESSED_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, rgba)
      HEAD: TextureHooks.upload ── call shape ours? ── no ──> return (driver path as vanilla)
              │ yes, switch on
              ├ Encoder.encode(rgba, w, h) ───────────────> block rows in parallel (DXT5: stb colour + own alpha)
              ├ GL13.glCompressedTexImage2D(target, level, DXT5, w, h, 0, blocks)
              ├ glGetError() != 0 ──> throw (gate stops the mod; the game's call runs)
              └ ci.cancel()
```

Package `com.dont39.pz.fasterloading`:

| Class | Role |
|---|---|
| `hooks/TextureHooks` | `@Hooks(GL11.class)`, `@Inject(method = "glTexImage2D(IIIIIIIILjava/nio/ByteBuffer;)V", at = HEAD, cancellable = true)`; decides the call shape, asks `Session`, calls the upload |
| `textures/Upload` | GL side: checks S3TC once, unpack state per call, uploads the encoder's blocks, checks the GL error; game-free apart from LWJGL |
| `textures/Encoder` (interface) | `glInternalFormat()`, `id()` (name and version: a future cache key part), `encode(ByteBuffer rgba, int width, int height) → Blocks` on the GL thread with the context current; the result's buffer is freed after the upload |
| `textures/Dxt5Encoder` | the shipped encoder: block rows split over a pool; colour by `STBDXT.stb_compress_dxt_block(..., STB_DXT_HIGHQUAL)`, alpha bytes replaced by `Dxt5Alpha` |
| `textures/Dxt5Alpha` | own DXT5 alpha block encoder: 8-value and 6-value (explicit 0/255) modes, least squares then a hill climb, picks the lower error; exact cases first, nearest palette step by fixed point over the block's distinct values (the first version's per-candidate palette scan was ~90 % of the encoder in game) |
| pool (in `Dxt5Encoder`) | lazily created fixed pool, `availableProcessors()` daemon threads named `Dont39FasterLoading-texture-N`; levels below `PARALLEL_BLOCKS` (256 blocks, from a size sweep) encode on the calling thread; a failed part is reported only after every part has stopped, so none writes into freed memory |
| `settings/Fix` | new `TEXTURE_COMPRESSION("textureCompression", ...)`; options page `FIXES` and `Translate/{EN,CN}/UI.json` |

The cache slot is a decorator implementing `Encoder` (content hash of the level + size + inner encoder `id()`); not
built this round. A GPU encoder implements `Encoder` and may use the current context, since `encode` is called on the
GL thread.

## Call shape the fix takes

All of: `target == GL_TEXTURE_2D`, `internalformat == GL_COMPRESSED_RGBA` (34030), `border == 0`,
`format == GL_RGBA`, `type == GL_UNSIGNED_BYTE`, `pixels != null`, direct, `remaining() >= w*h*4`, and tightly
packed unpack state (`GL_PIXEL_UNPACK_BUFFER_BINDING`, `GL_UNPACK_ROW_LENGTH`, `GL_UNPACK_SKIP_ROWS`,
`GL_UNPACK_SKIP_PIXELS` all 0, and `w*4` a multiple of `GL_UNPACK_ALIGNMENT`; `Upload.packedRows`). Anything else is not a compression request this fix defines and goes to the driver
untouched. The argument checks run before `Session.on(...)`, so the splash screen's early uploads never reach
`Session` (which needs the game log and cache folder).

Where the game makes such calls: `TextureID.generateHwId` (flag 4 + RGBA, research §"Texture compression"), each mip
level separately, levels down to 1×1. DXT5 levels smaller than 4×4 are one block; sizes not divisible by 4 round
up to whole blocks (`imageSize = ceil(w/4)*ceil(h/4)*16`), which the bench uploaded without error.

## Format

Always DXT5 (`GL_COMPRESSED_RGBA_S3TC_DXT5_EXT`): the driver's own pick on NVIDIA, same size as BC7, decoded in
hardware everywhere desktop GL runs (including macOS). `GL_EXT_texture_compression_s3tc` is checked once at the first
taken call; missing it throws (R2.4).

## Errors

- Default gate, as the mod's other hook: a throw from the handler stops all the mod's hooks for the session and is
  logged by Zabric; the glue's catch lets the game's `glTexImage2D` run, so that level still uploads (driver path).
- `glGetError()` right after our upload: non-zero throws, naming the error, level, size and encoder. The game's
  texture path never reads the flags (`PZGLUtil.checkGLErrorThrowTexture` passes `false`), so flags left by earlier
  game code are cleared just before our upload; the game would not have looked at them either.
- No catch in the handler; the pool's task failures surface from `encode` as the thrown cause.

## Cancelling `glTexImage2D` (an exception to the README's "hooks do not cancel")

The upload must be replaced, and no argument change makes `glTexImage2D` take compressed blocks. Cancelling skips
inner advice of other mods on the same method; on the live list nothing patches `GL11` (`[ZB] patching` lines). The
README's Constraints section records this exception and why.

## Threads and frames

`encode` blocks the render thread for the parallel time of one level (~2-3 ms for a 1024² level on 8-14 threads, the
mip chain ~1/3 more), well under the driver's ~20 ms, so `flushInvokeQueue`'s 10 ms pass fits several pages and frames
stay short. The pool runs only while a level is being encoded; game workers keep decoding meanwhile.

## Logging

One line after the first successful upload: `texture compression: <encoder id> on N threads`. Errors through the
gate. Nothing per call.

## Settings upgrade

Adding `textureCompression` would have made a 0.1.0 ini (only `stripComments`) "invalid" and reset every switch to its
default. A key missing from the file now takes its default and `Settings.load` rewrites the file with every key,
keeping the values read; the options page fills a missing key with its default too. Invalid files are still reported
and left as they are.

## Testing

- Unit (`faster-loading/src/test`, game jar on the test classpath as for comments): `Dxt5Alpha` exact cases and error
  against brute force on random blocks; `Dxt5Encoder` against a Java DXT5 decoder (PSNR floors), odd sizes and mip
  tails, parallel == serial bytes; call-shape decision (pure function of the arguments and unpack state).
- Quality bench (AC1): a `qualityCheck` Gradle task (not part of `check`: needs a GPU) running the hidden-window
  comparison from `scratchpad/dxtbench/DxtQuality.java`, rewritten against `Dxt5Encoder`.
- In game: `smokeTest`, then the local server series (`implement.md`).

## Compatibility and rollback

- Loading Progress (patches `waitFileTask`, `Display.*`) and Viewpoint (`Display.*`) are unaffected.
- `textureCompression = false` in `Dont39FasterLoading.ini` restores the driver path from the next launch; the game's
  own `textureCompression=false` makes no call take the fix.
- Revision check unchanged (`Session.REVISION`); the fix reproduces `generateHwId`'s call contract of 42.21.

## R2.5 worker pool (conditional; gate not met, not built)

The gate did not pass (`verification.md`: pack images 9.4 s of 47 s worker time with the fix on). Kept for reference:
only after the profile gate in `implement.md`. Shape if needed: widened `FileSystemImpl.executor`
(`ThreadPoolExecutor`), core and max size raised from the main thread at the world load's start and restored at its
end (JDK-only markers as Loading Progress uses: `DebugType.println(String)` wait start/end or `GameLoadingState`
entry/exit), own switch. Not designed further unless the gate passes.
