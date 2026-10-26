package com.dont39.pz.modsync.server

import com.dont39.pz.modsync.ModSync
import com.dont39.pz.modsync.protocol.Trailer
import com.dont39.pz.zbktutils.SandboxOption
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.Locale
import zombie.core.network.ByteBufferWriter
import zombie.network.GameServer

/** The dedicated server's side: the manifest of its Workshop items, the HTTP service, and the trailer. */
internal object ModSyncServer {
  const val PORT_OPTION = "Dont39AllInOne_ModSync.Port"

  private var service: HttpService? = null

  // written once the service is up, so a client never gets a location without a manifest
  @Volatile
  private var trailer: ByteArray? = null

  /** At `startServer` RETURN, before the main loop writes any connection-details. */
  fun start() {
    if (GameServer.coop) {
      ModSync.log { "off: this server runs a hosted (co-op) game; only dedicated servers offer their Workshop items" }
      return
    }
    val port = SandboxOption.int(PORT_OPTION)
    if (port == 0) {
      ModSync.log { "off: no port set (sandbox option $PORT_OPTION is 0)" }
      return
    }
    val started = System.nanoTime()
    val served = ManifestBuilder.build(sources())
    ModSync.log {
      val millis = (System.nanoTime() - started) / 1_000_000
      "manifest: ${served.manifest.items.size} Workshop items, ${served.fileCount} files, ${mb(served.bytes)} MB, " +
        "built in $millis ms"
    }
    val events = object : HttpService.Events {
      override fun ioFailed(message: String) = ModSync.error { message }

      override fun failed(t: Throwable) = stop("serving the Workshop items", t)
    }
    service = try {
      HttpService.start(InetSocketAddress(port), served.json, served.files, events)
    } catch (e: IOException) {
      // the port is the owner's choice: the server runs on, and clients join as without the mod
      ModSync.error { "could not listen on TCP port $port ($e); mod sync is off" }
      return
    }
    trailer = Trailer.encode(port)
    ModSync.log { "serving the Workshop items on TCP port $port" }
  }

  /** At `ConnectionDetails.write` RETURN: after the last part vanilla clients read. */
  fun appendTrailer(out: ByteBufferWriter) {
    trailer?.let(out::put)
  }

  @Synchronized
  private fun stop(what: String, t: Throwable) {
    trailer = null
    ModSync.disable(what, t)
    service?.close()
    service = null
  }

  // in -nosteam mode the server installs no Workshop items, and leaves both arrays unset
  private fun sources(): List<ManifestBuilder.Source> {
    val ids = GameServer.WorkshopItems
    val folders = GameServer.workshopInstallFolders
    val stamps = GameServer.workshopTimeStamps
    if (folders == null || stamps == null) {
      check(ids.isEmpty()) { "the server lists ${ids.size} Workshop items, but installed none" }
      return emptyList()
    }
    check(folders.size == ids.size && stamps.size == ids.size) {
      "the server lists ${ids.size} Workshop items, but has ${folders.size} install folders and ${stamps.size} versions"
    }
    return ids.mapIndexed { i, id ->
      val folder = checkNotNull(folders[i]) { "Workshop item $id has no install folder" }
      ManifestBuilder.Source(id, stamps[i], Path.of(folder))
    }
  }

  private fun mb(bytes: Long): String = String.format(Locale.ROOT, "%.1f", bytes / (1024.0 * 1024.0))
}
