# Zabric Java hooks sample

A reference Java mod that hooks Project Zomboid through Zabric: the top of `build.gradle.kts` (dependencies, processor
options) and `GameTimeHooks.java` (one handler of each kind, on its own gate) are the parts to copy. It is a standalone
build that gets Zabric only from Maven (`mavenLocal()`, then Maven Central). The hook API is documented in the Javadoc
of `com.pzlauncher.zabric.hook`.

## Run

```bash
./gradlew check -PpzDir="C:/Program Files (x86)/Steam/steamapps/common/ProjectZomboid"
```

`pzDir` is the folder with `projectzomboid.jar` and `ZombieBuddy.jar`; it can also go in `~/.gradle/gradle.properties`.
Gradle runs on JDK 17 or newer; the compiler toolchain is JDK 25.

## What `check` verifies

- `verifyGlue`: the jar holds the generated glue and a hook listing with the expected lines.
- `probeUnsafeLoaderPatch`: a ZombieBuddy `@Patch` class with `@UnsafeLoaderPatch` compiles, with a warning.
- `probeLoaderPatch`, `probeUnsupportedAt`: a `@Patch` class without the marker, and `@At("INVOKE")`, fail to compile.

The probes in `src/probes/` never go into the jar.
