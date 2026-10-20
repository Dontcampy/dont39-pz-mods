package com.dont39.pz.modsync

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class SafePathTest {
  @TempDir
  lateinit var root: Path

  @Test
  fun `a plain path under mods resolves inside the root`() {
    val path = "mods/My Mod/42/media/lua/client/a.lua"
    assertEquals(path, SafePath.check(path))
    assertEquals(root.resolve(path), SafePath.resolve(root, path))
    assertEquals("mods/x/.hidden", SafePath.check("mods/x/.hidden"))
    assertEquals("mods/x/a..b.txt", SafePath.check("mods/x/a..b.txt"))
  }

  @TestFactory
  fun `anything else is refused`() = listOf(
    "",
    "/mods/x/a.lua",
    "C:/mods/x/a.lua",
    "c:mods/x",
    "mods\\x\\a.lua",
    "mods/x\\..\\..\\a.lua",
    "mods",
    "mods/",
    "media/lua/a.lua",
    "Mods/x/a.lua",
    "./mods/x/a.lua",
    "mods/../../etc/passwd",
    "mods/x/../../a",
    "mods/x/./a.lua",
    "mods//a.lua",
    "mods/x/a.lua/",
    "mods/x/a:stream",
    "mods/x/a\u0000.lua",
    "mods/x/a\n.lua",
    "mods/x/a?.lua",
    "mods/x/a.",
    "mods/x/a ",
    "mods/x/...",
    "mods/x/CON",
    "mods/x/nul.txt",
    "mods/x/com1.lua",
  ).map { path ->
    DynamicTest.dynamicTest(path.replace("\u0000", "\\0").replace("\n", "\\n").ifEmpty { "(empty)" }) {
      assertThrows<SyncException> { SafePath.check(path) }
      assertThrows<SyncException> { SafePath.resolve(root, path) }
    }
  }
}
