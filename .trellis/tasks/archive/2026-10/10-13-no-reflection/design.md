# Design: access widener instead of reflection

## 1. Target mod jars on the compile classpath (build)

- `buildSrc/src/main/kotlin/pz-feature.gradle.kts` gains an extension, e.g.
  `pzTargets { workshopMod(3811472160, "ViewpointBloodFX") }`, resolving
  `<pzDir>/../../workshop/content/108600/<id>/mods/<mod>/42/media/java/**/*.jar`. Missing → `GradleException` naming
  the mod and id ("subscribe to it in Steam"). Never shipped (All-in-One's jar takes only each feature's own output).

## 2. The widener list (Fabric-style semantics, D7)

- One file per feature module: `src/main/resources/META-INF/dont39/<module>.classtweaker`. First line
  `dont39-classtweaker v1`; then one directive per line, `#` comments, internal names with `/`, JVM descriptors:
  ```
  accessible class bloodfx/Aim$Ray
  accessible field zombie/ui/UITextBox2 xOffset I
  accessible method bloodfx/Aim$Ray <init> (DDDDDDJ)V
  mutable field <owner> <name> <desc>
  ```
- `accessible` makes the member public (and its declaring class, plus enclosing classes of nested ones); `mutable`
  removes `final` from a field and must be paired with `accessible` if the field is not public. Only widens, never
  narrows. `accessible method` is allowed only for `<init>`; `extendable` and other methods are rejected (D2).
- Descriptors are checked against the jar at build time: a missing class/member or a type mismatch fails the build
  naming the line (unlike Loom, validation is not optional).
- All-in-One ships the merged directives for `PreMain` to read; duplicates across features are fine.

## 3. Build: widened compile jars

- A Gradle artifact transform or task in buildSrc rewrites the game jar and each declared target jar with ASM (from the
  ZombieBuddy jar or a build-only dependency): listed classes/fields/constructors get `ACC_PUBLIC` (`mutable` clears `ACC_FINAL`) (private/protected/package flags
  cleared), including the `InnerClasses` attribute entry for nested classes, so javac/kotlinc accept the access.
- `compileOnly` uses the widened copies instead of the originals. A listed member that is not found fails the build
  naming it (R2).
- Widen exactly the listed members (not everything), so compile-time access matches runtime access; anything else stays
  inaccessible to the compiler.

## 4. Runtime: preload + transformer

- A separate Java-only preload mod, Zabric (module `zabric`, D8): `javaPreload=true`, `ZB-Preload: true`, signed
  (`zbSigning`; key in `~/.gradle/gradle.properties`), no patches, own `javaPkgName`; shipped as a second mod folder of
  the same Workshop item. All-in-One stays unsigned and non-preload, `require`s it and loads after it. Update the Workshop text.
- Its `PreMain` (Java, no Kotlin: the Kotlin runtime mod is not loaded yet) calls `AccessWidener` (moved into the
  preload module so it is in one jar only):
  1. obtain `Instrumentation` by reading ZombieBuddy's `Loader.g_instrumentation` (one reflective read, D3);
  2. read the merged list;
  3. fail if any listed class is already loaded (`getAllLoadedClasses`): widening it is impossible now;
  4. install a ByteBuddy `AgentBuilder` (no retransformation, so it is registered retransform-incapable and later
     retransforms by ZombieBuddy start from the widened bytes) whose transformer applies `ModifierAdjustment`
     (public type / fields / constructors, non-final for `mutable`) to the listed types only.
  The outcome (ok, or the reason it failed) is kept in a static the Kotlin side can read. Nothing in `PreMain` may
  throw into ZombieBuddy: it records the failure instead.
- Not approved / not run: the state stays "not run".
- `Feature`: a feature that declares widened members checks that state before `resolve()` (the preload mod being
  absent is one such state, probed by class name); anything but ok makes it
  idle/disabled with the reason (R3). Features without widened members are unaffected.

## 5. Generated hook-target checks

- The list of hook targets is the `@Patch` annotations of the generated `<Feature>Patches` classes (no extra generated
  file); `Feature` verifies them before `resolve()` with reflection inside zombiebuddy-kotlin-utils (`HookTargets.kt`),
  naming the missing member. Replaces the
  hand-written checks in `ImeKeyboardFix.resolve()` / `ImeCaret.resolve()`.

## 6. Migration

- `ime-keyboard-fix`: widen `zombie.ui.UITextBox2` fields `xOffset`, `centerVertically`, `textOffsetOfLineStart`;
  `ImeCaret` reads them directly; drop its `resolve()` and the facade's member checks. IMM/JNI untouched (D6).
- `viewpoint-blood-fx-fix`: declare BloodFX/Viewpoint targets; widen `bloodfx.BloodFx` (`drops`, `mists`, `splats`,
  `splatCount`, `debug` is already public), nested particle classes and their `x/y/h`/`ox/oy/oh`, `bloodfx.GoreGpu`
  (`pieces`), `bloodfx.Aim` (`last`), `bloodfx.Aim$Ray` (class and its canonical constructor; components via its public accessors). Direct calls
  for public Viewpoint API. Per-frame loops keep no allocation/boxing; check with `javap -c`.
- zombiebuddy-kotlin-utils: prune unused `Resolver` API (`TargetMod.kt` keeps its by-name probing).

## 7. smokeTest

- Check `Transformed:` lines when the last status is `ready` or `active`; check the widening ok line.
- Preload needs a one-time in-game approval before smokeTest can see it; smokeTest fails with a clear message when the
  widening line says "not run".

## 8. Spec

Rewrite `zombiebuddy-patching.md` "Reaching Other Mods" and Gating bullets, `zombiebuddy-kotlin-utils.md`,
`directory-structure.md` (Adding a Feature: targets + widen list), `error-handling.md` rows, `quality-guidelines.md`
(build gate, preload approval for smokeTest, signing). Rules + links, no restated code.

## Rollback

Revert the commit(s); All-in-One returns to a non-preload unsigned mod.
