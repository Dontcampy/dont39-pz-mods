# Asset-wait speed-up (R2)

Parent: `.trellis/tasks/10-21-load-speed-optimization` (Goal, evidence and decisions live there).

Depends on `10-21-asset-wait-breakdown` (its data picks the levers) and `10-21-faster-loading-strip-comments` (the
mod). **Planning of this task resumes only after R1's report exists for a real join; do not start it before.**

## Goal

Cut the join's 39.8 s asset wait toward vanilla's few seconds.

## Candidate levers (to be chosen from R1 data)

From `../10-21-load-speed-optimization/research/asset-wait.md` §4:

- Wider worker pool and higher in-flight budget during the wait only (PZ_Optimization measured a 5.05 s frame at
  14 threads / 256 MB, and slower serial loading with too many threads: tune, keep the 50 MB texture budget).
- Depth-map decode outside its lock, if depth maps show up.
- A jassimp result cache for physics shapes (and animations) keyed by resolved path, size, mtime, loader variant,
  skeleton identity and game build, if model imports dominate.

## Requirements / Acceptance Criteria

- To be written after R1 data: chosen levers, a target wait time, and equivalence checks (same assets loaded, no
  missing textures/meshes, no frame over a stated limit).
