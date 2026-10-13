# Compile against game and target mod jars instead of reflection

## Goal

Writing fixes through reflection (`Resolver.field/static/virtual/constructor`, string class names, `MethodHandle`
call sites) is slow to write and hides type errors until runtime. Feature code reaches game and target-mod members
with ordinary, type-checked Kotlin compiled against their jars, for vanilla fixes and for fixes to other mods alike.

## Background (evidence)

- The game jar is already a compile dependency (`buildSrc/src/main/kotlin/pz-java.gradle.kts`:
  `compileOnly(projectzomboid.jar)`); public `zombie.*` API is already called directly.
- Reflection remains for two reasons: other mods' jars are not on the compile classpath, and the JVM enforces
  non-public access at runtime.
- The spec rule that forbade compiling against other mods' jars ("it is not on the classpath") was wrong and was
  removed at the user's request (`quality-guidelines.md`, `zombiebuddy-patching.md`, `index.md`; uncommitted, part of
  this task).
- At runtime ZombieBuddy puts every Java mod jar on the system class loader (`Loader.java`,
  `appendToSystemClassLoaderSearch`), the same loader as the game: code compiled against a target jar links to it,
  and a class declared in the target's package shares its runtime package, so package-private access is legal. The
  target jars are not sealed.
- ZombieBuddy's own docs give no guidance on private access; its own code uses reflection (`Accessor.java`).
- ByteBuddy can change modifiers only when a class is first loaded (JVMTI forbids it on retransform); `UITextBox2` is
  already loaded when regular mods load. Loading earlier needs a ZombieBuddy preload mod: `javaPreload=true` in
  `mod.info`, `ZB-Preload: true` in the jar manifest, a signed jar, per-player approval, and
  `<javaPkgName>.PreMain.premain(String[])` (no `Instrumentation` passed). Signing is wired but disabled
  (`pz-java.gradle.kts` `zbSigning`, commit `a80ca29`: signed jars are blocked for players whose ZombieBuddy cannot
  reach Steam); the key is configured in `~/.gradle/gradle.properties`. ZombieBuddy keeps preload mods by mod id +
  paths and re-checks only the signature each launch (`Loader.PreloadMods`, `preloadMods`), so after one approval
  with "trust author" rebuilt jars load without a new prompt. The game JRE has `java.instrument` but no
  `jdk.attach`.
- Members reached by reflection today:
  - `viewpoint-blood-fx-fix` (BloodFX 1.5.1 `3811472160`, Viewpoint 0.1.6 `3809306528`, under
    `steamapps/workshop/content/108600/<id>/mods/<Mod>/42/media/java/client/*.jar`): public `BloodFx.debug`,
    `Relief.current()`, `FarRelief$Lift.scene(double, double)`; everything else is package-private: `BloodFx.drops`,
    `mists`, `splats`, `splatCount`, `GoreGpu.pieces`, `Aim.last`, the `Aim$Ray` record (private components with
    accessors) and constructor, and the `x/y/h` (`ox/oy/oh`) fields of `BloodFx$Drop`, `$Mist`, `$Splat`,
    `Gore$Piece`. Hook targets `bloodfx.Gpu.render`, `bloodfx.GoreGpu.render`, `bloodfx.Aim.target`.
  - `ime-keyboard-fix`: private `UITextBox2.xOffset`, `centerVertically`, `textOffsetOfLineStart`, read in the hook on
    `UITextBox2.render` itself; member-existence checks in `ImeKeyboardFix.resolve()` / `ImeCaret.resolve()`.
- `bloodfx.Main.VERSION` is a `public static final String`: a direct reference is inlined by the compiler, so the
  build-time value would be read; runtime version probing must stay by name (`TargetMod.kt`).

## Decisions

- D1 (user): delivered on `feat/ime-keyboard-fix`, in PR Dontcampy/dont39-pz-mods#3, together with the IME work.
- D2 (user, revised): non-public fields and classes of the game and of target mods are made public by an access
  widener (option C): one list of members, applied at build time to copies of the jars the code compiles against, and
  at runtime by a ByteBuddy transformer installed from `PreMain` before those classes load. All-in-One becomes a
  signed ZombieBuddy preload mod. Signing and the per-player preload approval are acceptable: the user is the only
  player. Only fields, classes and constructors are widened; other methods are not (a visibility change can alter virtual
  dispatch), constructors are not dispatched virtually.
- D2 history: `@Advice.FieldValue` (option A') was tried first and failed: ZombieBuddy 2.3.4 treats unknown advice
  parameter annotations as target-method parameters (`PatchEngine.ADVICE_PARAM_SPECIAL_ANNOTATIONS` lacks
  `FieldValue`), so the hook on `UITextBox2.render()` was silently not woven although `Transformed:` was logged
  (confirmed with a ByteBuddy class dump). Same-package bridges and `@Hook.Field` are dropped.
- D7 (user): the widener list follows Fabric's access widener semantics (`accessible` / `mutable`, widen-only,
  internal names + JVM descriptors validated at build time), one `.classtweaker` file per feature module; not
  NeoForge-style modifiers and not annotations (an annotation would have to be read before the code it sits on can
  compile, and Fabric/NeoForge both use files for widening).
- D8 (user): the preload is a separate, Java-only mod, `Zabric` (module `zabric`, package `com.dont39.pz.zabric`;
  renamed by the user on 2026-10-13 from the working name `Dont39AllInOnePreload`), own `javaPkgName`, no patches,
  holding `PreMain`, `AccessWidener`, `ClassTweaker` and the merged list, signed, shipped as a second mod folder in the
  same Workshop item; All-in-One stays non-preload and unsigned, requires it and loads after it. Reason: ZombieBuddy
  applies a package's patches in the first phase that loads it (`Loader.java`, `phases.size() == 1`), so a preloaded
  All-in-One would apply its Kotlin glue at premain, load `kotlin.*` from the game's Kotlin 1.4.10 and break
  ZombieBuddyKotlin's runtime swap, and run hooks before the Kotlin runtime and `Main`.
- D9 (user): the preload mod is not signed. The user's ZombieBuddy runs with `policy=allow-all`
  (`ProjectZomboid64.json`), which skips ZBS entirely, and ZombieBuddy registers a preload mod from the approval flow
  only when its signature is valid (`Loader.java`: `javaPreload() && zbs.valid() && hasManifestPreload`), so a signature
  can never register it there. Instead it is registered once under `preload_mods` in `~/.zombie_buddy/config.json`
  (written 2026-10-13); ZombieBuddy's premain loads entries from there without a signature check under `allow-all`.
- D10 (user): the preload mod registers itself, so the setup works on any machine (Workshop subscription or local
  deploy, any drive): a Java `Main` in the preload mod (ZombieBuddy calls `<javaPkgName>.Main.main` in the regular
  phase, preloaded or not) checks whether `PreMain` ran this launch; if not, it merges its own entry (absolute
  `mod.info` and jar paths derived from its own jar's location) into `preload_mods` of ZombieBuddy's `config.json`
  (`Agent.configDir()`, public), keeps everything else in the file, and logs that one restart is needed. The manual
  entry written on 2026-10-13 becomes unnecessary. Relative paths are not an option (ZombieBuddy resolves them
  against the game directory; the mods can be on another drive).
  When it registers (or finds itself registered but not preloaded), it also shows a restart dialog (user, 2026-10-13):
  a static `@LuaMethod(global = true)` `Zabric_restartNeeded` in the preload mod's package and a client
  Lua file in the preload mod open a vanilla single-OK `ISModalDialog` on the first `OnMainMenuEnter`; all in the
  preload mod, nothing in All-in-One. The paths come from the mod folder the game uses this session
  (`ChooseGameInfo`), so a moved copy updates the entry; the JSON is edited with the game's Jackson (ZombieBuddy's
  Gson is relocated to `zb.com.google.gson`, an internal).
- D3: `Instrumentation` comes from one reflective read of ZombieBuddy's package-private `Loader.g_instrumentation`
  inside zombiebuddy-kotlin-utils (the game JRE has no `jdk.attach`, so ByteBuddy self-attach is impossible). The
  preload entry point is Java, since the Kotlin runtime mod is not loaded at preload time.
- D4: hook targets are verified from a processor-generated list before `resolve()` (reflection only inside
  zombiebuddy-kotlin-utils); hand-written member checks go away. Direct references that do not match at runtime fail
  with a `LinkageError` at first use, inside the hook's `try`, and disable the feature.
- D5: presence and version of a target mod stay probed by class name (`TargetMod.kt`), since the mod may be absent and
  its version constant is inlined.
- D6 (user): the IMM calls through `WindowsLibrary.getFunctionAddress` + `JNI.callPP*` are native linking, not
  reflection, and stay as they are.

## Requirements

- R1: A feature declares its target mods in its build script (Workshop id + mod folder); the build compiles against
  those jars and fails with a clear message when one is not found locally (mod not subscribed).
- R2: Each feature declares the game/mod members it needs widened in its `.classtweaker` file (D7); the build compiles
  against widened copies, and a listed member that does not exist or has another type fails the build naming it.
- R3: At runtime the same list is applied before the classes load. If a listed class is already loaded, the preload
  did not run (not approved, ZombieBuddy changed), or the transformer cannot be installed, every feature that needs
  widening reports it and stays off (one line each, naming the cause); the game is unaffected.
- R4: Hook targets of a feature are verified at its first activation; a mismatch disables the feature naming the
  member.
- R5: `viewpoint-blood-fx-fix` and `ime-keyboard-fix` contain no reflection or `MethodHandle` code; `Resolver` keeps
  only what zombiebuddy-kotlin-utils itself needs (unused API removed).
- R6: Behavior of both features is unchanged.
- R7: smokeTest checks a feature's `Transformed:` lines when its last status is `ready` or `active` (today only
  `ready`), and checks the preload/widening line.
- R8: The spec describes the access widener, the preload/signing setup, how to reach game and mod members, and the
  pitfalls: inlined constants, target types only in classes loaded after the feature is active, no method widening
  except constructors,
  classes must not load before `PreMain`.

## Acceptance Criteria

- [ ] AC1 (R5): `grep` finds no `Resolver.`, `java.lang.reflect`, `MethodHandle` or `MethodType` in
      `viewpoint-blood-fx-fix/src` and `ime-keyboard-fix/src`.
- [ ] AC2 (R1/R2): `./gradlew build assembleMod` passes; a wrong BloodFX id, or a widened member that does not exist,
      fails the build naming it.
- [ ] AC3 (R3/R4/R6/R7): after approving preload once in game, smokeTest passes: the widening line,
      `viewpoint-blood-fx-fix: ready (BloodFX 1.5.1)`, `ime-keyboard-fix: ready` then `active`, `Transformed:` for
      `bloodfx.Gpu`, `bloodfx.GoreGpu`, `bloodfx.Aim`, `org.lwjglx.opengl.Display`, `zombie.ui.UITextBox2`; no
      `disabled` line. The ByteBuddy dump of `UITextBox2` shows our call woven in and `xOffset` public.
- [ ] AC4 (R6): In game, windowed: IME windows still sit at the caret, including a horizontally scrolled long line;
      blood on sloped terrain still sits on the ground with Viewpoint relief.
- [ ] AC7 (D10): with the `preload_mods` entry removed from `config.json`, one launch logs that the preload mod
      registered itself, `restart notice: pending` (the dialog shows on the main menu in a normal launch), and the
      entry reappears with this machine's paths; the next launch logs `access widener: ok` and
      `restart notice: not needed`.
- [ ] AC5 (R3): with preload not applied (e.g. approval revoked or `PreMain` skipped in a scratch build), the features
      that need widening log why they are off and the game runs normally.
- [ ] AC6 (R8): Spec updated; no rule left that prescribes `Resolver` lookups for members the compiler can reach.

## Out of Scope

- Widening methods other than constructors; changes to ZombieBuddy (an upstream API for `Instrumentation` or `FieldValue` support would be
  separate).
- New features or behavior changes.
