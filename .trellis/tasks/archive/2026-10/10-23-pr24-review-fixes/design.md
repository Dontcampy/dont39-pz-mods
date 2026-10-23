# Design: PR #24 review fixes

Three independent parts; A and C touch different modules, B touches both mods' settings and Faster Loading's
structure and runs after A and C.

## A. BC7 encoder (faster-loading `textures/`, `BC7Encode.comp`)

- **Grid (A1).** `groups(n)`: `y = ceil(n / 65535)`, `x = ceil(n / y)`; the shader's `groupIndex()` becomes
  `gl_WorkGroupID.y * gl_NumWorkGroups.x + gl_WorkGroupID.x`. Groups past the last block return before any barrier
  (whole group, so barrier-safe); the 456 and encode entries cover 4 blocks a group and keep their per-block bound
  checks.
- **Exact blocks (A2).** At the top of `ENTRY_137`: if `g_InBuff[blockID].x == 0u` (error already 0), thread 0 copies
  in to out and the group returns. All threads read the same value, so the branch is uniform.
- **Shared memory (A5).** `BufferShared`'s fields under `#if` per entry, so each program declares only what it uses.
  Kept only if GPU time drops (measure per pass with `GL_TIME_ELAPSED`).
- **Barriers (A8).** `dispatch(program, in, out, groups, barrierBits)`: the try passes pass
  `SHADER_STORAGE_BARRIER_BIT` (the next pass reads their output as a storage buffer); the encode pass passes
  `PIXEL_BUFFER_BARRIER_BIT` only, since its output is read only by the level fill from the unpack buffer, and the
  separate barrier in `strip` goes.
- **Fewer GL queries (A3).**
  - `GlState`: query `_START`/`_SIZE` only when the indexed binding is non-zero (a zero binding restores to 0 with
    `glBindBufferBase`).
  - `Encoder.define`'s contract says no pixel unpack buffer is bound (the hook only calls it after
    `Upload.packedRows`): `GlState` stops saving/restoring the unpack binding and `issue` stops unbinding it; the
    encoder still leaves 0 bound after its fills.
  - Chain memo: at level 0 (or at an allocation), remember the bound texture id (one `GL_TEXTURE_BINDING_2D` query),
    size and level count; a later level of the same texture with the expected size skips the three
    `glGetTexLevelParameteri`. Anything else falls back to the queries.
  - Count `glGet*` a level before/after (a counting wrapper in `QualityCheck` or by reading the code) and record it.
- **Small levels (A4).** Levels of one mipmapped texture at or below a threshold (e.g. ≤ 256 blocks) are appended to
  one ring slot with a per-level offset table in the header; one set of dispatches runs when the 1×1 level arrives,
  then one `glCompressedTexSubImage2D` per level from offsets into `output`. A pending batch when a call for another
  texture or a non-consecutive level arrives is a mismatch with the game's level order (it sends every level in
  order, `calculateNumMips`): flush what is pending to its texture (rebinding it, then restoring) and throw.
  Kept only if GPU time a 1024² page drops ≥ 0.5 ms; otherwise reverted, numbers recorded.
- **Driver format probe (A6).** `Encoders.session` uploads a 4×4 `GL_COMPRESSED_RGBA` level to a scratch texture
  once, reads `GL_TEXTURE_INTERNAL_FORMAT`, deletes it, and restores the 2D binding. `TextureHooks`: when rows are
  not packed, throw if `encoder.glInternalFormat() != driverFormat` (any encoder), else let the driver compress.
- **Reuse (A7).** The size check lives in `Upload.compressed` only; `Blocks.count(w, h)` (and columns/rows helpers)
  replace the `(w + 3) / 4` copies; one `Upload.checkErrors(String what)` replaces the two glGetError-and-throw
  blocks.
- **DXT5 (A9).** `Dxt5Encoder.parallel` submits parts 0..n-2 and runs the last on the calling thread, then joins.
- **Measurement (A10).** `QualityCheck` pads each page to its `widthHw` size (next power of two, as the game's
  `ImageData` does) for the timing runs and keeps PSNR on the unpadded area. A `--dump <dir>` mode (or a system
  property) writes a hash of each level's BC7 blocks so the output before/after can be compared bit for bit.

## B. Faster Loading structure and shared settings

- **One settings source (B2).** A source folder `mod-settings/` (no Gradle project of its own) holds
  - `Switches.java.template`: the ini reader/writer generic over an enum of keys (key name, default, comment line):
    BOM skip, `#` comments, `key = value`, true/false, set-twice, unknown key, missing file written with defaults,
    missing key upgraded with its default (Faster Loading's behaviour, now also Loading Progress's);
  - `Switches.lua.template`: the options page logic (read, write, the `PZAPI.ModOptions.load` wrap, `apply`) taking
    a table of `{key, text, comment}`.
  A buildSrc convention (`pz-switches`) copies them into `build/generated/...` with the mod's package and Lua file
  name substituted (`com.dont39.pz.<mod>.settings.Switches`, `Dont39<Mod>_Switches.lua`), adds the Java folder to the
  main source set and the Lua to `assembleMod`. Each mod keeps a thin `Settings` and a small options Lua that passes
  its table. No class is shared between jars, and no Lua `require` name collides.
- **Lua/Fix check (B1).** A JUnit test per mod parses the mod's options Lua table (`key`, `text`, `comment`) and
  compares it in order with the Java keys (`Fix.values()` / Loading Progress's keys) and `Settings.text(DEFAULTS)`'s
  comment lines, and checks `UI_<Mod>_<Text>` and `...Tooltip` exist in EN and CN `UI.json`.
- **Layering (B3).** A root class `com.dont39.pz.fasterloading.Mod` holds `MOD_ID`/`PREFIX`. `DepthOrder` (the game
  adapter) moves into `hooks/` beside `DepthHooks`, or receives a logger and the game access as callbacks like
  `Encoders.session`; `depth/` keeps only pure `Batch`/`Schedule`. The widening state (`widened`,
  `wideningReported`) moves from `Session` to the depth hook side.
- **Folder walk (B4).** Keep `File.list()` for names and order (the order feeds the Lua checksum); per entry one
  `Files.readAttributes(path, BasicFileAttributes, NOFOLLOW_LINKS)` answers folder / link / other; one
  `getAbsolutePath` per file.
- **B5.** `Fix`'s Javadoc says `TEXTURE_BC7` is an option of `TEXTURE_COMPRESSION`.

## C. Loading Progress asset-wait report

- **One source (C1).** The `AssetWait` record is the only source for the wait's numbers: "Items by kind" leaves out
  `ASSET_WAIT` (or shows the record's span), the timeline keeps its line. File tasks go to `<report>-files.csv` with
  their own header (type, file, mod, queued, ran, worker time, failed...); the line CSV returns to its 0.1.0 columns.
- **Depth names (C2).** If `TileDepthTextures.createTileset(String, boolean)` (or another JDK-only call) runs inside
  `LoadTask.call` on the worker, a hook records the name for the current call like `callFile` does for models, and
  results are matched by (worker, name). Otherwise the summary marks the rows of null-returning types as
  approximate. Verify with javap before choosing.
- **C3.** The throttle hooks check `tracking` as well as `waiting` (or `waiting` is set only while tracking).
- **C4.** `byResult` entries for a cancelled task are released: e.g. entries carry the pump count when recorded and
  each pump drops those older than a few pumps, or the key is held weakly; a test cancels a task mid-run.
- **C5.** `packFile` returns null/a marker for an unmounted pack; the task is named `packName/imageName` and shows as
  failed. The pack name → file lookup is cached per pack for the load.
- **C6.** `Report.ranked(Map<String, Long>)` (or similar) serves "Time per mod", the by-type and per-mod file blocks;
  totals are computed once.
