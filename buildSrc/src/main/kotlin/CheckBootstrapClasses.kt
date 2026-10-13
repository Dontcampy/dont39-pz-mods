package buildsrc.convention

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.DataInputStream

/** Fails if one of [classes] references a `kotlin/` type: they run before ZombieBuddyKotlin has installed Kotlin. */
@DisableCachingByDefault(because = "a check without outputs")
abstract class CheckBootstrapClasses : DefaultTask() {
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val classes: ConfigurableFileCollection

  @TaskAction
  fun check() {
    val offenders = classes.asFileTree.matching { include("**/*.class") }.files.mapNotNull { file ->
      kotlinRefs(file.readBytes()).takeIf { it.isNotEmpty() }?.let { "${file.name}: ${it.joinToString()}" }
    }
    if (offenders.isNotEmpty()) {
      throw GradleException("Java bootstrap classes reference Kotlin types:\n" + offenders.joinToString("\n"))
    }
  }

  // Constant-pool strings naming a kotlin/ class: class entries ("kotlin/...") and descriptors ("...Lkotlin/...").
  // A raw byte search would also match packages such as com/dont39/pz/zbkt/.
  private fun kotlinRefs(bytes: ByteArray): List<String> {
    val input = DataInputStream(bytes.inputStream())
    input.skipBytes(8) // magic, minor, major
    val count = input.readUnsignedShort()
    val refs = mutableListOf<String>()
    var i = 1
    while (i < count) {
      when (val tag = input.readUnsignedByte()) {
        1 -> {
          val utf = input.readUTF()
          if (utf.startsWith("kotlin/") || "Lkotlin/" in utf) refs += utf
        }
        7, 8, 16, 19, 20 -> input.skipBytes(2)
        15 -> input.skipBytes(3)
        3, 4, 9, 10, 11, 12, 17, 18 -> input.skipBytes(4)
        5, 6 -> {
          input.skipBytes(8)
          i++ // takes two slots
        }
        else -> throw GradleException("unknown constant pool tag $tag")
      }
      i++
    }
    return refs
  }
}
