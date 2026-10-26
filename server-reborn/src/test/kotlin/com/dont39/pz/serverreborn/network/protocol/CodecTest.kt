package com.dont39.pz.serverreborn.network.protocol

import com.dont39.pz.serverreborn.network.protocol.Message.Bye
import com.dont39.pz.serverreborn.network.protocol.Message.Hello
import com.dont39.pz.serverreborn.network.protocol.Message.OpenCheck
import com.dont39.pz.serverreborn.network.protocol.Message.Snapshot
import com.dont39.pz.serverreborn.network.protocol.Message.SnapshotRequest
import com.dont39.pz.serverreborn.network.protocol.Message.SyncFrame
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.assertThrows
import java.nio.ByteBuffer

class CodecTest {
  private val player = WireKey.container(byteArrayOf(7, 0, 12, -1))
  private val item = WireKey.item(byteArrayOf(3, 0, 0, 1, 2, 0, 0, 0, 4, 5), -123456789)

  // an object container's id: type, x, y, z, object index, container index
  private val crate = byteArrayOf(4, 0, 0, 0, 1, 0, 0, 0, 2, 0, 0, 3, 0, 1)

  private fun encode(m: Message): ByteBuffer = ByteBuffer.allocate(4096).also { Codec.encode(m, it) }.flip()

  private fun nestedMessages(bags: List<Int>): List<Pair<String, Message>> {
    val nested = ContainerWire.nested(crate, bags)
    val container = WireKey.container(nested)
    val item = WireKey.item(nested, 99)
    return listOf(
      "frame of nested keys $bags" to
        SyncFrame(3, listOf(SyncFrame.Entry(container, false, 1, true), SyncFrame.Entry(item, true, 0))),
      "request of a nested key $bags" to SnapshotRequest(listOf(item)),
      "open check of a nested key $bags" to OpenCheck(container, 5),
    )
  }

  @TestFactory
  fun `messages round trip`() = listOf(
    "hello" to Hello("0.1.0"),
    "frame" to SyncFrame(
      42,
      listOf(
        SyncFrame.Entry(player, false, 0x0123_4567_89AB_CDEFL),
        SyncFrame.Entry(item, true, 0),
        SyncFrame.Entry(item, false, -1),
        SyncFrame.Entry(player, false, 7, true),
        SyncFrame.Entry(player, true, 0, true),
      ),
    ),
    "empty frame" to SyncFrame(1, emptyList()),
    "snapshot request" to SnapshotRequest(listOf(player, item)),
    "open check" to OpenCheck(item, Long.MIN_VALUE),
    "bye" to Bye,
  ).plus(nestedMessages(listOf(55))).plus(nestedMessages(listOf(55, -66, 77))).map { (name, message) ->
    DynamicTest.dynamicTest(name) { assertEquals(message, Codec.decode(encode(message))) }
  }

  @Test
  fun `a frame's size is the sum of its entries' sizes and bye has no body`() {
    val frame = SyncFrame(
      42,
      listOf(SyncFrame.Entry(player, false, 1), SyncFrame.Entry(item, true, 0), SyncFrame.Entry(item, false, -1, true)),
    )
    assertEquals(2 + Long.SIZE_BYTES + Int.SIZE_BYTES + frame.entries.sumOf(Codec::size), encode(frame).remaining())
    assertEquals(2, encode(Bye).remaining())
  }

  @Test
  fun `a frame entry's changed flag is its own bit and an unknown one is refused`() {
    val b = encode(SyncFrame(1, listOf(SyncFrame.Entry(player, false, 5, true))))
    val flags = b.limit() - Long.SIZE_BYTES - 1
    assertEquals(Codec.CHANGED.toByte(), b.get(flags))
    b.put(flags, 4)
    assertTrue(assertThrows<ProtocolException> { Codec.decode(b) }.message!!.contains("flags"))
  }

  @Test
  fun `asChanged keeps the rest`() {
    val e = SyncFrame.Entry(item, false, 9)
    assertFalse(e.changed)
    assertEquals(SyncFrame.Entry(item, false, 9, true), e.asChanged())
  }

  @Test
  fun `nested keys in messages decode to their chain`() {
    listOf(listOf(55), listOf(55, -66, 77)).forEach { bags ->
      val nested = ContainerWire.nested(crate, bags)
      val frame = SyncFrame(3, listOf(SyncFrame.Entry(WireKey.item(nested, 99), true, 0)))
      val read = assertInstanceOf<SyncFrame>(Codec.decode(encode(frame)))
      assertEquals(
        ContainerKey.nested(ContainerKey.objectContainer(1, 2, 0, 3, 1), bags),
        ContainerWire.decode(read.entries[0].key.containerBytes),
      )
    }
  }

  @Test
  fun `a snapshot with items`() {
    val out = ByteBuffer.allocate(4096)
    SnapshotWriter.start(out, player, 9, Snapshot.State.PRESENT, 77)
      .add(payload("add"))
      .item(1, 11, payload("fields1"), null, payload("stats1"), null)
      .item(2, -22, payload("fields2"), payload("mod2"), payload("stats2"), payload("weapon2"))
      .finish()
    val s = assertInstanceOf<Snapshot>(Codec.decode(out.flip()))
    assertEquals(player, s.key)
    assertEquals(9, s.version)
    assertEquals(Snapshot.State.PRESENT, s.state)
    assertEquals(77, s.digest)
    assertArrayEquals(bytes("add"), s.add)
    assertEquals(2, s.items.size)
    val (first, second) = s.items
    assertEquals(1, first.id)
    assertEquals(11, first.digest)
    assertArrayEquals(bytes("fields1"), first.fields)
    assertEquals(0, first.modData.size)
    assertArrayEquals(bytes("stats1"), first.stats)
    assertEquals(0, first.weapon.size)
    assertEquals(2, second.id)
    assertEquals(-22, second.digest)
    assertArrayEquals(bytes("fields2"), second.fields)
    assertArrayEquals(bytes("mod2"), second.modData)
    assertArrayEquals(bytes("stats2"), second.stats)
    assertArrayEquals(bytes("weapon2"), second.weapon)
  }

  @Test
  fun `a gone snapshot`() {
    val out = ByteBuffer.allocate(256)
    SnapshotWriter.start(out, item, 3, Snapshot.State.GONE, 0).add(null).finish()
    val s = assertInstanceOf<Snapshot>(Codec.decode(out.flip()))
    assertEquals(item, s.key)
    assertEquals(Snapshot.State.GONE, s.state)
    assertEquals(0, s.add.size)
    assertTrue(s.items.isEmpty())
  }

  @Test
  fun `the snapshot writer keeps its order`() {
    val w = SnapshotWriter.start(ByteBuffer.allocate(256), item, 3, Snapshot.State.PRESENT, 0)
    assertThrows<IllegalStateException> { w.item(1, 0, null, null, null, null) }
    assertThrows<IllegalStateException> { w.finish() }
    w.add(null)
    assertThrows<IllegalStateException> { w.add(null) }
  }

  @Test
  fun `snapshots have their own writer`() {
    val s = Snapshot(item, 1, Snapshot.State.GONE, 0, ByteArray(0), emptyList())
    assertThrows<IllegalArgumentException> { Codec.encode(s, ByteBuffer.allocate(256)) }
  }

  @Test
  fun `wire keys compare by content`() {
    assertEquals(WireKey.container(byteArrayOf(1, 2)), WireKey.container(byteArrayOf(1, 2)))
    assertEquals(WireKey.container(byteArrayOf(1, 2)).hashCode(), WireKey.container(byteArrayOf(1, 2)).hashCode())
    assertFalse(WireKey.container(byteArrayOf(1, 2)) == WireKey.item(byteArrayOf(1, 2), 0))
    assertThrows<IllegalArgumentException> { WireKey.container(ByteArray(0)) }
  }

  /** Bytes that are not one whole message, with a part of the expected reason (null: any). */
  @TestFactory
  fun `refused messages`(): List<DynamicTest> {
    val otherVersion = encode(Hello("x")).also { it.put(0, (Protocol.VERSION + 1).toByte()) }
    val openCheck = encode(OpenCheck(player, 1))
    val trailing = ByteBuffer.allocate(openCheck.remaining() + 1).put(openCheck).put(0).flip()
    val hugeCount = ByteBuffer.allocate(16).put(Protocol.VERSION.toByte()).put(Codec.SNAPSHOT_REQUEST)
      .putInt(Int.MAX_VALUE).flip()
    val whole = encode(SyncFrame(5, listOf(SyncFrame.Entry(item, false, 8))))
    val cuts = (0 until whole.remaining()).map { length ->
      Triple("frame cut at $length", whole.duplicate().limit(length), null)
    }
    return listOf(
      Triple("bye with a body", ByteBuffer.wrap(byteArrayOf(Protocol.VERSION.toByte(), Codec.BYE, 0)), "after"),
      Triple("another protocol version", otherVersion, "protocol version"),
      Triple("an unknown kind", ByteBuffer.wrap(byteArrayOf(Protocol.VERSION.toByte(), 99)), "kind"),
      Triple("trailing bytes", trailing, "after"),
      Triple("a huge count, which does not allocate", hugeCount, "count"),
    ).plus(cuts).map { (name, bytes, reason) ->
      DynamicTest.dynamicTest(name) {
        val e = assertThrows<ProtocolException> { Codec.decode(bytes) }
        if (reason != null) {
          assertTrue(e.message!!.contains(reason), e.message)
        }
      }
    }
  }

  private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

  private fun payload(s: String): Payload = { it.put(bytes(s)) }
}
