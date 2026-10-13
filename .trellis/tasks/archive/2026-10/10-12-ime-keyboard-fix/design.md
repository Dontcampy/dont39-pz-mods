# Design: IME keyboard fix

## Module

New Kotlin feature module `ime-keyboard-fix` (follows [Adding a Feature](../../spec/mod/directory-structure.md#adding-a-feature)):
package `com.dont39.pz.imekeyboardfix`, facade `ImeKeyboardFix : Feature("ime-keyboard-fix", "Dont39AllInOne")`, one
helper object for the IME state. No Lua, no target mods in `mod.info`, no mod option (PRD D2).

## Gating (facade)

- `problem()`: non-null ("not running on Windows") unless `org.lwjgl.system.Platform.get() == WINDOWS` → `idle`.
- `resolve()`: load `imm32.dll` through LWJGL (`org.lwjgl.system.Library.loadNative` / `WindowsLibrary`) and look up
  `ImmAssociateContextEx`; check by reflection that `org.lwjglx.opengl.Display.processMessages()` and
  `zombie.core.Core.currentTextEntryBox` exist (the hook target and the focus field), so a game update that renames
  them disables the feature with an error instead of silently never running (PRD D5). Throws on mismatch.

## Hook

`@Hook.Exit(className = "org.lwjglx.opengl.Display", methodName = "processMessages")` → helper `sync()`.

Why there: it runs once per frame on the thread that pumps the window's messages, which on Windows is the window's
owner thread, the only thread IMM association calls are valid on. `Display` is already loaded when patches apply, so
ZombieBuddy retransforms it (`PatchEngine` RETRANSFORMATION path); verify the `Transformed:` line in game.

## State machine (helper, render thread only)

```
wanted = box != null && box.isEditable() && box.isDoingTextEntry()   // box = Core.currentTextEntryBox, read once
hwnd   = GLFWNativeWin32.glfwGetWin32Window(Display.getWindow())
if hwnd != appliedHwnd -> appliedHwnd = hwnd; attached = true        // new window starts with the default context
if wanted != attached  -> ImmAssociateContextEx(hwnd, 0, wanted ? IACE_DEFAULT : 0); attached = wanted
```

- Read `currentTextEntryBox` once into a local: it is written on the main thread, and `Core.isDoingTextEntry()` reads
  the static three times, so calling it from the render thread can NPE. A one-frame lag is harmless.
- `IACE_DEFAULT` (0x10) re-attaches the thread's default input context, which is the one GLFW's window had, so the
  conversion mode the player chose is kept (PRD D4). Detach = `hIMC = NULL, flags = 0`. No handle bookkeeping.
- Native call: `org.lwjgl.system.JNI.callPPI(hwnd, 0L, flags, fnAddress)` returns `BOOL`; no allocation, no JNA.
  Confirm the overload with `javap -c` (spec: Threading and Hot Paths).
- `ImmAssociateContextEx` returning FALSE is an error (`no-hidden-degrade`): restore, then disable.
- Per frame without a transition: one static read, two interface calls, one GLFW pointer lookup, two compares. No
  allocation, lambdas or boxing; no logging.

## Error handling and restore

- `sync()` catches `Throwable`: restore, then `ImeKeyboardFix.disable("<what>", t)`.
- `restore()`: if `attached == false` and `appliedHwnd != 0`, call with `IACE_DEFAULT`; never throws (catch and drop);
  sets `attached = true`.
- Disabled is final for the session; the window is left with the IME attached (vanilla).
- Game exit / window destroy: nothing to undo; the association dies with the window and the default context is owned
  by the thread.

## Compatibility

- Other IME mods: if one also changes the association, the last writer wins each transition; not handled (none known
  for PZ).
- Alt-tab: association is per window, other apps unaffected (PRD R4).
- Window recreated (display mode change): detected by `hwnd` change, re-applied next frame.

## Rollback

Feature is self-contained: remove it from the feature list in `dont39-all-in-one/build.gradle.kts`, `settings.gradle.kts`
and `Main.kt`.
