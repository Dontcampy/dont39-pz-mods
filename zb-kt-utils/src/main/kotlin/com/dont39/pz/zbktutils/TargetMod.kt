package com.dont39.pz.zbktutils

/** False only when [className] is absent; a class that is there but fails to load is an error. */
fun targetLoaded(className: String): Boolean = try {
  Class.forName(className)
  true
} catch (e: ClassNotFoundException) {
  false
}

/** Null when the mod is not loaded. A loaded mod without the version constant is an error. */
fun targetVersion(versionClass: String, field: String = "VERSION"): String? {
  if (!targetLoaded(versionClass)) {
    return null
  }
  // by name: a compiled reference to a constant would read the value it had at build time
  val value = try {
    Class.forName(versionClass).getField(field).get(null)
  } catch (e: ReflectiveOperationException) {
    throw IllegalStateException("cannot read $versionClass.$field", e)
  }
  return value?.toString() ?: error("$versionClass.$field is null")
}

/** Why [version] is not accepted, with the mod option that overrides it; null when it is. */
fun versionProblem(
  target: String,
  version: String,
  known: Set<String>,
  force: Boolean,
  optionsPage: String,
  option: String,
): String? = if (force || version in known) {
  null
} else {
  "$target $version is not a known version $known (Options > Mods > $optionsPage > $option)"
}
