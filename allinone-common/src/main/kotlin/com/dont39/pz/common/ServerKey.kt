package com.dont39.pz.common

/** `<host>:<game port>`, the address as typed: a join sees only the address, and a new server entry has no id yet. */
fun serverKey(host: String, port: Int): String = "${host.trim()}:$port"
