# Single-player load harness (not shipped)

What ran the two loads of [../in-game.md](../in-game.md): the game as `ProjectZomboid64.exe` starts it (Steam on, the
user's mod list), main menu, a new sandbox world, quit. Run under the game test lock, with the game closed.

| File | What it does |
|---|---|
| `setup.sh <work dir>` | Backs up the Loading Progress reports, `default.txt`, `options.ini`, `Lua/*.ini` and the ZombieBuddy config to `<work dir>/backup`, sets `focusloss=false`, adds Faster Loading (if missing) and the driver to the mod list, copies the driver to `~/Zomboid/mods` |
| `run-game.ps1 -Label <name> -RunRoot <dir> -GameDir <game folder>` | Launches, shows the driver's steps, posts the click the loading screen waits for, and copies `console.txt` and the run's new Loading Progress reports to `<dir>/<name>` |
| `restore.sh <work dir>` | Puts the backup back, removes the driver, the test world and the run's reports from the user's folder, and compares hashes |
| `FLTestDriver/` | Lua-only mod, idle unless `~/Zomboid/Lua/fltest.txt` exists: waits `menuSec` in the main menu, starts the world `world` the way `MainScreen.lua` starts its demo world, quits `gameSec` after `OnGameStart` |

`setup.sh` checks the lock's owner line for `faster-loading`; another task changes that word. `<work dir>` and
`<dir>` are outside the repo.
