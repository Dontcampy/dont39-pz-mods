package com.dont39.pz.zbktutils.glue

// Read at compile time by zb-kt-utils-processor, which generates the Lua bridge from it. Hooks use Zabric's
// annotations (com.pzlauncher.zabric.hook).

/** Exposes the function as the global Lua function [name]. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class LuaGlobal(val name: String)
