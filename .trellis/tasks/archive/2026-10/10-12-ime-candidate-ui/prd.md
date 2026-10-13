# Show the IME composition and candidate UI in text boxes

## Goal

While typing CJK in a game text box, the player cannot see the IME's composition string (e.g. `nihao`) or candidate
list; committed text does arrive. Make both visible so typing is not blind. Vanilla behavior, not caused by
`ime-keyboard-fix` (confirmed by the user).

## Background

- Cause, in LWJGL's GLFW (`LWJGL-CI/glfw` `src/win32_window.c`, `windowProc`):
  - `WM_IME_SETCONTEXT` always clears `ISC_SHOWUICOMPOSITIONWINDOW` (the app is meant to draw preedit itself); it
    clears `ISC_SHOWUICANDIDATEWINDOW` only under the init hint `GLFW_MANAGE_PREEDIT_CANDIDATE`, which the game does
    not set (and which must be set before `glfwInit`, before mods load).
  - `WM_IME_COMPOSITION` (with `GCS_RESULTSTR`/`GCS_COMPSTR`), `WM_IME_ENDCOMPOSITION` and `WM_IME_NOTIFY`
    (`IMN_SETOPENSTATUS`, `IMN_OPENCANDIDATE`, `IMN_CHANGECANDIDATE`, `IMN_CLOSECANDIDATE`) return TRUE without
    `DefWindowProc`, so the system IME windows never open or update.
  - Result strings are committed through GLFW's char callback (`commitImmResultStr`), which is why typing works.
    `WM_IME_CHAR` is not handled by GLFW (it would reach `DefWindowProc` and become `WM_CHAR`).
  - `glfwSetPreeditCursorRectangle` → `ImmSetCompositionWindow(CFS_RECT)` + `ImmSetCandidateWindow(CFS_EXCLUDE)` on
    the window's current input context.
- The game registers no preedit/candidate callbacks. UI coordinates map 1:1 to window client pixels (`Core.width` comes
  from `Display.getWidth`).
- Lua text boxes (`ISTextEntryBox`) are Java `zombie.ui.UITextBox2`. Its `render()` draws the caret at (relative to
  the box, then shifted by `getAbsoluteX/Y` + `xScroll/yScroll` in `UIElement.DrawTextureScaledCol`):
  x = `getInset()` - `xOffset` + `font.getWidth(lines[cursorLine], 0, col - 1, true)` - 1 (0 when `lines` is empty),
  col = `getCursorPos()` - `textOffsetOfLineStart[cursorLine]` capped at the line length; y = `getInset()` (or
  centered when `centerVertically`) + `cursorLine` x line height. `xOffset`, `centerVertically` and
  `textOffsetOfLineStart` are private.
  `zombie.ui.AtomUITextEntry` (PZAPI atoms, used by the `BuildUI`/`Inventory` organisms) has no public absolute
  position.
- LWJGL ships `org.lwjgl.system.windows.WindowProc` and `User32` (`SetWindowLongPtr`, `CallWindowProc`,
  `DefWindowProc`), so the window procedure can be subclassed without new dependencies.
- Prior art: IMBlocker draws its own overlays; this task uses the system windows instead (D1).

## Decisions

- D1 (user): restore the system IME windows (window-procedure subclass), not an in-game overlay. The user plays
  borderless; exclusive fullscreen is not a target.
- D2 (user): the IME windows follow the text caret: the composition string starts at the caret, and the system places
  the candidate list next to the caret line (below, or above when there is no room). The caret position is
  recomputed the way `UITextBox2.render` draws the caret, which reads private fields; if the game changes that math
  without renaming them, the windows are misplaced but typing still works (accepted by the user).
- D4 (user, after in-game testing): ship as is with a documented limitation. In windowed mode the IME windows show
  (verified with WeChat IME). In the game's borderless mode they stay hidden: borderless is an undecorated window
  sized to the desktop (`Display.setBorderlessWindow` → `GLFW_DECORATED` false, `glfwSetWindowMonitor` with no
  monitor), which Windows treats as a fullscreen app, and WeChat IME then hides its windows even though every IME
  message reaches the system (checked with temporary logging). Making the window not count as fullscreen, or drawing
  the IME UI in game, is not pursued.
- D3 (user): part of the existing `ime-keyboard-fix` feature, not a separate module: one feature, one status line, one
  disable. A failure in either part turns both off and restores vanilla (IME attached, original window procedure).
  Windows only, no mod option, as before. Delivered on the same branch `feat/ime-keyboard-fix` and the same PR
  (Dontcampy/dont39-pz-mods#3) (user).

## Requirements

- R1: While typing in a `UITextBox2` with an IME, the composition string and the candidate list are visible.
- R2: Committed text is inserted exactly once (no doubled characters), as today.
- R3: The IME windows appear at the caret of the focused text box (D2) and follow it as the caret moves, the text
  scrolls, or the box moves (e.g. a dragged window).
- R4: Keyboard behavior from `ime-keyboard-fix` is unchanged (no composition outside text boxes).
- R5: On any failure the feature re-attaches the IME, restores the original window procedure if it can, logs one
  error and disables itself; it never crashes the game. Elsewhere than Windows it is idle.
- R6: Follows the feature conventions (status lines, no per-frame logging); the existing Workshop section describes
  the visible IME windows.

## Acceptance Criteria

- [x] AC1 (R1): Windowed (D4), WeChat IME CN mode, chat: typing `nihao` shows the pinyin string and a candidate
      list; picking a candidate (space / number) commits it.
- [ ] AC2 (R2): `你好` appears once in chat; English letters typed in EN mode appear once.
- [ ] AC3 (R3): In chat the composition string starts at the caret, after the text already typed, and the candidate
      list sits next to that line, above it when the chat box is at the bottom of the screen. Same in a mid-screen box
      (e.g. inventory search or a rename dialog), also after dragging its window. In a long line that scrolls
      horizontally the windows stay at the caret.
- [ ] AC4 (R4): Outside text boxes WASD/hotkeys work with the IME on, as after `ime-keyboard-fix`.
- [ ] AC5 (R6): `console.txt` shows `ime-keyboard-fix: ready` then `active`, with `Transformed:` lines for
      `org.lwjglx.opengl.Display` and `zombie.ui.UITextBox2`; `build assembleMod` and `smokeTest` pass.
- [ ] AC6 (R5): Non-Windows idle — code review only.

## Out of Scope

- Borderless and exclusive fullscreen visibility (D4).
- `AtomUITextEntry` text boxes (PZAPI atoms): the IME windows show but are not positioned at the caret.
- In-game drawn preedit/candidates; auto-English for chat commands.
