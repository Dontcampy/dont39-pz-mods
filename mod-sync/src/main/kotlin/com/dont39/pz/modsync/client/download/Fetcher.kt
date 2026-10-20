package com.dont39.pz.modsync.client.download

import com.dont39.pz.modsync.protocol.Manifest
import com.dont39.pz.modsync.protocol.ManifestFile
import com.dont39.pz.modsync.protocol.Protocol
import com.dont39.pz.modsync.protocol.SyncException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CancellationException

/** The client's HTTP side, for one server at [base]; blocking, so it runs on the join's worker thread. */
internal class Fetcher(private val base: URI, private val client: HttpClient = newClient()) {
  fun manifest(): Manifest = get(Protocol.MANIFEST, "the server's mod list") { body ->
    Manifest.parse(body.readNBytes(Manifest.MAX_BYTES + 1))
  }

  /** Puts [file] at [target] once its size and hash match. */
  fun download(file: ManifestFile, target: Path, progress: Progress) {
    get("${Protocol.FILES}${file.sha256}", file.path) { body ->
      writeVerified(body, target, file, "the server", progress)
    }
    progress.fileDone()
  }

  private fun <T> get(path: String, what: String, read: (InputStream) -> T): T {
    val request = HttpRequest.newBuilder(base.resolve(path)).timeout(REQUEST_TIMEOUT).GET().build()
    val response = try {
      client.send(request, BodyHandlers.ofInputStream())
    } catch (e: InterruptedException) {
      throw CancellationException("cancelled").apply { initCause(e) }
    }
    return response.body().use { body ->
      if (response.statusCode() != 200) {
        throw SyncException("the server answered HTTP ${response.statusCode()} for $what")
      }
      read(body)
    }
  }

  private companion object {
    val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)

    // until the response headers; a stalled body is left to the player, who sees the progress and can go back
    val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(30)

    fun newClient(): HttpClient = HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(CONNECT_TIMEOUT)
      .followRedirects(HttpClient.Redirect.NEVER)
      .build()
  }
}
