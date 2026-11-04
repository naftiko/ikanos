# Ikanos benchmark: 1 CPU, 1g

```
date=2026-11-04T16:50:56Z
host=Darwin arm64
docker_vm_cpus=15
docker_vm_memory_mib=7935
docker_server=29.6.1 Docker Desktop
ikanos_cpus=1
ikanos_memory=1g (no swap)
image=ikanos-benchmark:local
java_options=-XX:MaxRAMPercentage=75
warmup=60s first step, 20s others; measured=60s
oom_killed=false
restarted_after_collapse=0
out_of_memory_errors=0
```

- **Asked**: the rate k6 sends (constant arrival rate). **Answered**: requests per second that got a 200, as k6 sees them (completed requests × share without error). **Ikanos 200/s**: the same, counted by Ikanos itself (`ikanos_request_total`), including answers to clients that already gave up.
- **Dropped**: requests k6 couldn't even send because all its virtual users were waiting (60 s timeout).
- **p50 / p99**: latency of the answered requests only. **Errors**: share of completed requests that failed.
- **CPU** in % of one core and **memory** of the container (`docker stats`). **Heap**: the JVM's used heap; **after GC**: what remained after the last garbage collection, which shows a really full heap. **GC s**: seconds spent in garbage collection during the measured run. **Recovery s**: after the step, seconds until `/ships` answered within 1 s again.

| Load | Asked | Answered | Ikanos 200/s | Dropped | p50 ms | p99 ms | Errors | CPU avg / max % | Memory max MiB | Heap max MiB | Heap after GC max MiB | Threads | GC s | Recovery s |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| idle | – | – | – | – | – | – | – | 0.2 / 0.5 | 101 | 21 | 12 | 32 | – | – |
| /fleet | 30 | 30.0 | 30.0 | 0 | 17.3 | 20.3 | 0.0% | 53 / 56 | 849 | 549 | 270 | 174 | 1.0 | 0 |
| /fleet | 40 | 37.6 | 38.9 | 68 | 16.1 | 3762.7 | 0.0% | 71 / 105 | 858 | 566 | 268 | 173 | 3.6 | 0 |
