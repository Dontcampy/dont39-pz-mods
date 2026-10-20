package com.dont39.pz.common

import se.krka.kahlua.vm.KahluaTable

/** Lua numbers are doubles. */
fun KahluaTable.put(key: String, value: Number) = rawset(key, value.toDouble())
