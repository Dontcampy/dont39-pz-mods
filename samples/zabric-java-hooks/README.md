# Zabric Java hooks sample

The reference for Java mods that hook Project Zomboid through Zabric. It is a standalone Gradle build: it is not part
of the dont39-pz-mods build and gets Zabric only from Maven (`mavenLocal()`, then Maven Central).

## Build

```bash
./gradlew check -PpzDir="C:/Program Files (x86)/Steam/steamapps/common/ProjectZomboid"
```

`pzDir` is the folder with `projectzomboid.jar` and `ZombieBuddy.jar` (installing ZombieBuddy copies it there); put it
in `~/.gradle/gradle.properties` to drop the flag. The game and ZombieBuddy jars are `compileOnly`: players have them.
Gradle runs on JDK 17 or newer; the compiler toolchain is JDK 25, as the game's.

## What to copy

- `build.gradle.kts`, top part: `compileOnly` on `com.pzlauncher:zabric`, `annotationProcessor` on
  `com.pzlauncher:zabric-processor` (same version), and the processor options `zabric.modId` (your mod id) and
  `zabric.javaPkgName` (your `javaPkgName`; the generated glue goes there).
- `src/main/java/.../hooks/GameTimeHooks.java`: one handler of each kind on `zombie.GameTime`: `@Inject` at `HEAD`
  (cancellable), `RETURN` and `FINALLY`, `@ModifyReturnValue`, `@ModifyVariable` and `@Share`.
- `SampleGate.java`: a gate of its own, so a failing handler switches off only its hook classes. Without
  `gate = ...` a hook class uses the mod's default gate, and one failure stops all of the mod's hooks.
- `mod/42/mod.info`: requires ZombieBuddy and Zabric.

The rules (what each annotation does, gates, ordering) are in the Javadoc of `com.pzlauncher.zabric.hook` and in the
"For mod authors" section of Zabric's Workshop page.

## What the checks show

- `verifyGlue`: the jar holds the generated glue (`ZabricJavaHooksSampleGlue`, one nested class per hooked method)
  and the listing `META-INF/zabric/hooks/<hook class>.txt` with the expected `patch` and `hook` lines.
- `probeUnsafeLoaderPatch`: a hand-written ZombieBuddy `@Patch` class with `@UnsafeLoaderPatch` compiles, with a
  warning that Zabric does not support it.
- `probeLoaderPatch`, `probeUnsupportedAt`: a `@Patch` class without the marker, and `@At("INVOKE")`, are compile
  errors.

The probes in `src/probes/` are compiled only by those tasks, never into the jar.
