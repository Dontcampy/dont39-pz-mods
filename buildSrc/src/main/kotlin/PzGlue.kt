package buildsrc.convention

import org.gradle.api.provider.Property

/**
 * Where a feature's generated glue goes, set by the mod that bundles the feature (`pzGlue` in pz-feature): ZombieBuddy
 * looks for patch and Lua classes only in that mod's javaPkgName, and Lua globals are shared by all mods.
 */
interface PzGlue {
  /** The bundling mod's javaPkgName. */
  val gluePackage: Property<String>

  /** Prefix of every global Lua function the feature declares. */
  val luaPrefix: Property<String>
}
