# ZabricKotlin for mod authors

ZabricKotlin is the Kotlin runtime for ZombieBuddy Java mods (Workshop item 3820734224). The Workshop page links here.

It puts Kotlin 2.4.21 in place of the game's unused 1.4.10 for all Java mods when the mod list loads, before the mods
that use it, on every launch (no restart), and ships kotlinx-coroutines 1.11.0 and kotlinx-serialization-json 1.11.0.
console.txt says "[ZabricKotlin] ready: ..." or "[ZabricKotlin] not ready: ..." with the reason.

- Require Zabric and ZabricKotlin in your mod.info (require), load after ZabricKotlin (loadModAfter), and compile against the same Kotlin and kotlinx versions without bundling them.
- Your code needs no readiness check: ZabricKotlin installs Kotlin when ZombieBuddy reaches its jar in the mod list, on every launch, which is why you load after it: ZombieBuddy loads every class of your javaPkgName and applies your hooks when it reaches your jar. If installing fails, ZabricKotlin logs an error with the reason.
- Java code that loads Kotlin before ZombieBuddy reaches ZabricKotlin (a ZombieBuddy PreMain, a Zabric preLaunch entrypoint, or a mod above ZabricKotlin in the mod list) gets the game's 1.4.10 and keeps ZabricKotlin from installing. Code that runs at game start must be plain Java.
- This mod also ships the zb-kt-utils runtime (com.dont39.pz.zbktutils): feature gating, with each feature as the Zabric hook gate of its hooks, and access widening status through Zabric.

