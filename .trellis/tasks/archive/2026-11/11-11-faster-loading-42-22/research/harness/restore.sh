#!/bin/bash
# Under the lock: put the user's client files back as setup.sh found them and remove what the test added.
set -uo pipefail
SP="$1"; Z="$HOME/Zomboid"; BK="$SP/backup"
[ -d "$BK" ] || { echo "no backup"; exit 1; }
cp -a "$BK/default.txt" "$Z/mods/default.txt"
cp -a "$BK/options.ini" "$Z/options.ini"
cp -a "$BK/zb-config.json" "$HOME/.zombie_buddy/config.json"
for f in "$Z"/Lua/*.ini; do b="$BK/Lua-ini/$(basename "$f")"; if [ -f "$b" ]; then cp -a "$b" "$f"; else echo "new ini left by the run, removed: $f"; rm -f "$f"; fi; done
# the report folder as it was: the run's reports are kept elsewhere
for f in "$Z"/Lua/Dont39LoadingProgress/*; do [ -e "$BK/Dont39LoadingProgress/$(basename "$f")" ] || rm -f "$f"; done
cp -a "$BK/Dont39LoadingProgress/." "$Z/Lua/Dont39LoadingProgress/"
rm -rf "$Z/mods/FLTestDriver" "$Z/Saves/Sandbox/fl-42-22-test"
rm -f "$Z/Lua/fltest.txt" "$Z/Lua/fltest_state.txt"
( cd "$Z" && sha256sum -c "$BK/hashes.txt" 2>&1 | grep -v ": OK$" ) ; echo "hash check done (lines above are differences)"
diff <(ls "$BK/Dont39LoadingProgress") <(ls "$Z/Lua/Dont39LoadingProgress") && echo "report folder as before"
diff "$BK/mods-folder-listing.txt" <(ls "$Z/mods") && echo "mods folder as before"
