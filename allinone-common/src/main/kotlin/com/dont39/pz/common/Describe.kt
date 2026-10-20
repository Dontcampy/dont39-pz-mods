package com.dont39.pz.common

import java.io.EOFException
import java.io.IOException

/** How a network or disk failure reads in a log line or a reason the player sees. */
fun describe(e: IOException): String = when (e) {
  is EOFException -> "closed by the other end"
  else -> e.message?.let { "${e.javaClass.simpleName}: $it" } ?: e.javaClass.simpleName
}
