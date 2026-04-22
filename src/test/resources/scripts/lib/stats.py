# stats.py — reusable statistics helpers
def compute_stats(values):
    return {
        'average': sum(values) / len(values),
        'min': min(values),
        'max': max(values),
        'count': len(values)
    }
