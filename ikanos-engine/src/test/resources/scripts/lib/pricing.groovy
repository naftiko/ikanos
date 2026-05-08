// pricing.groovy — reusable pricing helpers
def applyDiscount(items, rate) {
    items.collect { item ->
        item + [discounted: item.price * (1 - rate)]
    }
}
