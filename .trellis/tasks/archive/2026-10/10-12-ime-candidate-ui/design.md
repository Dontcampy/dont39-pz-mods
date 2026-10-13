# Design: IME candidate UI

## Module

Extends the existing `ime-keyboard-fix` module (PRD D3): same facade `ImeKeyboardFix`, new helpers next to
`ImeContext.kt` (e.g. a caret helper and a window-procedure helper). No new module or wiring.

Gating unchanged (`problem()` = not Windows → idle). `ImeKeyboardFix.resolve()` additionally checks by reflection the
members the new parts hook or reads (`Display.processMessages()`, `UITextBox2.render()`, `Core.currentTextEntryBox`, and `UITextBox2`'s caret
members: `xOffset`, `centerVertically`, `textOffsetOfLineStart`, `lines`, `font`) and throws on mismatch.

## Two halves, two threads

### Main thread: where the caret is

`@Hook.Exit` on `zombie.ui.UITextBox2.render` with `@Patch.This` (main thread, after `keepCursorVisible` has updated
`xOffset` for this frame):

- Return at once unless `this === Core.currentTextEntryBox` (read once) and it is editable and doing text entry; every
  other box pays only that check.
- Caret rect exactly as `render()` draws the caret (prd.md Background): x/y relative to the box, plus
  `getAbsoluteX/Y()` and `getXScroll/getYScroll()`; width 1, height = the font's line height
  (`TextManager.instance.getFontFromEnum(font).getLineHeight()`). Private `xOffset`, `centerVertically`,
  `textOffsetOfLineStart` through `Resolver` fields resolved once; `font.getWidth(line, 0, col - 1, true)` takes a range,
  so no substring is allocated.
- Publish as one `@Volatile Long` packing four 16-bit fields (x, y, w, h), so the render thread never sees a torn rect
  and nothing is allocated. Coordinates outside 0..32767 are an error.
- The game's own getters box (`BoxedStaticValues`); acceptable because the work runs only for the focused box, and the
  game calls them every frame anyway.
- No box focused: the last rect stays published; it is only used when a composition starts, which happens in a box.

### Render thread: the window

`@Hook.Exit` on `org.lwjglx.opengl.Display.processMessages` (the window's thread, as in `ime-keyboard-fix`):

- Install the subclass once per `hwnd` (`GLFWNativeWin32.glfwGetWin32Window`): keep the original proc from
  `SetWindowLongPtr(GWLP_WNDPROC, ours)`; a new `hwnd` installs again.
- When the published rect changes and is non-zero, call `glfwSetPreeditCursorRectangle(window, x, y, w, h)`.

### Window procedure (render thread, inside `glfwPollEvents`)

Per message, against GLFW's handling (Background in prd.md):

| Message | GLFW does | We do |
|---------|-----------|-------|
| `WM_IME_SETCONTEXT` | strips `ISC_SHOWUICOMPOSITIONWINDOW`, then `DefWindowProc` | `DefWindowProc` with the original `lParam` only |
| `WM_IME_STARTCOMPOSITION` | `DefWindowProc` | apply the current rect (`glfwSetPreeditCursorRectangle`), then GLFW |
| `WM_IME_COMPOSITION` with `GCS_RESULTSTR`/`GCS_COMPSTR` | commits / preedit, returns TRUE | GLFW, then `DefWindowProc` (updates the system window) |
| `WM_IME_ENDCOMPOSITION` | returns TRUE | GLFW, then `DefWindowProc` |
| `WM_IME_NOTIFY` `IMN_SETOPENSTATUS`/`OPENCANDIDATE`/`CHANGECANDIDATE`/`CLOSECANDIDATE` | returns TRUE | GLFW, then `DefWindowProc` |
| `WM_IME_CHAR` | `DefWindowProc` → `WM_CHAR` | return 0 (GLFW already committed the result; R2) |
| anything else | — | GLFW (`CallWindowProc(original, ...)`) |

Rect application at `WM_IME_STARTCOMPOSITION` makes placement independent of the order in which this feature and
`ime-keyboard-fix` re-attach the context in the same frame.

## Error handling

- One facade, one disable: a failure in the caret helper (main thread), the render-thread hook or the window proc calls
  `ImeKeyboardFix.disable(...)`. Both restores (IME re-attach, window procedure) are Win32 calls on the window's
  thread, so the render-thread hook runs them: when `active()` is false it performs any pending restore once, then
  returns. A failure already on the render thread restores immediately.
- The proc catches `Throwable`: forward the message to the original proc, then disable (restore below).
- Disable / restore (never throws): if `GetWindowLongPtr(GWLP_WNDPROC)` is still ours, put the original back;
  otherwise someone subclassed after us, so ours stays installed and forwards everything to the original (it checks
  `active()` first). The `WindowProc` callback object is never freed while it may be installed.
- `SetWindowLongPtr` returning 0, a zero `hwnd`, or a packed coordinate out of range are errors.

## Risks

- The message table follows GLFW master source; the bundled `glfw.dll` (LWJGL 3.4.1) is assumed to match. Doubled or
  missing characters or a frozen composition window in game mean a mismatch: AC1/AC2 are the check.
- High-DPI: assumes GLFW's per-monitor DPI awareness keeps UI coords = client pixels; AC3 checks it.
- Caret math copied from `UITextBox2.render`: a game change to it that keeps the member names misplaces the windows
  silently (accepted, prd.md D2); `resolve()` catches renamed or retyped members.
