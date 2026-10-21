# Logging Guidelines

> All output goes to the game's `console.txt` through Project Zomboid's debug logger.

---

## Rules

- Use the game's `DebugType.Mod` logger only; no `System.out`, no ZombieBuddy logger. Build no message the logger
  would drop ([Building the Message](#building-the-message)). Lua code uses the same logger
  (`DebugType.Mod:println` / `:error`), not `print`; see the helpers in
  `mod-options-sidebar/src/main/lua/client/Dont39AllInOne_ModOptionsSidebar.lua`.
- Each feature logs through its facade's `log` / `debug` / `error` / `disable` helpers, which add the shared mod +
  feature prefix. A problem the feature runs on with is `error { }`, never an info line worded as one; an exception
  goes to `disable`. Mod-level lines come only from the mod's main entrypoint (ZabricKotlin: its `Main`); Zabric uses
  its own `[Zabric]` prefix.
- Every feature reports the same lifecycle moments so a player's `console.txt` shows its state: ready or idle at
  `init()`, active or inactive on first use, disabled on a runtime error, and each error after that. The wording is in
  `Feature.kt`; reasons come from the facade's `problem()` (`ImeKeyboardFix.kt`).
- Reasons are sentences a player can act on, including how to override when an override exists.
- Never repeat a line per call or per frame. State lines are logged once per decision: when the decision is re-made
  (e.g. after a mod option changes, see `setForce()` in `Feature.kt`) the new state is logged again.

## Building the Message

The game checks the severity only after the caller has built the message, so a dropped line still costs its string.

### Kotlin

Wrong:

```kotlin
DebugType.Mod.println("${prefix}raised $count")  // built even when Mod logging is off
DebugType.Mod.println { "raised $count" }        // resolves to println(Object): prints the lambda
```

Correct:

```kotlin
MyFeature.log { "raised $count" }                                 // feature code: facade helper, adds the prefix
DebugType.Mod.lzWarn { "[$modName] access widener: not applied" } // no facade: LoggerUtils.kt
```

### Java

Wrong:

```java
DebugType.Mod.println(PREFIX + "registered in " + config);                   // concatenated before the check
DebugType.Mod.printException(t, LogSeverity.Error, PREFIX + "failed: %s", why); // the game drops these arguments
```

Correct:

```java
DebugType.Mod.println(PREFIX + "registered in %s", config);  // PREFIX is a constant, folded at compile time
DebugType.Mod.printException(t, PREFIX + "failed: " + why, LogSeverity.Error); // error path only
```

A literal `%` in a pattern is written `%%`.

## Debug Output

- Output about a target mod's behaviour is gated on **that mod's own debug option** and logged with `log { }`, so
  players can turn it on with the mod's logs.
- Other diagnostic output uses `debug { }`: the game's debug level, shown only in `-debug` mode.
- Never log per frame: aggregate and emit a periodic summary.

Reference files are in `ime-keyboard-fix/src/main/kotlin/com/dont39/pz/imekeyboardfix/` and
`dont39-all-in-one/src/main/kotlin/com/dont39/pz/allinone/AllInOneMain.kt`. The library's lines are in
`Main.java` (`zb-kt/src/main/java/com/dont39/pz/zbkt/`). Kotlin features get the
prefix and lifecycle lines from `Feature.kt` ([ZabricKotlin Utils](./zb-kt-utils.md)).
