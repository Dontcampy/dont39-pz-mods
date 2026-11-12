#!/bin/bash
# Prepares the myserver2 test instance for the Server Reborn 42.22 runs. Run on the Linux test server.
set -euo pipefail
D=$HOME/pz-only/myserver2_Data
B=$D/backups-2026-11-11-sr-42.22
cd "$D"
if pgrep -f 'servername myserver2' >/dev/null; then echo "myserver2 is running"; exit 1; fi
if [ ! -f "$B/state.tgz" ]; then
  mkdir -p "$B"
  tar czf "$B/state.tgz" Server mods zombie_buddy db Lua Saves/Multiplayer/myserver2 2>/dev/null || true
  echo "backup: $(ls -la "$B/state.tgz" | awk '{print $5}') bytes"
else
  echo "backup kept: $B/state.tgz"
fi
ini=Server/myserver2.ini
grep -v -E '^(RCONPort|RCONPassword|Mods|WorkshopItems|PauseEmpty|Public|Open|DoLuaChecksum)=' "$ini" > "$ini.new" || true
{
  echo
  echo "RCONPort=48015"
  echo "RCONPassword=$(head -c 18 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 20)"
  echo "Mods=ZombieBuddy;Zabric;ZabricKotlin;Dont39ServerReborn;SGTestDriver"
  echo "WorkshopItems="
  echo "PauseEmpty=false"
  echo "Public=false"
  echo "Open=true"
  echo "DoLuaChecksum=false"
} >> "$ini.new"
mv "$ini.new" "$ini"
chmod 600 "$ini"
grep -E '^(RCONPort|Mods|PauseEmpty|DoLuaChecksum)=' "$ini"
rm -rf mods/Zabric mods/ZabricKotlin mods/Dont39ServerReborn mods/SGTestDriver mods/Dont39AllInOne
ls mods
pw=$(grep -o 'adminpassword [^ ]*' tickrate-start.sh | awk '{print $2}')
cat > sr4222-start.sh <<EOF
#!/bin/bash
# Server Reborn on 42.22 test instance (myserver2_Data, -nosteam); usage: sr4222-start.sh [extra JVM options]
# stop with: tmux send-keys -t pzsr quit Enter
D=\$HOME/pz-only/myserver2_Data
cd \$HOME/pz-only/servers
export LD_LIBRARY_PATH="\$PWD/linux64/:\$PWD:\$PWD/jre64/lib/amd64:\$PWD/jre64/lib/x86_64:/usr/lib64:\${LD_LIBRARY_PATH}"
mkdir -p \$D/zombie_buddy
export JAVA_TOOL_OPTIONS="-javaagent:\$PWD/ZombieBuddy.jar=policy=allow-all,config_dir=\$D/zombie_buddy"
exec ./jre64/bin/java -Xms1g -Xmx6g -Djava.awt.headless=true -Dzomboid.steam=0 -Dzomboid.znetlog=1 -XX:+UseZGC -XX:-OmitStackTraceInFastThrow -Dserverreborn.debugFieldDiff=true -Dserverreborn.debugTouches=true -Dserverreborn.summarySeconds=30 "\$@" -Djava.library.path=linux64/:. -cp java/.:java/projectzomboid.jar zombie.network.GameServer -nosteam -servername myserver2 -cachedir=\$D -adminpassword $pw -port 48261 -udpport 48262 2>&1 | tee -a \$D/sr4222-console.log
EOF
chmod 700 sr4222-start.sh
: > sr4222-console.log
grep -n "ServerTickRate" Server/myserver2_SandboxVars.lua || echo "no tick rate option in the sandbox file"
echo prepared
