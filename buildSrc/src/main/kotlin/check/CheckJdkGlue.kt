package buildsrc.convention.check

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * Fails if a method or field of the generated glue in [gluePackage] names a type outside the JDK and Zabric. The
 * loader reflects over a preloaded mod's glue at premain, which loads those types: game classes would load before the
 * game loads them, and other mods could patch them only by retransforming.
 */
@DisableCachingByDefault(because = "a check without outputs")
abstract class CheckJdkGlue : DefaultTask() {
  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val classes: DirectoryProperty

  /** In internal form, e.g. `com/example/mod`. */
  @get:Input
  abstract val gluePackage: Property<String>

  init {
    group = "verification"
  }

  @TaskAction
  fun check() {
    val dir = classes.get().asFile.resolve(gluePackage.get())
    val glue = dir.listFiles { file -> file.isFile && file.extension == "class" }.orEmpty()
    if (glue.isEmpty()) {
      throw GradleException("No generated glue in $dir")
    }
    val offenders = mutableListOf<String>()
    var methods = 0
    glue.sortedBy { it.name }.forEach { file ->
      ClassReader(file.readBytes()).accept(object : ClassVisitor(Opcodes.ASM9) {
        override fun visitMethod(
          access: Int,
          name: String,
          descriptor: String,
          signature: String?,
          exceptions: Array<out String>?,
        ): MethodVisitor? {
          methods++
          val method = Type.getMethodType(descriptor)
          report(file.name, name + descriptor, (method.argumentTypes + method.returnType).toList())
          return null
        }

        override fun visitField(
          access: Int,
          name: String,
          descriptor: String,
          signature: String?,
          value: Any?,
        ): FieldVisitor? {
          report(file.name, "$name $descriptor", listOf(Type.getType(descriptor)))
          return null
        }

        private fun report(file: String, member: String, types: List<Type>) {
          val foreign = types
            .map { if (it.sort == Type.ARRAY) it.elementType else it }
            .filter { it.sort == Type.OBJECT && ALLOWED.none(it.internalName::startsWith) }
          if (foreign.isNotEmpty()) {
            offenders += "$file $member: ${foreign.joinToString { it.className }}"
          }
        }
      }, ClassReader.SKIP_CODE)
    }
    if (methods == 0) {
      throw GradleException("The glue classes in $dir have no methods")
    }
    if (offenders.isNotEmpty()) {
      throw GradleException(
        "Glue members name types outside the JDK and Zabric, which the loader would load at premain. The glue " +
          "binds every argument of a hooked method, and its return value for RETURN and FINALLY handlers: hook " +
          "only methods whose signatures name JDK types, without @Self:\n" + offenders.joinToString("\n"),
      )
    }
  }

  private companion object {
    // Zabric is preloaded before every other mod
    val ALLOWED = listOf("java/", "com/pzlauncher/zabric/")
  }
}
