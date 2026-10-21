package com.dont39.pz.modsync.client.rejoin

import com.dont39.pz.common.writeAtomically
import com.dont39.pz.modsync.protocol.syncJson
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable

/**
 * The server a join stopped for a restart, in `rejoin.json` under [folder]: one record, the last restart wins.
 * [written] is the start time of the launch that wrote it, which does not offer it.
 */
internal class RejoinStore(private val folder: Path) {
  data class Rejoin(val host: String, val port: Int, val user: String, val name: String, val written: Long)

  @Serializable
  private data class Record(
    val format: Int,
    val host: String,
    val port: Int,
    val user: String,
    val name: String,
    val written: Long,
  )

  private val path: Path
    get() = folder.resolve(FILE_NAME)

  @Synchronized
  fun write(rejoin: Rejoin) {
    val record = Record(FORMAT, rejoin.host, rejoin.port, rejoin.user, rejoin.name, rejoin.written)
    writeAtomically(path) { temp -> Files.writeString(temp, syncJson.encodeToString(Record.serializer(), record)) }
  }

  /** Null when there is none. */
  @Synchronized
  fun read(): Rejoin? {
    if (!Files.exists(path)) {
      return null
    }
    val record = try {
      syncJson.decodeFromString(Record.serializer(), Files.readString(path))
    } catch (e: SerializationException) {
      throw IllegalStateException("$path is not a server to rejoin: ${e.message}", e)
    }
    check(record.format == FORMAT) { "$path has format ${record.format}; this version of the mod reads $FORMAT" }
    return Rejoin(record.host, record.port, record.user, record.name, record.written)
  }

  @Synchronized
  fun drop() {
    Files.deleteIfExists(path)
  }

  private companion object {
    const val FILE_NAME = "rejoin.json"
    const val FORMAT = 1
  }
}
