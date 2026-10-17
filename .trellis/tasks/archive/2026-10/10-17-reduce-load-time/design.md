# Design: Reduce game load time

Two deliverables: a standalone Java mod for B1 and a feature module in Dont39 All-in-One for B3 (B2 is out of scope, see
`prd.md`).

## D1 — `ZBSkipOnlineChecks` (B1), new Java mod with two ways to start

One jar, one idempotent installer, two callers. Whichever runs first installs the advice; the other finds it installed.

| Way | How it starts | Covers | Needs |
|---|---|---|---|
| Agent start (full) | ZombieBuddy's `patches_jar` option loads the jar and calls `<pkg>.PreMain.premain(String[])` (`Agent.java:95-121`, `Loader.ApplyPatchesFromPackage`) before `Loader.preloadMods()` | premain, menu and server-join passes | a copy of the jar in the game folder plus `,patches_jar=ZBSkipOnlineChecks.jar:com.dont39.pz.zbskiponlinechecks` appended to `-agentlib:zbNative=...` in `ProjectZomboid64.json` (or the launcher's vmArgs) |
| Mod only (partial) | the mod in the mod list: Zabric's `Zabric-PreLaunch` entrypoint, which also registers it as a ZombieBuddy preload mod (`ZB-Preload: true`, `javaPreload=true`; one restart on first install) | menu and server-join passes | nothing outside the mod |

Zabric `@Hooks` cannot do this: they open only after a loader pass is processed (`HookRuntime.passProcessed`,
`ModState.update`). PreMain and preLaunch code is plain Java with no game classes and no game log, so the mod cannot be
a Kotlin feature module.

### Boundaries

- Module `zb-skip-online-checks`, package `com.dont39.pz.zbskiponlinechecks`, mod id `ZBSkipOnlineChecks`. Build like
  `zabric/build.gradle.kts`: `pz-java` + `pz-mod`, jar into `42/media/java`, `ZB-Preload: true`, no Kotlin
  (`CheckBootstrapClasses`). Not published to Maven; a Workshop item only if the user decides so later.
- `mod.info`: `require=\ZombieBuddy,\Zabric`, `loadModAfter=\Zabric`, `javaPreload=true`.
- A Gradle task copies the jar into `pzDir` for the agent-start way. The vmArgs edit stays a documented manual step
  (module README and Workshop text): the game's file is overwritten by Steam updates and verification, and PzLauncher
  already owns ZombieBuddy's agent options.
- ZombieBuddy splits `patches_jar` entries on `:`, so the entry must be a path relative to the game folder (no drive
  letter).
- No class named `Main`: ZombieBuddy would call its `main(String[])` in every pass. Zabric entrypoint classes get other
  names; `PreMain` exists only for ZombieBuddy's agent-start call.
- This mod deliberately references ZombieBuddy types. The mod spec says only Zabric's loader seam does
  ([Overview](../../spec/mod/index.md), `CheckLoaderRefs`); this module gets its own `checkLoaderRefs` exclusion and
  the spec records the exception: a mod whose target *is* the loader.

### Mechanism

- `Installer.install(String way)` (synchronized, once per JVM): gets ZombieBuddy's `Instrumentation` and installs one
  ByteBuddy `AgentBuilder` with `RETRANSFORMATION` and fail-fast error escalation, the same shape as
  `zabric/.../loader/ModsLoadedHook.java`. Retransformation covers targets already loaded on the mod-only way.
- `Instrumentation`: at agent start Zabric is not on the classpath yet, so the installer reads ZombieBuddy's
  package-private `Loader.g_instrumentation` itself, as Zabric's `Loader.instrumentation()` does. This is the one
  reflective read; the game JRE has no `jdk.attach` for another instance.
- Both copies of the jar can be on the system classpath (game folder at agent start, mod folder when ZombieBuddy loads
  the mod). Classes resolve from the first, so one installer state serves both ways.

| Target (ZombieBuddy 2.3.4) | Advice | Effect |
|---|---|---|
| `me.zed_0xff.zombie_buddy.SteamWorkshop.fetchItemDetails(Set)` → `Map` | skip the body, return an empty mutable map | no Steam API request; every jar's ban status stays unknown (`null`), so none counts as banned |
| `me.zed_0xff.zombie_buddy.KnownAuthors.loadAuthors()` → `Map` | skip the body, return `Collections.emptyMap()` (ZombieBuddy's own result when neither network nor cache has a list) | no GitHub request and no cache read; its other callers (`loadSteamIdToDisplayName`, `loadAuthorEntries`) get no authors either |
| `me.zed_0xff.zombie_buddy.ZBSVerifier.fetchJavaModZBSHexesFromSteam(SteamID64)` → `List` (private static, throws `IOException`) | skip the body, throw `IOException("skipped by ZBSkipOnlineChecks")` | no Steam profile request; ZombieBuddy's caller treats it as a failed lookup (only reached when ZBS verification is on) |

Each advice also counts its skip per target and per phase (before the mod list loaded, or after) for the report.
Matchers are typed (`named(..).and(takesArguments(..)).and(returns(..))`, class literals compiled against
`ZombieBuddy.jar`).

### Errors and reporting

- When a target method is missing, has another signature, or was not transformed, the installer throws an
  `IllegalStateException` naming the method and ZombieBuddy's version. ZombieBuddy then makes its requests as before: a
  reported failure, not a silent fallback.
  - Agent start: `PreMain` rethrows; the failure stays recorded and the preLaunch entrypoint rethrows it, so Zabric
    logs it when the mod is in the list. Nothing earlier reaches `console.txt` (it starts when ZombieBuddy hooks the
    game log; stdout and stderr before that are lost), so the mod must stay in the list for reports.
  - Mod only: the preLaunch entrypoint throws; Zabric logs it with the mod id in the main phase.
- The `Zabric-Main` entrypoint (mod in the list) logs one line: which way installed the advice, the skip counts, and
  that the mod is for `allow-all` only.
- Stale copy: when both ways run and the class's code source is the game-folder copy, `Main` compares its SHA-256 with
  the mod folder's jar and logs an error naming both paths when they differ (copy it again).
- The advice is not gated by the mod list: once installed it acts for the whole launch, whatever the server's list.
  Removing the mod from the list takes effect after a restart (Zabric's preload rules); removing the `patches_jar` entry
  turns off the agent-start way.

### Trade-offs

- A Workshop jar that Steam has banned is no longer skipped by ZombieBuddy.
- The mod is for `allow-all` only. Authors feed ZBS verification (`ZBSVerifier`, off under `allow-all`,
  `Loader.java:89-92,110`), the `prompt` dialog's author names (`Loader.java:757`, `SwingApprovalMain`) and
  `untrustAuthorForBannedMod` (no jar counts as banned with this mod). Under `prompt` or `deny-new` signed mods fail
  verification at once instead of waiting on Steam; the Workshop text and the `Main` line say so.
- Mod only, the premain `loadAuthors` call for preload mods (`Loader.java:440`) still runs.
- Coupled to ZombieBuddy internals; a ZombieBuddy update that renames these methods becomes the reported error above.

## D2 — `missing-anim-folders` (B3), feature module in Dont39 All-in-One

### Why All-in-One

B3 is a vanilla bug that every player with many mods hits, with no trade-off; it fits All-in-One's "one fix per feature
module" layout, its `Feature` base, logging and Workshop page. The hook runs only in the server-join `ResetLua`, when
All-in-One's hooks are open (the mod is in the menu list and in `myserver1`'s list). Keeping B1 separate also lets the
user ship B3 to everyone without shipping B1's security trade-off.

### Mechanism

- Kotlin hook object on `zombie.core.skinnedmodel.advancedanimation.AdvancedAnimator`, `@Inject(method =
  "searchFolders", at = HEAD, cancellable = true)` on the public static `searchFolders(URI, Path): List<String>`: when
  the path does not exist, set the return value to a new empty `ArrayList` and cancel. Existing folders walk as
  before; one that exists but cannot be read still gets the game's error.
- Facade and module layout modeled on `ime-keyboard-fix` ([Directory Structure](../../spec/mod/directory-structure.md)
  "Adding a Feature"); no options, no target mods.
- Gating and error catching come from the generated glue; a failure stops this feature's hooks, the game then logs the
  traces as before.

## Rollback

Each deliverable is independent: remove the `patches_jar` entry and `ZBSkipOnlineChecks` from the mod list (restart),
or revert the feature module and rebuild All-in-One.
