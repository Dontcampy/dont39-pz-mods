# Implement: appearance-override

Gradle with `JAVA_HOME=~/.jdks/graalvm-ce-25.0.2`. References: `ime-keyboard-fix` (facade, `Feature`, hook objects),
`mod-sync` `client/ClientHooks.kt` (private / nested targets), `tunnel` (`@LuaGlobal`, shared Lua module file),
spec "Adding a Feature" in `.trellis/spec/mod/directory-structure.md`.

## Steps

1. Module `appearance-override/` (`pz-feature`), `settings.gradle.kts`, feature list in
   `dont39-all-in-one/build.gradle.kts`. No target mod.
2. Pure Kotlin, unit-tested (`pz-junit`): the override model and parsing from a Lua table shape, the set key, set
   replacement by revision.
3. Client store + `@LuaGlobal Dont39AllInOne_putAppearanceOverrideSet`; reset the player's model on change.
4. Render scope hooks (HEAD/FINALLY, thread-local depth and current subject) on the consumers in design.md; verify each
   target signature with `javap` first and list them in the module's widening/hook listing as the build requires.
5. `RETURN` edit hooks on `WornItems.getItemVisuals(ItemVisuals)` and `IsoPlayer.getItemVisuals(ItemVisuals)`; the
   substitute cache; a debug switch (system property, off by default) that logs each scoped edit once per model build.
6. Lua: shared API module, server file (OnClothingUpdated → publish, snapshot command), client file (snapshot request,
   OnServerCommand → store). Status lines.
7. Workshop line in `dont39-all-in-one/mod/workshop/workshop.txt` (what it does by itself: nothing visible until a mod
   uses it; one line). Version bump happens once in the parent's last child.

## Gate

```
./gradlew build assembleMod
./gradlew :dont39-all-in-one:smokeTest
```

Then `trellis-check` on the diff.

## In-game (myserver2; recipe: archived `10-27-server-reborn-sync-false-repairs/research/in-game.md`)

- Back up `myserver2_Data` and the client (`backup-client.ps1`); own RCON port; restore both after.
- SGTestDriver test `appearance`: server gives and wears a `KnapsackSprayer` with water and a jacket; `setHidden` on the
  sprayer, `setLook` on the jacket (another jacket type, fixed texture/tint); client logs the scoped edits (debug
  switch), the gameplay values (jacket defense, sprayer fluid amount, weight) on both sides. Restart the server, rejoin,
  check again. `clear` both; unequip the sprayer and equip a second sprayer: drawn normally.
- A screenshot per step from the client (the harness's capture) for the record.
- Remote view: try a second `-nosteam` client with its own `-cachedir`; if it does not run beside the first on this
  PC, report the remote path as not covered in game.
- Lines and screenshots under `research/in-game-<date>/`, summary in `research/in-game.md`.

## Rollback

Drop the module from the feature list; items keep a harmless mod data key.
