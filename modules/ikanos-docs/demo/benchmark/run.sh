#!/usr/bin/env bash
# Ikanos CPU/RAM benchmark (issue #737): runs the benchmark capability with fixed limits, drives it
# with k6 at several request rates and payload sizes, samples CPU, memory, heap and GC meanwhile,
# and writes results/<time>/summary.md. See README.md.
#
#   ./run.sh                                   # 1 CPU, 512 MB; 50, 200 and 500 req/s; small and large payloads
#   IKANOS_CPUS=2 IKANOS_MEMORY=1g ./run.sh
#   RATES="100 1000" ENDPOINTS="/ships" DURATION=120s ./run.sh
#   IKANOS_IMAGE=ghcr.io/naftiko/ikanos:1.0.0-beta9 ./run.sh   # a released image instead of this checkout
#   IKANOS_JAVA_OPTIONS=-XX:MaxRAMPercentage=75 ./run.sh       # JVM options
set -euo pipefail
cd "$(dirname "$0")"

export IKANOS_CPUS=${IKANOS_CPUS:-1} IKANOS_MEMORY=${IKANOS_MEMORY:-512m}
RATES=${RATES:-"50 200 500"}
ENDPOINTS=${ENDPOINTS:-"/ships /fleet"}
DURATION=${DURATION:-60s}
WARMUP=${WARMUP:-20s}
# A fresh JVM still compiles during its first minute, and a cold Ikanos can collapse at a rate it holds once
# warm: after each start, it first gets a gentle warm-up at WARMUP_RATE.
FIRST_WARMUP=${FIRST_WARMUP:-60s}
WARMUP_RATE=${WARMUP_RATE:-5}
IDLE=${IDLE:-30}
# After each step, how long to wait at most for /ships to answer in normal time again.
RECOVERY_MAX=${RECOVERY_MAX:-120}

results="results/$(date +%Y%m%d-%H%M%S)-${IKANOS_CPUS}cpu-${IKANOS_MEMORY}${IKANOS_JAVA_OPTIONS:+-tuned}"
mkdir -p "$results"
export RESULTS="$PWD/$results"
export UPSTREAM_DATA="$PWD/$results/upstream"
python3 -I upstream/generate.py "$UPSTREAM_DATA"

if [ -z "${IKANOS_IMAGE:-}" ]; then
  jar=../../../ikanos-cli/target/ikanos.jar
  [ -f "$jar" ] || { echo "Build the jar first: mvn package -DskipTests" >&2; exit 1; }
  docker build -q -t ikanos-benchmark:local -f Dockerfile "$(dirname "$jar")" >/dev/null
fi

cleanup() { docker compose down -v --remove-orphans >/dev/null 2>&1 || true; }
trap cleanup EXIT
docker compose up -d upstream ikanos >/dev/null 2>&1
for _ in $(seq 60); do curl -fs localhost:9090/metrics >/dev/null 2>&1 && break; sleep 1; done
curl -fs localhost:9090/metrics >/dev/null || { echo "Ikanos didn't start:" >&2; docker compose logs ikanos >&2; exit 1; }
container=$(docker compose ps -q ikanos)

# Where the figures come from: Ikanos, k6 and nginx share the Docker VM, so they compete for its CPUs.
{
  echo "date=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "host=$(uname -sm)"
  docker info --format 'docker_vm_cpus={{.NCPU}}
docker_vm_memory_mib={{.MemTotal}}
docker_server={{.ServerVersion}} {{.OperatingSystem}}' | awk -F= '$1 == "docker_vm_memory_mib" {$2 = int($2 / 1048576)} {print $1 "=" $2}'
  echo "ikanos_cpus=$IKANOS_CPUS"
  echo "ikanos_memory=$IKANOS_MEMORY (no swap)"
  echo "image=$(docker inspect "$container" --format '{{.Config.Image}}')"
  echo "java_options=${IKANOS_JAVA_OPTIONS:-none}"
  echo "warmup=$FIRST_WARMUP at $WARMUP_RATE req/s after each start, then $WARMUP at the step rate; measured=$DURATION"
} > "$results/environment.txt"

metrics() { curl -fs -m 2 localhost:9090/metrics || true; }
sum() { awk -v pattern="$1" '$0 ~ pattern {s += $NF} END {printf "%.0f\n", s}'; }
ok_total() { metrics | sum '^ikanos_request_total\\{.*status="200"'; }

# One line per sample: phase, time, CPU (% of one core), container memory, used heap, threads, and the
# heap left after the last garbage collection (what a full heap really looks like).
sample() {
  local stats m
  stats=$(docker stats --no-stream --format '{{.CPUPerc}};{{.MemUsage}}' "$container")
  m=$(metrics)
  printf '%s;%s;%s;%s;%s;%s\n' "$1" "$(date +%s)" "$stats" \
    "$(printf '%s\n' "$m" | sum '^jvm_memory_used\\{.*jvm_memory_type="heap"')" \
    "$(printf '%s\n' "$m" | sum '^jvm_thread_count')" \
    "$(printf '%s\n' "$m" | sum '^jvm_memory_used_after_last_gc\\{.*jvm_memory_type="heap"')" >> "$results/samples.csv"
}
sampling() { while :; do sample "$1"; sleep 2; done; }
gc_seconds() { metrics | awk '/^jvm_gc_duration_sum/ {s += $NF} END {print s + 0}'; }

# Waits until Ikanos completes no more requests (the ones a step left behind are done), at most 300 s.
wait_until_idle() {
  local previous current
  previous=$(ok_total)
  for _ in $(seq 100); do
    sleep 3
    current=$(ok_total)
    [ "$current" = "$previous" ] && return 0
    previous=$current
  done
}

# Seconds until /ships answers in under a second again after a step, or RECOVERY_MAX if it doesn't.
recovery_seconds() {
  local start=$(date +%s)
  while [ $(( $(date +%s) - start )) -lt "$RECOVERY_MAX" ]; do
    if [ "$(curl -s -o /dev/null -m 1 -w '%{http_code}' localhost:8080/ships)" = 200 ]; then
      echo $(( $(date +%s) - start )); return
    fi
    sleep 1
  done
  echo "$RECOVERY_MAX+"
}

echo "Idle for ${IDLE}s"
end=$(( $(date +%s) + IDLE )); while [ "$(date +%s)" -lt "$end" ]; do sample idle; sleep 2; done

warmup=$FIRST_WARMUP
for endpoint in $ENDPOINTS; do
  for rate in $RATES; do
    name="${endpoint#/}-${rate}"
    if [ -n "$warmup" ]; then
      echo "Gentle warm-up: $warmup at $WARMUP_RATE req/s"
      docker compose run --rm -e ENDPOINT="$endpoint" -e RATE="$WARMUP_RATE" -e DURATION="$warmup" \
        k6 run --quiet /scripts/load.js >/dev/null 2>&1 || true
      warmup=
    fi
    echo "$endpoint at $rate req/s: warm-up $WARMUP, then $DURATION"
    docker compose run --rm -e ENDPOINT="$endpoint" -e RATE="$rate" -e DURATION="$WARMUP" \
      k6 run --quiet /scripts/load.js >/dev/null 2>&1 || true
    wait_until_idle
    gc_before=$(gc_seconds); ok_before=$(ok_total)
    sampling "$name" & sampler=$!
    docker compose run --rm -e ENDPOINT="$endpoint" -e RATE="$rate" -e DURATION="$DURATION" \
      k6 run --quiet --summary-export "/results/k6-$name.json" /scripts/load.js >/dev/null 2>&1 || true
    kill "$sampler" 2>/dev/null; wait "$sampler" 2>/dev/null || true
    gc_after=$(gc_seconds); ok_after=$(ok_total)
    recovery=$(recovery_seconds)
    echo "$name;$gc_before;$gc_after;$ok_before;$ok_after;$recovery" >> "$results/steps.csv"
    if [ "${recovery%+}" != "$recovery" ]; then
      # A collapsed Ikanos doesn't come back by itself: restart it, or every later step measures a dead server.
      echo "  no recovery after ${RECOVERY_MAX}s: restarting Ikanos"
      docker compose restart ikanos >/dev/null 2>&1
      for _ in $(seq 60); do curl -fs localhost:9090/metrics >/dev/null 2>&1 && break; sleep 1; done
      warmup=$FIRST_WARMUP
    else
      wait_until_idle
    fi
  done
done

curl -fs localhost:9090/metrics > "$results/metrics-end.txt" || true
docker inspect "$container" --format '{{.Config.Image}}' > "$results/image.txt"
echo "${IKANOS_JAVA_OPTIONS:-}" > "$results/java-options.txt"
{
  echo "oom_killed=$(docker inspect "$container" --format '{{.State.OOMKilled}}')"
  echo "restarted_after_collapse=$(grep -c '+$' "$results/steps.csv" || true)"
  echo "out_of_memory_errors=$(docker logs "$container" 2>&1 | grep -c OutOfMemoryError || true)"
} >> "$results/environment.txt"
DURATION="$DURATION" python3 -I report.py "$results"
echo "Results: $results/summary.md"
