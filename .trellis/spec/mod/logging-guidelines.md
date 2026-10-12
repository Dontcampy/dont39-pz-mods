# Logging Guidelines

> All output goes to the game's `console.txt` through Project Zomboid's debug logger.

---

## Rules

- Use the game's `DebugType.Mod` logger only; no `System.out`, no ZombieBuddy logger. See the imports and helpers in
  `ViewpointBloodFxFix.java`.
- Each feature logs through its facade's `log` / `warn` / `disable` helpers, which add the shared mod + feature prefix.
  Helpers never call the logger directly. Mod-level lines come only from the mod's `Main`.
- Every feature reports the same lifecycle moments so a player's `console.txt` shows its state: ready or idle at
  `init()`, active or inactive on first use, disabled on a runtime error. Copy the wording style from
  `ViewpointBloodFxFix.java`.
- Reasons are sentences a player can act on, including how to override when an override exists.
- Never repeat a line per call or per frame. State lines are logged once per decision: when the decision is re-made
  (e.g. after a mod option changes, see `setForce()` in `ViewpointBloodFxFix.java`) the new state is logged again.

## Debug Output

- Gate detailed output on the **target mod's own debug option** instead of adding a setting (`debug()` in
  `ViewpointBloodFxFix.java`).
- Never log per frame: aggregate and emit a periodic summary (`Stats` in `LiftOffsets.java`).
- Build log strings only inside the debug branch.

Reference files are in `viewpoint-blood-fx-fix/src/main/java/com/dont39/pz/viewpointbloodfxfix/` and
`dont39-all-in-one/src/main/kotlin/com/dont39/pz/allinone/Main.kt`. The library's lines are in
`zombie-buddy-kotlin/src/main/java/com/dont39/pz/zombiebuddykotlin/ZombieBuddyKotlin.java`.
