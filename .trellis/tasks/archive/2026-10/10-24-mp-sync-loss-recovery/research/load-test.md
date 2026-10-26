# AC7 load test: Sync Guard off vs on

- **Scenario** (same script both runs, `ac7.ps1` in the session scratchpad, built from the harness scripts):
  `setup-server.ps1` with or without `-WithSyncGuard`; server start (`-Dsyncguard.summarySeconds=10`); client joins
  as admin, god mode, invisible, teleports to the Rosewood prison spot (7560,11815,0); sampler from arrival
  (`sampler.ps1`, 5 s); `removezombies -x 7560 -y 11815 -z 0 -radius 150` (zombies of earlier runs persist in the
  save), then `createhorde2` 1000 at once and 500 more at +120 s and +240 s; driver loot loop 600 s (take, put back,
  move to another container, eat now and then, each container shown in the loot window); server heap with
  `jcmd <pid> GC.heap_info` every 5 s.
- **Runs**: `load-off4`, `load-on4` (`%TEMP%\sgtest\runs\`); table by `summarize.ps1 -SkipFirstSec 30`.
- **Machine**: 16 logical cores; server JVM ZGC `-Xmx16g`.
- **Date**: 2026-10-24.

## Same work

| | off | on |
|---|---:|---:|
| Driver cycles / taken / put back / moved / eaten | 46 / 84 / 48 / 39 / 2 | 44 / 75 / 48 / 31 / 0 |
| Zombies the client had loaded, first and last progress line | 1,179 -> 2,176 | 1,177 -> 2,187 |

## Off vs on

| Metric | Mod off | Mod on | On - off |
|---|---:|---:|---:|
| Server CPU % of machine, mean (x16 for one core) | 0.60 | 0.58 | -0.02 |
| Server CPU % of machine, p95 | 0.94 | 0.77 | -0.17 |
| Server working set MB, mean | 14,784 | 14,509 | -275 |
| Server working set MB, max | 16,190 | 16,278 | +87 |
| Server private bytes MB, max | 1,480 | 1,474 | -6 |
| JVM heap used MB (jcmd), mean | 3,985 | 3,344 | -641 |
| JVM heap used MB (jcmd), max | 6,198 | 4,824 | -1,374 |
| Main loop update period ms, mean of avg | 4.61 | 4.61 | 0.00 |
| Server out B/s on the wire, mean | 10,832 | 8,879 | -1,953 |
| Server out B/s on the wire, p95 | 43,341 | 16,727 | -26,614 |
| Server in B/s on the wire, mean | 3,800 | 3,657 | -143 |
| Server in B/s on the wire, p95 | 6,562 | 5,000 | -1,562 |
| Vanilla packet bytes sent per 1-s stats period, mean | 7,429 | 4,814 | -2,615 |
| Vanilla packet bytes received per 1-s stats period, mean | 3,766 | 3,755 | -11 |
| Client CPU % of machine, mean | 12.31 | 12.67 | +0.36 |
| Client working set MB, max | 23,497 | 23,629 | +132 |

The differences are within run-to-run noise: zombie traffic dominates the wire (the off run's p95 is a burst of zombie
updates), ZGC's heap in use swings by gigabytes between collections, and the server stays under 1% of the machine
either way. `stats performance all` gave no reply over RCON on this server, so the sampler's heap and update-max
columns are empty; jcmd replaced the heap column.

## Sync Guard's own figures (on run)

| What | Value |
|---|---|
| Own bytes sent (`bytesOut`, frames and snapshots) | 9,861 B in the run, 14 B/s = 0.16% of the server's wire output |
| Frames / entries / of them change-recorded (D7) / gone | 152 / 305 / 296 / 2 |
| Checks / mismatches on the server (open checks) | 176 / 1 |
| Snapshots sent; most in one tick | 1; 1 (budget 8 per tick and 64 KiB, never reached) |
| Per-connection state (`stateEntries`) | max 49, 0 after the client left |
| Client | `frames=152 entries=305 checks=126 mismatches=0 snapshotsApplied=1 repaired=0 unrepairable=0 openChecks=176 deferred=296 stuckItems=0` |
| Errors | no new signature on either side; no duplicate-id line in this run |

The one snapshot answered an open check (`container 0 of object 2 at 7566,11830,0`) and changed nothing on the client:
a container shown while a transfer into it was in flight (`digest-tuning.md`, item-list row).

### End-of-tick phase

`tickNsP99` per 10-s window (100 ticks), power-of-two microsecond buckets, a bucket bound capped at the window's
maximum (`stats/Timings.java`), 63 windows with the client connected:

| Windows | p99 bucket bound |
|---|---|
| 33 | at most 256 us |
| 26 | at most 512 us |
| 2 | at most 1.024 ms (window maxima 1.84 and 2.99 ms) |
| 2, the join (86 and 91 ticks; the first frames cover the whole inventory) | 1.41 ms and 5.11 ms (= the window maxima) |

Only four windows have any tick over 1 ms (their maxima 1.41, 5.11, 1.84 and 2.99 ms); in the two outside the join
at most one tick each. So after the join every window's p99 is at most the 1.024-ms bucket bound, 59 of 61 at most
512 us: AC7's target (under 1 ms) holds in steady play, with the two 1.024-ms windows at the limit of the buckets'
resolution. The join's two windows exceed it (p50 1-4 us there, so the slow ticks are a few at the first frames);
the buckets cannot say how many, so a run-wide p99 is not claimed. Before the join, windows are 5-8 us.

## Earlier attempts (not used)

- `load-off`, `load-off2`: the driver took nothing; the containers next to the arrival square had been emptied by
  the tests, and the loop picked only among the first scan's containers. The loop now picks containers that still
  hold light items and widens the scan when none does.
- `load-off3`, `load-on3`: comparable work (63 vs 67 taken) but not comparable zombies (client counted 3,762-4,760
  off, 5,673-6,502 on): `removezombies ... -remove true` only marks the calling connection for removal, and RCON has
  none, so nothing was removed and each run's hordes stayed in the save. Their table is in
  `%TEMP%\sgtest\load3-table.md`; Sync Guard's own share there was 23 B/s (0.12%), tick p99 at most 512 us after the
  join.
