package com.dont39.pz.tunnel

import com.dont39.pz.tunnel.fec.Fec
import com.dont39.pz.tunnel.fec.FecDecoder
import com.dont39.pz.tunnel.fec.FecEncoder
import com.dont39.pz.tunnel.kcp.Kcp
import com.dont39.pz.tunnel.transport.kcp.KcpWire
import java.nio.ByteBuffer
import java.util.PriorityQueue
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * KCP and FEC wired as `KcpSession` wires them, over a simulated lossy link in virtual time, so a run is deterministic
 * per seed and takes milliseconds. Ported from the kcp-tunnel task's loss harness, without its bandwidth limit and
 * latency figures, with smaller bursts.
 */
class KcpFecLossTest {
  private companion object {
    const val DATA_SHARDS = 10
    const val INTERVAL = 10
    const val FLUSH = 20
    const val MTU = 1350
    const val WINDOW = 1024
    const val DELAY = 30
    const val JITTER = 4
    const val WORKLOAD_MILLIS = 60_000
    const val DRAIN_MILLIS = 30_000
    const val STEADY_EVERY = 40
    const val BURST_EVERY = 5_000
    const val BURST_MESSAGES = 40
    const val BURST_SIZE = 1400

    /** Message [seq] of [size] bytes: the sequence number, then bytes that follow from it. */
    fun message(seq: Int, size: Int): ByteArray {
      val bytes = Random(seq).nextBytes(size)
      ByteBuffer.wrap(bytes).putInt(seq)
      return bytes
    }
  }

  private class Arrival(val at: Long, val order: Long, val to: End, val datagram: ByteArray)

  private class Link(val loss: Double, val random: Random) {
    val queue = PriorityQueue(compareBy<Arrival>({ it.at }, { it.order }))
    private var order = 0L

    fun send(now: Long, to: End, datagram: ByteArray) {
      if (random.nextDouble() < loss) return
      queue += Arrival(now + DELAY + random.nextInt(JITTER + 1), order++, to, datagram)
    }
  }

  /** One end: its received messages by sequence, checked as they arrive. */
  private class End(val name: String, conv: Int, parity: Int) {
    var now = 0L
    lateinit var out: (ByteArray) -> Unit
    var nextExpected = 0
    private val encoder = FecEncoder(DATA_SHARDS, parity, FLUSH, KcpWire.CONV_SIZE) {
      KcpWire.writeConv(it, conv)
      out(it)
    }
    private val kcp = Kcp(conv) { buffer, length -> encoder.encode(buffer, 0, length, now) }.apply {
      setNoDelay(1, INTERVAL, 2, true)
      setWindow(WINDOW, WINDOW)
      setMtu(MTU - KcpWire.CONV_SIZE - Fec.overhead(parity))
    }
    val decoder = FecDecoder(DATA_SHARDS, parity) { buffer, offset, length -> kcp.input(buffer, offset, length, now) }

    fun send(message: ByteArray) {
      kcp.send(message)
      kcp.flush(now)
    }

    fun tick() {
      kcp.update(now)
      encoder.flushDue(now)
    }

    fun input(datagram: ByteArray) {
      decoder.decode(datagram, KcpWire.CONV_SIZE, datagram.size - KcpWire.CONV_SIZE, now)
      while (true) {
        val message = kcp.receive() ?: return
        val seq = ByteBuffer.wrap(message).int
        assertEquals(nextExpected, seq) { "$name received message $seq out of order" }
        assertArrayEquals(message(seq, message.size), message) { "$name received message $seq corrupted" }
        nextExpected++
      }
    }
  }

  @ParameterizedTest(name = "loss {0}, parity {1}")
  @CsvSource("0.10, 3", "0.10, 0", "0.20, 3", "0.20, 0")
  fun `every message arrives once, in order and intact`(loss: Double, parity: Int) {
    val random = Random(1)
    val toServer = Link(loss, random)
    val toClient = Link(loss, random)
    val client = End("client", 7, parity)
    val server = End("server", 7, parity)
    client.out = { toServer.send(client.now, server, it) }
    server.out = { toClient.send(server.now, client, it) }

    var clientSent = 0
    var serverSent = 0
    for (now in 0L..WORKLOAD_MILLIS + DRAIN_MILLIS) {
      client.now = now
      server.now = now
      for (link in listOf(toServer, toClient)) {
        while (link.queue.isNotEmpty() && link.queue.peek().at <= now) {
          link.queue.poll().let { it.to.input(it.datagram) }
        }
      }
      if (now % INTERVAL == 0L) {
        client.tick()
        server.tick()
      }
      if (now < WORKLOAD_MILLIS) {
        if (now % STEADY_EVERY == 0L) {
          client.send(message(clientSent++, 100 + random.nextInt(201)))
          server.send(message(serverSent++, 100 + random.nextInt(201)))
        }
        // larger than the KCP MTU, so each is fragmented
        if (now % BURST_EVERY == 0L) {
          repeat(BURST_MESSAGES) { server.send(message(serverSent++, BURST_SIZE)) }
        }
      }
      if (now >= WORKLOAD_MILLIS && server.nextExpected == clientSent && client.nextExpected == serverSent) break
    }

    assertEquals(clientSent, server.nextExpected, "messages the server received")
    assertEquals(serverSent, client.nextExpected, "messages the client received")
    for (end in listOf(client, server)) {
      val decoder = end.decoder
      if (parity == 0) {
        assertEquals(0, decoder.recovered, "${end.name}: rebuilt without parity")
      } else {
        assertTrue(decoder.recovered > 0, "${end.name}: FEC rebuilt nothing")
      }
      // seqMissing / seqExpected is the link's inbound loss, with FEC on or off
      val measured = decoder.seqMissing.toDouble() / decoder.seqExpected
      assertEquals(loss, measured, 0.03, "${end.name}: inbound loss")
    }
  }
}
