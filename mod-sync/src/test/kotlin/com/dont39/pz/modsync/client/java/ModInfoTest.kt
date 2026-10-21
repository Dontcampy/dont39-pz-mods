package com.dont39.pz.modsync.client.java

import com.dont39.pz.modsync.jar
import com.dont39.pz.modsync.writeBytes
import com.dont39.pz.modsync.writeItem
import java.io.IOException
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/** Which mods of a folder ZombieBuddy loads a jar for on a client, and which load at game start. */
class ModInfoTest {
  private companion object {
    const val JAVA = "javaJarFile=media/java/alpha.jar\njavaPkgName=com.example.alpha\n"
  }

  @TempDir
  lateinit var folder: Path

  private val mods: Path
    get() = folder.resolve("mods")

  private fun read(version: String = "42"): List<JavaMod> = ModInfo.javaMods(mods) { version }

  private fun mod(
    files: Map<String, String>,
    jars: List<String> = listOf("42/media/java/alpha.jar"),
    name: String = "Alpha",
  ) {
    writeItem(mods.resolve(name), files)
    jars.forEach { writeBytes(mods.resolve(name).resolve(it), jar()) }
  }

  @Test
  fun `the version folder's mod info and jar`() {
    mod(mapOf("common/.keep" to "", "42/mod.info" to "name=Alpha Mod\nid=Alpha\n$JAVA"))
    val m = read().single()
    assertEquals(mods.resolve("Alpha"), m.folder)
    assertEquals("Alpha", m.id)
    assertEquals("Alpha Mod", m.name)
    assertEquals(mods.resolve("Alpha/42/media/java/alpha.jar"), m.jar)
    assertEquals("com.example.alpha", m.pkg)
    assertFalse(m.preload)
  }

  @Test
  fun `common's mod info and jar, and common's mod info with the jar in the version folder`() {
    mod(mapOf("common/mod.info" to "id=Alpha\n$JAVA", "42/.keep" to ""), listOf("common/media/java/alpha.jar"))
    mod(mapOf("common/mod.info" to "id=Beta\n$JAVA", "42/.keep" to ""), name = "Beta")
    val (alpha, beta) = read()
    assertEquals(mods.resolve("Alpha/common/media/java/alpha.jar"), alpha.jar)
    assertEquals(mods.resolve("Beta/42/media/java/alpha.jar"), beta.jar)
  }

  @Test
  fun `a name outside ASCII reads as UTF-8`() {
    mod(mapOf("42/mod.info" to "name=阿尔法 Mod\nid=Alpha\n$JAVA"))
    assertEquals("阿尔法 Mod", read().single().name)
  }

  @Test
  fun `the version folder is the one the game picks`() {
    val newer = "id=Alpha\n$JAVA".replace("alpha.jar", "new.jar")
    mod(mapOf("42/mod.info" to "id=Alpha\n$JAVA", "42.13/mod.info" to newer),
      listOf("42/media/java/alpha.jar", "42.13/media/java/new.jar"))
    assertEquals(mods.resolve("Alpha/42.13/media/java/new.jar"), read("42.13").single().jar)
    assertEquals(mods.resolve("Alpha/42/media/java/alpha.jar"), read("42").single().jar)
  }

  @Test
  fun `mods ZombieBuddy does not load on a client`() {
    mod(mapOf("42/mod.info" to "id=Alpha\njavaJarFile=media/java/alpha.jar\n"))
    mod(mapOf("42/mod.info" to "id=Beta\n$JAVA".replace("media/java/", "media/java/server/")),
      listOf("42/media/java/server/alpha.jar"), "Beta")
    mod(mapOf("42/mod.info" to "id=Gamma\n$JAVA"), emptyList(), "Gamma")
    mod(mapOf("42/mod.info" to "id=Delta\nname=Delta\n"), name = "Delta")
    assertEquals(emptyList<JavaMod>(), read())
  }

  @Test
  fun `keys are case-insensitive and the first valid jar wins`() {
    mod(mapOf("42/mod.info" to "ID=x\nid=Alpha\nJavaJarFile=media/java/alpha.zip\nJAVAJARFILE=media/java/alpha.jar\n" +
      "javajarfile=media/java/other.jar\njavaPkgName=com.example.alpha\njavaPkgName=com.example.other\n"))
    val m = read().single()
    assertEquals(mods.resolve("Alpha/42/media/java/alpha.jar"), m.jar)
    assertEquals("com.example.alpha", m.pkg)
    assertEquals("Alpha", m.id)
    assertEquals("Alpha", m.name)
  }

  @Test
  fun `preload by mod info or by the jar's manifest`() {
    mod(mapOf("42/mod.info" to "id=Alpha\njavaPreload=TRUE\n$JAVA"))
    writeItem(mods.resolve("Beta"), mapOf("42/mod.info" to "id=Beta\n$JAVA"))
    writeBytes(mods.resolve("Beta/42/media/java/alpha.jar"), jar("ZB-Preload" to "true"))
    writeItem(mods.resolve("Gamma"), mapOf("42/mod.info" to "id=Gamma\n$JAVA"))
    writeBytes(mods.resolve("Gamma/42/media/java/alpha.jar"), jar("Zabric-PreLaunch" to "com.example.Pre"))
    writeItem(mods.resolve("Delta"), mapOf("42/mod.info" to "id=Delta\njavaPreload=false\n$JAVA"))
    writeBytes(mods.resolve("Delta/42/media/java/alpha.jar"), jar("Zabric-Main" to "com.example.Main"))
    assertEquals(
      mapOf("Alpha" to true, "Beta" to true, "Gamma" to true, "Delta" to false),
      read().associate { it.id to it.preload },
    )
  }

  @Test
  fun `a jar that does not read is an error naming it`() {
    writeItem(mods.resolve("Alpha"), mapOf("42/mod.info" to "id=Alpha\n$JAVA", "42/media/java/alpha.jar" to "no jar"))
    val e = assertThrows<IOException> { read() }
    assertTrue("alpha.jar" in e.message.orEmpty(), e.message)
  }

  @Test
  fun `no mods folder has no Java mods`() {
    assertEquals(emptyList<JavaMod>(), read())
  }
}
