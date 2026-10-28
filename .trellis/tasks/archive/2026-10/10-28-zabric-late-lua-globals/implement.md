# Implement

1. `Loader.globalLuaClasses()` in the seam.
2. `entrypoint/LuaGlobals.java` with `exposeMissing()` and the testable `expose(...)` core.
3. Call from `Lifecycle.modsLoaded`, own try/catch.
4. `LuaGlobalsTest` in `zabric/src/test/java/com/pzlauncher/zabric/entrypoint/`.
5. Bump `zabric` in `gradle/libs.versions.toml` to 0.7.3; README "What it does for players".
6. Validate: `JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew :zabric:test build`.
7. In game (myserver2 only, never `myserver`): build mods; stage Zabric 0.7.3 on the server and client; client menu list
   without Dont39AllInOne, server `Mods=` with it; join; check console. Then a client with it in the menu list.
8. Draft `upstream-issue.md` (ZombieBuddy: `addClassWithGlobalLuaMethod` should expose at once when Lua is up).
9. Spec: `zombiebuddy-patching.md` (Extending the Join / Lua row) — rule + link.

Rollback point: one commit; revert it.
