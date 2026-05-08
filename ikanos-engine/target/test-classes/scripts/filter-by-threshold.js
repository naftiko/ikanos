// filter-by-threshold.js — filters items below a stock threshold
var items = context['fetch-items'];
var threshold = context['threshold'];
var low = [];
for (var i = 0; i < items.length; i++) {
  if (items[i].stock < threshold) {
    low.push(items[i]);
  }
}
result = low;
