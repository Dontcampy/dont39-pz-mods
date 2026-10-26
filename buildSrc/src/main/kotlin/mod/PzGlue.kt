package buildsrc.convention.mod

import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * Where a Kotlin module's generated glue goes (`pzGlue`, set by pz-kotlin-glue; pz-feature and pz-kotlin-mod set the
 * values): ZombieBuddy looks for patch and Lua classes only in the shipping mod's javaPkgName, and Lua globals are
 * shared by all mods.
 */
abstract class PzGlue @Inject constructor(
  /** The module name in PascalCase (`server-reborn`: `ServerReborn`), the stem of the generated class names. */
  val stem: String,
) {
  /** The shipping mod's javaPkgName. */
  abstract val gluePackage: Property<String>

  /** Prefix of every global Lua function the module declares. */
  abstract val luaPrefix: Property<String>

  /** The shipping mod's id: Zabric opens the module's hooks while that mod is loaded. */
  abstract val modId: Property<String>
}
