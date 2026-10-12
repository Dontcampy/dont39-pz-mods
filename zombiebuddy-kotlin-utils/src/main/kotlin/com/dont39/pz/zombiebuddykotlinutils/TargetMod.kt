package com.dont39.pz.zombiebuddykotlinutils

/**
 * The target mod's version constant as text (a public static field, `VERSION` by default), or null when the mod is not
 * loaded or the constant is missing. Never throws.
 */
fun targetVersion(versionClass: String, field: String = "VERSION"): String? = try {
  val value: Any? = Class.forName(versionClass).getField(field).get(null)
  value.toString()
} catch (e: Exception) {
  null
} catch (e: LinkageError) {
  null
}

/**
 * Null when [version] of [target] is in the allowlist [known] or [force] is set (the feature's mod option, here
 * [optionsPage] > [option]); otherwise the reason for the feature's idle/inactive line, with the override hint. A newer
 * target version may change internals or fix the issue itself (zombiebuddy-patching.md "Gating").
 */
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
