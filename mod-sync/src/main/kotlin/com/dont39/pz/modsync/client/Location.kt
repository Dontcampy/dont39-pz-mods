package com.dont39.pz.modsync.client

import com.dont39.pz.modsync.protocol.Protocol
import com.dont39.pz.modsync.protocol.Trailer
import java.net.URI
import java.net.URISyntaxException
import java.nio.ByteBuffer

/** Where this join downloads from, or why it does not. */
internal sealed interface Location {
  data class At(val base: URI) : Location

  data class None(val reason: String) : Location

  companion object {
    /** [host]: the address the player joined; null for a join by Steam ID, which has none to download from. */
    fun of(buffer: ByteBuffer, host: String?): Location {
      val port = when (val decoded = Trailer.decode(buffer)) {
        is Trailer.Decoded.None -> return None(decoded.reason)
        is Trailer.Decoded.Port -> decoded.port
      }
      if (host == null) {
        return None("the join is by Steam ID, so there is no address to download the server's mods from")
      }
      return try {
        At(base(host, port))
      } catch (e: IllegalArgumentException) {
        None(checkNotNull(e.message))
      }
    }

    /**
     * `http://<host>:<port>/dont39-modsync/v1/`, with an IPv6 literal in brackets. Throws [IllegalArgumentException]
     * naming why [host] cannot be used.
     */
    fun base(host: String, port: Int): URI {
      val trimmed = host.trim()
      require(trimmed.isNotEmpty()) { "the server's address has no host" }
      val literal = if (':' in trimmed && !trimmed.startsWith("[")) "[${trimmed.replace("%", "%25")}]" else trimmed
      val uri = try {
        URI("http://$literal:$port${Protocol.BASE_PATH}")
      } catch (e: URISyntaxException) {
        throw IllegalArgumentException("\"$trimmed\" is not a host the mod can download from", e)
      }
      // a name URI cannot parse as a host (e.g. with '_') leaves it null, and HttpClient refuses it
      require(uri.host != null) { "\"$trimmed\" is not a host the mod can download from" }
      return uri
    }
  }
}
