#!/bin/bash
# AC5 test instance "ac5test": the live server's ini, sandbox and spawn files in a fresh cache folder ac5_Data, with
# test ports, no password, no backups, the Lua checksum on, the tunnel and mod sync off, and the test driver mod.
# Nothing of myserver_Data or myserver2_Data is changed. Remove with: rm -rf ~/pz-only/ac5_Data ~/pz-only/ac5-start.sh
set -euo pipefail
P=$HOME/pz-only
L=$P/myserver_Data/Server
D=$P/ac5_Data
if [ -e "$D" ]; then echo "$D exists"; exit 1; fi
mkdir -p "$D/Server" "$D/mods" "$D/zombie_buddy"
sed -e 's/^DefaultPort=.*/DefaultPort=48261/' -e 's/^UDPPort=.*/UDPPort=48262/' -e 's/^RCONPort=.*/RCONPort=47016/' \
    -e 's/^Password=.*/Password=/' -e 's/^DoLuaChecksum=.*/DoLuaChecksum=true/' \
    -e 's/^BackupsOnStart=.*/BackupsOnStart=false/' -e 's/^\(Mods=.*\)$/\1;LPTestDriver/' -e 's/;;LPTestDriver$/;LPTestDriver/' \
    "$L/myserver.ini" > "$D/Server/ac5test.ini"
perl -0pe 's/(Dont39AllInOne_TcpTunnel = \{[^}]*?Port = )\d+/${1}0/; s/(Dont39AllInOne_Tunnel = \{[^}]*?KcpPort = )\d+/${1}0/; s/(Dont39AllInOne_ModSync = \{[^}]*?Port = )\d+/${1}0/' \
    "$L/myserver_SandboxVars.lua" > "$D/Server/ac5test_SandboxVars.lua"
cp "$L/myserver_spawnregions.lua" "$D/Server/ac5test_spawnregions.lua"
cp "$L/myserver_spawnpoints.lua" "$D/Server/ac5test_spawnpoints.lua"
cat > "$P/ac5-start.sh" <<'EOS'
#!/bin/bash
# AC5 test instance; stop with: tmux send-keys -t pzac5 quit Enter
D=$HOME/pz-only/ac5_Data
cd $HOME/pz-only/servers
export LD_LIBRARY_PATH="$PWD/linux64/:$PWD:$PWD/jre64/lib/amd64:$PWD/jre64/lib/x86_64:/usr/lib64:${LD_LIBRARY_PATH}"
export JAVA_TOOL_OPTIONS="-javaagent:$PWD/ZombieBuddy.jar=policy=allow-all,config_dir=$D/zombie_buddy"
exec ./jre64/bin/java -Xms4g -Xmx16g -Djava.awt.headless=true -Dzomboid.steam=1 -Dzomboid.znetlog=1 -XX:+UseZGC -XX:+UseCompactObjectHeaders -XX:-OmitStackTraceInFastThrow -Djava.library.path=linux64/:. -cp java/.:java/projectzomboid.jar zombie.network.GameServer -servername ac5test -cachedir=$D -adminpassword "$(cat $D/adminpw)" -port 48261 -udpport 48262 2>&1 | tee -a $D/ac5-console.log
EOS
chmod +x "$P/ac5-start.sh"
head -c 12 /dev/urandom | base64 | tr -dc A-Za-z0-9 > "$D/adminpw"
grep -nE '^(DefaultPort|UDPPort|RCONPort|Password|DoLuaChecksum|BackupsOnStart|Open)=' "$D/Server/ac5test.ini"
grep -o 'LPTestDriver' "$D/Server/ac5test.ini" | wc -l
grep -nE '^\s+(Port|KcpPort) = ' "$D/Server/ac5test_SandboxVars.lua" | head
diff <(grep -v -E '^(DefaultPort|UDPPort|RCONPort|Password|DoLuaChecksum|BackupsOnStart|Mods)=' "$L/myserver.ini") \
     <(grep -v -E '^(DefaultPort|UDPPort|RCONPort|Password|DoLuaChecksum|BackupsOnStart|Mods)=' "$D/Server/ac5test.ini") && echo "other ini lines identical"
