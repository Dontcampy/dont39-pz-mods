#!/bin/bash
# usage: run.sh <hz> <label> [warmup_s] [measure_s] [clients]
HZ=$1; LABEL=$2; WARM=${3:-120}; MEAS=${4:-300}; N=${5:-8}
S="$(cd "$(dirname "$0")" && pwd)"; PZ="E:/SteamLibrary/steamapps/common/ProjectZomboid"; R=sirius@192.168.50.249
OUT="$S/results"; mkdir -p "$OUT"
ssh -o BatchMode=yes $R 'cd ~/pz-only/myserver2_Data && rm -rf Saves/Multiplayer/myserver2 db && cp -a ticktest-backup/save-snapshot/myserver2 Saves/Multiplayer/ && cp -a ticktest-backup/save-snapshot/db . && : > tickrate-console.log && tmux new-session -d -s pztick "./tickrate-start.sh '$HZ'" && timeout 300 bash -c "until grep -q \"SERVER STARTED\" tickrate-console.log; do sleep 2; done" && grep "server-tick-rate: tick rate" tickrate-console.log' || { echo "server start failed"; exit 1; }
pids=()
for i in $(seq 1 $N); do
  (cd "$PZ" && exec "$PZ/jre64/bin/java" -Xmx512m -Djava.awt.headless=true -Dzomboid.steam=0 -Djava.library.path="$PZ" -cp "$PZ/projectzomboid.jar" zombie.network.FakeClientManager -scenarios="$S/scenario.json" -id=$i -loglevel=1 > "$OUT/$LABEL-client$i.log" 2>&1) &
  pids+=($!)
  sleep 2
done
echo "clients started: ${pids[*]}"
sleep $WARM
ssh -o BatchMode=yes $R "cd ~/pz-only/myserver2_Data && P=\$(pgrep -f 'servername myserver2' | head -1); a=\$(awk '{print \$14+\$15}' /proc/\$P/stat); n0=\$(grep -c 'probe hz' tickrate-console.log); sleep $MEAS; b=\$(awk '{print \$14+\$15}' /proc/\$P/stat); echo \"proc_cpu_cores=\$(echo \"(\$b-\$a)/100/$MEAS\" | bc -l)\"; tail -n +1 tickrate-console.log | grep 'probe hz' | tail -n +\$((n0+1))" > "$OUT/$LABEL.txt"
ssh -o BatchMode=yes $R 'cd ~/pz-only/myserver2_Data && grep -cE "Connected new client" tickrate-console.log; tmux send-keys -t pztick quit Enter; timeout 120 bash -c "while tmux has-session -t pztick 2>/dev/null; do sleep 2; done"; cp tickrate-console.log ticktest-backup/console-'$LABEL'.log' > "$OUT/$LABEL-stop.txt"
powershell.exe -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -like '*FakeClientManager*' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }"
wait
echo "done $LABEL"; head -1 "$OUT/$LABEL.txt"; grep -c 'probe hz' "$OUT/$LABEL.txt"
