# Implementation Plan

Gradle must run on JDK 25: `JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew ...`.

## Checklist

1. **Versions.**
   - Confirm Kotlin `2.4.20` (done: bumped to `2.4.21`, coroutines `1.11.0`) in `gradle/libs.versions.toml` is the latest stable release.
   - Check whether kotlinx-coroutines and kotlinx-serialization have newer releases compatible with that Kotlin on Maven
     Central, and bump the catalog only if so.
   - Add catalog entries: `kotlinStdlib`, `kotlinxCoroutinesCore`, the serialization Gradle plugin artifact.
2. **buildSrc.**
   - Add the serialization plugin artifact to `buildSrc/build.gradle.kts`.
   - Create `pz-kotlin.gradle.kts`.
   - Extract `pz-mod.gradle.kts` (deployWorkshop + Workshop-id guard) from `dont39-all-in-one/build.gradle.kts`.
   - Delete `kotlin-jvm.gradle.kts`.
   - Add `kotlin.stdlib.default.dependency=false` to `gradle.properties`.
   - Rollback point: `./gradlew build assembleMod` still passes with All-in-One unchanged in behavior.
3. **Library module `zombie-buddy-kotlin`.**
   - Register it in `settings.gradle.kts`.
   - Write `build.gradle.kts`: `bundled` + `stdlib` configurations, generated `stdlib.properties`, jar merge,
     `assembleMod`, and `pz-mod`.
   - Add the bytecode check task that fails if `Main`/`ZombieBuddyKotlin` reference `kotlin/`.
   - Add `mod/42/mod.info` and `mod/workshop/workshop.txt` without an `id=` line.
   - Copy All-in-One's poster/preview images as placeholders, and report them as placeholders.
4. **Library code.**
   - `Main.java` and `ZombieBuddyKotlin.java`: swap sequence and readiness API per `design.md`.
   - `SelfCheck.kt`: a stdlib API newer than 1.4 (e.g. `kotlin.uuid.Uuid` or `enumEntries`), `runBlocking`, and a
     `@Serializable` round-trip.
5. **All-in-One.**
   - Apply `pz-kotlin`.
   - Replace `Main.java` with `Main.kt`: `object Main` + `@JvmStatic fun main`, with readiness handling per `design.md`.
   - Update `mod.info` (`require`, `loadModAfter`), `workshop.txt` (requirements, load order), and the version.
6. **smokeTest.** Add `dependsOn` on the library's `deployWorkshop`, plus the library's loaded/ready expectations.
7. **Specs (Phase 3).** Update `directory-structure.md`, `quality-guidelines.md`, `logging-guidelines.md` and
   `index.md`. Add a `kotlin-runtime.md` covering the bootstrap contract, the Java-only rule, and no bundling of
   `kotlin`/`kotlinx` in dependent mods.

## Validation

```bash
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew build assembleMod
```

- Inspect the jar contents per the PRD acceptance criteria with `unzip -l` on both assembled jars.
- `smokeTest` needs Steam running and both mods enabled in the game's mod list, library first.
- Negative check: corrupt the deployed `lib/kotlin-stdlib-*.jar`, rerun the game to init, and read `console.txt`.
  Then redeploy.

## Risky Files

- `dont39-all-in-one/build.gradle.kts`: the `pz-mod` extraction must keep the Workshop-id guard behavior identical.
- `ZombieBuddyKotlin.java`: must never reference a Kotlin type statically.
- `dont39-all-in-one/mod/42/mod.info`: changing `require` affects published subscribers.
