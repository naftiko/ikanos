"""Turns one run.sh run (samples.csv, gc.csv, k6-*.json) into summary.md: one row per load step."""
import json
import os
import pathlib
import re
import statistics
import sys

results = pathlib.Path(sys.argv[1])
UNITS = {"B": 1, "KiB": 1024, "MiB": 1024**2, "GiB": 1024**3, "kB": 1000, "MB": 1000**2, "GB": 1000**3}


def mib(text):
    """'123.4MiB' (docker stats) as MiB."""
    number, unit = re.fullmatch(r"([\d.]+)\s*([A-Za-z]+)", text.strip()).groups()
    return float(number) * UNITS[unit] / 1024**2


samples = {}
for line in (results / "samples.csv").read_text().splitlines():
    phase, _, cpu, memory, heap, threads = line.split(";")
    samples.setdefault(phase, []).append((float(cpu.rstrip("%")), mib(memory.split("/")[0]),
                                          float(heap) / 1024**2, float(threads)))
gc = {}
for line in (results / "gc.csv").read_text().splitlines() if (results / "gc.csv").exists() else []:
    phase, before, after = line.split(";")
    gc[phase] = float(after) - float(before)


def figures(phase):
    rows = samples.get(phase, [])
    cpu, memory, heap, threads = zip(*rows) if rows else ([0], [0], [0], [0])
    return (statistics.mean(cpu), max(cpu), statistics.mean(memory), max(memory), max(heap), max(threads))


lines = [
    f"# Ikanos benchmark: {os.environ.get('IKANOS_CPUS', '?')} CPU, {os.environ.get('IKANOS_MEMORY', '?')}",
    "",
    f"Image: `{(results / 'image.txt').read_text().strip() if (results / 'image.txt').exists() else '?'}`, "
    f"JVM options: `{(results / 'java-options.txt').read_text().strip() or 'none' if (results / 'java-options.txt').exists() else 'none'}`. "
    "CPU is in % of one core (docker stats); memory is the container's; heap is the JVM's used heap "
    "(`jvm_memory_used`); GC is the time spent in garbage collection during the measured run.",
    "",
    "| Load | Achieved req/s | p50 ms | p95 ms | p99 ms | Errors | CPU avg / max % | Memory avg / max MiB | Heap max MiB | Threads | GC s |",
    "|---|---|---|---|---|---|---|---|---|---|---|",
]
cpu, cpu_max, mem, mem_max, heap, threads = figures("idle")
lines.append(f"| idle | – | – | – | – | – | {cpu:.1f} / {cpu_max:.1f} | {mem:.0f} / {mem_max:.0f} | {heap:.0f} | {threads:.0f} | – |")
for summary in sorted(results.glob("k6-*.json"), key=lambda p: (p.stem.split("-")[1], int(p.stem.split("-")[-1]))):
    phase = summary.stem[3:]
    m = json.loads(summary.read_text())["metrics"]
    duration = m["http_req_duration"]
    reqs = m.get("http_reqs", {}).get("rate", 0)
    errors = m.get("http_req_failed", {}).get("value", 0) * 100
    dropped = m.get("dropped_iterations", {}).get("count", 0)
    endpoint, rate = phase.rsplit("-", 1)
    cpu, cpu_max, mem, mem_max, heap, threads = figures(phase)
    label = f"/{endpoint} at {rate} req/s" + (f" ({dropped:.0f} dropped)" if dropped else "")
    lines.append(f"| {label} | {reqs:.0f} | {duration['p(50)']:.1f} | {duration['p(95)']:.1f} | {duration['p(99)']:.1f} "
                 f"| {errors:.2f}% | {cpu:.0f} / {cpu_max:.0f} | {mem:.0f} / {mem_max:.0f} | {heap:.0f} | {threads:.0f} "
                 f"| {gc.get(phase, 0):.2f} |")
(results / "summary.md").write_text("\n".join(lines) + "\n")
print("\n".join(lines))
