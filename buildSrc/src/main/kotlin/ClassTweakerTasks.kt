package buildsrc.convention

import com.pzlauncher.zabric.classtweaker.ClassTweaker
import com.pzlauncher.zabric.classtweaker.ClassTweaker.Access
import com.pzlauncher.zabric.classtweaker.ClassTweaker.Entry
import com.pzlauncher.zabric.classtweaker.ClassTweaker.Kind
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Copies the jars a feature compiles against with what its `.classtweaker` lists made public (fields in `mutable` lines
 * non-final), as the game-start widening does, so the compiler accepts direct access.
 */
@DisableCachingByDefault(because = "the copies are as large as the game jar")
abstract class WidenJars : DefaultTask() {
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val tweakers: ConfigurableFileCollection

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.NAME_ONLY)
  abstract val jars: ConfigurableFileCollection

  /** The widened copies, under the input jars' names. */
  @get:OutputDirectory
  abstract val widened: DirectoryProperty

  /** The directives plus the enclosing classes they make public, as the game-start widening applies them. */
  @get:OutputFile
  abstract val expanded: RegularFileProperty

  private class Info(
    val fields: Map<String, Pair<String, Int>>,
    val constructors: Set<String>,
    // null for a top-level class
    val outer: String?,
    val local: Boolean,
  )

  private class Spec {
    var publicType = false
    val publicFields = mutableSetOf<String>()
    val nonFinalFields = mutableSetOf<String>()
    val publicConstructors = mutableSetOf<String>()
  }

  @TaskAction
  fun widen() {
    val entries = tweakers.files.sortedBy { it.path }.flatMap {
      try {
        ClassTweaker.parse(it.path, it.readLines())
      } catch (e: IllegalArgumentException) {
        throw GradleException(e.message!!)
      }
    }
    val inputs = jars.files.toList()
    inputs.groupBy { it.name }.filterValues { it.size > 1 }.keys.firstOrNull()?.let {
      throw GradleException("Two jars to widen are both named $it: ${inputs.filter { f -> f.name == it }}")
    }
    val zips = inputs.associateWith { ZipFile(it) }
    try {
      val infos = mutableMapOf<String, Info?>()
      fun info(owner: String): Info? = infos.getOrPut(owner) { read(owner, zips) }

      val errors = mutableListOf<String>()
      val expandedEntries = linkedMapOf<String, Entry>()
      val specs = mutableMapOf<String, Spec>()
      fun spec(owner: String) = specs.getOrPut(owner) { Spec() }

      // compile-time and runtime access must match, so the class and its enclosing classes become public too
      fun makePublic(e: Entry, owner: String) {
        val info = info(owner)
        if (info == null || info.local) {
          val why = if (info == null) "not in ${names(zips)}" else "local or anonymous"
          errors += "${e.where()}: enclosing class $owner is $why"
          return
        }
        spec(owner).publicType = true
        val implied = Entry(e.source(), e.line(), Access.ACCESSIBLE, Kind.CLASS, owner, null, null)
        expandedEntries.putIfAbsent(implied.directive(), implied)
        info.outer?.let { makePublic(e, it) }
      }

      for (e in entries) {
        val problem = check(e, info(e.owner()), entries, zips)
        if (problem != null) {
          errors += "${e.where()}: $problem"
          continue
        }
        expandedEntries.putIfAbsent(e.directive(), e)
        val key = "${e.name()}:${e.descriptor()}"
        when {
          e.access() == Access.MUTABLE -> spec(e.owner()).nonFinalFields += key
          e.kind() == Kind.FIELD -> spec(e.owner()).publicFields += key
          e.kind() == Kind.METHOD -> spec(e.owner()).publicConstructors += e.descriptor()
        }
        if (e.access() == Access.ACCESSIBLE) {
          makePublic(e, e.owner())
        }
      }
      if (errors.isNotEmpty()) {
        throw GradleException("Cannot widen the compile jars:\n" + errors.distinct().joinToString("\n") { "  $it" })
      }

      expanded.get().asFile.writeText(ClassTweaker.write(expandedEntries.values.toList()))
      val publicTypes = specs.filterValues { it.publicType }.keys
      val outDir = widened.get().asFile
      outDir.listFiles()?.forEach { it.delete() }
      zips.forEach { (file, zip) -> copy(zip, outDir.resolve(file.name), specs, publicTypes) }
    } finally {
      zips.values.forEach { it.close() }
    }
  }

  /** What does not match the jars, or null. */
  private fun check(e: Entry, info: Info?, all: List<Entry>, zips: Map<File, ZipFile>): String? {
    if (info == null) {
      return "class ${e.owner()} is not in ${names(zips)}"
    }
    return when (e.kind()) {
      Kind.CLASS -> null
      Kind.METHOD -> if (e.descriptor() in info.constructors) {
        null
      } else {
        "${e.owner()} has no constructor ${e.descriptor()}; it has ${info.constructors.joinToString()}"
      }
      Kind.FIELD -> {
        val (desc, access) = info.fields[e.name()] ?: return "${e.owner()} has no field ${e.name()}"
        val alsoAccessible = all.any {
          it.access() == Access.ACCESSIBLE && it.kind() == Kind.FIELD && it.owner() == e.owner() && it.name() == e.name()
        }
        when {
          desc != e.descriptor() -> "${e.owner()}.${e.name()} has type $desc, not ${e.descriptor()}"
          e.access() == Access.MUTABLE && access and Opcodes.ACC_PUBLIC == 0 && !alsoAccessible ->
            "the field is not public, so it also needs an 'accessible field' line"
          else -> null
        }
      }
    }
  }

  private fun names(zips: Map<File, ZipFile>) = zips.keys.joinToString { it.name }

  private fun read(owner: String, zips: Map<File, ZipFile>): Info? {
    val found = zips.entries.filter { it.value.getEntry("$owner.class") != null }
    if (found.size > 1) {
      throw GradleException("$owner is in more than one jar: ${found.joinToString { it.key.name }}")
    }
    val zip = found.singleOrNull()?.value ?: return null
    val bytes = zip.getInputStream(zip.getEntry("$owner.class")).use { it.readBytes() }
    val fields = mutableMapOf<String, Pair<String, Int>>()
    val constructors = mutableSetOf<String>()
    var outer: String? = null
    var local = false
    ClassReader(bytes).accept(
      object : ClassVisitor(Opcodes.ASM9) {
        override fun visitInnerClass(name: String, outerName: String?, innerName: String?, a: Int) {
          if (name == owner) {
            outer = outerName
            local = outerName == null
          }
        }

        override fun visitField(a: Int, name: String, desc: String, s: String?, value: Any?): FieldVisitor? {
          fields[name] = desc to a
          return null
        }

        override fun visitMethod(a: Int, name: String, desc: String, s: String?, ex: Array<out String>?): MethodVisitor? {
          if (name == "<init>") {
            constructors += desc
          }
          return null
        }
      },
      ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
    )
    return Info(fields, constructors, outer, local)
  }

  private fun copy(zip: ZipFile, target: File, specs: Map<String, Spec>, publicTypes: Set<String>) {
    ZipOutputStream(target.outputStream().buffered()).use { out ->
      out.setLevel(Deflater.BEST_SPEED)
      zip.entries().asSequence().forEach { entry ->
        out.putNextEntry(ZipEntry(entry.name).also { it.time = entry.time })
        if (!entry.isDirectory) {
          val spec = if (entry.name.endsWith(".class")) specs[entry.name.removeSuffix(".class")] else null
          if (spec == null) {
            zip.getInputStream(entry).use { it.transferTo(out) }
          } else {
            out.write(widenClass(zip.getInputStream(entry).use { it.readBytes() }, spec, publicTypes))
          }
        }
        out.closeEntry()
      }
    }
  }

  private fun widenClass(bytes: ByteArray, spec: Spec, publicTypes: Set<String>): ByteArray {
    val reader = ClassReader(bytes)
    val writer = ClassWriter(reader, 0)
    reader.accept(
      object : ClassVisitor(Opcodes.ASM9, writer) {
        override fun visit(v: Int, a: Int, name: String, s: String?, sup: String?, ifs: Array<out String>?) =
          super.visit(v, if (spec.publicType) public(a) else a, name, s, sup, ifs)

        // javac and kotlinc take a nested class's access from here
        override fun visitInnerClass(name: String, outerName: String?, innerName: String?, a: Int) =
          super.visitInnerClass(name, outerName, innerName, if (name in publicTypes) public(a) else a)

        override fun visitField(a: Int, name: String, desc: String, s: String?, value: Any?): FieldVisitor? {
          val key = "$name:$desc"
          var access = if (key in spec.publicFields) public(a) else a
          if (key in spec.nonFinalFields) {
            access = access and Opcodes.ACC_FINAL.inv()
          }
          return super.visitField(access, name, desc, s, value)
        }

        override fun visitMethod(a: Int, name: String, desc: String, s: String?, ex: Array<out String>?): MethodVisitor? =
          super.visitMethod(
            if (name == "<init>" && desc in spec.publicConstructors) public(a) else a,
            name,
            desc,
            s,
            ex,
          )
      },
      0,
    )
    return writer.toByteArray()
  }

  private fun public(access: Int) = access and (Opcodes.ACC_PRIVATE or Opcodes.ACC_PROTECTED).inv() or Opcodes.ACC_PUBLIC
}

/** One list for the game-start widening from the features' expanded lists ([WidenJars.expanded]). */
@CacheableTask
abstract class MergeClassTweakers : DefaultTask() {
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.NAME_ONLY)
  abstract val lists: ConfigurableFileCollection

  @get:OutputFile
  abstract val merged: RegularFileProperty

  @TaskAction
  fun merge() {
    val entries = linkedMapOf<String, Entry>()
    lists.files.sortedBy { it.name }.forEach { file ->
      ClassTweaker.parse(file.name, file.readLines()).forEach { entries.putIfAbsent(it.directive(), it) }
    }
    merged.get().asFile.writeText(ClassTweaker.write(entries.values.toList()))
  }
}
