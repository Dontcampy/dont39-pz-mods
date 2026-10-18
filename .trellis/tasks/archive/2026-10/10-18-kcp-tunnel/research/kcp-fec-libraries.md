# Research: KCP + FEC libraries for a Kotlin/JVM mod

- **Query**: Best way to get KCP (skywind3000/kcp ARQ) plus Reed-Solomon FEC (kcp-go / kcptun style) into the
  All-in-One mod: Java/Kotlin KCP ports, Java RS libraries, kcp-go FEC wire layout and kcptun presets, effort of
  port vs vendor vs depend+shade.
- **Scope**: mixed (repo build/spec files, game jar, GitHub sources cloned at the dates below)
- **Date**: 2026-10-18

## Constraints from this repo and the game

- Runtime: the client and dedicated server ship **Azul Zulu Java 25.0.1** (archived
  `../../archive/2026-10/10-17-tcp-connection/research/game-internals.md` §7). Any bytecode level up to 25 loads.
- Classpath: game `ProjectZomboid64.json` classpath is `['.', 'projectzomboid.jar']`. The game jar has **no netty**
  and no KCP; it does contain `com/google/zxing/common/reedsolomon/*` (QR-code RS: corrects symbol errors inside one
  codeword, no erasure-position API) - not a packet-erasure codec.
- All mod jars share one class loader, unrelocated (`.trellis/spec/mod/kotlin-runtime.md:31`,
  `.trellis/spec/mod/zb-kt-utils.md:21,32`). A third-party class bundled under its own package could collide with the
  same library in another mod, so anything bundled must live in (or be relocated into) our own package.
- ZombieBuddy loads every class in exactly `javaPkgName` as patch candidates (`kotlin-runtime.md:12-16`), so vendored
  code belongs in a feature subpackage (e.g. `com.dont39.pz.<feature>.kcp`), like `com.dont39.pz.tcptunnel`.
- `kotlin/`/`kotlinx/` classes must never be bundled (`quality-guidelines.md:72`); Kotlin libraries that only
  reference the stdlib are fine, ZabricKotlin provides it.
- `dont39-all-in-one/build.gradle.kts` `tasks.jar` copies only `features.forEach { from(project(it)...main.output) }`
  with `duplicatesStrategy = DuplicatesStrategy.FAIL`; there is no shadow/relocation step today.

## 1. KCP implementations (JVM)

| Repo | License | Last code activity | Core size | Deps | Core usable without networking? | Threading |
|---|---|---|---|---|---|---|
| [skywind3000/kcp](https://github.com/skywind3000/kcp) (reference `ikcp.c`) | MIT | 2026-06-23 | `ikcp.c` 1423 lines (~670 non-comment) + `ikcp.h` 452 | none (C) | yes, pure state machine with `output` callback | caller-confined |
| [jokelbaf/kcp.kt](https://github.com/jokelbaf/kcp.kt) | MIT | 2026-06-22 (2 commits, 3 stars) | `Kcp.kt` 903 lines (~670 non-comment) + `Codec.kt` 61 + `CongestionControl.kt` 32 + `KcpOutput.kt` | **none** (Kotlin Multiplatform, `explicitApi()`, `jvmToolchain(25)`) | yes - it *is* only the core: `send/input/recv/receive/update/check/flush/noDelay/setWndSize/setMtu`, `fun interface KcpOutput { output(buffer, size) }` | "Instances are not thread-safe; confine each Kcp to a single thread" (Kcp.kt:17) |
| [l42111996/java-Kcp](https://github.com/l42111996/java-Kcp) | Apache-2.0 | 2026-08-12, v1.6.2 on Maven Central (`com.github.l42111996:kcp-base`) | `kcp/Kcp.java` 1596 lines | **netty-all 4.1.104**, disruptor, jctools, fastutil, slf4j | no: `Kcp.java` imports `io.netty.buffer.ByteBuf`, `io.netty.util.Recycler`, netty logging | own netty event loop + disruptor/ordered thread pools |
| [szhnet/kcp-netty](https://github.com/szhnet/kcp-netty) | MIT | code 2023-09-27 (repo pushed 2026-07) | `Kcp.java` 1437 lines | netty | no: `ByteBuf`, `ObjectPool` from netty | netty channel |
| [beykery/jkcp](https://github.com/beykery/jkcp) | Apache-2.0 | 2025-08-07 | `Kcp.java` 984 lines | netty-all, slf4j | no: `ByteBuf`, `PooledByteBufAllocator` | own `KcpThread` |
| [hkspirt/kcp-java](https://github.com/hkspirt/kcp-java) | **none** | 2017 | small | none | yes | - (no license: cannot be reused) |
| [zhaodice/grasskcpper](https://github.com/zhaodice/grasskcpper) | Apache-2.0 | 2022-11 | fork of java-Kcp | netty | no | netty |

Notes:
- kcp.kt tracks current upstream `ikcp.c`, including the June 2026 `ccops` congestion-control hooks
  (`Kcp.kt:105,287,302,498,542` vs `ikcp.c:293,571,600`), `fastLimit`/`parseFastack` (IKCP_FASTACK_CONSERVE). It has
  `KcpSimulationTest`, `KcpBasicTest`, `KcpCongestionControlTest`, `CodecTest`. Published as
  `dev.jokelbaf:kcp-jvm:1.0.0` on Maven Central (repo1 returns 200; search.maven.org index does not list it yet).
  Uses Kotlin unsigned types (`UInt` for conv/sn/timestamps), built with Kotlin 2.3.21; our build is 2.4.21.
  Risk: brand-new single-author port with no external users.
- Every established Java port (java-Kcp, kcp-netty, jkcp) has netty `ByteBuf` woven through the core `Kcp` class,
  not just the networking layer; using any of them means netty on the classpath or rewriting buffer handling.
- java-Kcp is the only Java project with FEC; its `kcp-fec` module is a vendored copy of Backblaze's RS
  (`com/backblaze/erasure/*`) plus kcp-go-style `FecEncode/FecDecode` rewritten on netty `ByteBuf` + fastutil.

## 2. Reed-Solomon / FEC on the JVM

| Library | License | State | Size | Deps | Notes |
|---|---|---|---|---|---|
| [Backblaze/JavaReedSolomon](https://github.com/Backblaze/JavaReedSolomon) | MIT | last commit 2025-04-10, 830 stars, stable (algorithm unchanged for years) | core = `ReedSolomon` 343 + `Matrix` 348 + `Galois` 328 + `CodingLoop` 118 + `CodingLoopBase` + one loop impl, ~1270 lines | JDK only | Not on Maven Central (plain `apply plugin: 'java'` build); JitPack serves it. API: `ReedSolomon.create(data, parity)`, `encodeParity(byte[][] shards, offset, byteCount)`, `decodeMissing(byte[][] shards, boolean[] present, offset, byteCount)`; max 256 shards; all shards in a call must be equal length. Default loop `InputOutputByteTableCodingLoop` (fastest in their benchmark, ~525 MB/s on a 2015 storage pod). `CodingLoop.ALL_CODING_LOOPS` references all 12 loop classes, so a minimal vendored copy needs that array removed (or all 12 files). Same Vandermonde construction klauspost/reedsolomon (used by kcp-go) was ported from. |
| java-Kcp `kcp-fec` | Apache-2.0 (+ Backblaze MIT inside) | 2026-08 | Backblaze + ~7 FEC classes | netty, fastutil | Only useful as a reference for a kcp-go-style FEC on Java. |
| Apache Hadoop `RSRawEncoder/Decoder` (hadoop-common) | Apache-2.0 | maintained | huge dependency | hadoop-common | Not reasonable for a mod. |
| zxing `common.reedsolomon` (already in `projectzomboid.jar`) | Apache-2.0 | - | - | - | Error-correcting codeword decoder, no erasure decode across packets; unsuitable. |
| Small GitHub RS repos (`noelsimbolon/reed-solomon`, `BolivarTech/ReedSolomon`, ...) | mixed/none | 1-star, dormant | - | - | Not considered. |

Load is trivial for any of these: a 10+3 set of ~1.4 KB shards is ~14 KB of input per parity computation; game
traffic is at most a few MB/s during map download.

## 3. kcp-go FEC wire layout and kcptun presets

Source: [xtaci/kcp-go](https://github.com/xtaci/kcp-go) `fec.go`, `sess.go` (commit `f3f1bbd`, 2026-05-15, MIT).
kcp-go uses [klauspost/reedsolomon](https://github.com/klauspost/reedsolomon).

**`github.com/xtaci/kcptun` returns 404 since mid-2026** (repo wiped; GitHub shows restorations such as
`dumbybumby/kcptun-archive`, "restoration from last state before wipe-out", and forks like `nonPointer/kcptun`).
Presets below are read from the fork [nonPointer/kcptun](https://github.com/nonPointer/kcptun) (`client/main.go`,
`server/main.go`, `std/config.go`), which pins `kcp-go/v5 v5.6.72`.

### Packet layout (all little-endian)

```
[crypt header: nonce 16B + crc32 4B, only when encryption on]
| FEC seqid u32 | FEC type u16 | size u16 | KCP packet(s) ... |      data shard   (type 0xf1)
| FEC seqid u32 | FEC type u16 | RS parity bytes ...         |      parity shard (type 0xf2)
| 0xffffffff    | 0xf3        | size u16 | payload          |      out-of-band, not FEC-protected
```

- `fecHeaderSize = 6` (seqid + type), `fecHeaderSizePlus2 = 8` (plus the `size` field, which is the length of
  `size`+payload, written before encoding so it is covered by parity) - `fec.go:50-57`, `encode()` `fec.go:~400`.
- RS is computed over the bytes from `size` onward, each data shard zero-padded to the longest one in the set; parity
  shards are sent at `maxSize` length (`fec.go` encode). On recovery the receiver reads `size` from the recovered
  shard to trim the padding (`sess.go:1065-1070`: `kcp.Input(r[2:sz], IKCP_PACKET_FEC, ...)`).
- seqid is a global counter mod `paws = 0xffffffff / shardSize * shardSize`; within a set, `seqid % shardSize <
  dataShards` is data, the rest parity. Group id = `seqid / shardSize` (`getShardId`).
- Encoder emits parity only after `dataShards` data packets, and only if the gap between the last two data packets
  is `< rto` ms; otherwise it skips the parity seqids (`skipParity`) so numbering stays aligned. There is no timer
  flush: a partially filled set never gets parity.
- Data shards go to KCP immediately on arrival (`sess.go:1057-1058`); the decoder only acts when a set has
  `>= dataShards` shards and some data shard is missing, then calls `ReconstructData`.
- Decoder keeps at most `maxShardSets = 3` sets behind the newest and drops older ones; duplicates are filtered by
  seqid; it can auto-tune data/parity counts from the observed data/parity pulse period (`autotune.go`).
- KCP MTU passed to ikcp = UDP MTU - header size (`sess.go:208-224`); `mtuLimit = 1500` buffers.

### kcptun defaults

| Setting | Client | Server |
|---|---|---|
| mode | `fast` | `fast` |
| mtu | 1350 | 1350 |
| sndwnd / rcvwnd | 128 / 512 | 1024 / 1024 |
| datashard / parityshard | 10 / 3 | 10 / 3 |
| acknodelay | false | false |
| sockbuf | 4 MiB | 4 MiB |
| stream mode / write delay | `SetStreamMode(true)`, `SetWriteDelay(false)` | same |

Mode presets `{nodelay, interval, resend, nc}` (`std/config.go:77-80`):

| mode | nodelay | interval ms | resend | nc |
|---|---|---|---|---|
| normal | 0 | 40 | 2 | 1 |
| fast | 0 | 30 | 2 | 1 |
| fast2 | 1 | 20 | 2 | 1 |
| fast3 | 1 | 10 | 2 | 1 |

(`manual` uses the hidden `--nodelay/--interval/--resend/--nc` flags, defaults 0/50/0/0.)
For a datagram relay we would use message mode (stream mode off) so each RakNet datagram stays one KCP message.

## 4. Effort: port vs vendor vs depend+shade

| Option | Work | Build impact | Risk |
|---|---|---|---|
| A. Port `ikcp.c` to Kotlin ourselves | ~670 C statements -> ~800-900 Kotlin lines (kcp.kt shows the shape). Roughly 1.5-3 days including a lossy-link simulation test; subtle parts are unsigned 32-bit wrap (`_itimediff`), fastack, window probe, RTO. | none | our own bugs in well-trodden code |
| B. Vendor kcp.kt (MIT) + Backblaze RS core (MIT) into the feature module, own FEC framing | KCP: copy 4 files, repackage, keep MIT headers, review against `ikcp.c` (half a day). RS: copy ~6 Java files (drop `ALL_CODING_LOOPS`), or convert to Kotlin. FEC encoder/decoder modeled on kcp-go: ~250-350 lines Kotlin. | none: sources compile into the feature's own `main.output`, which `tasks.jar` already bundles | kcp.kt is young, so its tests plus our own loss tests have to stand in for field use |
| C. Depend on `dev.jokelbaf:kcp-jvm:1.0.0` + Backblaze (JitPack) and shade | Same FEC framing work as B. | Add `com.gradleup.shadow` (latest 9.6.1) to All-in-One with `relocate("dev.jokelbaf.kcp", ...)` and `relocate("com.backblaze.erasure", ...)`, replacing or wrapping the current `tasks.jar` (which merges features, classtweakers and `FAIL` duplicates); add JitPack repo for Backblaze. | build-pipeline change for ~2.2k lines of code; relocation must cover both libs or they leak onto the shared loader |
| D. java-Kcp / kcp-netty / jkcp | Would bring netty (+ disruptor/jctools/fastutil for java-Kcp) and shading of netty | large jar, netty on a shared loader unless relocated | rejected by the brief (no netty) |

## Recommendation

**Option B: vendor kcp.kt (core ARQ) and the Backblaze RS core into the KCP feature's own subpackage, and write our
own kcp-go-style FEC layer (seqid u32 / type u16 / size u16, 10+3, parity only on a full set) in Kotlin.**

Trade-offs:
- + No new Gradle plugin, no relocation, no repository additions; the existing All-in-One jar pipeline and its
  shared-class-loader rules stay as they are. No netty.
- + Both sources are MIT, dependency-free, small (~1k + ~1.3k lines), and already match what kcp-go does on the
  wire (ikcp segment format, Vandermonde RS), so kcp-go's FEC code can be followed directly.
- + KCP is used purely as a state machine (`input/send/recv/update/check` + `output` callback), which fits one
  server UDP socket demultiplexing by conv/address to per-player `Kcp` instances confined to one thread.
- - We own the code: no upstream bug fixes arrive automatically. kcp.kt in particular has no field record (2 commits,
  3 stars), so it needs a line-by-line review against `ikcp.c` and a loss/reorder simulation test before shipping.
- - MIT notices must travel with the vendored files.
- - If kcp.kt's review finds problems, the fallback is option A, with kcp.kt and `ikcp.c` side by side as the
  reference; the FEC and RS parts are unaffected.

## Caveats / Not Found

- kcptun's canonical repo is gone; presets were taken from a fork that claims to mirror the last upstream state.
- The kcp.kt review against `ikcp.c` was a spot check (API surface, ccops, fastack), not a full diff.
- Backblaze RS speed figures are the project's own 2015 benchmark, not measured on Java 25.
- Linux server `jre64` version was not checked in the archived research (Windows only).
- `com.backblaze` groupId on Maven Central: not published (only JitPack).
