package buildsrc.convention

import org.gradle.api.GradleException
import java.io.DataInputStream

/**
 * Constant-pool strings of a class file naming a type in [pkg] (internal form, ending in `/`): class entries
 * ("<pkg>...") and descriptors ("...L<pkg>..."). A raw byte search would also match a package whose name merely
 * ends in [pkg].
 */
internal fun classRefs(bytes: ByteArray, pkg: String): List<String> {
  val input = DataInputStream(bytes.inputStream())
  input.skipBytes(8) // magic, minor, major
  val count = input.readUnsignedShort()
  val refs = mutableListOf<String>()
  var i = 1
  while (i < count) {
    when (val tag = input.readUnsignedByte()) {
      1 -> {
        val utf = input.readUTF()
        if (utf.startsWith(pkg) || "L$pkg" in utf) refs += utf
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
