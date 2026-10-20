package com.dont39.pz.common

import zombie.Lua.LuaEventManager
import zombie.core.Translator

/**
 * Shows why a join stopped as vanilla's unknown host does: the join screen shows [key]'s text, with [args] as `%1`,
 * `%2`, ..., and a Back button.
 */
fun failJoin(key: String, args: List<String> = emptyList()) {
  LuaEventManager.triggerEvent("OnConnectFailed", Translator.getText(key, *args.toTypedArray()))
}
