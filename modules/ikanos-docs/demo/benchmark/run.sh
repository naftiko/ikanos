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
IDLE=${IDLE:-30}

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

# One line per sample: phase, time, CPU (% of one core), container memory, heap used, threads.
sample() {
  local stats metrics
  stats=$(docker stats --no-stream --format '{{.CPUPerc}};{{.MemUsage}}' "$container")
  metrics=$(curl -fs localhost:9090/metrics || true)
  printf '%s;%s;%s;%s;%s\n' "$1" "$(date +%s)" "$stats" \
    "$(printf '%s\n' "$metrics" | awk '/^jvm_memory_used\{.*jvm_memory_type="heap"/ {s += $NF} END {print s + 0}')" \
    "$(printf '%s\n' "$metrics" | awk '/^jvm_thread_count/ {s += $NF} END {print s + 0}')" >> "$results/samples.csv"
}
sampling() { while :; do sample "$1"; sleep 2; done; }
gc_seconds() { curl -fs localhost:9090/metrics | awk '/^jvm_gc_duration_sum/ {s += $NF} END {print s + 0}'; }

echo "Idle for ${IDLE}s"
end=$(( $(date +%s) + IDLE )); while [ "$(date +%s)" -lt "$end" ]; do sample idle; sleep 2; done

for endpoint in $ENDPOINTS; do
  for rate in $RATES; do
    name="${endpoint#/}-${rate}"
    echo "$endpoint at $rate req/s: warm-up $WARMUP, then $DURATION"
    docker compose run --rm -e ENDPOINT="$endpoint" -e RATE="$rate" -e DURATION="$WARMUP" \
      k6 run --quiet /scripts/load.js >/dev/null 2>&1 || true
    gc_before=$(gc_seconds)
    sampling "$name" & sampler=$!
    docker compose run --rm -e ENDPOINT="$endpoint" -e RATE="$rate" -e DURATION="$DURATION" \
      k6 run --quiet --summary-export "/results/k6-$name.json" /scripts/load.js >/dev/null 2>&1 || true
    kill "$sampler" 2>/dev/null; wait "$sampler" 2>/dev/null || true
    echo "$name;$gc_before;$(gc_seconds)" >> "$results/gc.csv"
  done
done

curl -fs localhost:9090/metrics > "$results/metrics-end.txt" || true
docker inspect "$container" --format '{{.Config.Image}}' > "$results/image.txt"
echo "${IKANOS_JAVA_OPTIONS:-}" > "$results/java-options.txt"
python3 -I report.py "$results"
echo "Results: $results/summary.md"
