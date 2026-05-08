# filter-by-threshold.py — filters items below a stock threshold
items = context['fetch-items']
threshold = int(context['threshold'])
result = [item for item in items if item['stock'] < threshold]
