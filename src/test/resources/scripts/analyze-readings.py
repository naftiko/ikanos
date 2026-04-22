# analyze-readings.py — computes statistics from sensor readings
readings = context['fetch-readings']
values = [r['temperature'] for r in readings]
result = {
    'average': sum(values) / len(values),
    'min': min(values),
    'max': max(values),
    'count': len(values)
}
