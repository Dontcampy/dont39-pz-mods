package com.dont39.pz.modsync.client.java

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class JavaRunTest {
  @TempDir
  lateinit var dir: Path

  private fun mod(id: String?, name: String): JavaMod {
    val jar = dir.resolve("$name/42/media/java/$name.jar")
    Files.createDirectories(jar.parent)
    Files.write(jar, byteArrayOf(1))
    return JavaMod(id, name, jar, "com.example.${name.lowercase()}", false)
  }

  @Test
  fun `each mod the server lists is from its tree, not loaded, or served by another jar`() {
    val tree = mod("Tree", "Tree")
    val declined = mod("Declined", "Declined")
    val other = mod("Other", "Other")
    val elsewhere = dir.resolve("elsewhere.jar")
    val served = mapOf(tree.pkg to tree.jar.toRealPath(), other.pkg to elsewhere)

    val runs = JavaRun.of(listOf(tree, declined, other), setOf("Tree", "Declined", "Other")) { served[it] }

    assertEquals(
      listOf(
        JavaRun.FromTree(tree, tree.jar.toRealPath()),
        JavaRun.NotLoaded(declined, declined.jar.toRealPath()),
        JavaRun.Other(other, other.jar.toRealPath(), elsewhere),
      ),
      runs,
    )
    assertEquals(
      listOf(
        JavaRun.Notice("NoticeNotLoaded", listOf("Declined")),
        JavaRun.Notice("NoticeNotServed", listOf("Other", elsewhere.toString())),
      ),
      JavaRun.notice(runs),
    )
  }

  @Test
  fun `mods the server does not list are left out`() {
    val required = mod("Required", "Required")
    val noId = mod(null, "NoId")

    assertEquals(emptyList<JavaRun>(), JavaRun.of(listOf(required, noId), setOf("Other")) { null })
  }

  @Test
  fun `no notice when every mod runs from its tree`() {
    val tree = mod("Tree", "Tree")

    val runs = JavaRun.of(listOf(tree), setOf("Tree")) { tree.jar.toRealPath() }

    assertNull(JavaRun.notice(runs))
    assertNull(JavaRun.notice(emptyList()))
  }
}
