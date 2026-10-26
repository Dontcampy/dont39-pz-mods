package com.dont39.pz.common

import zombie.SandboxOptions
import zombie.config.BooleanConfigOption
import zombie.config.ConfigOption
import zombie.config.IntegerConfigOption

/** A server owner's setting by name; an option that is not there, or of another kind, is a mismatch and throws. */
object SandboxOption {
  fun int(name: String): Int = option<IntegerConfigOption>(name, "an integer").value

  fun boolean(name: String): Boolean = option<BooleanConfigOption>(name, "a boolean").value

  private inline fun <reified T : ConfigOption> option(name: String, kind: String): T {
    val option = SandboxOptions.instance.getOptionByName(name)
      ?: throw IllegalStateException("the sandbox option $name is not registered; was sandbox-options.txt loaded?")
    return option.asConfigOption() as? T ?: throw IllegalStateException("the sandbox option $name is not $kind option")
  }
}
