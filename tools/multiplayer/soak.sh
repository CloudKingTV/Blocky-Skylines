#!/bin/bash
# Phase 10 soak: dedicated server + Alice + Bob for about an hour, with a client logout mid-construction and a server
# restart during a fire. Logs: ts-server.log, ts-alice.log, ts-bob.log, soak.log (timeline and /townstead perf).
# Set T to the test project (with the alice, bob and server runs of README.md) and S to a folder for the logs.
T=${TOWNSTEAD_TEST:?set TOWNSTEAD_TEST to the test project}; S=${SOAK_LOGS:-$PWD}; RCON="python3 $(dirname "$0")/rcon.py"
export SDL_VIDEODRIVER=offscreen EGL_PLATFORM=surfaceless XDG_RUNTIME_DIR=/tmp/xdg
mkdir -p /tmp/xdg
cd $T
START=$(date +%s)
say() { echo "[$(( ($(date +%s) - START) / 60 ))m$(( ($(date +%s) - START) % 60 ))s] $*" | tee -a $S/soak.log; }
server() { echo "==== server start $(date)" >> $S/ts-server.log; nohup ./gradlew runServer >> $S/ts-server.log 2>&1 & 
  for i in $(seq 200); do sleep 3; $RCON list >/dev/null 2>&1 && break; done; say "server up"; }
client() { echo "==== $1 start $(date)" >> $S/ts-$1.log; nohup ./gradlew run${1^} >> $S/ts-$1.log 2>&1 & say "$1 launching"; }
kill_client() { pkill -f "[${1:0:1}]${1:1}RunProgramArgs"; for i in $(seq 30); do pgrep -f "[${1:0:1}]${1:1}RunProgramArgs" >/dev/null || break; sleep 1; done; say "$1 gone"; }
perf() { $RCON "townstead perf" "list" 2>&1 | sed 's/^/    /' >> $S/soak.log; say "perf sampled"; }
wait_min() { local until=$(( START + $1 * 60 )); while [ $(date +%s) -lt $until ]; do sleep 20; done; }

: > $S/soak.log; rm -f $T/run-alice/*.done $T/run-alice/*.sent $T/run-bob/*.done $T/run-alice/bot.log $T/run-bob/bot.log
server
client alice; sleep 45; client bob
for m in 5 10; do wait_min $m; perf; done
# The fire: Alice lights one at about ten minutes in; restart the server while it burns.
for i in $(seq 120); do grep -q "/townstead ignite" $T/run-alice/bot.log 2>/dev/null && break; sleep 10; done
say "fire lit; restarting the server in 15 s"; sleep 15; perf
$RCON stop >/dev/null 2>&1; for i in $(seq 60); do pgrep -f '[s]erverRunProgramArgs' >/dev/null || break; sleep 2; done; say "server stopped"
kill_client alice; kill_client bob; sleep 5
server; client alice; sleep 30; client bob
for m in 20 25; do wait_min $m; perf; done
# Bob logs out mid-construction for four minutes; his town is far from Alice, so its chunks unload.
# A minute before, he knocks down four homes so new ones are going up when he leaves.
wait_min 29; touch $T/run-bob/build-now.flag; say "bob knocking down homes"
wait_min 30; perf; kill_client bob; wait_min 34; client bob
for m in 40 45 50 55 60 65; do wait_min $m; perf; done
say "soak over"; $RCON "save-all" >/dev/null 2>&1
kill_client alice; kill_client bob; $RCON stop >/dev/null 2>&1
for i in $(seq 60); do pgrep -f '[s]erverRunProgramArgs' >/dev/null || break; sleep 2; done; say "all stopped"
