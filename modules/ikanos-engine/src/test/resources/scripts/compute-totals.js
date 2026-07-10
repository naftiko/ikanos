// compute-totals.js — computes total amount and order count
var orders = context['get-orders'];
var total = 0;
var count = 0;
for (var i = 0; i < orders.length; i++) {
  total += orders[i].amount;
  count++;
}
result = { totalAmount: total, orderCount: count };
