# Design: ZabricKotlin as a preload mod

Requirements and decisions: `prd.md`. The code stays the source of truth for details.

## 1. Launch flow

```
ZabricKotlin preloaded (every launch after the first):
  premain   Zabric PreMain → ZabricKotlin PreMain: KotlinSwap.install, result kept → other preload mods
  preLaunch Zabric preLaunch phase: ZabricKotlin's preLaunch entrypoint (self-check, result kept)
  MAIN      ZabricKotlin Main: Kotlin already installed → report ready ("at game start")
            Zabric: ZabricKotlin entry current → no restart

ZabricKotlin not preloaded (first launch, or entry lost):
  premain   no ZabricKotlin code
  MAIN      ZabricKotlin Main: KotlinSwap.install, self-check, report ready (not at game start)
            Zabric: registers ZabricKotlin → RestartList → dialog / server log line
```

## 2. ZabricKotlin (`zb-kt`)

- New `PreMain` in `com.dont39.pz.zbkt` (Java, under `checkBootstrapClasses`): calls `KotlinSwap.install` and keeps
  the result or the failure; it cannot log (no game log at premain).
- `Main`: installs only when `PreMain` did not run this launch; a failure kept from `PreMain` is reported, never
  retried at MAIN (a half-done swap is not repeated). Then self-check and the report. If `PreMain` ran, the ready line
  says Kotlin was installed at game start; otherwise it says so and that a restart installs it at game start.
- preLaunch entrypoint (`Zabric-PreLaunch`): exists so Zabric registers the mod (design 3); it runs `SelfCheck` early
  and keeps the result for `Main`. If it would only duplicate `Main`'s self-check, a no-op is acceptable; decide while
  implementing, keeping one self-check result per launch.
- `KotlinSwap`'s "already loaded" message names the likely cause at premain (a preload mod placed before
  ZabricKotlin, or one that loads Kotlin without requiring it).
- `mod.info`: `javaPreload=true`; manifest `ZB-Preload: true` and `Zabric-PreLaunch`.

## 3. Zabric

- Registration needs no change: ZabricKotlin declares `Zabric-PreLaunch`, so `Lifecycle.load` registers it, and on a
  launch where Zabric itself is not preloaded `onZabricMain` registers it with the others.
- Order: `PreloadConfig.withZabricFirst` becomes an order by `require` read from each entry's `infPath`: Zabric first,
  each entry after the entries it requires, the rest in their stored order; a cycle keeps the stored order of its
  members and is logged as an error. The file is rewritten when the stored order differs, not only when Zabric is not
  first. `PreloadConfig` cannot log; it returns what `Preload.update` logs.
- Contract text (`PreLaunchEntrypoint` javadoc, `zabric/README.md`, Workshop text): preload mods are preloaded in
  `require` order; Kotlin at game start is ZabricKotlin's rule, stated in its own README, not Zabric's.

## 4. Compatibility

- Players after the update: one launch with the MAIN swap and a restart prompt, then premain from the next launch on.
  Kotlin mods work on both launches.
- A third-party Kotlin preload mod on a launch where ZabricKotlin is not preloaded loads `kotlin.*` 1.4.10 at premain;
  the MAIN swap then fails with an error for every Kotlin mod that launch, and the prompt fixes it after one restart.
  No such mod exists; accepted (`zb-kt/README.md` says it).
- Release order: Zabric first (ordering), then ZabricKotlin.
- Rollback: an older ZabricKotlin jar without `ZB-Preload` makes ZombieBuddy drop the entry (today's server smoke test
  proves this), and its `Main` swaps as before.
