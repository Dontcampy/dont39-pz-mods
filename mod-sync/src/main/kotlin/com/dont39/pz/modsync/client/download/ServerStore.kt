package com.dont39.pz.modsync.client.download

import com.dont39.pz.common.serverKey
import com.dont39.pz.common.writeAtomically
import com.dont39.pz.modsync.protocol.syncJson
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable

/**
 * Servers the player let download without asking again, in `servers.json` under [folder], by `<host>:<game port>` as
 * typed: the join only sees the address.
 */
internal class ServerStore(private val folder: Path) {
  @Serializable
  private data class Servers(val format: Int, val servers: List<String>)

  private val path: Path
    get() = folder.resolve(FILE_NAME)

  @Synchronized
  fun remembered(host: String, port: Int): Boolean = serverKey(host, port) in read()

  @Synchronized
  fun remember(host: String, port: Int) {
    val servers = read()
    if (servers.add(serverKey(host, port))) {
      write(servers)
    }
  }

  private fun read(): LinkedHashSet<String> {
    if (!Files.exists(path)) {
      return linkedSetOf()
    }
    val servers = try {
      syncJson.decodeFromString(Servers.serializer(), Files.readString(path))
    } catch (e: SerializationException) {
      throw IllegalStateException("$path is not a list of servers: ${e.message}", e)
    }
    check(servers.format == FORMAT) { "$path has format ${servers.format}; this version of the mod reads $FORMAT" }
    return LinkedHashSet(servers.servers)
  }

  private fun write(servers: Set<String>) {
    writeAtomically(path) { temp ->
      Files.writeString(temp, syncJson.encodeToString(Servers.serializer(), Servers(FORMAT, servers.toList())))
    }
  }

  private companion object {
    const val FILE_NAME = "servers.json"
    const val FORMAT = 1
  }
}
