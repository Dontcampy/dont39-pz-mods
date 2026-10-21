# Implement: Zabric mod folder override for one launch

Branch from `main` (user, 2026-10-20: first to ship takes 0.5.0, design "Compatibility"). Zabric only; no `mod-sync` code (that is `10-20-mod-sync-java`).

## Checklist

1. [x] Loader seam: `LoadStatus` reads `sha256`; `Loader` gains the loaded-jar list with hash and the class-path
       resource lookup for `servingJar`. Unit tests on a fake record shape where the existing tests do so.
2. [x] Phases: `Lifecycle.modsLoaded` records the first pass and each pass's jars; `JavaMods` (`loaded`, `lastPass`,
       `servingJar`) in the new `com.pzlauncher.zabric.mods` package.
3. [x] Request file: `LaunchOverride.request` / `pending` with the checks of design "Request file"; unit tests on a temp
       cache dir (valid request, refused ids, missing folder, replace a pending request, atomic write).
4. [x] Premain: `LaunchOverride.install` after `AccessWidener.install` reads and deletes the file, validates, installs
       the advice only when valid; `current()`. Unit test: read + delete + discard on any invalid item.
5. [x] Advice on `getInstalledItemModsFolders` RETURN (retransformation, POOL_ONLY, by name, no game types; design "Advice").
       Weave test on a stand-in class with the same signature: replacement, untouched list without a request, a later
       retransformation keeps it.
6. [x] Widening: `ModFolders.roots()` adds override folders and `widen-roots.txt`; `WidenRoots.add` / `scanned`.
       Extend `AccessWidenerTest` with a list in an extra root.
7. [x] MAIN report (design "Reporting"), `workshop.txt` author contract lines, Javadoc on the public classes.
8. [x] Build gate, `smokeTest`, `serverSmokeTest`; in game (Windows client): a request written from the Lua console or
       a debug call makes the next launch load a Workshop item (with a jar, enabled on the main menu) from another
       folder, ZombieBuddy asks for the new hash, the launch after is back to the client's copy; accessors checked at
       startup, at a join and for a package never loaded; a widened class in an extra root.
9. [-] Moved to the parent's integration review (user, 2026-10-21: release with mod-sync-java). Version bump and release per [kotlin-runtime.md](../../spec/mod/kotlin-runtime.md) (Zabric first). The parent's
       links to the archived core child are updated.

## Validation

```bash
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew :zabric:test build
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew :dont39-all-in-one:smokeTest
JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew :dont39-all-in-one:serverSmokeTest
```

Steps 1-7 done 2026-10-20 (trellis-check passed, `:zabric:test build` green).

Step 8, 2026-10-21 (Windows client, launched through `smokeTest`, which exits after init):

- `smokeTest` and `serverSmokeTest` pass without a request; no override line in the log (R6).
- First run with a request: the file was consumed, but the advice failed to install. The default ignore matcher
  resolved every loaded class through `POOL_ONLY`, a runtime proxy (`jdk.proxy2.$Proxy43`) could not be described,
  and `onError` took that for the target. Fixed with a name-only ignore, explicit discovery of the target, and an
  `onError` limited to the target; regression test with a `Proxy` created before install.
- Second run, request for ViewpointBloodFX (3811472160, enabled on the main menu) pointing at a copy with a changed
  jar and an extra Lua file: MAIN logs `launch override: Workshop item 3811472160 uses ...`; ZombieBuddy resolves and
  loads the copy's jar (new hash, approved and stored under this install's `policy=allow-all`); the extra Lua file
  runs; `JavaMods.loaded/lastPass` list the copy's jar as `STARTUP`, preloaded jars as `PRELOAD`; `servingJar` gives
  the copy for `bloodfx`, the preloaded jar for Zabric, null for a package never loaded; `WidenRoots.scanned` is true
  for the override folder; the file is gone afterwards.
- Next run without a request: the jar loads from the Steam folder again, `current()` is empty, no override line.
- Probe for the accessors was a temporary log block, removed; the deployed Zabric has none.
- Not checked in game: the accessors after a join (the pass phase `LATER`), and a widened class in an extra root
  (covered by `AccessWidenerTest`; BloodFX has no widening list). `mod-sync-java` exercises both at a join.
- Environment: another session's `Dont39LoadingProgress` preload entry (needs the loading-progress branch's Zabric)
  was removed from `~/.zombie_buddy/config.json` for the runs and restored from a dated backup afterwards.

## Rollback Points

- After step 5: if the advice does not survive ZombieBuddy's retransformation of `ZomboidFileSystem` (a mod's hook on
  it), stop and revise the design before the API is public.
- Before step 9: nothing is published; the branch can be dropped.

## Review Gates

- `trellis-check` after steps 5 and 8.
- Spec update (3.3): access-widening.md "Zabric" (the second game advice, the request file, extra roots),
  zombiebuddy-patching.md "Loader Seam" (the new accessors).
