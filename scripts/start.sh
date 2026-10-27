#!/usr/bin/env bash
# Starts the three services on the host against the docker-compose infra. Logs go to $RUN_DIR/logs.
# Secrets are read from the environment; without them the services fall back to their dev-only values.
set -euo pipefail
cd "$(dirname "$0")/.."

RUN_DIR=${RUN_DIR:-/workspace/tokengate-run}
MODEL_DIR=${TOKENGATE_EMBEDDING_MODEL_DIR:-$PWD/models/all-MiniLM-L6-v2}
mkdir -p "$RUN_DIR/logs" "$RUN_DIR/pids"

start() {
  local name=$1 port=$2; shift 2
  if curl -sf "localhost:$port/actuator/health" >/dev/null 2>&1; then
    echo "$name already up on :$port"
    return
  fi
  setsid nohup "$@" > "$RUN_DIR/logs/$name.log" 2>&1 < /dev/null &
  echo $! > "$RUN_DIR/pids/$name.pid"
  echo "started $name (pid $!) on :$port"
}

wait_ready() {
  local name=$1 port=$2
  for _ in $(seq 1 90); do
    if curl -sf "localhost:$port/actuator/health/readiness" >/dev/null 2>&1 \
       || curl -sf "localhost:$port/actuator/health" >/dev/null 2>&1; then
      echo "$name ready"
      return
    fi
    sleep 1
  done
  echo "$name did not become ready, see $RUN_DIR/logs/$name.log" >&2
  exit 1
}

start mock-provider 9082 java -Xmx256m -jar tokengate-mock-provider/target/tokengate-mock-provider.jar
if [ -f tokengate-metering/target/tokengate-metering.jar ]; then
  start metering 9081 java -Xmx256m -jar tokengate-metering/target/tokengate-metering.jar
fi
TOKENGATE_EMBEDDING_MODEL_DIR=$MODEL_DIR \
  start gateway 9080 java -Xmx512m -jar tokengate-gateway/target/tokengate-gateway.jar

wait_ready mock-provider 9082
[ -f tokengate-metering/target/tokengate-metering.jar ] && wait_ready metering 9081
wait_ready gateway 9080
