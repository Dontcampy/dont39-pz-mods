package com.dont39.pz.modsync.client.download

import com.dont39.pz.modsync.SERVER_FILES
import com.dont39.pz.modsync.protocol.ManifestFile
import com.dont39.pz.modsync.protocol.ManifestItem
import com.dont39.pz.modsync.server.ManifestBuilder
import com.dont39.pz.modsync.writeItem
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PlanTest {
  @TempDir
  lateinit var folder: Path

  private fun serverItem(files: Map<String, String> = SERVER_FILES): ManifestItem {
    val item = writeItem(folder.resolve("server"), files)
    return ManifestBuilder.build(listOf(ManifestBuilder.Source(1, 2, item))).manifest.items.single()
  }

  private fun plan(server: ManifestItem, local: Map<String, String>): Plan {
    val copy = writeItem(folder.resolve("local"), local)
    return Plan.of(server, LocalCopy.scan(copy, server))
  }

  private fun paths(files: List<ManifestFile>) = files.map { it.path }

  @Test
  fun `matching files are copied, the rest downloaded`() {
    val server = serverItem()
    val local = SERVER_FILES + mapOf(
      "mods/Alpha/42/media/lua/client/Alpha.lua" to "print('alpha 1')\n",
      "mods/Alpha/42/media/lua/client/Old.lua" to "gone on the server",
    ) - "mods/Alpha/42/media/lua/shared/Shared.lua"
    val plan = plan(server, local)
    assertEquals(
      listOf("mods/Alpha/42/media/lua/client/Alpha.lua", "mods/Alpha/42/media/lua/shared/Shared.lua"),
      paths(plan.download),
    )
    assertEquals(
      listOf("mods/Alpha/42/media/java/alpha.jar", "mods/Alpha/42/mod.info", "mods/Alpha/common/.keep"),
      paths(plan.copy),
    )
    assertEquals("print('alpha 2')\n".length + "return {}\n".length.toLong(), plan.downloadBytes)
    assertEquals(emptyList<String>(), plan.jarChanges)
  }

  @Test
  fun `a file of another size is not hashed`() {
    val server = serverItem()
    val changed = SERVER_FILES + ("mods/Alpha/42/mod.info" to "longer than the server's")
    val copy = writeItem(folder.resolve("local"), changed)
    val local = LocalCopy.scan(copy, server)
    assertEquals(false, "mods/Alpha/42/mod.info" in local.hashes)
    assertEquals(true, "mods/Alpha/42/mod.info" in local.files)
  }

  @Test
  fun `no copy downloads everything`() {
    val server = serverItem()
    val plan = Plan.of(server, LocalCopy.NONE)
    assertEquals(paths(server.files), paths(plan.download))
    assertEquals(server.bytes, plan.downloadBytes)
    assertEquals(listOf("mods/Alpha/42/media/java/alpha.jar"), plan.jarChanges)
    assertEquals(paths(server.files), paths(plan(server, mapOf("other/x" to "x")).download))
  }

  @Test
  fun `a changed, new or removed jar is a jar change`() {
    val server = serverItem(SERVER_FILES + ("mods/Alpha/42/media/java/New.JAR" to "new"))
    val local = SERVER_FILES + mapOf(
      "mods/Alpha/42/media/java/alpha.jar" to "jar v0",
      "mods/Alpha/42/media/java/removed.jar" to "only here",
    )
    assertEquals(
      listOf(
        "mods/Alpha/42/media/java/New.JAR",
        "mods/Alpha/42/media/java/alpha.jar",
        "mods/Alpha/42/media/java/removed.jar",
      ),
      plan(server, local).jarChanges,
    )
  }
}
