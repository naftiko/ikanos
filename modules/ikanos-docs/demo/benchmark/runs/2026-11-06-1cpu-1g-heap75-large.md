# Ikanos benchmark: 1 CPU, 1g

```
date=2026-11-06T09:41:43Z
host=Darwin arm64
docker_vm_cpus=15
docker_vm_memory_mib=7935
docker_server=29.6.1 Docker Desktop
ikanos_cpus=1
ikanos_memory=1g (no swap)
image=ikanos-benchmark:local
java_options=-XX:MaxRAMPercentage=75
warmup=60s at 5 req/s after each start, then 20s at the step rate; measured=60s
oom_killed=false
restarted_after_collapse=0
out_of_memory_errors=0
gc_collectors=Copy,MarkSweepCompact
```

- **Asked**: the rate k6 sends (constant arrival rate). **Answered**: requests per second that got a 200, as k6 sees them (completed requests × share without error). **Ikanos 200/s**: the same, counted by Ikanos itself (`ikanos_request_total`), including answers to clients that already gave up.
- **Dropped**: requests k6 couldn't even send because all its virtual users were waiting (60 s timeout).
- **p50 / p99**: latency of the answered requests only. **Errors**: share of completed requests that failed.
- **CPU** in % of one core and **memory** of the container (`docker stats`). **Heap**: the JVM's used heap; **after GC**: what remained after the last garbage collection, which shows a really full heap. **GC s**: seconds spent in garbage collection during the measured run. **Recovery s**: after the step, seconds until `/ships` answered within 1 s again.

| Load | Asked | Answered | Ikanos 200/s | Dropped | p50 ms | p99 ms | Errors | CPU avg / max % | Memory max MiB | Heap max MiB | Heap after GC max MiB | Threads | GC s | Recovery s |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| idle | – | – | – | – | – | – | – | 0.5 / 1.6 | 101 | 21 | 12 | 32 | – | – |
| /fleet | 30 | 30.0 | 30.0 | 0 | 16.5 | 33.8 | 0.0% | 58 / 64 | 156 | 47 | 23 | 44 | 8.7 | 0 |
| /fleet | 40 | 40.0 | 40.0 | 0 | 14.8 | 36.3 | 0.0% | 67 / 82 | 168 | 55 | 28 | 65 | 9.7 | 0 |
