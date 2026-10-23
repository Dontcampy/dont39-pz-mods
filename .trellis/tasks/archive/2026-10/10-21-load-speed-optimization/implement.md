# Implementation plan: load speed optimization

The parent owns no code; each child is planned, implemented, checked and archived on its own. Order:

1. **`10-21-asset-wait-breakdown` (R1)** and **`10-21-faster-loading-strip-comments` (mod + R3)**: independent,
   can run in parallel (different modules: `loading-progress/` vs new `faster-loading/`).
2. **`10-21-lua-folder-walk` (R4)**: after the mod exists (step 1's second child).
3. **`10-21-asset-wait-speedup` (R2)**: re-enter planning with a real join report from R1, write its PRD
   requirements/acceptance and design, then implement.
4. **Parent integration check**: all switches on vs off, 3 launches + 3 joins each; record medians against the
   2026-10-21 baseline in `verification.md`; confirm AC5.

## Validation commands (each child)

- `JAVA_HOME=~/.jdks/graalvm-ce-25.0.2 ./gradlew :<module>:check` (build gate incl. `checkJdkGlue`, unit tests).
- In-game: deploy, launch to menu and join the Linux test server; read the newest reports in
  `~/Zomboid/Lua/Dont39LoadingProgress/` and `console.txt`. Check the mod list first (the launcher rewrites
  `default.txt`).
- Worktrees get LFS pointer PNGs: `git lfs pull` before any Workshop staging. Uploads are the user's.

## Review gates

- Before each child's `task.py start`: its PRD reviewed; R2 additionally needs R1 data.
- After each child: `trellis-check`, then in-game numbers in that child's `verification.md`.

## Rollback points

- Per child: its own commit(s); a fix can also be switched off in the ini without a build.
