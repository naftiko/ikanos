// enrich-products.groovy — Groovy script for enriching products
def products = context['fetch-products']
result = products.collect { p ->
    [name: p.name, price: p.price, discounted: p.price * 0.9]
}
