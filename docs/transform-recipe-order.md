# Enrich a recipe order with DummyJSON ingredients

The primary transform examples are:

- [Live API request](../examples/transform-recipe-order-api.json).
- [Offline request](../examples/transform-recipe-order.json), using an inline reference.
- [Expected offline response](../examples/transform-recipe-order-response.json).

Input data is one order with a nested recipe:

```json
{
  "orderId": "ORDER-1001",
  "quantity": 2,
  "customer": { "name": "Example Customer" },
  "recipe": {
    "name": "Classic Margherita Pizza",
    "notes": "Bake when collected",
    "ingredients": ["stale ingredient"]
  }
}
```

Four configured steps fetch/bind recipes, temporarily wrap the order in an array,
decorate the matching recipe, then restore the original order shape. No temporary
orders wrapper appears in the final response. The join compares `/recipe/name`
on the order to `/name` in each fetched recipe, and assigns `/ingredients` to
`/recipe/ingredients`. Other order and recipe fields are preserved.

Matching is exact and case-sensitive. A missing name match explicitly replaces
ingredients with null. Duplicate non-null recipe names fail with
AMBIGUOUS_REFERENCE rather than picking one arbitrarily. The fetched ingredients
array replaces existing ingredients, preventing stale ingredients from remaining;
it is not concatenated. Ingredient quantities are not inferred from order.quantity.

[DummyJSON's recipes documentation](https://dummyjson.com/docs/recipes) describes
the recipes wrapper, name and ingredients fields, and the default 30-item page.
The example uses query `limit=0` to request all recipes and `select=name,ingredients`
to keep the response small. Query fields are encoded independently of the path.
The public dataset may change; the offline fixture is deterministic.

From spring-functions-api, select Java 21 and start the application with the
explicitly allowed HTTPS origin:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -F -v 21)
export PATH="$JAVA_HOME/bin:$PATH"
export TRANSFORM_ALLOWED_ORIGINS='https://dummyjson.com'
./mvnw spring-boot:run
```

In another terminal in the application directory:

```sh
curl -sS -H 'Content-Type: application/json' \
  --data-binary @examples/transform-recipe-order-api.json \
  http://127.0.0.1:8080/transformJsonData
```

Or use the offline example, which needs no network or allowed origin:

```sh
curl -sS -H 'Content-Type: application/json' \
  --data-binary @examples/transform-recipe-order.json \
  http://127.0.0.1:8080/transformJsonData
```

Expected status is transformed with executedSteps=4. The matching order retains
its identity, customer, quantity, recipe name and notes, and receives the
configured ingredients array. See the complete response example above.

Restart the application after changing TRANSFORM_ALLOWED_ORIGINS or rebuilding.
The default allowlist remains the local configuration API; callers cannot add
origins through JSON. Multiple operator-approved origins are comma-separated.
DNS hosts are supported for explicitly allowed origins. Verified HTTPS, redirect
blocking, body limits and timeouts remain in effect. The API fetch budget is
10 seconds and the pipeline budget is 15 seconds. A slow DNS or TLS connection
returns REFERENCE_TIMEOUT instead of holding the HTTP invocation indefinitely.
At most 16 reference workers exist. DNS cannot always be interrupted by Java;
workers blocked in DNS remain occupied until the resolver returns, and additional
fetches can return BUSY. No unbounded queue of network work is created.

Existing product-directory JSON/XML examples and map/merge/bind examples are
retained as additional configurations. See [the full step contract](transform-json-data.md).

Verification on October 5, 2026: `./mvnw -B -ntp verify` passed all 77 tests.
The live API example was also posted through a temporary instance of the built
application with the DummyJSON origin allowed. It returned transformed after four
steps with six ingredients and preserved orderId and quantity. The temporary
application was stopped afterward. JSON syntax checks passed for all 24 schema
and example files; `git diff --check` passed.
