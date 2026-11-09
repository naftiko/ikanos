# Ikanos CPU/RAM benchmark: first results

A first baseline from [`run.sh`](run.sh), on 2026-11-04 (2 CPUs) and 2026-11-06 (1 CPU) (Java 21, Temurin,
Alpine image). Every table comes from the `run.sh` in this directory.

**Engine measured:** `main` at `ef08f405`, before the limit on requests in progress (#920), plus this PR's commit
"feat: expose the JVM's CPU, memory, GC and thread metrics on the Control Port", which `run.sh` needs for its heap
and GC columns. The runs' `environment` blocks predate the `engine` line that `run.sh` now writes. To measure
that engine again: `git checkout ef08f405`, cherry-pick that commit, build the jar (`mvn package -DskipTests`),
then run `run.sh`. On a later engine, the overload results differ: see finding 5 and #920.

**Where it ran:** Docker Desktop on an Apple Silicon Mac. The Docker VM has 15 CPUs and 7.7 GiB, and k6 and nginx
run in the same VM as Ikanos, so at high rates they compete with it for the VM's CPUs. Ikanos had fixed CPU and
memory limits and **no swap** (`memswap_limit` = `mem_limit`, as in Kubernetes), but the host was not dedicated:
treat these figures as an order of magnitude. The issue asks for a dedicated, resource-fixed machine for the
reference figures: the same `run.sh` runs there unchanged.

## Figures

The capability proxies an upstream API and reshapes its JSON (see [README.md](README.md)): **small** is
`/ships` (20 ships, 6 KB from the upstream), **large** is `/fleet` (1,000 ships, 285 KB). Each step is a 20 s
warm-up at its rate, a 60 s measured run, and a recovery check; the first step of a run, or the first after a
restart, gets a longer warm-up (each run's `environment` block says which). The tables below are the `summary.md` that `run.sh` wrote, unedited, in [`runs/`](runs/); each one
starts with the environment and explains its columns. The main columns:

- **Answered** is the requests per second that got a 200 (k6's completed requests count failures too), and
  **Ikanos 200/s** the same counted by Ikanos itself. **Dropped**: requests k6 couldn't even send.
- **Heap after GC** shows a really full heap, unlike the used heap, which with the Serial collector rises to the
  heap size under any load. **GC s**: seconds of garbage collection in the 60 s.
- **Recovery s**: after the step, how long until `/ships` answers within 1 s again. `120+` means it didn't, and
  `run.sh` restarted Ikanos.

### Small answers, 1 CPU, 512 MB ([run](runs/2026-11-06-1cpu-512m-small.md))

| Load | Asked | Answered | Ikanos 200/s | Dropped | p50 ms | p99 ms | Errors | CPU avg / max % | Memory max MiB | Heap max MiB | Heap after GC max MiB | Threads | GC s | Recovery s |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| idle | – | – | – | – | – | – | – | 0.2 / 0.4 | 98 | 21 | 10 | 32 | – | – |
| /ships | 50 | 50.0 | 50.0 | 0 | 2.6 | 5.3 | 0.0% | 16 / 25 | 145 | 33 | 19 | 53 | 0.5 | 0 |
| /ships | 200 | 200.0 | 200.0 | 0 | 1.3 | 3.6 | 0.0% | 27 / 32 | 181 | 59 | 29 | 105 | 1.4 | 0 |
| /ships | 500 | 500.0 | 500.0 | 0 | 0.7 | 1.7 | 0.0% | 31 / 33 | 240 | 109 | 69 | 195 | 1.2 | 0 |

### Large answers, 1 CPU, 512 MB ([run](runs/2026-11-06-1cpu-512m-large.md), [run from 45 req/s](runs/2026-11-06-1cpu-512m-large-edge.md))

| Load | Asked | Answered | Ikanos 200/s | Dropped | p50 ms | p99 ms | Errors | CPU avg / max % | Memory max MiB | Heap max MiB | Heap after GC max MiB | Threads | GC s | Recovery s |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| idle | – | – | – | – | – | – | – | 0.4 / 1.7 | 99 | 21 | 10 | 32 | – | – |
| /fleet | 10 | 10.0 | 10.0 | 0 | 25.6 | 30.6 | 0.0% | 27 / 34 | 224 | 104 | 49 | 54 | 1.9 | 0 |
| /fleet | 20 | 20.0 | 20.0 | 0 | 19.0 | 21.9 | 0.0% | 39 / 44 | 232 | 107 | 49 | 57 | 2.7 | 0 |
| /fleet | 25 | 25.0 | 25.0 | 0 | 18.2 | 25.7 | 0.0% | 47 / 53 | 234 | 118 | 49 | 64 | 3.4 | 0 |
| /fleet | 30 | 30.0 | 30.0 | 0 | 17.1 | 18.7 | 0.0% | 51 / 53 | 233 | 67 | 24 | 73 | 3.5 | 0 |
| /fleet | 35 | 35.0 | 35.0 | 0 | 17.4 | 19.0 | 0.0% | 61 / 64 | 234 | 105 | 24 | 78 | 3.9 | 0 |
| /fleet | 40 | 40.0 | 40.0 | 0 | 16.6 | 18.6 | 0.0% | 64 / 66 | 236 | 116 | 24 | 80 | 4.3 | 0 |
| /fleet | 45 | 13.9 | no answer | 1228 | 15.6 | 143.5 | 10.3% | 88 / 104 | 259 | 107 | 44 | 58 | no answer | 120+ |
| /fleet | 50 | 0.0 | no answer | 2652 | – | – | 100.0% | 100 / 108 | 265 | no answer | no answer | no answer | no answer | 120+ |
| /fleet | 60 | 0.0 | no answer | 3288 | – | – | 100.0% | 100 / 104 | 263 | no answer | no answer | no answer | no answer | 120+ |

The rows from 45 req/s come from a second run of the same script. After each collapse `run.sh` restarted Ikanos
and gave it the gentle warm-up again, so 50 and 60 started from a warm JVM too. "no answer": the Control Port
didn't answer during the collapse either.

### Large answers, 2 CPUs, 1 GB ([run](runs/2026-11-04-2cpu-1g-large.md))

| Load | Asked | Answered | Ikanos 200/s | Dropped | p50 ms | p99 ms | Errors | CPU avg / max % | Memory max MiB | Heap max MiB | Heap after GC max MiB | Threads | GC s | Recovery s |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| idle | – | – | – | – | – | – | – | 0.2 / 0.4 | 100 | 21 | 12 | 32 | – | – |
| /fleet | 40 | 40.0 | 40.0 | 0 | 16.6 | 36.2 | 0.0% | 76 / 82 | 156 | 51 | 24 | 48 | 10.6 | 0 |
| /fleet | 50 | 50.0 | 50.0 | 0 | 16.0 | 36.5 | 0.0% | 94 / 111 | 163 | 48 | 25 | 69 | 12.4 | 0 |
| /fleet | 60 | 60.0 | 60.0 | 0 | 18.5 | 37.1 | 0.0% | 113 / 128 | 168 | 51 | 26 | 87 | 13.9 | 0 |
| /fleet | 75 | 75.0 | 75.0 | 0 | 25.6 | 44.1 | 0.0% | 167 / 171 | 176 | 59 | 30 | 102 | 18.4 | 0 |

The highest step tried, 75 req/s, held: the edge at 2 CPUs is above it.

### Large answers with a bigger heap, 1 CPU, 1 GB, `-XX:MaxRAMPercentage=75` ([run](runs/2026-11-06-1cpu-1g-heap75-large.md))

| Load | Asked | Answered | Ikanos 200/s | Dropped | p50 ms | p99 ms | Errors | CPU avg / max % | Memory max MiB | Heap max MiB | Heap after GC max MiB | Threads | GC s | Recovery s |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| idle | – | – | – | – | – | – | – | 0.5 / 1.6 | 101 | 21 | 12 | 32 | – | – |
| /fleet | 30 | 30.0 | 30.0 | 0 | 16.5 | 33.8 | 0.0% | 58 / 64 | 156 | 47 | 23 | 44 | 8.7 | 0 |
| /fleet | 40 | 40.0 | 40.0 | 0 | 14.8 | 36.3 | 0.0% | 67 / 82 | 168 | 55 | 28 | 65 | 9.7 | 0 |

### In charts

![CPU for small answers, at 1 CPU](images/cpu-small-answers.png)

![Container memory for small answers, split into the Java heap and everything else](images/memory-small-answers.png)

![Large answers at 1 CPU: requests asked and answered, with the collapse at 45 req/s](images/large-answers-collapse.png)

![CPU for large answers at 1 CPU, up to the collapse at 45 req/s](images/cpu-large-answers.png)

## What it shows

1. **Idle, Ikanos takes about 100 MB and no CPU.**
2. **Small answers are cheap.** 500 req/s used under a third of a core, with a p99 under 2 ms. Memory grows
   with the request rate, to about 250 MB, mostly outside the heap (threads, compiled code).
3. **A large answer costs about 17 ms of CPU** (51% of a core at 30 req/s). Most of it is avoidable: for each
   field it reads, the output mapping builds a new JSON mapper and JSONPath configuration
   (`Converter.jsonPathFind`), about 4,000 times per `/fleet` answer. Building them once gave the same answer for
   6.3 ms of CPU instead of 22.5, and a sixth of the garbage collection, in a quick test (#919).
4. **The collapse is not CPU running out.** At 1 CPU every request was answered up to 40 req/s; at 45, 45 × 17 ms
   is 77% of a core, yet Ikanos answered 13.9 req/s, and at 50 none. Nothing limits how many requests it works on
   at once (Jetty takes up to 200, with an unbounded queue behind), and the more it works on at once, the less it
   gets done: each large request in progress holds about 2 MB of parsed JSON, and the heap fills: heap after GC
   24 → 44 MiB before the Control Port stopped answering, and 1,687 log lines with an OutOfMemoryError in that
   run. In separate tests GC reached 79–99% of the time. The edge is unstable: in separate tests, 25 req/s held
   for two minutes, then flipped after one short GC pause. And a cold JVM collapses at a rate it holds once warm:
   the first 1-CPU runs, warmed up directly at the step's rate, collapsed at 35 req/s, where `run.sh`'s gentle
   warm-up held 40; a 2-CPU run warmed up that way collapsed at 40 too.
5. **It doesn't recover by itself.** After a collapse, Ikanos still answered nothing 120 s after the load
   stopped (and 20 minutes in a separate test). The leftover work alone doesn't explain it (at 50 req/s k6 has at
   most 200 requests in progress, and 200 × 17 ms is 3.4 s of CPU). Two minutes after the load, a thread dump showed 51 requests still being mapped for
   clients that had left, the heap after GC stayed at about 120 of 128 MiB with no load, and full GCs kept
   running: the requests in progress hold the memory they need to finish, so Ikanos completed about one a
   minute (#944). With `main`'s engine of 2026-11-06, which has the limit on requests in progress from #920,
   the same run recovered at once.
   The Control Port isn't a reliable signal of it: in separate tests `/health/live` sometimes still answered 200
   (after up to 13 s) while the API answered nothing, and sometimes timed out too.
6. **A bigger heap isn't needed below the edge.** With 75% of 1 GB, 30 and 40 req/s held as with the default heap,
   and the heap stayed small (heap after GC at most 28 MiB, container 168 MiB): the JVM only grows it under
   pressure. In separate tests with more heap sizes, the collapse moved but always came. At 512 MB, 75% would
   leave 128 MiB for everything outside the heap, which already takes about 130–140 MiB under load: Kubernetes
   would kill the container once the heap grows.
7. **Two CPUs nearly double the capacity.** At 2 CPUs and 1 GB, every step up to 75 req/s held (167% CPU, about
   22 ms per answer, heap after GC at most 30 MiB), where 1 CPU held 40 and collapsed at 45: at least 1.7 times
   as much, with the same script and warm-up. The second CPU leaves room for garbage collection and for the
   requests waiting their turn. In a test that shared the VM with other load, 2 CPUs collapsed at 60 req/s, so
   this edge too depends on conditions.
8. **The JVM sizes itself small in a container.** By default the heap is 25% of the container's memory, and with
   fewer than 2 CPUs or less than about 1.8 GB the JVM uses the Serial garbage collector. So the 2-CPU, 1 GB run
   used Serial too (`Copy` and `MarkSweepCompact`, checked with `-XX:+PrintFlagsFinal`): its GC seconds are
   pauses, like at 1 CPU. G1 starts at 2 CPUs and 2 GB; `run.sh` then leaves out its `G1 Concurrent GC`
   collector, which runs next to the application, and `environment` lists the collectors of each run.

## Sizing recommendations

These hold until Ikanos limits the requests it works on at once; see Next.

| Capability profile | CPU request / limit | Memory request / limit |
|---|---|---|
| Small answers (a few KB), up to a few hundred req/s | 250m / 1 | 256Mi / 512Mi |
| Large answers (hundreds of KB) | at least 2 CPUs; keep the peak under about 40 req/s per pod | 512Mi / 1Gi |

- **What breaks is concurrency, not CPU.** Size for the peak, with a wide margin under the edge measured above:
  a burst or a GC pause past it takes the pod down for good, CPU-based autoscaling can't react in time, and the
  pod doesn't come back by itself. For large answers at 2 CPUs the measured edge is 60 req/s (in a test that
  shared the VM) to more than 75 (alone); 40 req/s is a third below the lower figure.
- **Enforce the ceiling in front of the pods.** The engine measured here has no limit on requests in progress;
  from engine 1.0.0-beta9, `maxRequestsInProgress` adds one (#920). Before that version, a rate limit at the
  gateway or ingress is what keeps a pod under its ceiling.
- **Raise the heap only with the memory to match.** `-XX:MaxRAMPercentage=75` fits a 1Gi limit (768 MiB of heap
  plus about 140 MiB outside it), not 512Mi. At 512Mi, keep the default.
- **Probe the API, not only `/health`, but never the upstream.** `/health` doesn't reliably see the collapse. A
  liveness probe that calls a cheap operation of the capability restarts a collapsed pod, but only if that
  operation doesn't call an upstream API: otherwise the day the upstream goes down, the probe fails on every pod
  and Kubernetes restarts them all in a loop, though Ikanos is fine. A liveness probe must depend on nothing
  outside the process; a readiness probe may, since failing it only takes the pod out of the load balancer. This
  is a workaround until the engine handles overload itself (#920, and #944).

## Next

- Run the same benchmark on a dedicated, resource-fixed machine for the reference figures.
- Separate issues, found while measuring:
  - #920: a limit on requests in progress (with a fast 503 for the excess), so overload degrades instead of
    collapsing; in the engine from 1.0.0-beta9;
  - #919: building the JSON mapper and JSONPath configuration once in `Converter.jsonPathFind`;
  - #944: after an overload, Ikanos stays unresponsive for tens of minutes (finding 5);
  - #897: a REST request with `Connection: close` (sent by HTTPie Desktop, for instance) hangs instead of being
    answered, and any exception that escapes a REST operation leaves its client waiting forever. Both fixes
    are in its scope.
- More capability profiles: MCP tools, aggregates with several calls, scripting steps.
