#!/usr/bin/env bash
# Stops only the processes start.sh launched (by recorded pid), nothing else on the machine.
RUN_DIR=${RUN_DIR:-/workspace/tokengate-run}
for f in "$RUN_DIR"/pids/*.pid; do
  [ -e "$f" ] || continue
  pid=$(cat "$f")
  if kill -0 "$pid" 2>/dev/null && ps -p "$pid" -o args= | grep -q tokengate; then
    kill "$pid" && echo "stopped $(basename "$f" .pid) ($pid)"
  fi
  rm -f "$f"
done
