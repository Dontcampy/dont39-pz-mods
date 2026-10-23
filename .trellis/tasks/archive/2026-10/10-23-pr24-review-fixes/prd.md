# PR #24 review fixes: BC7 dispatch, settings reuse, Loading Progress report

## Goal

Fix every finding of the 2026-10-23 review of PR #24 (architecture, code reuse, performance) in Faster Loading
(BC7 encoder, settings, depth layering, Lua folder walk) and Loading Progress (asset-wait report). User decision
(2026-10-23): "Fix all", as a Trellis task, started without a separate approval stop.

## Findings to fix

### A. Faster Loading textures

- A1 (high) `Bc7Encoder.groups` puts `count > 65535` on a `(65535, y)` grid: a 1024² level (65,536 blocks) dispatches
  131,070 one-block groups for each mode 1/3/7 pass; the extra groups do the full search and drop it.
- A2 Mode 1/3/7 passes search blocks whose best error is already 0 (transparent, flat, padding).
- A3 About 22 `glGet*` a BC7 level (`Upload.packedRows`, `GlState`, `allocated`, `mipmapped`, error checks).
- A4 Small mip levels each pay the full fixed cost (2 uploads, 5 dispatches, 6 barriers, 1 level fill).
- A5 Every shader entry declares the full ~7 KB `shared_temp` struct array; the 137 entry needs ~1.3 KB.
- A6 `TextureHooks` tests `encoder instanceof Bc7Encoder`; the real rule is "the driver's format for
  `GL_COMPRESSED_RGBA` differs from the encoder's".
- A7 Reuse: the size check in both encoders, `(w + 3) / 4` six times, the glGetError-and-throw twice.
- A8 The encode pass's shader-storage barrier is followed by a pixel-buffer barrier; one barrier with the next
  consumer's bits is enough.
- A9 CPU DXT5: the render thread idles in `f.get()` while the pool encodes.
- A10 `QualityCheck` times PNG sizes (1024×1019), never the game's power-of-two `widthHw` size, so A1 was missed;
  `Bc7EncoderTest` asserts the wasteful grid.

### B. Faster Loading structure and settings

- B1 The Lua options page copies `Fix` (keys, comments) and the translation keys by hand; nothing checks they match.
- B2 Settings reader and options page are copies of Loading Progress's and already differ (missing key: Loading
  Progress refuses, Faster Loading adds the default).
- B3 `depth/DepthOrder` imports `hooks.Session` (package cycle) and reaches the game and the log itself; `Session`
  holds the depth fix's widening state.
- B4 `FolderWalk` asks the disk twice for a folder (`isDirectory`, then `readAttributes`) and calls
  `getAbsolutePath` twice a file.
- B5 `Fix.TEXTURE_BC7` is an option of `TEXTURE_COMPRESSION`, not a fix of its own; its Javadoc does not say so.

### C. Loading Progress

- C1 The asset wait is stored twice (an `ASSET_WAIT` item line re-timed by `animations()`, and the `AssetWait`
  record) and the numbers differ ("Items by kind" 700 ms vs "Asset wait" 900 ms); the file tasks widen the line CSV
  and break "a step's rows add up to its time".
- C2 Depth-map tasks return null, so their per-file and per-mod times are handed out first-in, first-out by worker
  and are approximate; the report does not say so.
- C3 The decoder throttle hooks time tasks while the report is off.
- C4 A task cancelled mid-run leaves its result (e.g. decoded pixels) in `byResult` until the world load ends.
- C5 `packFile` throws when a pack name has no mounted file: mod content, not a game mismatch, disables the whole mod;
  the file is resolved again for every pack image inside `FileWork`'s lock.
- C6 Report's by-type and per-mod blocks repeat the group → sum → sort → row pattern, and recompute sums in
  comparators.

## Requirements

- Every change keeps behaviour the same except where a finding says otherwise. BC7 output stays bit-identical for
  every page and level the tests cover (the fixes change how work is scheduled, not what is computed).
- Mismatch handling stays per `.trellis/spec/mod/error-handling.md`: a game or GL state not as expected throws with
  context; mod content problems (C5) are recorded as a failed task, not thrown.
- No class is shared unrelocated between the two mods' jars: shared settings code is compiled into each mod's own
  package (B2); Lua shared code gets a per-mod file name (Lua `require` names are global).
- A4 and A5 stay only if measured worth it: A4 when it cuts GPU time a 1024² page by ≥ 0.5 ms, A5 when it cuts GPU
  time at all; otherwise reverted and recorded with the numbers.
- Loading Progress's README documents the report's new files CSV; Faster Loading's README and the specs follow the
  code.

## Acceptance Criteria

- [x] AC1 (A1, A10): `groups` returns a balanced grid (`x * y ≥ count`, `x * y - count < y`), the shader numbers
  groups with `gl_NumWorkGroups.x`, tests cover the grid; `QualityCheck` times pages at their `widthHw` size and
  prints GPU and GL-thread time a page before/after (recorded in `verification.md`).
- [x] AC2 (A2, A5, A8): output bit-identical to the commit before this task on all `QualityCheck` pages, levels and
  large/odd sizes; GPU time a 1024² page lower than before; A5 kept or reverted per the requirement.
- [x] AC3 (A3, A4): GL-thread time a page not higher than before, `glGet*` a level counted before/after in
  `verification.md`; A4 kept or reverted per the requirement with numbers; the relative AC3 of the BC7 task (≤ 40 %
  of DXT5) still passes.
- [x] AC4 (A6, A7, A9): the hook has no concrete encoder type; the driver's `GL_COMPRESSED_RGBA` format is probed
  once a session; shared helpers replace the duplicates; DXT5 encodes the last part on the calling thread; DXT5
  output unchanged.
- [x] AC5 (B1): a JUnit test fails when the Lua page's keys/comments/order or the EN/CN translation keys differ from
  `Fix`; it passes on the current files.
- [x] AC6 (B2): one settings source (Java reader/writer and Lua page logic) is compiled into both mods under their
  own package / file names; Loading Progress gains the missing-key upgrade; both mods' existing settings tests pass;
  `error-handling.md`'s row points at the shared code.
- [x] AC7 (B3, B4, B5): no package cycle in Faster Loading; `MOD_ID`/`PREFIX` live in a root-level class; the depth
  game adapter sits with its hook; `FolderWalk` asks the disk once an entry; `loadList` tests still equal vanilla's.
- [x] AC8 (C1-C6): one source for the wait's numbers; file tasks in their own `*-files.csv`; depth-map tasks named by
  the tileset the call loads (or their rows marked approximate if no hookable name exists); throttle hooks idle with
  the report off; cancelled results released; `packFile` never throws for mod content and resolves each pack once;
  one ranked helper in Report; Loading Progress tests cover each.
- [x] AC9: the build gate passes for `faster-loading` and `loading-progress`
  (`.trellis/spec/mod/quality-guidelines.md`).

Results (`verification.md`): BC7 output bit-identical on all 284 hashed levels; GPU a padded 1024² page 6.5-6.7 →
2.9-3.0 ms; GL thread unchanged (1.64 ms padded); `glGet*` a page 241 → 145; A5 kept, A4 not built (at most 0.45 ms
to gain, below the 0.5 ms bar); the 40 % gate stays on the pages' own sizes (32 %), padded share printed (37 %);
depth-map tasks named through a `createTileset` hook; both mods build their own `settings.Switches` from
`mod-settings/`; build gate of both mods passes.

## Out of Scope

- AC5 of the parent (integration check on the Linux test server): resumed after this task.
- Measuring Loading Progress's report overhead in game.
