# analyze-with-deps.py — uses compute_stats from lib/stats.py
readings = context['fetch-readings']
values = [r['temperature'] for r in readings]
result = compute_stats(values)
