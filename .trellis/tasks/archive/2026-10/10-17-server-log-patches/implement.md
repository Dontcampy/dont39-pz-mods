# Implement: anim-extends-case

## Checklist

1. `anim-extends-case/build.gradle.kts` (`pz-feature`), `include(":anim-extends-case")` in `settings.gradle.kts`,
   add to `features` in `dont39-all-in-one/build.gradle.kts`.
2. `AnimExtendsCase.kt` (facade, short KDoc saying why) and `ExtendsPath.kt` (the `@ModifyReturnValue` hook), per
   `design.md`.
3. `AnimExtendsCase.init()` in `AllInOneMain.kt`.
4. Workshop section in `dont39-all-in-one/mod/workshop/workshop.txt`, following the existing ones: who is affected
   (Linux dedicated servers with the Workshop folder inside the server folder) and what it fixes (animation files that
   extend another file fail to load). No list of mods.
5. Build: `JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew build`.
6. Client check: `:dont39-all-in-one:smokeTest` (needs Steam; the feature must report `ready`).
7. Server check (AC1-AC3): the user publishes All-in-One 0.8.0 to the Workshop and restarts the server, which picks
   up the update; do not touch the server's files or restart it. After that, read `server-console.txt` (read-only)
   and compare with the 2026-10-17 copy: `FileNotFoundException` count, `AnimNode.Parse` traces, feature status line.

## Validation commands

```bash
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew build
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew :dont39-all-in-one:smokeTest
ssh sirius@192.168.50.249 "grep -ac FileNotFoundException /home/sirius/pz-only/myserver_Data/server-console.txt"
```

## Risk points

- Kotlin hook with `@ModifyReturnValue` and `@Self` has no existing Kotlin user in this repo (only the Java sample);
  if the processor rejects it, check `zabric-processor` messages before changing the design.
