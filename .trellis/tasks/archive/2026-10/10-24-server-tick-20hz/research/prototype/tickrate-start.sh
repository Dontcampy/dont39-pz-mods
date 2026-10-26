#!/bin/bash
# Tick-rate test instance (myserver2_Data, -nosteam for FakeClientManager); usage: tickrate-start.sh <hz>
# stop with: tmux send-keys -t pztick quit Enter
HZ=${1:-10}
D=$HOME/pz-only/myserver2_Data
cd $HOME/pz-only/servers
export LD_LIBRARY_PATH="$PWD/linux64/:$PWD:$PWD/jre64/lib/amd64:$PWD/jre64/lib/x86_64:/usr/lib64:${LD_LIBRARY_PATH}"
mkdir -p $D/zombie_buddy
export JAVA_TOOL_OPTIONS="-javaagent:$PWD/ZombieBuddy.jar=policy=allow-all,config_dir=$D/zombie_buddy -Ddont39.serverTickHz=$HZ"
exec ./jre64/bin/java -Xms1g -Xmx4g -Djava.awt.headless=true -Dzomboid.steam=0 -Dzomboid.znetlog=1 -XX:+UseZGC -XX:-OmitStackTraceInFastThrow -Djava.library.path=linux64/:. -cp java/.:java/projectzomboid.jar zombie.network.GameServer -nosteam -servername myserver2 -cachedir=$D -adminpassword <redacted> -port 48261 -udpport 48262 2>&1 | tee -a $D/tickrate-console.log
