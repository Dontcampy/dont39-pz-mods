package com.dont39.pz.serverreborn.networksyncoptimization.protocol

import kotlin.math.abs
import kotlin.math.round

/**
 * The hash of a Lua table, independent of pair order: two copies of one table iterate in different orders. An empty
 * table hashes as [EMPTY], which callers also use for a missing one, as the game sends neither.
 */
internal object LuaTableHash {
  val EMPTY: Long = Unordered().get()

  /** Deeper than any table the game saves; a deeper one is a cycle. */
  const val MAX_DEPTH = 32

  /** How the caller's tables look. */
  interface Tables {
    fun isTable(value: Any?): Boolean

    fun forEach(table: Any, pair: (Any?, Any?) -> Unit)

    /** Whether the game saves and sends the pair; it drops the others, so they cannot match. */
    fun saved(key: Any?, value: Any?): Boolean

    /** A pair of the outermost table that each side writes for itself, so it cannot match. */
    fun ownPerSide(key: Any?, value: Any?): Boolean = false
  }

  fun hash(table: Any, tables: Tables): Long = table(table, tables, 0)

  private fun table(table: Any, tables: Tables, depth: Int): Long {
    check(depth <= MAX_DEPTH) { "a Lua table nested deeper than $MAX_DEPTH levels" }
    val pairs = Unordered()
    tables.forEach(table) { k, v ->
      if (counts(k, v, tables, depth)) {
        pairs.add(Digest.of(value(k, tables, depth), value(v, tables, depth)))
      }
    }
    return pairs.get()
  }

  private fun counts(k: Any?, v: Any?, tables: Tables, depth: Int): Boolean =
    tables.saved(k, v) && !(depth == 0 && tables.ownPerSide(k, v))

  /**
   * The pairs [hash] covers, as `path -> value` for the field diff: nested tables down to [maxDepth] levels, deeper
   * ones as their hash. Paths join keys with dots; the order is the table's own.
   */
  fun describe(table: Any, tables: Tables, maxDepth: Int, out: (String, String) -> Unit) {
    describe(table, tables, "", 0, maxDepth, out)
  }

  // returns how many values it described; an empty nested table is one, "{}"
  private fun describe(
    table: Any,
    tables: Tables,
    prefix: String,
    depth: Int,
    maxDepth: Int,
    out: (String, String) -> Unit,
  ): Int {
    var n = 0
    tables.forEach(table) { k, v ->
      if (!counts(k, v, tables, depth)) return@forEach
      val path = "$prefix$k"
      if (tables.isTable(v) && depth < maxDepth) {
        if (describe(v!!, tables, "$path.", depth + 1, maxDepth, out) == 0) {
          out(path, "{}")
        }
      } else {
        out(path, summary(v, tables, depth))
      }
      n++
    }
    return n
  }

  private fun summary(v: Any?, tables: Tables, depth: Int): String = when {
    v is String -> "\"" + (if (v.length > 40) v.substring(0, 40) + "..." else v) + "\""
    v is Number -> {
      val d = v.toDouble()
      if (d == round(d) && abs(d) < 1e15) d.toLong().toString() else d.toString()
    }
    tables.isTable(v) -> "table#%016x".format(table(v!!, tables, depth + 1))
    else -> v.toString()
  }

  private fun value(v: Any?, tables: Tables, depth: Int): Long {
    val d = Digest()
    when (v) {
      null -> d.putInt(0)
      is Boolean -> d.putInt(1).putBoolean(v)
      // Lua has one number type; the game stores Doubles, a mod may put an Integer
      is Number -> d.putInt(2).putLong(v.toDouble().toBits())
      is String -> d.putInt(3).putString(v)
      else -> {
        check(tables.isTable(v)) { "a saved Lua table holds a ${v.javaClass.name}" }
        d.putInt(4).putLong(table(v, tables, depth + 1))
      }
    }
    return d.get()
  }
}
