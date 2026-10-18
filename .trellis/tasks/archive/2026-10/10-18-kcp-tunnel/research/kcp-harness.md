# Harness: KCP + FEC core under loss (implement step 6, AC7)

- **Date**: 2026-10-18
- **Code under test**: the scratch project `kcp-core` (session scratchpad, outside the repo per D9). Its production
  sources are the ones copied into the `tunnel` module: `com.dont39.pz.tunnel.kcp.Kcp` (port of `ikcp.c` at
  `b1a7a21`), `com.dont39.pz.tunnel.fec.rs` (Backblaze JavaReedSolomon at `d3c481d`), `com.dont39.pz.tunnel.fec`
  (`Fec`, `FecEncoder`, `FecDecoder`).
- **Result**: every run delivered every message exactly once, in order and intact. The stop rule passes: at 10 % loss,
  steady-stream p99 is ~62 ms with parity 3 against ~243 ms with parity 0.

## Setup

The harness is a discrete-event simulation in virtual microseconds. It is deterministic per seed, so rows compare
like for like. It wires two ends (client, server) the way the transport will:

- KCP output goes to `FecEncoder.encode`. Datagrams carry a 4-byte transport header (conv) in the encoder's headroom.
- An arriving datagram goes to `FecDecoder.decode`, then to `Kcp.input`, then to `Kcp.receive` until it returns
  nothing.
- Every 10 ms each end ticks: `Kcp.update`, then `FecEncoder.flushDue`.
- Each application message goes to `Kcp.send`, then `Kcp.flush(now)` right away.

Parameters, the design's defaults: interval 10, resend 2, no congestion window, window 1024, datagram MTU 1350 (KCP
MTU = 1350 - 4 - `Fec.overhead(parity)`), 10 data shards, 3 or 0 parity shards, flush 20 ms. KCP nodelay is on.

Link model, the same each way:

- 30 ms one-way delay plus 0-4 ms uniform jitter. The jitter reorders some packets.
- 10 Mbit/s (1.25 MB/s) bottleneck. A lost datagram still uses up the bandwidth.
- Independent random loss each way, after the bottleneck.

Workload, 60 s:

- Both directions send a steady 25 msg/s of 100-300 B.
- Every 5 s the server sends a 200 KB burst as 1400 B messages (each 2 KCP fragments), paced at 500 KB/s the way
  RakNet paces its own sends.
- Each message carries a sequence number, a send time and a CRC32. The receiver checks order, uniqueness and CRC, and
  records the one-way latency (send to `Kcp.receive`).

Self checks (`selfCheck`) also pass:

- RS rebuilds 500 random erasure sets (k ≤ 64, p ≤ 32, up to p shards erased).
- A part-filled FEC group gets its parity exactly at the flush time with k = 3, and two lost packets of different
  lengths come back byte for byte. A late original counts as a duplicate.
- A full group closes at once. Parity 0 sends data only.
- Malformed datagrams are rejected and counted.
- KCP delivers fragmented messages of 0-5000 B in order.

## Results (paced bursts, seeds 1-3)

"Steady" is the 25 msg/s stream in both directions; "burst" is the burst messages server to client. Counters are the
sum of both ends. Wire MB counts both directions including 28 B of IP/UDP headers per datagram.

| loss | parity | seed | in order, once, intact | steady p50 ms | steady p99 ms | steady max ms | burst p50 ms | burst p99 ms | FEC recovered | FEC unrecoverable | KCP timeout rtx | KCP fast rtx | wire MB |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 0 % | 3 | 1 | yes | 32.3 | 34.6 | 39.1 | 34.6 | 38.2 | 725 | 0 | 139 | 4 | 7.6 |
| 0 % | 3 | 2 | yes | 32.3 | 34.6 | 37.6 | 34.6 | 38.3 | 731 | 0 | 134 | 4 | 7.6 |
| 0 % | 3 | 3 | yes | 32.3 | 34.9 | 38.6 | 34.5 | 38.0 | 702 | 0 | 118 | 4 | 7.5 |
| 0 % | 0 | 1 | yes | 32.3 | 34.2 | 35.0 | 34.0 | 35.2 | 0 | 0 | 103 | 1 | 3.6 |
| 0 % | 0 | 2 | yes | 32.3 | 34.2 | 35.9 | 34.0 | 35.2 | 0 | 0 | 113 | 0 | 3.6 |
| 0 % | 0 | 3 | yes | 32.3 | 34.2 | 35.2 | 34.1 | 35.2 | 0 | 0 | 91 | 1 | 3.5 |
| 10 % | 3 | 1 | yes | 32.6 | 61.7 | 116.5 | 36.9 | 113.7 | 1537 | 13 | 597 | 26 | 8.1 |
| 10 % | 3 | 2 | yes | 32.6 | 62.9 | 152.5 | 37.2 | 116.1 | 1572 | 15 | 580 | 43 | 8.1 |
| 10 % | 3 | 3 | yes | 32.6 | 62.9 | 194.1 | 37.3 | 166.7 | 1547 | 16 | 620 | 42 | 8.1 |
| 10 % | 0 | 1 | yes | 33.1 | 234.2 | 390.5 | 108.8 | 228.2 | 0 | 0 | 1218 | 160 | 4.3 |
| 10 % | 0 | 2 | yes | 33.0 | 263.6 | 810.9 | 110.8 | 335.5 | 0 | 0 | 1300 | 161 | 4.3 |
| 10 % | 0 | 3 | yes | 33.0 | 231.8 | 393.5 | 108.8 | 259.4 | 0 | 0 | 1252 | 151 | 4.2 |
| 20 % | 3 | 1 | yes | 32.8 | 145.2 | 285.7 | 107.3 | 272.2 | 2079 | 127 | 992 | 196 | 8.7 |
| 20 % | 3 | 2 | yes | 32.8 | 134.2 | 214.0 | 105.2 | 204.3 | 2060 | 118 | 1047 | 198 | 8.8 |
| 20 % | 3 | 3 | yes | 32.9 | 156.9 | 305.6 | 110.0 | 287.6 | 2100 | 128 | 1032 | 188 | 8.7 |
| 20 % | 0 | 1 | yes | 39.8 | 426.2 | 671.7 | 191.5 | 412.6 | 0 | 0 | 2326 | 539 | 5.1 |
| 20 % | 0 | 2 | yes | 34.2 | 403.2 | 683.2 | 188.1 | 447.2 | 0 | 0 | 2496 | 597 | 5.2 |
| 20 % | 0 | 3 | yes | 34.0 | 394.2 | 673.1 | 188.7 | 634.9 | 0 | 0 | 2383 | 589 | 5.1 |

Summary of steady-stream p99 (mean of 3 seeds):

| loss | parity 3 | parity 0 |
|---|---|---|
| 0 % | 34.7 ms | 34.2 ms |
| 10 % | 62.5 ms | 243 ms |
| 20 % | 145 ms | 408 ms |

## Stress: unpaced bursts (whole 200 KB at once)

| loss | parity | seed | in order, once, intact | steady p50 ms | steady p99 ms | steady max ms | burst p50 ms | burst p99 ms | FEC recovered | FEC unrecoverable | KCP timeout rtx | KCP fast rtx | wire MB |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 0 % | 3 | 1 | yes | 32.4 | 423.5 | 479.6 | 166.0 | 296.5 | 1191 | 0 | 4884 | 0 | 13.2 |
| 0 % | 3 | 2 | yes | 32.4 | 423.6 | 482.7 | 165.9 | 296.6 | 1156 | 0 | 4886 | 0 | 13.2 |
| 0 % | 3 | 3 | yes | 32.3 | 423.4 | 480.2 | 166.2 | 297.0 | 1143 | 0 | 4842 | 0 | 13.1 |
| 0 % | 0 | 1 | yes | 32.3 | 186.6 | 269.6 | 119.9 | 204.2 | 0 | 0 | 3729 | 0 | 6.3 |
| 0 % | 0 | 2 | yes | 32.3 | 185.8 | 271.5 | 119.5 | 204.3 | 0 | 0 | 3695 | 0 | 6.3 |
| 0 % | 0 | 3 | yes | 32.3 | 185.6 | 269.4 | 119.9 | 203.9 | 0 | 0 | 3720 | 0 | 6.3 |
| 10 % | 3 | 1 | yes | 32.6 | 418.0 | 474.3 | 211.1 | 445.8 | 2069 | 28 | 4664 | 1 | 12.8 |
| 10 % | 3 | 2 | yes | 32.6 | 421.0 | 500.4 | 273.0 | 521.4 | 2178 | 25 | 4817 | 3 | 13.0 |
| 10 % | 3 | 3 | yes | 32.6 | 425.6 | 591.5 | 286.1 | 612.5 | 2160 | 24 | 4892 | 0 | 13.1 |
| 10 % | 0 | 1 | yes | 33.2 | 358.2 | 544.2 | 375.0 | 465.9 | 0 | 0 | 4980 | 76 | 7.1 |
| 10 % | 0 | 2 | yes | 33.1 | 342.8 | 503.3 | 380.5 | 443.8 | 0 | 0 | 5130 | 58 | 7.1 |
| 10 % | 0 | 3 | yes | 33.0 | 325.3 | 465.4 | 380.3 | 457.4 | 0 | 0 | 4956 | 77 | 7.0 |

## Findings

- **FEC works at low packet rates.** At 10 % loss most losses are rebuilt within one flush window (20-30 ms) instead of
  waiting for a KCP retransmit (RTO ≥ RTT + 10 ms). That is where the p99 gap comes from. Parity 3 also halves the
  KCP retransmits.
- **Bandwidth cost is higher than parity/data.** Groups that the timer closes at k = 1-2 still get 3 parity shards,
  and acks and the steady stream mostly travel in such groups. On this workload, parity 3 doubles the wire bytes (7.6
  vs 3.6 MB at 0 % loss), not +30 %. The Workshop text should say KCP can use several times the bandwidth of a quiet
  connection.
- **A burst above the link rate causes spurious retransmits.** With no congestion window (`KcpNoCongestion = true`),
  a burst queues longer than KCP's RTO, so the whole burst is resent once. FEC parity lengthens the queue further, so
  parity 3 is worse than parity 0 in that case. RakNet paces its own sends, which is why the main table paces bursts.
  If players report rubber-banding during map download on slow links, look here first. Options would be pacing in the
  transport or `KcpNoCongestion = false`, but random loss would then collapse KCP's window.
- **One change from `ikcp.c` came from the harness.** `Kcp.input` and `Kcp.flush` take the current time, as kcp-go
  does. With ikcp's clock from the last `update`, RTT samples and resend times were off by up to one interval. At 0 %
  loss that caused ~1000 spurious timeout retransmits per run; with the change it is ~120.
- At 0 % loss, "FEC recovered" counts packets rebuilt before their reordered original arrived (the 0-4 ms jitter is
  larger than the ~1 ms serialization gap between packets). The original then counts as a duplicate and is not fed to
  KCP again.
- The ~100 timeout retransmits left at 0 % loss are KCP's own: nodelay RTO = srtt + max(interval, 4·rttval) is
  tighter than the jitter plus the up-to-10 ms ack delay.

## Rerun

The project needs JDK 25 and uses the repo's Gradle wrapper (9.8.0) and Kotlin 2.4.21:

```bash
cd <scratchpad>/kcp-core
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew -q selfCheck
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew -q lossHarness                                  # main table
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew -q lossHarness -PharnessArgs="--loss 0,10 --burstRate 0"  # stress
```

`-PharnessArgs` accepts `--seconds N`, `--seeds N`, `--loss a,b,c` (percent) and `--burstRate bytes/s` (0 =
unpaced). Link and workload defaults are in `LossHarness.kt` (`LinkModel`, `Workload`). A full run takes about 2 s.
