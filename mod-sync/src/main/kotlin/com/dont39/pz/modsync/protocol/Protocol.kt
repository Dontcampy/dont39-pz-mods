package com.dont39.pz.modsync.protocol

/**
 * The server's HTTP layout: `manifest.json` and `files/<sha256>` under [BASE_PATH]. Content addressing keeps it
 * servable by a static server later.
 */
internal object Protocol {
  const val BASE_PATH = "/dont39-modsync/v1/"
  const val MANIFEST = "manifest.json"
  const val FILES = "files/"
}
