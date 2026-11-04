# Ikanos CPU/RAM benchmark: first results

A first baseline from [`run.sh`](run.sh), on 2026-11-04, with the jar of `main` plus the JVM metrics of issue
#737 (Java 21, Temurin, Alpine image).

**Where it ran:** Docker Desktop on an Apple Silicon Mac, with k6 on the same machine. Ikanos had fixed CPU and
memory limits, but the host was not dedicated, so treat these figures as an order of magnitude. The issue asks
for a dedicated, resource-fixed machine for the reference figures: the same `run.sh` runs there unchanged.

## Figures

The capability proxies an upstream API and reshapes its JSON (see [README.md](README.md)): **small** is
`/ships` (20 ships, 6 KB from the upstream), **large** is `/fleet` (1,000 ships, 285 KB). Each step is a 20 s
warm-up, then 60 s at a constant rate. CPU is in % of one core.

### 1 CPU, 512 MB (default JVM settings)

| Load | Served req/s | p99 ms | Errors | CPU avg % | Memory MiB | Heap max MiB | Threads |
|---|---|---|---|---|---|---|---|
| idle | – | – | – | 0.2 | 97 | 21 | 32 |
| small, 50 req/s | 50 | 5.0 | 0% | 15 | 145 | 33 | 46 |
| small, 200 req/s | 200 | 3.5 | 0% | 25 | 186 | 61 | 99 |
| small, 500 req/s | 500 | 1.5 | 0% | 30 | 248 | 107 | 198 |
| large, 10 req/s | 10 | 21 | 0% | 21 | 225 | 107 | 78 |
| large, 20 req/s | 20 | 21 | 0% | 35 | 229 | 117 | 78 |
| large, 30 req/s | 30 | 23 | 0% | 50 | 230 | 104 | 77 |
| large, 50 req/s | **3** | 60,000 (timeouts) | **100%** | 100 | 267 | 124 (full) | 245 |

### 2 CPUs, 1 GB (default JVM settings)

| Load | Served req/s | p99 ms | Errors | CPU avg % | Memory MiB | Heap max MiB | Threads |
|---|---|---|---|---|---|---|---|
| idle | – | – | – | 0.8 | 101 | 20 | 32 |
| small, 50 req/s | 50 | 2.6 | 0% | 9 | 149 | 37 | 64 |
| small, 200 req/s | 200 | 1.8 | 0% | 15 | 197 | 77 | 110 |
| small, 500 req/s | 500 | 1.3 | 0% | 27 | 267 | 111 | 161 |
| large, 50 req/s | 50 | 19 | 0% | 88 | 276 | 136 | 200 |
| large, 200 req/s | **12** | 60,000 (timeouts) | **100%** | 150 | 394 | 247 (full) | 241 |

Raising the heap to 75% of the memory (`-XX:MaxRAMPercentage=75`) did not save the large payload at 1 CPU and
50 req/s: memory reached the container's limit and the JVM spent 29 s of the 60 in garbage collection.

### In charts

![CPU for small answers, at 1 CPU and 2 CPUs](images/cpu-small-answers.png)

![Container memory for small answers, split into the Java heap and everything else](images/memory-small-answers.png)

![Large answers at 1 CPU: requests asked and answered, with the collapse at 50 req/s](images/large-answers-collapse.png)

![CPU for large answers at 1 CPU, up to the collapse at 50 req/s](images/cpu-large-answers.png)

## What it shows

1. **Idle, Ikanos takes about 100 MB and no CPU.**
2. **Small answers are cheap.** 500 req/s used about a third of a core, with a p99 under 2 ms, and memory
   grew to about 250 MB.
3. **The cost follows the size of what is reshaped.** A 285 KB answer costs about 17 ms of CPU, so one core
   serves about 30 such requests per second at half its capacity.
4. **Past its capacity, Ikanos collapses instead of slowing down.** Requests that arrive faster than they are
   served pile up (240 threads), each holds its parsed JSON, the heap fills, and garbage collection takes the
   CPU: almost nothing is served and every request times out. There is no limit on concurrent requests to
   make it degrade gracefully, and more heap only delays it.
5. **The JVM sizes itself small in a container.** By default the heap is 25% of the container's memory (128 MB
   of 512 MB), and with fewer than 2 CPUs the JVM uses the Serial garbage collector.

## Sizing recommendations

| Capability profile | CPU request / limit | Memory request / limit |
|---|---|---|
| Small answers (a few KB), up to a few hundred req/s | 250m / 1 | 256Mi / 512Mi |
| Large answers (hundreds of KB) | 1 core per 30 req/s of peak, at least 2 | 512Mi / 1Gi |

- **Set `-XX:MaxRAMPercentage=75`** (through `JAVA_TOOL_OPTIONS`), so the heap can use the memory the container
  is given rather than a quarter of it.
- **Scale out before saturation.** Since Ikanos collapses rather than degrades, autoscale on CPU at about 60%,
  and size for the peak rate, not the average.
- **Give at least 2 CPUs to capabilities with large payloads**, which also gets the JVM's G1 collector.

## Next

- Run the same benchmark on a dedicated, resource-fixed machine for the reference figures.
- A limit on concurrent requests (or load shedding) in the server adapters, so that overload gives fast errors
  for the excess instead of a collapse: worth its own issue.
- More capability profiles: MCP tools, aggregates with several calls, scripting steps.
