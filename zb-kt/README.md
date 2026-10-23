# ZabricKotlin for mod authors

ZabricKotlin is the Kotlin runtime for ZombieBuddy Java mods (Workshop item 3820734224). The Workshop page links here.

It puts Kotlin 2.4.21 in place of the game's unused 1.4.10 for all Java mods and ships kotlinx-coroutines 1.11.0 and
kotlinx-serialization-json 1.11.0. It is a preload mod: Zabric keeps it running at game start, where it installs Kotlin
before any mod code runs. On a launch where it was not preloaded (its first launch, or after ZombieBuddy dropped its or
Zabric's preload entry) it installs Kotlin when ZombieBuddy reaches it in the mod list, before the mods that require it, and the
main menu asks for one restart. console.txt says "[ZabricKotlin] ready: ..." with "installed at game start" or "installed
when the mod list loaded", or "[ZabricKotlin] not ready: ..." with the reason.

- Require Zabric and ZabricKotlin in your mod.info (require), load after ZabricKotlin (loadModAfter), and compile against the same Kotlin and kotlinx versions without bundling them.
- Your code needs no readiness check: on every launch Kotlin is installed before ZombieBuddy reaches your jar in the mod list, which is why you load after ZabricKotlin: ZombieBuddy loads every class of your javaPkgName and applies your hooks when it reaches your jar. If installing fails, ZabricKotlin logs an error with the reason.
- Code at game start (a Zabric preLaunch entrypoint, the hooks of a preloaded mod, the classes ZombieBuddy loads when it preloads your jar) may use Kotlin when your mod requires ZabricKotlin: Zabric preloads ZabricKotlin before the preload mods that require it. On a launch where ZabricKotlin was not preloaded, that code runs before Kotlin is installed: it gets the game's 1.4.10 and keeps ZabricKotlin from installing, so every Kotlin mod fails that launch until the restart the main menu asks for. Java code at game start that loads no Kotlin class works on that launch too.
- This mod also ships the zb-kt-utils runtime (com.dont39.pz.zbktutils): feature gating, with each feature as the Zabric hook gate of its hooks, and access widening status through Zabric.
