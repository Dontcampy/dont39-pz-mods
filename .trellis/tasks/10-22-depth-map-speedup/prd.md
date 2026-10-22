# Depth-map load speed-up

Parent: `.trellis/tasks/10-21-load-speed-optimization`. Follows R2 (`archive/2026-10/10-21-asset-wait-speedup`), which
moved texture compression off the render thread (Faster Loading 0.2.0). **Planning stage: research spike first.**

## Goal

Cut the depth maps' share of the join's remaining asset wait (12.5 s median with Faster Loading 0.2.0) from the
preloaded client mod Faster Loading, with the game's own result.

## Known

- With Faster Loading 0.2.0 on (live mod list, join, `../archive/2026-10/10-21-asset-wait-speedup/verification.md`):
  worker time during the wait is depth maps (`TileDepthTextures$LoadTask`) 22.6 s / 900 tasks, animations 14.8 s /
  1,721, pack images 9.4 s / 273; the 4 workers are 98 % busy.
- JFR (texture compression off, `.../research/upload-path.md`): workers 39 % of samples in `TileDepthTexture.load`;
  the render thread 39 % in `TileDepthTexture.lambda$updateGPUTexture$0` (`DirectByteBuffer.put`, `Buffer.checkIndex`).
- `TileDepthTextures$LoadTask.call` runs `createTileset(name, true)` → `TilesetDepthTexture.load()` inside
  `synchronized (textures)` (parent `research/asset-wait.md` §1), so one instance's depth maps decode one at a time and
  a waiting task holds a worker.
- Moving the decode out of the lock needs the task (`@Self`), which a preloaded mod cannot use (JDK-only glue).
  `TileDepthTexture.load([FLjava/nio/ByteBuffer;III)V` has a JDK-only descriptor and takes its arrays as arguments:
  if its work depends only on them, it can be split over the mod's own threads while the lock is held.

## Research questions (answer before requirements)

1. What `TileDepthTexture.load(float[], ByteBuffer, int, int, int)` computes, what it reads and writes, and whether
   the work can be done from its arguments alone.
2. What the lock protects, who else takes it, and how many `TileDepthTextures` instances (locks) exist.
3. What the render-thread `updateGPUTexture` lambda does and whether a JDK-only hook reaches it.
4. Which levers a preloaded mod can reach (JDK-only descriptors, no `@Self`), ranked by likely gain; which are out of
   reach and why.
5. Whether the same work runs at launch (main menu) too.

## Requirements / Acceptance Criteria

To be written after the research, as in R2: a measured target (median of ≥ 3 joins on vs off), identical output,
no new errors or frame gaps.
