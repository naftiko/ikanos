"""Turns one run.sh run (samples.csv, steps.csv, k6-*.json) into summary.md: one row per load step."""
import json
import os
import pathlib
import re
import statistics
import sys

results = pathlib.Path(sys.argv[1])
UNITS = {"B": 1, "KiB": 1024, "MiB": 1024**2, "GiB": 1024**3, "kB": 1000, "MB": 1000**2, "GB": 1000**3}
DURATION = float(re.sub(r"s$", "", os.environ.get("DURATION", "60s")))


def mib(text):
    """'123.4MiB' (docker stats) as MiB."""
    number, unit = re.fullmatch(r"([\d.]+)\s*([A-Za-z]+)", text.strip()).groups()
    return float(number) * UNITS[unit] / 1024**2


samples = {}
for line in (results / "samples.csv").read_text().splitlines():
    phase, _, cpu, memory, heap, threads, after_gc = line.split(";")
    samples.setdefault(phase, []).append((float(cpu.rstrip("%")), mib(memory.split("/")[0]),
                                          float(heap) / 1024**2, float(threads), float(after_gc) / 1024**2))
steps = {}
for line in (results / "steps.csv").read_text().splitlines() if (results / "steps.csv").exists() else []:
    phase, gc_before, gc_after, ok_before, ok_after, recovery = line.split(";")
    # A counter read as 0 means the Control Port didn't answer: GC time is never 0 once the JVM runs, and the
    # request count only drops when the reading failed.
    gc = float(gc_after) - float(gc_before) if float(gc_before) and float(gc_after) else None
    ok = (float(ok_after) - float(ok_before)) / DURATION if float(ok_after) >= float(ok_before) and float(ok_after) else None
    steps[phase] = (gc, ok, recovery)


def figures(phase):
    rows = samples.get(phase, [])
    cpu, memory, heap, threads, after_gc = zip(*rows) if rows else ([0], [0], [0], [0], [0])
    return statistics.mean(cpu), max(cpu), max(memory), max(heap), max(after_gc), max(threads)


def seen(value):
    """A JVM figure, or "no answer" when the Control Port never answered during the step."""
    return f"{value:.0f}" if value else "no answer"


environment = (results / "environment.txt").read_text().strip() if (results / "environment.txt").exists() else ""
lines = [
    f"# Ikanos benchmark: {os.environ.get('IKANOS_CPUS', '?')} CPU, {os.environ.get('IKANOS_MEMORY', '?')}",
    "",
    "```",
    environment,
    "```",
    "",
    "- **Asked**: the rate k6 sends (constant arrival rate). **Answered**: requests per second that got a 200, "
    "as k6 sees them (completed requests × share without error). **Ikanos 200/s**: the same, counted by Ikanos "
    "itself (`ikanos_request_total`), including answers to clients that already gave up.",
    "- **Dropped**: requests k6 couldn't even send because all its virtual users were waiting (60 s timeout).",
    "- **p50 / p99**: latency of the answered requests only. **Errors**: share of completed requests that failed.",
    "- **CPU** in % of one core and **memory** of the container (`docker stats`). **Heap**: the JVM's used heap; "
    "**after GC**: what remained after the last garbage collection, which shows a really full heap. "
    "**GC s**: seconds spent in garbage collection during the measured run. **Recovery s**: after the step, "
    "seconds until `/ships` answered within 1 s again.",
    "",
    "| Load | Asked | Answered | Ikanos 200/s | Dropped | p50 ms | p99 ms | Errors | CPU avg / max % "
    "| Memory max MiB | Heap max MiB | Heap after GC max MiB | Threads | GC s | Recovery s |",
    "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|",
]
cpu, cpu_max, mem, heap, after_gc, threads = figures("idle")
lines.append(f"| idle | – | – | – | – | – | – | – | {cpu:.1f} / {cpu_max:.1f} | {mem:.0f} | {heap:.0f} | {after_gc:.0f} "
             f"| {threads:.0f} | – | – |")
for summary in sorted(results.glob("k6-*.json"), key=lambda p: (p.stem.split("-")[1], int(p.stem.split("-")[-1]))):
    phase = summary.stem[3:]
    m = json.loads(summary.read_text())["metrics"]
    answered_latency = m.get("http_req_duration{expected_response:true}", {})
    completed = m.get("http_reqs", {}).get("rate", 0)
    failed = m.get("http_req_failed", {}).get("value", 0)
    dropped = m.get("dropped_iterations", {}).get("count", 0)
    endpoint, rate = phase.rsplit("-", 1)
    cpu, cpu_max, mem, heap, after_gc, threads = figures(phase)
    gc_seconds, server_ok, recovery = steps.get(phase, (None, None, "?"))
    # The Control Port didn't answer at the end of a collapsed step: its counters read as 0.
    gc_text = f"{gc_seconds:.1f}" if gc_seconds is not None and gc_seconds >= 0 else "no answer"
    server_text = f"{server_ok:.1f}" if server_ok is not None and server_ok >= 0 else "no answer"
    answered = completed * (1 - failed)
    p50 = f"{answered_latency['p(50)']:.1f}" if answered_latency and answered else "–"
    p99 = f"{answered_latency['p(99)']:.1f}" if answered_latency and answered else "–"
    lines.append(f"| /{endpoint} | {rate} | {answered:.1f} | {server_text} | {dropped:.0f} | {p50} | {p99} "
                 f"| {failed * 100:.1f}% | {cpu:.0f} / {cpu_max:.0f} | {mem:.0f} | {seen(heap)} | {seen(after_gc)} "
                 f"| {seen(threads)} | {gc_text} | {recovery} |")
(results / "summary.md").write_text("\n".join(lines) + "\n")
print("\n".join(lines))
