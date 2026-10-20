package com.dont39.pz.modsync

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
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
  fun remembered(host: String, port: Int): Boolean = key(host, port) in read()

  @Synchronized
  fun remember(host: String, port: Int) {
    val servers = read()
    if (servers.add(key(host, port))) {
      write(servers)
    }
  }

  private fun key(host: String, port: Int) = "${host.trim()}:$port"

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
    Files.createDirectories(folder)
    // a crash mid-write leaves the old file
    val temp = path.resolveSibling("$FILE_NAME.tmp")
    Files.writeString(temp, syncJson.encodeToString(Servers.serializer(), Servers(FORMAT, servers.toList())))
    Files.move(temp, path, REPLACE_EXISTING, ATOMIC_MOVE)
  }

  private companion object {
    const val FILE_NAME = "servers.json"
    const val FORMAT = 1
  }
}
