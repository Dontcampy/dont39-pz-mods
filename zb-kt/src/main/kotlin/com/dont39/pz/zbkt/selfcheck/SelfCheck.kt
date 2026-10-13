package com.dont39.pz.zbkt.selfcheck

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Run reflectively by `ZombieBuddyKotlin.install` after the stdlib swap; throws if the runtime does not work.
 *
 * Kept out of the javaPkgName package: ZombieBuddy loads every class in that exact package (after `Main.main` today);
 * if it ever did so first, this class would pull in the game's old Kotlin before the swap.
 */
internal object SelfCheck {
  private enum class Part { STDLIB, COROUTINES, SERIALIZATION }

  @Serializable
  private data class Probe(val name: String, val parts: List<String>)

  @JvmStatic
  fun run() {
    // Enum.entries needs stdlib 1.9, String.lowercase() 1.5; the game's own stdlib is 1.4.10
    val parts = Part.entries.map { it.name.lowercase() }

    val count = runBlocking {
      val counted = async {
        delay(1)
        parts.size
      }
      counted.await()
    }
    check(count == Part.entries.size) { "coroutine returned $count" }

    val probe = Probe("self-check", parts)
    val json = Json.encodeToString(Probe.serializer(), probe)
    val decoded = Json.decodeFromString(Probe.serializer(), json)
    check(decoded == probe) { "serialization round-trip turned $json into $decoded" }
  }
}
