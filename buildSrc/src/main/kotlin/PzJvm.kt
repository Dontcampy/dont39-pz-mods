package buildsrc.convention

import java.io.File
import java.util.zip.ZipFile

/** The JDK every module compiles with (pz-jvm, pz-kotlin). */
const val PZ_TOOLCHAIN = 25

/** Bytecode level of every module's Java and Kotlin classes (pz-jvm, pz-kotlin). */
const val PZ_RELEASE = 17

/** Whether the classpath entry [file] (folder or jar) has classes in package `kotlin`. */
fun hasKotlinClasses(file: File): Boolean = when {
  file.isDirectory -> file.resolve("kotlin").isDirectory
  file.isFile -> ZipFile(file).use { zip -> zip.entries().asSequence().any { it.name.startsWith("kotlin/") } }
  else -> false
}
