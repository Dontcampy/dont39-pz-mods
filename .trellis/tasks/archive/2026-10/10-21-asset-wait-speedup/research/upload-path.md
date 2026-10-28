# Research: where a pack image's time goes between decode and GPU (B42.21 `4a0e9546ec`)

- **Query**: R1 shows the 4 file workers 99 % busy and ~60 % of that asleep in `TextureIDAssetManager.waitFileTask`
  (decoded bytes > 50 MB awaiting upload). What drains the budget, and how fast can it?
- **Scope**: javap `-c -p -l` of `projectzomboid.jar`; pack files on disk; the user's `options.ini`. Citations are
  `Class.method Lnnn` (LineNumberTable) or bytecode offsets `@nnn`.
- **Date**: 2026-10-22

## The path of one pack page

| Step | Thread | Where |
|---|---|---|
| Budget check: sleep 20 ms while `DirectBufferAllocator.getBytesAllocated() > 52,428,800` | worker | `FileTask_LoadPackImage.call` @0 → `TextureIDAssetManager.waitFileTask L73-79` |
| Open the page in its `.pack`, decode PNG into an `ImageData` (direct buffer via `DirectBufferAllocator.allocate`), `initMipMaps()` when the pack's flags have 64 | worker | `FileTask_LoadPackImage.call` @6-58 |
| Result waits in its future until the next pump; `handleResult` → `TextureIDAssetManager.onFileTaskFinished L62-69` → `TextureID.setImageData` | main, once per frame | `FileSystemImpl.updateAsyncTransactions` |
| `setImageData` queues `TextureID::createTexture` on the render queue | main | `TextureID.setImageData` @64-69 → `RenderThread.queueInvokeOnRenderContext` |
| `flushInvokeQueue` runs queued items FIFO until **10 ms** have passed, then only up to the last item someone waits on; called every render-loop pass and from `waitForRenderStateCallback` while the render thread waits for the next frame's state | render | `RenderThread.flushInvokeQueue`, `renderLoop` @144, `waitForRenderStateCallback` |
| `createTexture()` → `createTexture(true)` → `generateHwId(true)`: `glTexImage2D` per mip level, then `ImageData.dispose()` (frees the budget) | render | `TextureID.generateHwId` @360-488, @648 |

`getBytesAllocated()` sums every undisposed `WrappedBuffer` made through `DirectBufferAllocator`, not only pack pages.

## Texture compression is on for every mod pack page

- Pack flags: a mod pack (`pack=` in mod.info) gets `(TextureID.useCompressionOption ? 4 : 0) | 64`
  (`ChooseGameInfo` @826-844); `Texture` ORs the same bit into a texture's flags (`Texture` @230-242).
- `generateHwId` @128-174: flag 4 and an `RGBA` internal format and `GL_ARB_texture_compression` →
  internal format `34030` = `GL_COMPRESSED_RGBA`, i.e. the **driver compresses each mip level** inside
  `glTexImage2D`, on the render thread.
- The user's `~/Zomboid/options.ini` has `textureCompression=true`.

## Sizes

Pages are about 1024² (PNG `IHDR`s in the packs): `melos_tiles_for_miles1.pack` 535 pages, 2.17 GB as RGBA;
`melos_tiles_for_miles2.pack` 393 pages, 1.59 GB; vanilla `Tiles2x.pack` 369 pages, 1.50 GB. One page is ~4 MB RGBA,
~5.3 MB with mipmaps, so the 50 MB budget holds ~10 pages. A join's ~1,700 pack pages are 7-9 GB through the render
thread in 48-74 s, about 150 MB/s.

## Depth maps

`TileDepthTextures$LoadTask.call` @35 `monitorenter` on its `TileDepthTextures` and runs `createTileset(name, true)`
→ `TilesetDepthTexture.load()` (`NativeImage.read` of a PNG, per-tile `TileDepthTexture.load`) inside it, so one
instance's depth maps decode one at a time and a waiting task holds a worker.

## Measured (2026-10-22, local nosteam server, live mod list; Faster Loading not loaded; JFR `settings=profile`)

PC: Ryzen 7 9800X3D (8C/16T), RTX 4090 24 GB, driver 617.42 (OpenGL 4.6). `vsync=false`, `frameRate=144`.
Raw reports, JFR files and scripts were not kept.

| | `textureCompression=true` (as played) | `=false` |
|---|---|---|
| Join total | 151.5 s | 82.3 s |
| Loading the world | 99.5 s | 33.6 s |
| Asset wait | 66.1 s, 3,650 frames (18 ms) | 13.8 s, 1,913 frames (7 ms) |
| Pack pages done during the wait | 1,738 (208 s worker time; 168 s asleep on the budget) | 85 (the rest finished before the wait began, 17 s earlier) |
| Launch total | 73.4 s | 69.3 s |

Render thread (Java name `main`) during the wait, compression on (sampled stacks): 52 % `GL11.glTexImage2D` from
`TextureID.generateHwId` ← `flushInvokeQueue`; 26 % `glfwSwapBuffers` (not vsync: it is off; most likely the driver
finishing queued texture work); ~10 % depth-map uploads (`TileDepthTexture.lambda$updateGPUTexture$0`); the rest
the loading screen's own drawing. Workers: PNG decode (`PNGDecoder.unfilterPaeth`/`decode`, `Inflater`), mipmaps
(`ImageData.scaleMipLevelMaxAlpha`), `performPreMultipliedAlpha`, depth maps, jassimp.

Compression off, the 13.8 s wait: workers 39 % in `TileDepthTexture.load` (under the per-instance lock), render
thread 39 % in `TileDepthTexture.updateGPUTexture` lambdas; 900 depth-map tasks, 32.7 s worker time.

**Conclusion.** The join's asset wait is bound by the render thread compressing mod pack pages inside
`glTexImage2D` (`GL_COMPRESSED_RGBA`), ~20 ms per 1024² page, about one page per frame since each
`flushInvokeQueue` pass stops after 10 ms. Texture compression costs ~66 s per join here. Wider pools or a larger
budget cannot help (the budget is full because the render thread is behind). With compression out of the render
thread, depth maps are the next bound.

## Moving compression off the render thread

- LWJGL's `org.lwjgl.stb.STBDXT` (stb_dxt) is in the game jar with its Windows/Linux/macOS natives; `STBImage` is
  already loaded by the game (`nstbi_load` shows in the profile).
- Bench (`scratchpad/dxtbench/DxtBench.java`, game JRE): first page of `melos_tiles_for_miles1.pack`, 1024×1019, the
  full mip chain (87,127 blocks) to DXT5 = **~23 ms on one core** (normal and high-quality modes alike), about what
  the driver takes on the render thread, but runnable on every file worker at once. Uploading DXT5 data is
  `GL13.glCompressedTexImage2D` (a copy, no compression).
- Quality (`scratchpad/dxtbench/DxtQuality.java`: hidden GLFW window, 24 real pages from melos 1/2, `Tiles2x`,
  `tikitown_tiles.floor`, premultiplied like the game, level 0, PSNR of the GPU-decoded result against the source):

  | Encoder | Format | PSNR RGB / alpha (dB) | Time per page level 0 |
  |---|---|---|---|
  | NVIDIA driver, `GL_COMPRESSED_RGBA` | picks DXT5 (`0x83F3`) | 37.10 / 56.57 | 15.3 ms, render thread |
  | stb_dxt normal | DXT5 | 38.77 / 54.36 | 10.3 ms, one core |
  | stb_dxt high quality | DXT5 | 38.92 / 54.36 | 10.5 ms, one core |
  | NVIDIA driver, `GL_COMPRESSED_RGBA_BPTC_UNORM` | BC7 (`0x8E8C`) | 42.74 / 42.03 | 26.8 ms, render thread |

  stb_dxt beats the driver on colour (+1.8 dB) and loses on alpha (−2.2 dB): its alpha block is a plain min/max
  fit. BC7 (same 1 byte per pixel) gains colour but the driver's quick BC7 encoder loses 14 dB of alpha, which the
  tile sprites' edges depend on; a good BC7 encoder is far slower, is not in the game jar, and BPTC is missing on
  macOS's OpenGL 4.1.
- Parallel (`ParBench.java`, stb_dxt high quality, level 0 of a 1024×1019 page split into block rows): 1 thread
  13.9 ms, 8 threads 2.7 ms, 14 threads 1.8 ms. DXT blocks are independent, so one upload can use many cores.

## Where the fix can hook (preloaded mod: JDK-only signatures, no `@Self`)

- `@Self` always names the target class in the glue (`GlueWriter` `Param.self(target.ownerSource())`), so
  `checkJdkGlue` rejects it whatever the handler declares.
- Worker side: `FileTask_LoadPackImage.call()` has no arguments; every `ImageData`/`MipMapLevel` method that sees
  the pixels is an instance method or takes game types; `openStream(DeviceList, String)` and
  `getTexturePackDevice` name game types. A worker hook cannot tell which page it is decoding, so a cache that skips
  the PNG decode is out of reach without `@Self` or rebuilding the game's file queue.
- Render side: `GL11.glTexImage2D(IIIIIIIILjava/nio/ByteBuffer;)V` gets each mip level's premultiplied RGBA,
  width, height and the requested internal format (`GL_COMPRESSED_RGBA` = 34030, format `GL_RGBA`, type
  `GL_UNSIGNED_BYTE`). A HEAD hook can compress that level in parallel and call
  `GL13.glCompressedTexImage2D(..., GL_COMPRESSED_RGBA_S3TC_DXT5_EXT, ...)` instead. No other mod patches
  `GL11` (the run's `[ZB] patching` lines: only `org.lwjglx.opengl.Display.*` and Loading Progress's
  `TextureIDAssetManager.waitFileTask`).
- Depth maps: the lock is a `monitorenter` inside `TileDepthTextures$LoadTask.call()`; the slow part,
  `TileDepthTexture.load([FLjava/nio/ByteBuffer;III)V`, is an instance method run per tile inside it. Moving it out
  of the lock needs the task (`@Self`), so it is not reachable from a preloaded mod.
- Other hookable entry points with JDK-only descriptors: `GL11.glTexImage2D(IIIIIIIILjava/nio/ByteBuffer;)V`,
  `TextureID.createTexture()V` (render thread), `FileTask_LoadPackImage.call()Ljava/lang/Object;` (worker),
  `RenderThread.flushInvokeQueue()V`.
