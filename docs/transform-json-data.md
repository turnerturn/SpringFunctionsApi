# transformJsonData

POST `/transformJsonData`. Input is `{ "data": ..., "steps": [...],
"totalTimeoutMs":10000 }`. This is a configuration-driven JSON pipeline with
AWS-inspired typed bindings, not an AWS syntax compatibility implementation.
No scripts or executable expressions run. The original input is preserved.
Success is `{ "status":"transformed", "executedSteps":2, "data": ... }`.
Failure returns status=error, a fixed code, and a zero-based step when applicable;
partial transformed data is never returned. Unknown properties are rejected.

All paths are RFC 6901 JSON pointers. Empty path means the root. Missing reads
produce JSON null. Set operations create missing object parents; existing array
indices are required. Arrays cannot be appended with `-`. Steps execute in order,
and all configuration and API URLs are validated before any request is sent.
Named references must exist earlier in the pipeline. They are immutable.

| Step type | Configuration and behavior |
| --- | --- |
| reference | id, value: bind inline JSON under a name. |
| fetch | id, baseUrl, path, optional parameters, query, format, timeoutMs: bind an API response. |
| set | path, value: assign a literal JSON value. |
| copy | from, path: copy within current data. |
| remove | path: remove a field or existing array entry. |
| bind | reference, from, path: copy from a named reference. |
| merge | path, from, optional reference: deep merge objects; incoming scalars, arrays and null replace existing values. |
| template | bindings, template: replace the current data with a typed template. |
| map | items, bindings, template: replace each item of a selected array. |
| decorate | items, reference, referenceItems, itemKey, referenceKey, assign, optional indexBase: join and decorate objects. |

Bindings map variable names to pointers relative to current data (or each map
item), or to `{ "reference":"config", "path":"/site" }`. An entire template
string `<variable>` is replaced with that JSON value, preserving its type.
Embedded interpolation and field-name substitution are not supported.
See [map, merge and bind](../examples/transform-map-merge-bind.json).

For recipe-order enrichment, start with [the DummyJSON walkthrough](transform-recipe-order.md).
It fetches recipes, matches recipe.name, and decorates recipe.ingredients while
preserving the order. Offline and live examples share the same lookup steps.

For product enrichment, start with [the inline directory example](../examples/transform-products.json).
It matches each product.productCode to the directory's productCode and assigns
acculoadProductDirectoryPosition from position. A missing match or missing source
field explicitly overwrites the result with null. Duplicate non-null directory
keys fail with AMBIGUOUS_REFERENCE. Keys use exact JSON scalar equality, so
numeric and textual codes differ. Null directory keys are ignored.
Assign maps target pointers to source pointers, `$index` (zero-based, or indexBase=1),
or `{ "from":"/position", "type":"integer" }`. Supported casts are value,
string, integer, number and boolean. Failed casts produce DATA_SHAPE_ERROR.

## External JSON and XML APIs

[The API example](../examples/transform-products-api.json) fetches
`/api/acculoads/{ip}/productDirectory`. parameters maps ip to `/ip` in the original
request data; scalar values are encoded as path segments. baseUrl is an origin,
without credentials, query, fragment or path. Paths cannot include inline query strings. Optional query is an object of up to
16 named literal scalar values (no null); values are URL encoded. Keys use
letters, digits, underscores and hyphens, up to 64 characters; values are bounded
to 1024 characters.
Only GET is supported. No redirects, proxies, automatic retries or configurable
authentication headers are used. localhost, IPv4 and explicitly allowlisted DNS hosts are supported; HTTPS
uses normal JVM certificate validation. URLs and response content are never logged.

Before starting the app, allow the required origins in operator configuration:

```sh
export TRANSFORM_ALLOWED_ORIGINS='http://127.0.0.1:8090'
./mvnw spring-boot:run
```

The default is that single local origin. Multiple origins use commas. Caller JSON
cannot override the allowlist. Production authentication and destination policy
remain outside this local starter. Adjust pointers to your real API response;
no supplied or live product-directory contract has been verified.

format defaults to json. JSON rejects trailing non-JSON content. For format=xml,
DTD, external entities, external schema access and XInclude are disabled. XML is
normalized with a root wrapper: text leaves become strings, attributes use `@name`,
repeated child names become arrays, singleton children remain single values,
and mixed text uses `#text`. Whitespace of text leaves is preserved. Namespace
prefixes remain part of names. A singleton directory object is accepted for joins.
For example:

```xml
<productDirectory><product><productCode>P001</productCode><position>3</position></product></productDirectory>
```

becomes `{"productDirectory":{"product":{"productCode":"P001","position":"3"}}}`.
Use [the XML API example](../examples/transform-products-xml-api.json), which
selects `/productDirectory/product` and explicitly casts position to integer.

Limits: 32 steps, 8 API fetches, 4096 entries per map/join array, 16 concurrent
pipelines, 1 MiB request/response/output, depth 64 and 20000 JSON nodes.
Template expansion additionally uses conservative escaped-byte accounting and
can reject a value before reaching the serialized 1 MiB boundary. totalTimeoutMs
is 1–30000 (default 10000); each fetch timeoutMs is 1–10000 (default 3000).
Connect/read waits and the caller wait for DNS/network work are bounded by the
remaining deadline. Network work uses a fixed pool of 16 workers without a queue;
DNS may ignore cancellation, leaving a worker occupied until it returns. Pool
exhaustion returns BUSY. Computation checks the
deadline between operations; these are cooperative limits, not hard real-time guarantees.

Codes: INVALID_REQUEST, BUSY, DATA_SHAPE_ERROR, DATA_LIMIT,
AMBIGUOUS_REFERENCE, REFERENCE_HTTP_ERROR, REFERENCE_PARSE_ERROR,
REFERENCE_NETWORK_ERROR, REFERENCE_TIMEOUT, REFERENCE_SIZE_LIMIT,
TOTAL_TIMEOUT, INTERRUPTED, TRANSFORM_ERROR. Reference errors distinguish external
service failures from absent lookup matches. API tests use disposable local HTTP
servers; no real external configuration API has been contacted.
