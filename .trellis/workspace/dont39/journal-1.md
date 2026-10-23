# Journal - dont39 (Part 1)

> AI development session journal
> Started: 2026-10-12

---



## Session 1: Bootstrap Trellis spec for ZombieBuddy mods
<!-- trellis-session: v=2 fp=bfd5365ce2b73e2e -->

**Date**: 2026-10-12
**Task**: Bootstrap Trellis spec for ZombieBuddy mods
**Branch**: `main`

### Summary

Replaced template backend/frontend specs with .trellis/spec/mod/ (index, directory-structure, zombiebuddy-patching, error-handling, logging, quality) for the Java ZombieBuddy mod repo. Specs state rules and link code files and the external-repos/ZombieBuddy docs/source instead of restating logic. Verified the @Patch/@LuaMethod exact-javaPkgName rule in PatchEngine.collectPatches (ModdingGuide example subpackage would not load). Synced with cbad9ce (mod/42 layout, Lua mod option, signing, smokeTest; new features must extend smokeTest expectations). Build gate needs a JDK 25 Gradle runtime; build assembleMod passed. Kept DebugType.Mod over ZombieBuddy's internal Logger. Bootstrap task archived.

### Git Commits

| Hash | Message |
|------|---------|
| `76fa026` | Remove outdated documentation files for database, directory structure, error handling, and quality guidelines |

### Status

[OK] **Completed**


## Session 2: ZombieBuddyKotlin library mod and Kotlin All-in-One
<!-- trellis-session: v=2 fp=cd2213133ef50829 -->

**Date**: 2026-10-12
**Task**: ZombieBuddyKotlin library mod and Kotlin All-in-One
**Branch**: `feat/kotlin-lib`

### Summary

Added ZombieBuddyKotlin, a prerequisite Workshop mod that swaps the game's bundled kotlin-stdlib 1.4.10 for 2.4.21 via the bootstrap class loader (MAIN phase, SHA-256 checked) and ships kotlinx-coroutines/serialization 1.11.0 unrelocated. New pz-kotlin and pz-mod build conventions. All-in-One 0.2.0: Kotlin Main, requires and loads after ZombieBuddyKotlin. smokeTest passed in game (ready: Kotlin 2.4.21); negative corrupted-stdlib check skipped. Specs: kotlin-runtime.md, trimmed to rules and links. PR Dontcampy/dont39-pz-mods#1. Next: upload ZombieBuddyKotlin first, record its Workshop id, link it from All-in-One's workshop.txt, then upload All-in-One. KSP deferred until a second Kotlin feature shows repeated boilerplate.

### Git Commits

| Hash | Message |
|------|---------|
| `7803c3d` | Add ZombieBuddyKotlin, a Kotlin runtime library mod |
| `9a9e383` | Move All-in-One's Main to Kotlin and require ZombieBuddyKotlin |
| `852ec6c` | Document the Kotlin runtime and the second shipped mod in the specs |
| `bbceb55` | Trim the specs to rules and links |

### Status

[OK] **Completed**


## Session 3: Kotlin utils, KSP glue, viewpoint in Kotlin, Mod Options sidebar
<!-- trellis-session: v=2 fp=32bebe155a61e1c0 -->

**Date**: 2026-10-12
**Task**: Kotlin utils, KSP glue, viewpoint in Kotlin, Mod Options sidebar
**Branch**: `feat/kotlin-features-sidebar`

### Summary

Added zombiebuddy-kotlin-utils (Feature/Resolver), KSP glue generation (@Hook/@LuaGlobal -> generated @Patch/@LuaMethod in com.dont39.pz.allinone, KSP 2.3.12), ported viewpoint-blood-fx-fix to pure Kotlin, added the Options > Mods sidebar (jump + highlight, last choice saved across restarts), All-in-One 0.3.0. build + smokeTest pass; in-game checks and corrupted-stdlib negative check not done. Follow-ups: reject @Patch.Local, type-check Thrown/Return/AllArguments params.

### Git Commits

| Hash | Message |
|------|---------|
| `5ca2f8e` | Add zombiebuddy-kotlin-utils and KSP glue generation |
| `594f9bb` | Port viewpoint-blood-fx-fix to Kotlin |
| `6d0f9ec` | Add a sidebar to Options > Mods |
| `39789de` | Wire the new modules into All-in-One; 0.3.0 |
| `b837be4` | Update the mod specs for Kotlin features, generated glue and Lua-only features |

### Status

[OK] **Completed**


## Session 4: PR #2 follow-ups: processor binding checks, no ZombieBuddyKotlin fallback, comment/spec trims
<!-- trellis-session: v=2 fp=2a8141ee5d8b0381 -->

**Date**: 2026-10-12
**Task**: PR #2 follow-ups: processor binding checks, no ZombieBuddyKotlin fallback, comment/spec trims
**Branch**: `feat/kotlin-features-sidebar`

### Summary

Processor rejects @Patch.Local and checks Thrown/AllArguments/Argument/Return types. Specs trimmed to rules and links. Dropped the ZombieBuddyKotlin-not-ready fallback (user: never handle that case): Main no longer checks readiness, Feature uses an enum state without INERT, library isReady()/problem() removed (ZombieBuddyKotlin 0.2.0), last 1.4.10 workarounds removed. BloodFX debug field resolved once. Feature modName is required. Comments cut to the non-obvious why. build + smokeTest pass; in-game checks still open on PR #2.

### Git Commits

| Hash | Message |
|------|---------|
| `b6ffd1b` | Check hook parameter bindings and types at compile time |
| `563dcd6` | Trim the mod specs to rules and links |
| `2c3452c` | Drop the fallback for ZombieBuddyKotlin not being ready |
| `f9c4a89` | Remove ZombieBuddyKotlin's readiness API; 0.2.0 |
| `69636d5` | Drop the last workarounds for the game's old Kotlin |
| `4abf653` | Resolve BloodFX's debug field once |
| `1463527` | Make Feature's mod name a required argument |
| `cdb4e1e` | Shorten the comments to the non-obvious reasons |

### Status

[OK] **Completed**


## Session 5: IME keyboard fix
<!-- trellis-session: v=2 fp=a2d7ed94fba9bcec -->

**Date**: 2026-10-12
**Task**: IME keyboard fix
**Branch**: `main`

### Summary

Added ime-keyboard-fix: on Windows the IME is detached from the game window outside text boxes (ImmAssociateContextEx, IACE_DEFAULT on re-attach keeps CN/EN mode), hooked at Display.processMessages exit for the window thread. smokeTest and in-game checks passed; patching spec updated; PR Dontcampy/dont39-pz-mods#3. Found that the bundled GLFW hides the IME composition/candidate UI (vanilla) - next task.

### Git Commits

| Hash | Message |
|------|---------|
| `a8aaf8b` | Add ime-keyboard-fix: keep the IME out of the game window outside text boxes |

### Status

[OK] **Completed**


## Session 6: IME candidate windows
<!-- trellis-session: v=2 fp=ebb0c9ccc0d33caa -->

**Date**: 2026-10-13
**Task**: IME candidate windows
**Branch**: `feat/ime-keyboard-fix`

### Summary

ime-keyboard-fix now subclasses the game window procedure to show the system IME composition/candidate windows (GLFW hides them), swallows WM_IME_CHAR, and places them at the UITextBox2 caret. Works in windowed mode; in the game's borderless mode (undecorated desktop-sized window) Windows treats the game as fullscreen and WeChat IME hides its windows - accepted limitation. Fixed Resolver.virtual default asType. Pushed to PR Dontcampy/dont39-pz-mods#3.

### Git Commits

| Hash | Message |
|------|---------|
| `31c8d9e` | Show the IME composition and candidate windows at the text caret |

### Status

[OK] **Completed**


## Session 7: Typed access via access widener (Zabric)
<!-- trellis-session: v=2 fp=eb89c50652dced4c -->

**Date**: 2026-10-13
**Task**: Typed access via access widener (Zabric)
**Branch**: `feat/ime-keyboard-fix`

### Summary

Replaced reflection with typed access: target mod jars on the compile classpath (pzTargets), Fabric-style .classtweaker widening at build time, and Zabric (new unsigned Java preload mod) widening the same members at runtime before classes load; it self-registers in ZombieBuddy config.json under policy=allow-all and shows a restart dialog. @Advice.FieldValue was tried first and fails in ZombieBuddy 2.3.4 (silently not woven). Migrated ime-keyboard-fix and viewpoint-blood-fx-fix, removed Resolver, rewrote specs. Verified by smokeTest and in game. PR Dontcampy/dont39-pz-mods#3.

### Git Commits

| Hash | Message |
|------|---------|
| `6634038` | Reach game and target-mod members by direct access instead of reflection |

### Status

[OK] **Completed**


## Session 8: Idiomatic Kotlin, lazy logging, module rename
<!-- trellis-session: v=2 fp=7d442686f7486225 -->

**Date**: 2026-10-13
**Task**: Idiomatic Kotlin, lazy logging, module rename
**Branch**: `feat/ime-keyboard-fix`

### Summary

Trimmed specs to rules and links. Rewrote the Kotlin code to kotlinlang.org idioms and coding conventions (forEach, property syntax, check/error, coerceAtMost) and dropped the spec bans on iterators/lambdas/allocation on per-frame paths, since the JIT handles short-lived objects. Added LoggerUtils (lzInfo/lzDebug/lzWarn DebugType extensions that check severity before building the message); Feature.log/debug wrap them with the prefix. Java logs use DebugType format arguments with compile-time-constant patterns; printException keeps concatenation because the game's varargs overload drops its arguments. Removed redundant 'as Any' casts and the wrong varargs-overload spec example. Added Kotlin/Java wrong-vs-correct logging examples to logging-guidelines.md. Renamed module zombie-buddy-kotlin to zombiebuddy-kotlin. smokeTest passes.

### Git Commits

| Hash | Message |
|------|---------|
| `5f15a46` | Trim the specs to rules and links |
| `22c5703` | refactor: simplify property access and improve readability |
| `2eeac59` | Write idiomatic Kotlin and build log messages only when they are written |
| `3539b32` | docs: update logging guidelines to clarify message building rules |
| `bac5b39` | Rename the zombie-buddy-kotlin module to zombiebuddy-kotlin |

### Status

[OK] **Completed**


## Session 9: Zabric as a standalone platform, ZombieBuddyKotlin built on it
<!-- trellis-session: v=2 fp=d762784848826b04 -->

**Date**: 2026-10-13
**Task**: Zabric as a standalone platform, ZombieBuddyKotlin built on it
**Branch**: `zb-kt-on-zabric`

### Summary

Planned (D1-D10) and implemented: Zabric as its own Workshop item that widens every installed mod's .classtweaker list at premain with per-mod status, a public preload registration service with an order-independent handshake and identical-copy rule, and a restart dialog listing mods; ZombieBuddyKotlin preloaded through Zabric, Kotlin swap at premain only, zb-kt-utils shipped inside it, isReady() gate in generated glue and Main; All-in-One ships its own merged list. Verified with client smokeTest (register, then pass), new serverSmokeTest on the dedicated server, and a scratch broken-list mod. Fixed load-order registration bug and pre-existing configuration-cache problems. Specs trimmed to rules and links after user feedback. PR Dontcampy/dont39-pz-mods#5. Not verified: mid-session restart-list path; in-game IME/blood check.

### Git Commits

| Hash | Message |
|------|---------|
| `6264e41` | Make Zabric a standalone platform and build ZombieBuddyKotlin on it |
| `54ccb04` | Trim the specs to rules and links |

### Status

[OK] **Completed**


## Session 10: Zabric loader seam for ZombieBuddy
<!-- trellis-session: v=2 fp=57dab4758624314e -->

**Date**: 2026-10-13
**Task**: Zabric loader seam for ZombieBuddy
**Branch**: `zabric-loader-adapter`

### Summary

Planned (D1 scope R+H, D2 compile-time seam) and implemented a loader seam: Zabric's new loader package (Loader, PreloadConfig) is the only runtime code using ZombieBuddy; ZombieBuddyAgent removed; Preload contract and config.json bytes unchanged. Hooks bind parameters with neutral Hook.This/Argument/Return/Thrown/AllArguments and Hook.Exit.onThrow; GlueProcessor still emits identical @Patch glue; glue listing gains hook lines checked by HookTargets. checkLoaderRefs (shared constant-pool scanner with checkBootstrapClasses) fails the build on ZombieBuddy refs outside the seam. Specs: Loader Seam section + switch checklist. Zabric 0.3.0, zb-kt 0.4.0, All-in-One 0.5.0. Verified build, negative check, smokeTest, serverSmokeTest; in-game IME/blood check not done. PR Dontcampy/dont39-pz-mods#6.

### Git Commits

| Hash | Message |
|------|---------|
| `ee284b5` | Put ZombieBuddy behind a loader seam in Zabric |

### Status

[OK] **Completed**


## Session 11: Zabric phased entrypoints: load-order independence
<!-- trellis-session: v=2 fp=adff916110f48232 -->

**Date**: 2026-10-14
**Task**: Zabric phased entrypoints: load-order independence
**Branch**: `zabric-load-order`

### Summary

Zabric gets Fabric-style phased entrypoints (manifest Zabric-PreLaunch/Zabric-Main; preLaunch on the game's main, main after each ZombieBuddy pass, only for mods ZombieBuddy approved and loaded) and drives preload registration from them; the system-property handshake is gone. ZombieBuddyKotlin is no longer a preload mod: its ZombieBuddy Main swaps Kotlin before any dependent; dependents and generated glue no longer check readiness. loadModAfter lists only direct class-path dependencies. Tried and dropped: an own-jar glue gate (overkill) and preload plus late swap. Client and server smoke tests pass; smokeTest configuration-cache fix found during client checks. PR #7.

### Git Commits

| Hash | Message |
|------|---------|
| `54b6e53` | Give Zabric phased entrypoints so mods do not depend on load order |
| `f90b1e3` | Keep smokeTest's expected lines out of the script object |
| `5d593d2` | List only direct dependencies in loadModAfter; trim specs to rules and links |

### Status

[OK] **Completed**


## Session 12: Move hook gate and catch into generated glue
<!-- trellis-session: v=2 fp=2ff0976bdf5e235f -->

**Date**: 2026-10-14
**Task**: Move hook gate and catch into generated glue
**Branch**: `main`

### Summary

GlueProcessor now generates Java patch classes: each advice gates on the facade's active() (paired exit via @Patch.Local, exact per call) and catches Throwable into Feature.disable; Hook.always for unpaired hooks that must run inactive (ImeWindow.sync); compile errors for non-public hooks, always on paired hooks, missing/duplicate facade. All hooks dropped their own gate and try/catch; specs updated. build + smokeTest pass; paired-hook path not exercised in game (BloodFX 1.5.2 idle). PR #8 open for review. Next: remove viewpoint-blood-fx-fix after merge.

### Git Commits

| Hash | Message |
|------|---------|
| `ec4f25d` | Move the hook gate and catch into the generated glue |

### Status

[OK] **Completed**


## Session 13: Remove viewpoint-blood-fx-fix
<!-- trellis-session: v=2 fp=76a44c24a01c4a3a -->

**Date**: 2026-10-14
**Task**: Remove viewpoint-blood-fx-fix
**Branch**: `remove-viewpoint-blood-fx-fix`

### Summary

Removed the Blood FX for Viewpoint feature module and its wiring (settings, features list, AllInOneMain, loadModAfter); Workshop/mod.info text now covers only the vanilla-only features; All-in-One 0.6.0. Shared platform code kept (TargetMod, force, LuaGlobal + bridge, hook bindings, paired-hook Local gate, PzTargets) per user decision; specs repointed to ime-keyboard-fix and gained a rule that generic platform API is not removed with its first user. build + smokeTest pass (0.6.0 loaded, ime-keyboard-fix active).

### Git Commits

| Hash | Message |
|------|---------|
| `55908c8` | Remove the Blood FX for Viewpoint fix |

### Status

[OK] **Completed**


## Session 14: Report IME undo failures and errors after a disable
<!-- trellis-session: v=2 fp=bf07ed753cc4a5a6 -->

**Date**: 2026-10-14
**Task**: Report IME undo failures and errors after a disable
**Branch**: `remove-viewpoint-blood-fx-fix`

### Summary

Feature.disable now logs errors after the feature is disabled as 'disabled; then an error while ...' (still read as disabled by smokeTest); resolveOnce no longer retries after a racing failure. ImeWindowProc/ImeContext restores drop their empty catches, check Win32 results and throw; each clears state first so a failure is reported once; ImeWindow.restore runs both and throws the first with the second suppressed. Specs updated. build + smokeTest pass; the after-disable path verified by code review only. Folded into PR #9 at the user's request.

### Git Commits

| Hash | Message |
|------|---------|
| `49faa7b` | Report errors after a disable and stop swallowing IME undo failures |

### Status

[OK] **Completed**


## Session 15: Zabric hooks: Java mod API, generated glue, weave check
<!-- trellis-session: v=2 fp=3a58baa4b1dee357 -->

**Date**: 2026-10-15
**Task**: Zabric hooks: Java mod API, generated glue, weave check
**Branch**: `zabric-hooks`

### Summary

Zabric hook API for third-party Java mods (MixinExtras-style @Hooks/@Inject/@ModifyReturnValue/@ModifyVariable/@Share, gates); zabric-processor generates ZombieBuddy glue (javac + KSP front ends, FreeMarker templates); hand-written @Patch rejected unless @UnsafeLoaderPatch; weave check reports silently dropped advice; IME hooks migrated; com.pzlauncher rename, MIT license, Maven Central publish setup, sample author build. PR Dontcampy/dont39-pz-mods#10. Open: in-game IME check, Maven Central release (account, DNS, GPG), KSP processor unit tests.

### Git Commits

| Hash | Message |
|------|---------|
| `27c410f` | Move Zabric to com.pzlauncher.zabric and license the repo under MIT |
| `72c5b1b` | Give Zabric a hook API and runtime, with a check that hooks were woven |
| `5cac403` | Add zabric-processor, which turns Zabric hook classes into loader glue |
| `f0c4719` | Build the Kotlin glue on Zabric's hook API |
| `56b8197` | Publish Zabric and zabric-processor as com.pzlauncher artifacts, with a sample author build |
| `c0c3df5` | Document Zabric hooks for mod authors and in the specs |
| `201c0da` | Trim the hook specs to rules and links |
| `fcf510c` | Trim the comments to the reasons |
| `7a8b06f` | Run the sample with the repository's Gradle wrapper |
| `58f3f02` | Render the processor's glue and listings with FreeMarker templates |
| `10b7466` | Fix IDE inspection warnings |
| `96c51a9` | Update the Gradle wrapper to 9.8.0 |
| `90ed4ca` | Give the Gradle daemon room for KSP and ignore heap dumps |

### Status

[OK] **Completed**


## Session 16: Release Zabric to Maven Central from GitHub Actions
<!-- trellis-session: v=2 fp=9bac0f9d32363976 -->

**Date**: 2026-10-16
**Task**: Release Zabric to Maven Central from GitHub Actions
**Branch**: `ci-maven-central`

### Summary

Tag zabric-v<version> workflow: verify job (version check, :zabric/:zabric-processor checks) then publish job gated by the maven-central Environment, secrets on the publish step only. Game jar from the dedicated server (380870, unstable) via DepotDownloader, anonymously; DepotDownloader and ZombieBuddy.jar via gh release download, pinned by tag; Gradle caching off so the game jar never leaves the runner. gradlew made executable. Environment and secrets set up by the user. PR Dontcampy/dont39-pz-mods#11. Open: merge, verify namespace and GPG key, test tag zabric-v0.0.0, first release zabric-v0.4.0 (AC1-AC5 on GitHub), then switch the sample to mavenCentral only.

### Git Commits

| Hash | Message |
|------|---------|
| `1360000` | Make gradlew executable |
| `3209316` | Release Zabric to Maven Central from GitHub Actions |
| `5063a9b` | Reference the release workflow's actions by version tag |
| `3c1b3a4` | Fetch the release workflow's tools with gh release download |

### Status

[OK] **Completed**


## Session 17: Cut mod load time: skip ZombieBuddy's network requests and missing animation folders
<!-- trellis-session: v=2 fp=29053c3845cac1f1 -->

**Date**: 2026-10-17
**Task**: Cut mod load time: skip ZombieBuddy's network requests and missing animation folders
**Branch**: `reduce-load-time`

### Summary

Measured startup and server join from DebugLog timestamps (65 s to menu, 89 s join). ZombieBuddy's Steam ban check and authors.json download blocked every mod list load on 10-50 s timeouts; new Java mod ZBSkipOnlineChecks advises all three ZombieBuddy requests, starting as a mod (Zabric preLaunch) or via patches_jar (also covers ZombieBuddy's premain request). All-in-One 0.7.0 adds missing-anim-folders, which skips AdvancedAnimator walks of nonexistent AnimSets/actiongroups (918 stack traces at join). In game: ZB join pass 25.9 s -> <1 s, join 89 -> 67-70 s, traces 0. Server-join ResetLua (B2) left as vanilla: 166 mod Lua files branch on isClient at load. No allow-all policy guard by user decision; PR #13.

### Git Commits

| Hash | Message |
|------|---------|
| `38db17d` | Add ZBSkipOnlineChecks, which skips ZombieBuddy's network requests |
| `a2b0a31` | Skip missing animation folders when loading mods |

### Status

[OK] **Completed**


## Session 18: Fix lowercased x_extends animation paths on Linux servers
<!-- trellis-session: v=2 fp=5af35e32c2979834 -->

**Date**: 2026-10-17
**Task**: Fix lowercased x_extends animation paths on Linux servers
**Branch**: `anim-extends-case`

### Summary

Read the Linux dedicated server log and picked one fix. ZomboidFileSystem.resolveRelativePath lowercases x_extends paths for mod files under the server folder, so 175 animation nodes from six mods failed to load (350 FileNotFoundException). Added the All-in-One feature anim-extends-case, which uses the original-case path when only that one exists, plus a Zabric weave test for a ModifyReturnValue hook with @Self. Build and client smokeTest pass. On the server after the Workshop release: FileNotFoundException 350 -> 0, AnimNode.Parse traces 175 -> 0, no new error types. PR #14. The published jar reports 0.7.0 although the branch is at 0.8.0.

### Git Commits

| Hash | Message |
|------|---------|
| `1abcf43` | Weave-test a return-value hook that reads the receiver |
| `361a9cc` | Keep the case of animation files that an XML extends |

### Status

[OK] **Completed**


## Session 19: Temporary fix for SCTD TransmogSync.Flush next() error
<!-- trellis-session: v=2 fp=cee76d56d9f8ae51 -->

**Date**: 2026-10-17
**Task**: Temporary fix for SCTD TransmogSync.Flush next() error
**Branch**: `sctd-transmog-flush-fix`

### Summary

New Lua-only All-in-One feature sctd-transmog-flush-fix replaces Shadow Cat Tactics: Dispatch's SCTD_TransmogSync.Flush (calls next(), missing in Kahlua) at runtime, gated on its exact source; idle once upstream drops next(). No loadModAfter (mod selector auto-sort leaves a hole for absent mods); spec updated. All-in-One 0.9.0, PR #15. Build + smokeTest ready; disabled/idle paths checked; multiplayer check pending the Workshop release.

### Git Commits

| Hash | Message |
|------|---------|
| `fdec349` | Replace Shadow Cat Tactics: Dispatch's TransmogSync.Flush, which calls next() |

### Status

[OK] **Completed**


## Session 20: TCP tunnel for players whose network drops UDP
<!-- trellis-session: v=2 fp=d821e745be91d3cf -->

**Date**: 2026-10-18
**Task**: TCP tunnel for players whose network drops UDP
**Branch**: `claude/tcp-connection-setup-c5fda8`

### Summary

New All-in-One feature tcp-tunnel: carries the whole RakNet connection over one TCP connection per player, UDP and TCP side by side. Server: sandbox option Dont39AllInOne_TcpTunnel.Port (0 = off), listener started at GameServer.startServer RETURN, relays to 127.0.0.1 DefaultPort (ch 0) and UDPPort (ch 1). Client: per-address TCP port field in the add/edit server dialog (Zomboid/Lua store), probe 1 s + 1 s, rewrites only the host of UdpEngine.Connect, UDP fallback with one log line; a server on the same machine is not tunnelled. Spike: Steam-mode clients try RakNet UDP to host:UDPPort before Steam networking (measured against the Linux server), so the tunnel relays both ports. All-in-One jar moved to media/java/ (loads on both sides; ZB 2.3.4 loaded client/ too), ime-keyboard-fix idle on servers. In game on a temporary Steam-mode instance (myserver2_Data, ports 48261/48262/48300, stopped, ini restored): tunnelled join and 8 min play, wrong port fell back to UDP in 1 s, dialog stored/prefilled, killed client closed only its tunnel; dedicated servers write no map_sand.bin, so SandboxVars.lua edits apply on restart. smokeTest fails only on sctd-transmog-flush-fix after an SCTD update (separate follow-up). Not tested: concurrent UDP client, real packet loss. No version bump.

### Git Commits

| Hash | Message |
|------|---------|
| `755b694` | Weave-test a variable hook that reads the receiver and every argument |
| `109c54c` | Add a TCP tunnel for players whose network drops UDP |

### Status

[OK] **Completed**


## Session 21: Shorter Workshop text; remove sctd-transmog-flush-fix
<!-- trellis-session: v=2 fp=b0cca674226c93e4 -->

**Date**: 2026-10-18
**Task**: Shorter Workshop text; remove sctd-transmog-flush-fix
**Branch**: `claude/tcp-connection-setup-c5fda8`

### Summary

Follow-ups on PR #16. workshop.txt shortened from 68 to 40 lines: each feature in one or two sentences, the TCP tunnel as three bullets (server, players, limits). Removed sctd-transmog-flush-fix: Shadow Cat Tactics: Dispatch changed SCTD_TransmogSync.Flush, so the fix reported itself disabled and failed smokeTest; module, feature list entry, Workshop section and smokeTest entry gone, smokeTest keeps an empty luaTargeted list for the next Lua-only fix of another mod, spec paragraph points to git history. Build and :dont39-all-in-one:smokeTest pass. PR description updated; the SCTD follow-up card withdrawn.

### Git Commits

| Hash | Message |
|------|---------|
| `53daffe` | Shorten the All-in-One Workshop description |
| `99e1b67` | Remove sctd-transmog-flush-fix |

### Status

[OK] **Completed**


## Session 22: KCP tunnel with FEC; tunnel feature without UDP fallback
<!-- trellis-session: v=2 fp=d71b0d4d5c5796cc -->

**Date**: 2026-10-18
**Task**: KCP tunnel with FEC; tunnel feature without UDP fallback
**Branch**: `claude/trellis-kcp-design-78eb49`

### Summary

tcp-tunnel renamed to tunnel with a Transport seam (TCP, KCP). KCP: own Kotlin port of ikcp.c b1a7a21 (caller clock in input/flush), Backblaze RS d3c481d in fec/rs, FEC layer kind-first header with a flush timer for part-filled groups; one UDP socket per server, sessions by address+conv, HELLO/WELCOME with params, CLOSE x3, idle 60 s, backpressure 4x window. Sandbox options Dont39AllInOne_Tunnel.KcpPort + 8 KCP/FEC params (defaults fast mode, 1024, MTU 1350, FEC 10+3, flush 20 ms), sent to the client; Dont39AllInOne_TcpTunnel.Port kept. Client: Tunnel dropdown Off/TCP/KCP + tunnel port row, per-transport ports per address in Dont39AllInOne_Tunnel.txt migrated read-only from the released file; server list tag. No UDP fallback: cancellable @Inject on UdpEngine.Connect with re-entrant Connect, OnConnectFailed texts; Lua serverConnect guard refuses tunnel joins when the feature is disabled/Java missing, fail closed. zb-kt-utils Feature.whyNotActive (release ZabricKotlin first). Harness: 10% loss p99 62 vs 243 ms. In game on Linux myserver2 test instance (48261/48262, TCP 48300, KCP 48301): KCP/TCP joins, sandbox override, migration, wrong port and server down stop with reason, no-Java refused; instance stopped, ini/sandbox restored. Not tested: simulated loss in game, two clients, feature-disabled refusal. User feedback fixed: cramped port row, server-list tag.

### Git Commits

| Hash | Message |
|------|---------|
| `3777b48` | Add Feature.whyNotActive for player-facing reasons |
| `369ea9c` | Add a KCP tunnel with FEC next to the TCP tunnel; no UDP fallback |

### Status

[OK] **Completed**


## Session 23: Multiplayer network status icon
<!-- trellis-session: v=2 fp=b48e960b2180c0aa -->

**Date**: 2026-10-18
**Task**: Multiplayer network status icon

### Summary

Bottom-left signal icon in multiplayer: bars show ping tier, warning badge shows loss; tooltip with method, ping, loss, traffic, server and RakNet debug. Loss source per method (RakNet / KCP FEC gaps + retransmits / none for TCP); tunnel now exposes KCP counters via Dont39AllInOne_getTunnelStatus. Verified in game on UDP/TCP/KCP with a user-space impairment proxy; packetlossTotal from native is 0, so session loss comes from byte totals.

### Git Commits

| Hash | Message |
|------|---------|
| `a85c7a9` | Add a network status icon for multiplayer (#18) |

### Status

[OK] **Completed**


## Session 24: Codebase structure review and four-part refactor
<!-- trellis-session: v=2 fp=a5442ece5987d670 -->

**Date**: 2026-10-19
**Task**: Codebase structure review and four-part refactor
**Branch**: `refactor/tunnel-refactor`

### Summary

Surveyed the repo (no true god class; hook package is all public API). Build: typed buildSrc conventions, variants instead of cross-project access, generated initFeatures, pzDir moved to user properties. Processors: KSP tests on kctfork, JavacHookReader, HookAnalyzer split, LuaBridge, shared Messages. Zabric runtime: AccessWidener/HookListing/Lifecycle splits, internal Log/Jars, one HookListingFormat. Tunnel: 42 in-repo tests, LocalChannels, TunnelStatus keys renamed seq*/srttMillis, Tunnel.lua split in four. Output diffs and Zabric console lines matched baseline; smokeTest/serverSmokeTest passed after each part. In-game tunnel joins (TCP/KCP), dialog, server list and tooltip not tested.

### Git Commits

| Hash | Message |
|------|---------|
| `c118cf1` | Move shared build wiring into typed buildSrc conventions |
| `070f607` | Test the KSP processor and give each processor class one job |
| `8585ace` | Split Zabric's large runtime classes and define the listing format once |
| `65d88b0` | Prepare tunnel for JUnit tests |
| `37a0270` | Add tunnel tests: loss, wire, loopback, store |
| `e334146` | Share the tunnel's local end between TCP and KCP |
| `b0fcd00` | Build the tunnel status table outside TunnelClient |
| `742b586` | Split the tunnel's Lua into guard, dialog and server list |
| `000246d` | Describe the in-repo tunnel tests in the specs |
| `8454f3a` | Keep the tunnel join refusal and session setup safe on failure |

### Status

[OK] **Completed**


## Session 25: Loading progress mod and Zabric 0.5.0 preLaunch hooks
<!-- trellis-session: v=2 fp=6dc7ecbf6c6fbbdf -->

**Date**: 2026-10-20
**Task**: Loading progress mod and Zabric 0.5.0 preLaunch hooks
**Branch**: `claude/trellis-loading-progress-tasks-67a636`

### Summary

Carried the 10-17 loading-progress plan onto current main and built it. Zabric 0.5.0: preloaded mods' hooks open at preLaunch (byte-checked targets, held log, Zabric-Hooks: launch opt-in), never retransform at preLaunch (nested class loads skip every transformer of the agent), LoaderProgress per-jar listener and heartbeat. New client-only plain-Java mod Dont39LoadingProgress: step bar + item sub-bar over launch and game start, own frames on MainThread, render-thread interim frames inside a Java mod jar's patching (bind-based GL copy), JDK-only glue (checkJdkGlue), below ZombieBuddy's watermark, EN/CN. Build: pz-widening plugin, pz-mod jar folder. Acceptance: launch/SP/local-server joins/CN-EN/timing (+4.4 % launch, +2.3 % join)/forced mismatch pass; open: myserver2 TCP/KCP tunnel joins, version-mismatch join, Workshop art, releases.
## Session 26: Server mod sync core (PR #21)
<!-- trellis-session: v=2 fp=96fcedb4331b5669 -->

**Date**: 2026-10-20
**Task**: Server mod sync core (PR #21)
**Branch**: `claude/server-sync-mod-33c0e3`

### Summary

New mod-sync feature: a client whose Workshop copy differs from the dedicated server's (VersionMismatch) joins with the server's copy, downloading only differing files over HTTP (port from a connection-details trailer), with a confirm dialog, a per-server cache and a session-only folder swap; jar changes and loader mods are refused with a reason. Zabric weave/widener tests for private and nested targets. Per-feature translations and sandbox options merged by mergeMedia. After review: mod-sync split into protocol/server/client packages, and a shared allinone-common module (Kotlin helpers + Dont39AllInOne_Common.lua) replaces copies in tunnel, network-status and mod-sync. In-game tested before the refactor on the Linux test server; after it only build, assembled-mod comparison and a main-menu smokeTest. PR https://github.com/Dontcampy/dont39-pz-mods/pull/21 open; follow-up children zabric-launch-override and mod-sync-java still in planning.

### Git Commits

| Hash | Message |
|------|---------|
| `6e111f8` | Plan loading progress and a responsive window during loads |
| `0680c6e` | Drop the server smoke test from the loading progress plan; the mod is client only |
| `93488b8` | Open preloaded mods' hooks at preLaunch, with a launch-wide opt-in |
| `a5a07f6` | Loading progress plan: JDK-only glue for the progress mod; task started |
| `a27e3ea` | Add Loading Progress, a client mod with a progress bar for loads |
| `ad5a1b2` | Never retransform at preLaunch; load a target's member types before retransforming |
| `628d819` | Loading progress plan: cover mod discovery and ZombieBuddy's Java-mod pass (D10, D11) |
| `e2bc6e4` | Report loader progress per Java mod jar; cover launch discovery |
| `fa38a45` | Show interim frames from the render thread inside a Java mod jar's patching |
| `a43c892` | Loading progress research: the remaining launch gaps |
| `b8e788d` | Loading progress plan: known gaps, item sub-bar, interim frames without DSA (D12-D14) |
| `28e538e` | Add the item sub-bar, more load triggers and a bind-based interim copy |
| `60fff51` | Keep the watermark clean while fonts load; burst jar frames; skip interim frames on the game's GL errors |
| `d0af4f0` | Log the dedicated-server line once, also when only preloaded; end flows when Zabric closed the hooks |
| `f576c5c` | Loading progress: record the acceptance results and open items |

### Status

[OK] **Completed**


## Session 27: Loading Progress switches and load timing report
<!-- trellis-session: v=2 fp=018d7aad3fc7b1d4 -->

**Date**: 2026-10-20
**Task**: Loading Progress switches and load timing report
**Branch**: `claude/trellis-loading-progress-tasks-67a636`

### Summary

Two switches (bar/log, timing report) in Dont39LoadingProgress.ini and on the mod options page (EN/CN); per-load summary + CSV under Lua/Dont39LoadingProgress (last 10). In-game: reports for launch and SP start; first report showed the last item of each run taking the loop's tail, so runs now end at the game's loop return (R3 amended) and the last script file row is labelled as holding the game's script processing. Display off: 0 own/interim frames; both off: no flow. Report on vs off -0.27% over 8 launches. Build gate and smokeTest pass; user's Zomboid files restored hash-identical.

### Git Commits

| Hash | Message |
|------|---------|
| `25f5be8` | Add Loading Progress switches and a load timing report |
| `5902e6d` | Prove hooks on private and nested-class methods weave and widen |
| `371fbe7` | Take a Workshop item from the server when its version differs from the player's |
| `43253dc` | Give each feature its own translation and sandbox option files |
| `a4c9b25` | Split mod-sync into protocol, server and client, and share code across features |

### Status

[OK] **Completed**


## Session 28: Zabric one-launch mod folder override
<!-- trellis-session: v=2 fp=97ef697910cf7c11 -->

**Date**: 2026-10-21
**Task**: Zabric one-launch mod folder override
**Branch**: `claude/trellis-main-update-subtasks-6c01f8`

### Summary

Updated main; planned and implemented child 10-20-zabric-launch-override (Zabric LaunchOverride, JavaMods, WidenRoots); in-game check found and fixed a retransformation bug on runtime proxies; release deferred to the parent's integration review. Next: plan 10-20-mod-sync-java.

### Git Commits

| Hash | Message |
|------|---------|
| `8560834` | Let a mod load a Workshop item from another folder for one launch, and tell which jar serves a package |

### Status

[OK] **Completed**


## Session 29: mod-sync-java: server Java mods after one restart
<!-- trellis-session: v=2 fp=e226d61509aa582d -->

**Date**: 2026-10-21
**Task**: mod-sync-java: server Java mods after one restart
**Branch**: `claude/trellis-main-update-subtasks-6c01f8`

### Summary

Planned (J1 always restart, J3 refuse preload mods, J4 always ask on jar changes) and implemented 10-20-mod-sync-java; in-game rounds on the Linux test server found and fixed Zabric's premain side detection (empty sun.java.command on exe launches), the core's blank screen after a Workshop download, and CN line breaks. All three children done; next: the parent's integration review and release (Zabric first).

### Git Commits

| Hash | Message |
|------|---------|
| `394ec65` | Decide Zabric's launch override side at the game's main, not from sun.java.command |
| `790281a` | Take a server's changed Java mod after one restart, then offer Rejoin |

### Status

[OK] **Completed**


## Session 30: mod-sync-relaunch: restart the game with this launch's command line
<!-- trellis-session: v=2 fp=f4c8446a20c88d96 -->

**Date**: 2026-10-21
**Task**: mod-sync-relaunch: restart the game with this launch's command line
**Branch**: `claude/mod-sync-relaunch-fce8bc`

### Summary

Planned (D1 waiter process, D2 failures never silent, D3 one branch with the cachedir fix merged, D4 Windows only, D5 +connect by case) and implemented 10-21-mod-sync-relaunch: Zabric Relaunch (helper on the game's javaw, raw GetCommandLineW to CreateProcessW, failure log/marker/message box, main-menu notice) and mod-sync's Restart game. In game on the Linux test server: identical command line after relaunch (ZombieBuddy on the command line), override applied, Rejoin with the server's jar, +connect kept/cut per D5, both failure paths, Steam stayed Running; fixed the notice being covered by the Rejoin offer. Next: the parent's integration review and release (Zabric 0.5.0 first).

### Git Commits

| Hash | Message |
|------|---------|
| `a3727aa` | Merge claude/cranky-pike-68a148: read -cachedir from the process |
| `c558aa8` | Let Zabric restart the game with this launch's command line |
| `ea65324` | Offer "Restart game" on mod-sync's restart stop |
| `f9c5449` | Show a failed restart above the main menu's rejoin offer |
| `10a8378` | chore(spec): Zabric relaunch rules; parent D9 now restarts the game |

### Status

[OK] **Completed**


## Session 31: PR #20 merges, review fixes and Workshop staging
<!-- trellis-session: v=2 fp=6ff89c80d2883fab -->

**Date**: 2026-10-21
**Task**: PR #20 merges, review fixes and Workshop staging
**Branch**: `claude/trellis-loading-progress-tasks-67a636`

### Summary

Merged main twice into PR #20 (#21 mod sync, #22 Java mod restart); resolved conflicts in pz-feature (pz-bundled-feature + pz-widening), specs, journal index, Lifecycle; Zabric author contract moved to zabric/README.md, so this branch's additions (launch-wide hooks, hooks from game start, loader progress) went there. zabric-v0.5.0 was already tagged on main, so Zabric goes out as 0.6.0 (PR title/body updated). Reviewed the PR (architecture/performance/generality, three parallel reviewers) and fixed the four pre-release items: LoaderProgress.listen checks the listener's jar against the mod id's records; JarLoadHook checks each pass loaded every recorded jar and ends a jar whose load throws (onThrowable); Zabric-Hooks: launch without preLaunch fails like an invalid value; Loading Progress ends a join failed on RakNet's thread at the game thread's next frame and asserts flows end on the game thread. Build gate, 255 tests, both smoke tests and a full launch pass. Rewrote the Loading Progress Workshop text for players and staged Zabric 0.6.0 and Loading Progress 0.1.0 into ~/Zomboid/Workshop for the user's upload. Open: merge PR, tag zabric-v0.6.0 + Maven Central, sample zabricVersion bump, write Loading Progress's new Workshop id into workshop.txt. Archived 10-20-server-mod-sync (done on main).

### Git Commits

| Hash | Message |
|------|---------|
| `bcf8059` | Merge remote-tracking branch 'origin/main' into claude/trellis-loading-progress-tasks-67a636 |
| `9d16c7c` | Merge origin/main into claude/trellis-loading-progress-tasks-67a636 |
| `8ca0216` | Check loader progress listeners, pass ends and launch-wide hooks |
| `8233d10` | Shorten the Loading Progress Workshop text |
| `0cb3f78` | Write the Loading Progress Workshop text for players |

### Status

[OK] **Completed**


## Session 32: R2: texture compression off the render thread
<!-- trellis-session: v=2 fp=1474599f0bd53d45 -->

**Date**: 2026-10-22
**Task**: R2: texture compression off the render thread
**Branch**: `claude/asset-wait-speedup-2d74f7`

### Summary

Found the join's asset wait bound by driver-side texture compression on the render thread (textureCompression on: wait 66 s vs 14 s off). Faster Loading 0.2.0 takes GL_COMPRESSED_RGBA uploads at GL11.glTexImage2D and encodes DXT5 on a pool (stb_dxt colour, own alpha), behind an encoder interface; quality above the driver on 21 pages. Local server, live mod list, median of 3: join 141.7 -> 69.8 s, asset wait 70.4 -> 12.5 s. Settings upgrade keeps old ini values. BC7 GPU encoder planned as a child task.

### Git Commits

| Hash | Message |
|------|---------|
| `3a9b526` | Compress textures on all CPU cores in Faster Loading, halving a server join |
| `743afc7` | chore(task): add child 10-22-gpu-bc7-encoder |

### Status

[OK] **Completed**


## Session 33: Depth-map order in Faster Loading 0.3.0
<!-- trellis-session: v=2 fp=cda073218315e256 -->

**Date**: 2026-10-22
**Task**: Depth-map order in Faster Loading 0.3.0
**Branch**: `claude/dreamy-varahamihira-298ef8`

### Summary

Researched depth-map loading (one lock per mod, tasks queued mod after mod, depth maps the tail of quick joins). Added switch depthMapOrder: reprioritises the launch/join depth batch at TileDepthTextureManager.init RETURN into a greedy longest-chain-first schedule; first access-widening list for Faster Loading. Quick join: depth phase 5.64->2.91 s, asset wait -1.46 s (AC2 target not met). After 30 s in the menu the join has no depth tasks; launch background loading ends ~4 s sooner. Kept in 0.3.0; prefetch lever dropped. Specs: access-widening (standalone list), quality-guidelines (measure joins after a menu stay).

### Git Commits

| Hash | Message |
|------|---------|
| `f05c47c` | Load mods' depth maps side by side in Faster Loading 0.3.0 |

### Status

[OK] **Completed**


## Session 34: R4: Lua folder walk in Faster Loading 0.4.0
<!-- trellis-session: v=2 fp=d35318c2d71a59cb -->

**Date**: 2026-10-22
**Task**: R4: Lua folder walk in Faster Loading 0.4.0
**Branch**: `feat/lua-folder-walk-143b68`

### Summary

Measured LuaManager.searchFolders offline: ~95% is a canonical-path lookup of the parent folder repeated per entry; getRelativeFile ~6 ms, Viewpoint's patch binds getRelativeFile(String) only. User chose variant B: skip the lookup for folders already canonical by construction (root, links/reparse points and names ending in dot/space still take the game's call). New switch luaFolderWalk (cancellable HEAD hook, list built aside, handed over only when complete); tests match the game's method on the game's and 1,149 Workshop Lua folders plus junction and odd-name trees. In game (local full server, live mod list, medians of 3): walks 3.9 -> 0.9 s per launch, 4.3 -> 1.2 s per join; launch 34.6 -> 31.4 s, join 68.5 -> 64.8 s; identical Lua lists, no new errors, no checksum mismatch. Precompile measured (~1.15 s) and not done: no preloadable hook point. Spec: cancel exception extended to recursive targets. User files restored after the runs.

### Git Commits

| Hash | Message |
|------|---------|
| `ce7b3e9` | List the Lua files without repeated path lookups in Faster Loading 0.4.0 |

### Status

[OK] **Completed**


## Session 35: GPU BC7 texture encoder (Faster Loading 0.5.0)
<!-- trellis-session: v=2 fp=721bf539c1259cfb -->

**Date**: 2026-10-23
**Task**: GPU BC7 texture encoder (Faster Loading 0.5.0)
**Branch**: `feat/gpu-bc7-encoder-2b7d0a`

### Summary

Planned and shipped a GPU BC7 encoder for Faster Loading's textureCompression: DirectXTex compute shader ported to GLSL (modes 4-7, alpha weight 4, nearest index in the encode pass), new switch textureBc7, CPU DXT5 where OpenGL 4.3 is missing. Quality on 21 tile pages: RGB 45.33 / over white 44.34 / over grey 45.06 dB vs DXT5 39.26 / 38.95 / 39.18; alpha 53.43 vs 58.15 (accepted). Render thread 1.34 ms a page (32% of DXT5), GPU ~4.5 ms. Live mod list joins: 57.4 -> 55.1 s; VRAM peak +39 MiB vs DXT5 after fixing encoder buffers that grew with the largest level (+922 MiB). Spec: GL work on the render thread, ported code, capability choices. User files restored after every in-game run.

### Git Commits

| Hash | Message |
|------|---------|
| `9214c21` | Encode textures to BC7 on the GPU in Faster Loading 0.5.0 |

### Status

[OK] **Completed**


## Session 36: PR #24 review fixes
<!-- trellis-session: v=2 fp=370da0a9ac4fd2ee -->

**Date**: 2026-10-23
**Task**: PR #24 review fixes
**Branch**: `feat/gpu-bc7-encoder-2b7d0a`

### Summary

Fixed every finding of the PR #24 architecture/reuse/performance review: BC7 balanced dispatch grid, exact-block skip, per-entry shared memory, fewer GL queries (GPU a padded 1024² page 6.6 -> 3.0 ms, output bit-identical); shared settings source for both mods (mod-settings/, pz-switches); Faster Loading layering; Loading Progress asset-wait single source, files CSV, depth-map naming, leak and throw fixes. 40 % GL-thread gate kept on the pages' own sizes (user).

### Git Commits

| Hash | Message |
|------|---------|
| `97e28bd` | Fix PR #24 review findings: BC7 dispatch, shared settings, asset-wait report |

### Testing

- [OK] build gate faster-loading + loading-progress; qualityCheck with hash dump identical to baseline

### Status

[OK] **Completed**

### Next Steps

- Fast-forward load-speed-optimization when the user asks; resume parent AC5 on the Linux test server
