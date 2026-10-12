package com.dont39.pz.zombiebuddykotlinutils

/** Null when the mod is not loaded. */
fun targetVersion(versionClass: String, field: String = "VERSION"): String? = try {
  val value: Any? = Class.forName(versionClass).getField(field).get(null)
  value.toString()
} catch (e: Exception) {
  null
} catch (e: LinkageError) {
  null
}

/** Why [version] is not accepted, with the mod option that overrides it; null when it is. */
fun versionProblem(
  target: String,
  version: String,
  known: Set<String>,
  force: Boolean,
  optionsPage: String,
  option: String,
): String? = if (force || known.contains(version)) {
  null
} else {
  "$target $version is not a known version $known (Options > Mods > $optionsPage > $option)"
}
