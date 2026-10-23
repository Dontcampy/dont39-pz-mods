# Depth-map load speed-up: interleave the mods' depth-map tasks

Parent: `.trellis/tasks/10-21-load-speed-optimization`. Follows R2 (`archive/2026-10/10-21-asset-wait-speedup`), which
moved texture compression off the render thread (Faster Loading 0.2.0). Mod: `faster-loading/` (client-only,
preloaded, JDK-only hook glue, one switch per fix, on by default; a game or target mismatch is reported and disables
the mod, never a silent fallback). Technical design: `design.md`; steps: `implement.md`.

## Goal

Shorten the join's asset wait by running the depth maps of different mods side by side on the game's 4 file workers
instead of one at a time, with the game's own result.

## Background (evidence)

Sources: `research/depth-maps.md` (code path, lock, levers, launch vs join, measured timeline),
`research/reprioritise.md` (hook design and schedule simulation), `research/timeline.py`.

- Each mod with depth maps has its own `TileDepthTextures` instance, and each depth-map task decodes inside
  `synchronized` on its instance. The game queues one instance's tasks back to back at the same priority, so the 4
  workers mostly hold tasks of one instance: one decodes, three wait on its lock.
- The file queue runs by priority, then FIFO: pack images (7), meshes/physics (6), animations (4), then the depth
  maps (4, queued after the animations). On five joins with the live list, **the depth maps are the last 5.2-6.3 s of
  every asset wait, with nothing else running**: 900 tasks, 20-24 s of worker time, ~5.6 s of it actual work.
- 900 tasks per join: `game` 216 (queued by the join's own `init`, last), melos 144, tikitown 116, CommunityTilePack
  102, Tryhonesty 55, then smaller. With the instances in parallel the phase's floor is ~2 s (the game's own chain,
  measured alone at 1.97 s); a schedule that starts the longest chains first reaches it in simulation (1.6-2.0 s),
  plain round-robin does not (2.2-2.7 s).
- Unreachable for a preloaded mod: moving the decode out of the lock (needs `@Self`). The prefetch decode via
  `STBImage.stbi_load` is reachable but deferred (decision below).

## Decisions (user, 2026-10-22)

- Lever: interleave the instances' tasks in the queue (lever 2 of `research/depth-maps.md`), with a timeline
  measurement of the wait. The prefetch decode (lever 1) is considered later, only if depth maps are still the
  wait's tail.
- Target: median depth phase ≤ 2.5 s, and median asset wait ≥ 3 s shorter than with the switch off (compression fix
  on in both), ≥ 3 joins each.

## Requirements

- **R1 Depth-map order** (switch `depthMapOrder`, on by default). When the game queues depth-map tasks, their order
  in the file queue is changed so that tasks of different `TileDepthTextures` instances alternate and the longest
  instances start early. Depth-map tasks stay behind every task they are behind today (all depth tasks keep a
  priority ≤ the game's 4); no other task's priority or order changes. With the switch off, the game's order is
  unchanged.
- **R2 Same result.** Only the order changes: the same tasks run, each instance's tilesets and tiles end up the same,
  and the game's merge after the last task runs as before.
- **R3 Mismatch handling.** If the game's queue or depth-map classes are not as the fix expects (a field or class
  missing, a task of unexpected type, depth tasks not all still pending when the fix runs), it is reported with
  context and disables the mod; never a silent partial reorder.
- **R4 Timeline.** The wait's timeline by task type (first sent / last done per type, the depth phase length) is
  measured from Loading Progress's CSV for every verification run (`research/timeline.py`), on and off.

## Acceptance Criteria

- [x] AC1 (R1): unit tests of the ordering code (game-free): tasks of different instances alternate, longer chains
  start earlier, every assigned priority ≤ 4, an empty or single-instance batch is left as the game queued it.
- [ ] AC2 (**not met**) (R1 target): in-game, local nosteam server with the live mod list, compression fix on in both: median of
  ≥ 3 joins with the switch on vs ≥ 3 off: depth phase (first depth task sent → last done, `timeline.py`) ≤ 2.5 s on,
  and the asset wait ≥ 3 s shorter on than off.
- [x] AC3 (R2): in those runs, the same number of depth-map tasks (900) completes with none failed; no new errors in
  `console.txt` (no mod disable, no exception); a screenshot of the same in-world spot on vs off shows the same
  rendering; the largest frame gap of "Loading the world" with the switch on is no larger than off's median + 100 ms.
- [x] AC4 (launch): the launch with the switch on shows no new errors and its total is within the off runs' spread.
- [x] AC5 (R3): the mismatch checks are unit-tested where game-free, and the fix's first use logs what it did
  (instances, tasks reordered) once per batch.
- [x] AC6: `./gradlew :faster-loading:check :faster-loading:assembleMod` and `:faster-loading:smokeTest` pass.

Results (`verification.md`):
- Join right after launch (0.5 s in the menu), medians of 3: depth phase 5.64 → 2.91 s, asset wait 11.67 → 10.21 s
  (−1.46 s), join total 69.5 → 67.6 s. The rest of the phase is the game's own 216-map chain (1.8 s alone, ~2.9 s
  beside three other decodes). AC3/AC4 met: 900/900 depth tasks, no new errors, same frame gaps and screenshot.
- Join after 30 s in the menu: the join's wait has no depth-map (or animation) tasks, on or off; the launch's depth
  maps finish in the menu, ~12 s after it is up with the switch on vs ~16 s off. The join's 900 depth tasks in the
  first series were the launch's backlog, not re-queued by the join (`init` at the join queues 0).
- Decision (user, 2026-10-22): keep `depthMapOrder` in 0.3.0 (helps quick joins and the launch's background
  loading, no harm found); AC2 recorded as not met; lever 1 (prefetch decode) dropped, since a normal join has no
  depth maps left to wait for.

## Out of Scope

- Decoding depth PNGs ahead (`STBImage.stbi_load` prefetch): later, only if depth maps are still the tail.
- Moving work out of the lock or changing what a depth task computes; the render-thread depth uploads.
- The file worker pool size; other task types' order.
- Loading Progress changes (its CSV already has the per-task times the timeline needs).
