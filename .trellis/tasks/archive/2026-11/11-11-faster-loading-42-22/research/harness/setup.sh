#!/bin/bash
# Under the lock: back up the user's client files, then enable the test's mods and the driver.
set -euo pipefail
SP="$1"; Z="$HOME/Zomboid"; BK="$SP/backup"
[ -d "$Z/.dont39-game-test.lock" ] && grep -q "faster-loading" "$Z/.dont39-game-test.lock/owner.txt" || { echo "lock not held"; exit 1; }
[ -e "$BK" ] && { echo "$BK exists"; exit 1; }
mkdir -p "$BK/Lua-ini"
cp -a "$Z/Lua/Dont39LoadingProgress" "$BK/Dont39LoadingProgress"
cp -a "$Z/mods/default.txt" "$BK/default.txt"
cp -a "$Z/options.ini" "$BK/options.ini"
cp -a "$HOME/.zombie_buddy/config.json" "$BK/zb-config.json"
cp -a "$Z"/Lua/*.ini "$BK/Lua-ini/" 2>/dev/null || true
ls "$Z/mods" > "$BK/mods-folder-listing.txt"
( cd "$Z" && sha256sum mods/default.txt options.ini Lua/*.ini ) > "$BK/hashes.txt"; sha256sum "$HOME/.zombie_buddy/config.json" >> "$BK/hashes.txt"
echo "backup at $BK: $(ls "$BK/Dont39LoadingProgress" | wc -l) report files"
# unattended run: no pause when the window is not in front
sed -i 's/^focusloss=true/focusloss=false/' "$Z/options.ini"
grep -n '^focusloss' "$Z/options.ini"
python -I - "$Z/mods/default.txt" <<'PY'
import sys,re
p=sys.argv[1]; s=open(p,encoding="utf-8",newline="").read()
nl="\r\n" if "\r\n" in s else "\n"
add=[m for m in ("Dont39FasterLoading","FLTestDriver") if not re.search(r"(?m)^\s*mod = "+re.escape(m)+r",\s*$",s)]
anchor=re.search(r"(?m)^(\s*)mod = Zabric,[ \t]*\r?\n",s)
assert anchor, "no 'mod = Zabric,' line"
ins="".join("%smod = %s,%s"%(anchor.group(1),m,nl) for m in add)
s=s[:anchor.end()]+ins+s[anchor.end():]
open(p,"w",encoding="utf-8",newline="").write(s)
print("added to default.txt:",add)
PY
rm -rf "$Z/mods/FLTestDriver"; cp -r "$(dirname "$0")/FLTestDriver" "$Z/mods/FLTestDriver"; mkdir -p "$Z/mods/FLTestDriver/common"
echo "driver at $Z/mods/FLTestDriver"
