package com.dont39.pz.zombiebuddykotlinutils

import me.zed_0xff.zombie_buddy.Patch

// pz-feature's dont39.gluePackage
private const val GLUE_PACKAGE = "com.dont39.pz.allinone"

/**
 * Throws naming a class or method that [feature]'s generated patches hook but that is not there; ZombieBuddy would leave
 * such a hook out without an error.
 */
internal fun checkHookTargets(feature: String) {
  val patches = try {
    val outer = feature.split('-').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
    Class.forName("$GLUE_PACKAGE.${outer}Patches")
  } catch (e: ClassNotFoundException) {
    return // the feature hooks nothing
  }
  for (patch in patches.declaredClasses) {
    val target = patch.getAnnotation(Patch::class.java) ?: continue
    val owner = try {
      Class.forName(target.className, false, patches.classLoader)
    } catch (e: ClassNotFoundException) {
      throw IllegalStateException("class ${target.className}, which ${patch.name} hooks, is not there", e)
    }
    check(owner.declaredMethods.any { it.name == target.methodName }) {
      "${target.className} has no method ${target.methodName}, which ${patch.name} hooks"
    }
  }
}
