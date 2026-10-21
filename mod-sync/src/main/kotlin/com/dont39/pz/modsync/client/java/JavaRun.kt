package com.dont39.pz.modsync.client.java

import java.nio.file.Path

/** How a Java mod of a swapped item runs after the join's pass; [tree] the real path of its jar in the item's tree. */
internal sealed interface JavaRun {
  val mod: JavaMod
  val tree: Path

  /** From the tree: the server's Java code. */
  data class FromTree(override val mod: JavaMod, override val tree: Path) : JavaRun

  /** No loaded jar has its package, e.g. its approval was declined. */
  data class NotLoaded(override val mod: JavaMod, override val tree: Path) : JavaRun

  /** Another jar, [served], has its package. */
  data class Other(override val mod: JavaMod, override val tree: Path, val served: Path) : JavaRun

  /** A line of the player's notice: the key after `JoinSession.KEY_PREFIX` and its arguments. */
  data class Notice(val key: String, val args: List<String>)

  companion object {
    /**
     * [expected] as the pass left them, [servingJar] as [Launch.servingJar]. Mods not in [serverMods] are left out: the
     * join's pass never looks at them.
     */
    fun of(expected: List<JavaMod>, serverMods: Set<String>, servingJar: (String) -> Path?): List<JavaRun> =
      expected.filter { it.id in serverMods }.map { mod ->
        val tree = mod.jar.toRealPath()
        when (val served = servingJar(mod.pkg)) {
          tree -> FromTree(mod, tree)
          null -> NotLoaded(mod, tree)
          else -> Other(mod, tree, served)
        }
      }

    /** What the player is told once in game, null when every mod runs the server's Java code. */
    fun notice(runs: List<JavaRun>): List<Notice>? = runs.mapNotNull {
      when (it) {
        is FromTree -> null
        is NotLoaded -> Notice("NoticeNotLoaded", listOf(it.mod.name))
        is Other -> Notice("NoticeNotServed", listOf(it.mod.name, it.served.toString()))
      }
    }.ifEmpty { null }
  }
}
