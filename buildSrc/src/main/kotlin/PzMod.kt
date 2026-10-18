package buildsrc.convention

import javax.inject.Inject

/** A shipped mod's identity from its `mod/42/mod.info` (`pzMod`, set by pz-mod). */
abstract class PzMod @Inject constructor(val modId: String, val javaPkgName: String)
