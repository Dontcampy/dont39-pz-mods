package buildsrc.convention

import org.gradle.api.provider.Property

/** The project dir of the mod that bundles every feature. */
const val FEATURE_BUNDLE = "dont39-all-in-one"

/**
 * Where a feature's generated glue goes (`pzGlue` in pz-feature), by default from [FEATURE_BUNDLE]'s mod.info:
 * ZombieBuddy looks for patch and Lua classes only in that mod's javaPkgName, and Lua globals are shared by all mods.
 */
interface PzGlue {
  /** The bundling mod's javaPkgName. */
  val gluePackage: Property<String>

  /** Prefix of every global Lua function the feature declares. */
  val luaPrefix: Property<String>

  /** The bundling mod's id: Zabric opens the feature's hooks while that mod is loaded. */
  val modId: Property<String>
}
