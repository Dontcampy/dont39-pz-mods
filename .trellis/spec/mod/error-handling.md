# Error Handling

> A feature may switch itself off, but it never breaks the game or the mod it patches.

---

## Rules

| Where | Rule | Reference |
|-------|------|-----------|
| Static initializers that resolve other mods | Never throw; record the problem so the facade can report it | static blocks in `LiftOffsets.java`, `AimShift.java`, `Relief.java` |
| Helpers called from patches | Catch everything at this boundary, restore what was changed, then disable the feature through the facade | `LiftOffsets.liftBlood` / `liftGore`, `AimShift.lower` |
| Restore paths | Never throw; keep restoring the rest after one failure | `LiftOffsets` (`Saved.restore`), `AimShift.restore` |
| Probes of other mods (version, flags) | Return a neutral value on failure | `ViewpointBloodFxFix.java` |
| Optional sub-part unavailable | Warn once, skip that part, keep the rest running | `AimShift.lower` |

All reference files are in `viewpoint-blood-fx-fix/src/main/java/com/dont39/pz/viewpointbloodfxfix/`.

Disabling is final for the session: no retries. Use the facade's `disable(...)` (`ViewpointBloodFxFix.java`) and phrase
the description like the existing calls.

---

## Anti-Patterns

- Throwing from a static initializer: the failure surfaces inside the patched game method on first call.
- Catching in a patch path without restoring and disabling: the target stays half-modified and the error repeats every
  frame.
- Reporting the same failure more than once.
- try/catch in the patches class; error handling belongs to the helpers it calls.
