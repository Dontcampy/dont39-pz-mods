package com.dont39.pz.zbktutils.processor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import javax.tools.ToolProvider

/** Targets read from class files, as from the game jar: members that are not public, and a nested class. */
class LibraryTargetTest {
  @TempDir
  lateinit var dir: File

  // the widened form of a game class: lists make classes and fields public, never methods
  private val library = """
    package t;

    public final class Outer {
        public final Item item = new Item();

        public void update() {
            step();
        }

        private void step() {
        }

        public static final class Item {
            public String error;

            void setState(State next) {
            }
        }

        public enum State { READY, FAIL }
    }
  """.trimIndent()

  private val hooks = """
    package h

    import com.dont39.pz.zbktutils.Feature
    import com.pzlauncher.zabric.hook.*
    import t.Outer

    object Facade : Feature("Test", "test-mod") {
      override fun problem(): String? = null
    }

    @Hooks(targets = ["t.Outer\${'$'}Item"])
    object ItemHooks {
      @ModifyVariable(method = ["setState"], at = At("HEAD"), argsOnly = true)
      fun takeOver(next: Outer.State?, @Self self: Outer.Item): Outer.State? =
        if (next == Outer.State.FAIL && self.error == "taken") Outer.State.READY else next
    }

    @Hooks(Outer::class)
    object OuterHooks {
      @Inject(method = ["step"], at = [At("HEAD")], cancellable = true)
      fun hold(@Self self: Outer, ci: CallbackInfo) {
        if (self.item.error != null) ci.cancel()
      }
    }
  """.trimIndent()

  @Test
  fun privateAndPackagePrivateMethodsOfAClassFileAreTargets() {
    val source = dir.resolve("lib-src/t/Outer.java").apply { parentFile.mkdirs(); writeText(library) }
    val classes = dir.resolve("lib").apply { mkdirs() }
    val javac = ToolProvider.getSystemJavaCompiler()
    assertEquals(0, javac.run(null, null, null, "-d", classes.path, source.path))

    val result = ksp(dir.resolve("ksp"), listOf(kotlin("H.kt", hooks)), classpath = listOf(classes))
    result.assertCompiles()
    val glue = result.generated["java/glue/TestGlue.java"]!!
    assertTrue("methodName = \"setState\"" in glue, glue)
    assertTrue("methodName = \"step\"" in glue, glue)
    assertTrue(
      "hook t.Outer\$Item setState (Lt/Outer\$State;)V" in
        result.generated["resources/META-INF/zabric/hooks/h.ItemHooks.txt"]!!,
    )
    assertTrue("hook t.Outer step ()V" in result.generated["resources/META-INF/zabric/hooks/h.OuterHooks.txt"]!!)
  }
}
