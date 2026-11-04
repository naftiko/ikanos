# Ikanos CPU/RAM benchmark

How much CPU and memory does Ikanos need? This folder measures it (issue #737), so that a deployment can set
its container or Kubernetes requests and limits from figures rather than guesses.

## What it measures

- **The capability** ([`capability.yml`](capability.yml)): a REST adapter that calls an upstream API and
  reshapes its answer, the most common shape of a capability. `/ships` returns 20 ships (about 6 KB from the
  upstream), `/fleet` 1,000 (about 285 KB), and `/ships/{imo}` one.
- **The upstream** is static JSON served by nginx in the same compose project, so it never limits the run.
- **Ikanos** runs in Docker with fixed limits (`IKANOS_CPUS`, `IKANOS_MEMORY`), from the jar of this checkout,
  or from a released image with `IKANOS_IMAGE`.
- **The load** is [k6](https://k6.io) at a constant request rate (an open model, like real clients): a warm-up,
  then a measured run, for each rate and endpoint.
- **During each run**, every 2 seconds: the container's CPU and memory (`docker stats`), and from the Control
  Port's `/metrics`, the JVM's used heap and thread count. GC time is the change in `jvm_gc_duration_sum`.

## Run it

Needs Docker and Python 3.

```bash
mvn package -DskipTests                       # from the repository root: builds the jar the image uses
cd modules/ikanos-docs/demo/benchmark
./run.sh                                      # 1 CPU, 512 MB; 50, 200 and 500 req/s; /ships and /fleet
IKANOS_CPUS=2 IKANOS_MEMORY=1g ./run.sh       # another size
RATES="100 1000" ENDPOINTS="/ships" DURATION=120s ./run.sh
```

Each run writes `results/<time>-<cpus>cpu-<memory>/summary.md` (one row per load step), with the raw samples
and k6's summaries next to it. To watch a run live, start the [observability demo](../observability) as well:
its Prometheus scrapes the Control Port on `localhost:9090`, and its Grafana dashboard has a **JVM Resources**
row (CPU, heap, non-heap, GC, threads).

## Results

See [RESULTS.md](RESULTS.md) for the figures and the sizing they lead to.
