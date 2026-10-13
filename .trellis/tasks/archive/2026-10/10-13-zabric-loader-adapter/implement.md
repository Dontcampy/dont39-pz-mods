# Implement: Zabric loader adapter layer

Gradle needs JDK 25: prefix commands with `JAVA_HOME=~/.jdks/graalvm-ce-25.0.2`.

## Checklist

1. **Loader package (R).** Create `zabric/src/main/java/com/dont39/pz/zabric/loader/` with `Loader` (public facade:
   `name`, `version`, `instrumentation`, `keepPreloaded`) and package-private ZB implementation. Move the
   `config.json` logic out of `preload/Preload.java`; keep `Preload`'s public contract. Delete `agent/`.
2. Point `AccessWidener.apply()` and `zb-kt/.../PreMain.java` at `Loader.instrumentation()`. Neutral Zabric messages
   use `Loader.name()`.
3. **Glue bindings (H).** Add `Hook.This/Argument/Return/Thrown/AllArguments` and `Hook.Exit.onThrow` in `Glue.kt`;
   update `GlueProcessor.kt` to read them and still emit `@Patch.*`; add `hook <class> <method>` listing lines.
4. Rewrite `HookTargets.kt` to check listing `hook` lines; confirm `checkGlue` in `dont39-all-in-one/build.gradle.kts`
   ignores them.
5. Migrate `AimShift.kt` and `ImeCaret.kt`; diff the generated glue under `build/generated/ksp/main/` before/after —
   it must be equivalent.
6. **Enforcement.** Extract the constant-pool scanner from `CheckBootstrapClasses.kt`; add `checkLoaderRefs` to the
   conventions with the exclusions in `design.md`; hook into `check`.
7. Version bumps for changed Workshop items per existing spec rules (Zabric API change; zb-kt ships the changed
   `zb-kt-utils`; All-in-One rebuilt).
8. **Specs.** `zombiebuddy-patching.md`: the seam rule + the switch checklist (link code, do not restate);
   bindings note now points at `Glue.kt`. `access-widening.md:44`: new path of the `g_instrumentation` read.
   `zb-kt-utils.md` / `index.md` if they name ZB bindings.

## Validation

```bash
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew build assembleMod
```

- Negative check: temporarily add `import me.zed_0xff.zombie_buddy.Patch` usage in a feature file → `build` fails in
  `checkLoaderRefs` naming the class; revert.
- `grep -rn "me.zed_0xff" --include=*.java --include=*.kt` outside `external-repos/` and `build/` lists only the loader
  package and `GlueProcessor.kt`.
- `./gradlew :dont39-all-in-one:smokeTest` and `:zb-kt:serverSmokeTest` pass (widening ok, ZombieBuddyKotlin ready,
  no new preload registration since `config.json` entries are unchanged).
- In game (optional, report if skipped): IME caret and Viewpoint blood fix still work.

## Risky points

- `Preload` split: the identical-copy rule and Zabric-first ordering must behave exactly as before, or every launch
  re-registers (smokeTest catches a registration line).
- Processor rewording must not change generated code; compare outputs.
