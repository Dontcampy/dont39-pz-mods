package com.dont39.pz.tunnel

import com.dont39.pz.common.serverKey
import com.dont39.pz.common.writeAtomically
import com.dont39.pz.zbktutils.glue.LuaGlobal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import zombie.ZomboidFileSystem

/**
 * The tunnel of each server address, as `<host>:<game port>=<mode>,<TCP port>,<KCP port>` lines in Zomboid/Lua; a port
 * is 0 when never set. Keyed by address, as typed: the join only sees the address, and a new server entry has no id
 * yet. The add/edit server dialog writes it; `Dont39AllInOne_TunnelShared.lua` parses it too, so change both
 * together; the test fixtures under `store/` pin the format.
 */
internal object TunnelStore {
  private const val FILE_NAME = "Dont39AllInOne_Tunnel.txt"

  // the released TCP-only store, `<host>:<game port>=<TCP port>`; only read, so an older mod version still finds it
  private const val RELEASED_FILE_NAME = "Dont39AllInOne_TcpTunnel.txt"
  private const val MAX_PORT = 65535

  enum class Mode(val id: String) {
    OFF("off"),
    TCP("tcp"),
    KCP("kcp"),
    ;

    companion object {
      fun of(id: String): Mode? = entries.firstOrNull { it.id == id }
    }
  }

  data class Entry(val mode: Mode, val tcpPort: Int, val kcpPort: Int) {
    /** The chosen tunnel's port; 0 when off. */
    val port: Int
      get() = when (mode) {
        Mode.OFF -> 0
        Mode.TCP -> tcpPort
        Mode.KCP -> kcpPort
      }
  }

  // Zomboid/Lua; tests pass their own folder
  private val gameFolder: Path
    get() = Paths.get(ZomboidFileSystem.instance.cacheDir, "Lua")

  @Synchronized
  fun get(host: String, port: Int, folder: Path = gameFolder): Entry? = read(folder)[serverKey(host, port)]

  // port arguments as text: Lua numbers would arrive as doubles

  /** "off", "tcp" or "kcp". */
  @LuaGlobal("Dont39AllInOne_getTunnelMode")
  fun luaMode(host: String, port: String): String = (get(host, port.toInt())?.mode ?: Mode.OFF).id

  /** Null when never set. */
  @LuaGlobal("Dont39AllInOne_getTunnelTcpPort")
  fun luaTcpPort(host: String, port: String): String? = get(host, port.toInt())?.tcpPort?.takeIf { it != 0 }?.toString()

  /** Null when never set. */
  @LuaGlobal("Dont39AllInOne_getTunnelKcpPort")
  fun luaKcpPort(host: String, port: String): String? = get(host, port.toInt())?.kcpPort?.takeIf { it != 0 }?.toString()

  /**
   * Stores [mode] and both ports (empty or 0: not set) for [host]:[port]; off without ports removes the address.
   * [oldHost]:[oldPort], the edited entry's address before the edit, is removed first, so the tunnel moves with the
   * entry.
   */
  @LuaGlobal("Dont39AllInOne_setTunnel")
  fun luaSet(
    oldHost: String?,
    oldPort: String?,
    host: String,
    port: String,
    mode: String,
    tcpPort: String,
    kcpPort: String,
  ) {
    set(oldHost, oldPort, host, port, mode, tcpPort, kcpPort, gameFolder)
  }

  @Synchronized
  fun set(
    oldHost: String?,
    oldPort: String?,
    host: String,
    port: String,
    mode: String,
    tcpPort: String,
    kcpPort: String,
    folder: Path,
  ) {
    val entry = Entry(
      Mode.of(mode) ?: throw IllegalArgumentException("unknown tunnel mode $mode"),
      parsePort(tcpPort, "TCP"),
      parsePort(kcpPort, "KCP"),
    )
    require(entry.mode == Mode.OFF || entry.port != 0) { "tunnel ${entry.mode.id} chosen without its port" }
    val entries = read(folder)
    if (oldHost != null && oldPort != null) {
      entries.remove(serverKey(oldHost, oldPort.toInt()))
    }
    val key = serverKey(host, port.toInt())
    if (entry == Entry(Mode.OFF, 0, 0)) {
      entries.remove(key)
    } else {
      entries[key] = entry
    }
    write(folder, entries)
  }

  private fun parsePort(text: String, what: String): Int {
    val port = text.trim().ifEmpty { "0" }.toInt()
    require(port in 0..MAX_PORT) { "$what port $port is not between 0 and $MAX_PORT" }
    return port
  }

  private fun read(folder: Path): MutableMap<String, Entry> {
    val path = folder.resolve(FILE_NAME)
    if (Files.exists(path)) {
      return parse(path) { value -> entry(value) }
    }
    val released = folder.resolve(RELEASED_FILE_NAME)
    if (!Files.exists(released)) {
      return linkedMapOf()
    }
    val entries = parse(released) { value ->
      value.toIntOrNull()?.takeIf { it in 1..MAX_PORT }?.let { Entry(Mode.TCP, it, 0) }
    }
    write(folder, entries)
    return entries
  }

  /** `off|tcp|kcp,<TCP port>,<KCP port>`; null when malformed. */
  private fun entry(value: String): Entry? {
    val parts = value.split(',').map { it.trim() }
    if (parts.size != 3) {
      return null
    }
    val mode = Mode.of(parts[0]) ?: return null
    val (tcp, kcp) = parts.drop(1).map { it.toIntOrNull()?.takeIf { port -> port in 0..MAX_PORT } ?: return null }
    return Entry(mode, tcp, kcp).takeIf { it.mode == Mode.OFF || it.port != 0 }
  }

  private fun parse(path: Path, entry: (String) -> Entry?): MutableMap<String, Entry> {
    val entries = linkedMapOf<String, Entry>()
    Files.readAllLines(path).forEachIndexed { index, line ->
      if (line.isBlank()) {
        return@forEachIndexed
      }
      val key = line.substringBeforeLast('=', "").trim()
      val value = entry(line.substringAfterLast('=').trim())
      check(key.isNotEmpty() && value != null) { "$path line ${index + 1} is not a tunnel entry: $line" }
      entries[key] = value
    }
    return entries
  }

  private fun write(folder: Path, entries: Map<String, Entry>) {
    writeAtomically(folder.resolve(FILE_NAME)) { temp ->
      Files.write(temp, entries.map { (key, e) -> "$key=${e.mode.id},${e.tcpPort},${e.kcpPort}" })
    }
  }
}
