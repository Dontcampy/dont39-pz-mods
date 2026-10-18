package com.dont39.pz.tunnel

import com.dont39.pz.zbktutils.glue.LuaGlobal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import zombie.ZomboidFileSystem

/**
 * The TCP port of each server address, as `<host>:<game port>=<TCP port>` lines in Zomboid/Lua. Keyed by address, as
 * typed: the join only sees the address, and a new server entry has no id yet. The add/edit server dialog writes it.
 */
internal object TunnelStore {
  private const val FILE_NAME = "Dont39AllInOne_TcpTunnel.txt"
  private const val MAX_PORT = 65535

  private val file: Path
    get() = Paths.get(ZomboidFileSystem.instance.cacheDir, "Lua", FILE_NAME)

  @Synchronized
  fun get(host: String, port: Int): Int? = read()[key(host, port)]

  /** Port arguments as text: Lua numbers would arrive as doubles. */
  @LuaGlobal("Dont39AllInOne_getTunnelTcpPort")
  fun luaGet(host: String, port: String): String? = get(host, port.toInt())?.toString()

  /**
   * Stores [tcpPort] for [host]:[port]; empty or 0 removes it. [oldHost]:[oldPort], the edited entry's address before
   * the edit, is removed first, so the port moves with the entry.
   */
  @LuaGlobal("Dont39AllInOne_setTunnelTcpPort")
  @Synchronized
  fun luaSet(oldHost: String?, oldPort: String?, host: String, port: String, tcpPort: String) {
    val tcp = tcpPort.trim().ifEmpty { "0" }.toInt()
    require(tcp in 0..MAX_PORT) { "TCP port $tcp is not between 0 and $MAX_PORT" }
    val ports = read()
    if (oldHost != null && oldPort != null) {
      ports.remove(key(oldHost, oldPort.toInt()))
    }
    val key = key(host, port.toInt())
    if (tcp == 0) {
      ports.remove(key)
    } else {
      ports[key] = tcp
    }
    write(ports)
  }

  private fun key(host: String, port: Int) = "${host.trim()}:$port"

  private fun read(): MutableMap<String, Int> {
    val path = file
    val ports = linkedMapOf<String, Int>()
    if (!Files.exists(path)) {
      return ports
    }
    Files.readAllLines(path).forEachIndexed { index, line ->
      if (line.isBlank()) {
        return@forEachIndexed
      }
      val key = line.substringBeforeLast('=', "").trim()
      val port = line.substringAfterLast('=').trim().toIntOrNull()
      check(key.isNotEmpty() && port != null && port in 1..MAX_PORT) {
        "$path line ${index + 1} is not <address>:<port>=<TCP port>: $line"
      }
      ports[key] = port
    }
    return ports
  }

  private fun write(ports: Map<String, Int>) {
    val path = file
    Files.createDirectories(path.parent)
    // a crash mid-write leaves the old file
    val temp = path.resolveSibling("$FILE_NAME.tmp")
    Files.write(temp, ports.map { (key, port) -> "$key=$port" })
    Files.move(temp, path, REPLACE_EXISTING, ATOMIC_MOVE)
  }
}
