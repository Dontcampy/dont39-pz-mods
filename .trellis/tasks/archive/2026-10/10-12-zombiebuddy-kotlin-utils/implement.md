# Implement: zombiebuddy-kotlin-utils

Done together with `10-12-viewpoint-kotlin` (its first user); this task's code lands first, the port then proves it.

## Checklist

1. `zombiebuddy-kotlin-utils/build.gradle.kts` (pz-kotlin); `settings.gradle.kts`.
2. `dont39-all-in-one/build.gradle.kts`: `internalLibs` list, dependency, jar inclusion (design.md "Packaging").
3. `Resolver`, `Feature`, target-version helpers in `src/main/kotlin/com/dont39/pz/zombiebuddykotlinutils/`.
4. Port viewpoint onto it (sibling task); trim any utils API the port did not use.
5. Specs: new `.trellis/spec/mod/zombiebuddy-kotlin-utils.md` (or a section in `kotlin-runtime.md`) — when to use
   it, link the files, "one bundling mod only; promoting to the library is a separate decision". Update
   `directory-structure.md` layout, `error-handling.md` / `logging-guidelines.md` / `zombiebuddy-patching.md` reference
   pointers to the Kotlin files. Link, don't restate.

## Validation

```
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew build assembleMod
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew :dont39-all-in-one:smokeTest
```

- `unzip -l` All-in-One jar: utils classes present; no `kotlin/`, `kotlinx/`.
- `javap -c` on the ported hot paths (R5).
- Compare `console.txt` lifecycle lines with a run of the current Java build (R6).

## Risky Points

- Over-abstracting from one user: keep only what the viewpoint port uses.
- Base-class initializer touching new stdlib APIs before the readiness gate.
