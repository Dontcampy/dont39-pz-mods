# Fix keyboard game controls blocked by active IME

## Goal

When an IME (e.g. a Chinese input method) is switched on, keystrokes go into IME composition instead of the game, so
movement and hotkeys stop working. Make gameplay keys work regardless of IME state, while text boxes (chat, search,
naming dialogs, main-menu fields) can still use the IME to type CJK text.

## Background (from the game jar, B42, and prior art)

- The game uses LWJGL 3.4.1 with LWJGL's patched GLFW 3.4. Its `GLFW_IME` input mode only calls `ImmSetOpenStatus`
  (switch to English), it cannot detach the IME, and the game never uses it. Key/char callbacks are installed in
  `org.lwjglx.opengl.Display$Callbacks`.
- GLFW messages are pumped by `org.lwjglx.opengl.Display.processMessages()` (`glfwPollEvents`), called from
  `zombie.core.opengl.RenderThread`; on Windows that is necessarily the window's owner thread.
- The game routes keys to a text box instead of gameplay when `zombie.core.Core.currentTextEntryBox` (static
  `UITextEntryInterface`) is non-null and `isDoingTextEntry()` (`zombie.input.GameKeyboard.isKeyDown`/`update`).
  `Core.isDoingTextEntry()` additionally requires `isEditable()`. Lua `ISTextEntryBox` wraps Java `UITextBox2`, so
  Lua text boxes go through the same field.
- Prior art: Minecraft's IMBlocker (`reserveword/IMBlocker`, `common/.../IMManagerWindows.java` `setState`) detaches
  the IME with `ImmAssociateContext(hwnd, null)` outside text widgets and attaches a freshly created context inside
  them, on the window's thread. A fresh context resets the player's CN/EN mode.

## Decisions

- D1 (user): outside text boxes the IME is fully detached from the game window, not merely switched to English mode;
  the player cannot turn it back on while playing.
- D2 (user): no mod option; the feature is always on where it can run.
- D3: Windows only. Elsewhere the feature reports idle and does nothing.
- D4: re-attaching restores the window's default input context, so the player's CN/EN mode is kept (unlike
  IMBlocker's fresh context).
- D5: no game-version allowlist (the target is vanilla, not another mod); the game members the feature relies on are
  checked at resolve time and a mismatch disables it with an error.

## Requirements

- R1: While no text box is taking text entry, the IME is detached from the game window: with the IME switched on
  (CN mode), WASD/hotkeys control the game and no IME composition or candidate window appears.
- R2: When a text box takes text entry (editable, focused), the IME is attached again in the mode the player last had;
  CJK typing works as in vanilla. When it loses focus, the IME is detached again.
- R3: Applies everywhere the game window runs: main menu and in game.
- R4: Only the game window is affected; other applications' IME state is untouched, including after alt-tab.
- R5: On any failure the feature re-attaches the IME (vanilla behavior), logs one error and disables itself for the
  session; it never crashes the game.
- R6: Follows the feature conventions: own module, `ready`/`idle`/`active` status lines, no per-frame logging or
  allocation, Workshop description section.

## Acceptance Criteria

- [ ] AC1 (R1): Windows, Microsoft Pinyin in CN mode, in game: WASD moves, hotkeys (e.g. inventory, map) work, no
      composition/candidate window; pressing Shift or Ctrl+Space does not bring the IME back.
- [ ] AC2 (R2): Open chat, type `nihao` → candidates appear and `你好` is committed; leave chat → AC1 holds again.
- [ ] AC3 (R2/D4): Switch the IME to EN mode inside chat, close chat, reopen → still EN mode.
- [ ] AC4 (R3): Main-menu text fields (e.g. a server name / search field) accept Chinese input; main menu keyboard
      navigation is not blocked outside them.
- [ ] AC5 (R4): Alt-tab to another app: its IME works normally; back in game AC1 holds.
- [ ] AC6 (R6): `console.txt` shows `[Dont39AllInOne] <feature>: ready`, then `active`; nothing per frame.
      `./gradlew build assembleMod` and `smokeTest` pass.
- [ ] AC7 (D3): On a non-Windows OS the feature logs `idle: ...` and changes nothing (verified by code review; no
      non-Windows test machine).

## Out of Scope

- Auto-switching to English for chat commands (IMBlocker feature).
- Positioning the IME candidate window or drawing preedit text in game.
- ImGui debug windows' text fields; dedicated server (no window); Linux/macOS IMEs.
- A mod option to turn the feature off (D2).
