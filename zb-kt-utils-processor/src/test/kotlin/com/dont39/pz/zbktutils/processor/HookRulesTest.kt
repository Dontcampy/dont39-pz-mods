package com.dont39.pz.zbktutils.processor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The rules the Kotlin front end adds to Zabric's; the shared ones are tested on the javac side. */
class HookRulesTest {
  @TempDir
  lateinit var dir: File

  private val target = java(
    "Target.java",
    """
    package t;

    public class Target {
        public int compute(int x) {
            return x;
        }

        public String name(String s) {
            return s;
        }

        public void run(String s, int n) {
        }
    }
    """,
  )

  private val facade = kotlin(
    "Facade.kt",
    """
    package h

    import com.dont39.pz.zbktutils.Feature

    object Facade : Feature("Test", "test-mod") {
      override fun problem(): String? = null
    }
    """,
  )

  /** Hook sources in package h with the hook API and the target imported, compiled with the target. */
  private fun compile(hooks: String, withFacade: Boolean = true): KspResult {
    val header = "package h\n\nimport com.pzlauncher.zabric.hook.*\nimport t.Target\n\n"
    val source = kotlin("H.kt", header + hooks.trimIndent())
    return ksp(dir, listOfNotNull(target, facade.takeIf { withFacade }, source))
  }

  private fun KspResult.listing(hookObject: String) = generated["resources/META-INF/zabric/hooks/$hookObject.txt"]!!

  private fun KspResult.glue() = generated["java/glue/TestGlue.java"]!!

  // ---- nullability ----

  @Test
  fun valuesTheTargetPassesMustBeNullable() {
    val result = compile(
      """
      @Hooks(Target::class)
      object H {
        @Inject(method = ["name"], at = [At("HEAD")])
        fun h(s: String, ci: CallbackInfoReturnable<String?>) {}

        @ModifyReturnValue(method = ["name"])
        fun m(original: String): String? = original
      }
      """,
    )
    result.assertError(
      "H.h: parameter s: a value the target passes must be nullable (kotlin.String?): the target may pass null",
    )
    result.assertError(
      "H.m: parameter original: a value the target passes must be nullable (kotlin.String?): the target may pass null",
    )
  }

  @Test
  fun thrownMustBeNullable() {
    compile(
      """
      @Hooks(Target::class)
      object H {
        @Inject(method = ["run"], at = [At("FINALLY")])
        fun h(s: String?, n: Int, ci: CallbackInfo, @Thrown t: Throwable) {}
      }
      """,
    ).assertError("H.h: parameter t: @Thrown must be nullable (Throwable?): it is null when the target returned")
  }

  @Test
  fun valuesTheGlueCreatesMayBeNonNull() {
    compile(
      """
      @Hooks(Target::class)
      object H {
        @Inject(method = ["run"], at = [At("FINALLY")])
        fun h(
          @Self self: Target,
          s: String?,
          n: Int,
          ci: CallbackInfo,
          @Thrown t: Throwable?,
          @Share("k") k: LocalRef<String>,
        ) {}

        @Inject(method = ["compute"], at = [At("HEAD")])
        fun c(x: Int, ci: CallbackInfoReturnable<Int>) {}
      }
      """,
    ).assertCompiles()
  }

  // ---- hook objects and handlers ----

  @Test
  fun hooksAreTopLevelObjects() {
    val handler = """
      @Inject(method = ["run"], at = [At("HEAD")])
      fun h(s: String?, n: Int, ci: CallbackInfo) {}
    """
    val result = compile(
      """
      @Hooks(Target::class)
      class C {
        $handler
      }

      object Outer {
        @Hooks(Target::class)
        object Nested {
          $handler
        }
      }

      class K {
        @Hooks(Target::class)
        companion object {
          $handler
        }
      }
      """,
    )
    listOf("h.C", "h.Outer.Nested", "h.K.Companion").forEach {
      result.assertError("$it: in Kotlin, @Hooks is for top-level objects; the glue calls the handlers on its INSTANCE")
    }
  }

  @Test
  fun hookObjectsAreVisibleToTheGlue() {
    compile(
      """
      @Hooks(Target::class)
      private object H {
        @Inject(method = ["run"], at = [At("HEAD")])
        fun h(s: String?, n: Int, ci: CallbackInfo) {}
      }
      """,
    ).assertError("h.H: a hook object must be public or internal: the glue calls its handlers from another package")
  }

  @Test
  fun internalHookObjects() {
    val result = compile(
      """
      @Hooks(Target::class)
      internal object H {
        @Inject(method = ["run"], at = [At("HEAD")])
        fun h(s: String?, n: Int, ci: CallbackInfo) {}
      }
      """,
    )
    result.assertCompiles()
    assertTrue("h.H.INSTANCE.h(arg0, arg1, " in result.glue())
  }

  @Test
  fun handlersArePlainPublicFunctions() {
    val result = compile(
      """
      @Hooks(Target::class)
      object H {
        @Inject(method = ["run"], at = [At("HEAD")])
        internal fun internalHandler(s: String?, n: Int, ci: CallbackInfo) {}

        @Inject(method = ["run"], at = [At("HEAD")])
        private fun privateHandler(s: String?, n: Int, ci: CallbackInfo) {}

        @Inject(method = ["run"], at = [At("HEAD")])
        fun String.extension(s: String?, n: Int, ci: CallbackInfo) {}

        @Inject(method = ["run"], at = [At("HEAD")])
        fun <X> generic(s: String?, n: Int, ci: CallbackInfo) {}

        @Inject(method = ["run"], at = [At("HEAD")])
        suspend fun suspending(s: String?, n: Int, ci: CallbackInfo) {}

        @Inject(method = ["run"], at = [At("HEAD")])
        fun varargs(vararg s: String?, ci: CallbackInfo) {}
      }
      """,
    )
    val hidden = "a handler must be public (the generated Java glue calls it; internal names are mangled)"
    result.assertError("H.internalHandler: $hidden")
    result.assertError("H.privateHandler: $hidden")
    result.assertError("H.extension: a handler cannot be an extension function")
    result.assertError("H.generic: a handler cannot have type parameters")
    result.assertError("H.suspending: a handler cannot be a suspend function")
    result.assertError("H.varargs: a handler cannot take vararg parameters")
  }

  // ---- gates ----

  @Test
  fun theFacadeGatesHookObjectsWithoutAGate() {
    val result = compile(
      """
      @Hooks(Target::class)
      object H {
        @Inject(method = ["run"], at = [At("HEAD")])
        fun h(s: String?, n: Int, ci: CallbackInfo) {}
      }

      @Hooks(value = [Target::class], gate = HookGate::class)
      object Named {
        @Inject(method = ["run"], at = [At("HEAD")])
        fun h(s: String?, n: Int, ci: CallbackInfo) {}
      }
      """,
    )
    result.assertCompiles()
    assertTrue("\ngate h.Facade\n" in result.listing("h.H"))
    assertTrue("\ngate h.Facade\n" in result.listing("h.Named"))
    assertTrue("MOD.active(h.Facade.INSTANCE)" in result.glue())
  }

  @Test
  fun namedGatesNeedNoFacade() {
    val result = compile(
      """
      object Gate : HookGate {
        override fun active() = true

        override fun failed(what: String, t: Throwable) {}
      }

      @Hooks(value = [Target::class], gate = Gate::class)
      object H {
        @Inject(method = ["run"], at = [At("HEAD")])
        fun h(s: String?, n: Int, ci: CallbackInfo) {}
      }
      """,
      withFacade = false,
    )
    result.assertCompiles()
    assertTrue("\ngate h.Gate\n" in result.listing("h.H"))
    assertTrue("MOD.active(h.Gate.INSTANCE)" in result.glue())
  }

  @Test
  fun aNamedGateHasAnInstance() {
    compile(
      """
      class NoInstance : HookGate {
        override fun active() = true

        override fun failed(what: String, t: Throwable) {}
      }

      @Hooks(value = [Target::class], gate = NoInstance::class)
      object H {
        @Inject(method = ["run"], at = [At("HEAD")])
        fun h(s: String?, n: Int, ci: CallbackInfo) {}
      }
      """,
    ).assertError(
      "h.H: the gate h.NoInstance must be a public HookGate with a public static final INSTANCE of a HookGate type " +
        "(or a Kotlin object)",
    )
  }

  @Test
  fun withoutAFacade() {
    val result = compile(
      """
      import com.dont39.pz.zbktutils.Feature

      abstract class NotAnObject : Feature("Test", "test-mod")

      class Holder {
        object Nested : Feature("Test", "test-mod") {
          override fun problem(): String? = null
        }
      }

      @Hooks(Target::class)
      object H {
        @Inject(method = ["run"], at = [At("HEAD")])
        fun h(s: String?, n: Int, ci: CallbackInfo) {}
      }
      """,
      withFacade = false,
    )
    result.assertError(
      "hook objects without a gate are gated by the feature's facade, the one top-level object extending " +
        "com.dont39.pz.zbktutils.Feature; none in this module",
    )
  }

  @Test
  fun withTwoFacades() {
    val result = compile(
      """
      import com.dont39.pz.zbktutils.Feature

      object Second : Feature("Test", "test-mod") {
        override fun problem(): String? = null
      }

      @Hooks(Target::class)
      object H {
        @Inject(method = ["run"], at = [At("HEAD")])
        fun h(s: String?, n: Int, ci: CallbackInfo) {}
      }
      """,
    )
    val facades = result.errors.single { "facade" in it }
    assertEquals(
      "hook objects without a gate are gated by the feature's facade, the one top-level object extending " +
        "com.dont39.pz.zbktutils.Feature; this module has ",
      facades.substringBefore("h."),
    )
    assertEquals(setOf("h.Facade", "h.Second"), facades.substringAfter("this module has ").split(", ").toSet())
  }
}
