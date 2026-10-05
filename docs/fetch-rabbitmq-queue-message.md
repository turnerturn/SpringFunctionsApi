# fetchRabbitMqQueueMessage

Native Spring Cloud Function endpoint: `POST /fetchRabbitMqQueueMessage`
with `Content-Type: application/json`. Public bean contract:
`Function<JsonNode, JsonNode>`. No REST controller duplicates the function.

## Request

The [JSON Schema](fetch-rabbitmq-queue-message.schema.json) describes the
structural validation enforced in Java before connecting. Unknown fields are
rejected. A configured nonempty secret is also required at runtime.

| Field | Meaning |
| --- | --- |
| host | Required localhost or IPv4 literal. localhost maps to 127.0.0.1. |
| port | Integer 1–65535. Default 5672, or 5671 for TLS. |
| virtualHost | Default `/`. |
| username | Required broker username. |
| passwordEnv | Required `RABBITMQ_PASSWORD`, the sole allowed environment reference. Direct passwords and connection URIs are rejected. |
| queue | Required existing queue. |
| settlement | Required `acknowledge` or `requeue`. No implicit destructive default. |
| pollTimeoutMs | Integer 0–30000, default 0. |
| tls | Boolean, default false. JVM trust store and hostname verification are used. For IP destinations the certificate needs an IP subject alternative name. |

Nonempty string fields have a maximum of 255 characters. Provision broker
permissions outside this API. Examples contain no passwords.

## Fetching and settlement

One request owns one connection and one channel. `basic.get` uses manual
acknowledgment. It never declares, modifies, purges, or deletes the queue.
An immediate fetch makes one attempt. Polling repeats empty reads with at most
100 ms between reads until a message arrives or the monotonic deadline expires.
Only empty reads are polled; failures are never retried.

`acknowledge` sends `basic.ack` before returning, removing the message.
`requeue` sends `basic.nack` with requeue=true before returning. The message
can immediately be delivered again, potentially to another consumer; ordering
and later delivery are not guaranteed. Delivery tags are never returned.
Mapping failures occur before settlement, and closing the channel makes an
unsettled message eligible for redelivery.

AMQP acknowledgments have no broker confirmation. A successful HTTP result
means the client settlement call queued the command for writing, not that the broker confirmed it.
Connection loss or HTTP response loss can cause duplicates, or leave a consumed
message unavailable to the caller. There is no exactly-once guarantee across
AMQP and HTTP. Do not automatically retry uncertain settlement.

## Response

Valid JSON invocations return HTTP 200 with a JSON object. Inspect `status`;
errors are not empty results. Malformed JSON or unsupported media types are
handled separately: malformed JSON returns HTTP 400 with INVALID_JSON; unsupported
media types are handled by Spring.

- Message: `status: message`, requested `settlement`, and `message` object.
- Empty: `{"status":"empty"}`.
- Failure: `{"status":"error","code":"BROKER_ERROR"}` or another code below.

Message fields include `bodyBase64` (lossless binary body), `redelivered`,
`exchange`, `routingKey`, `remainingMessageCount` (broker snapshot),
`properties`, and `headers`. Headers are always a key/value object, empty when
absent. AMQP long strings become strings, nested maps/lists stay objects/arrays,
and byte-array headers become `{"base64":"..."}`. Properties include content
type/encoding, correlation/reply/message identifiers, type, app/user IDs,
expiration, delivery mode, priority, and timestamp (epoch milliseconds or null).
The body is not assumed to be UTF-8 or parsed as JSON. See
[message-response.json](../examples/message-response.json).

| Code | Meaning |
| --- | --- |
| INVALID_JSON | HTTP 400: JSON could not bind to the function input. |
| INVALID_REQUEST | Wrong JSON structure, invalid field, or unavailable allowed secret. |
| BUSY | All 16 broker request slots are occupied. |
| AUTHENTICATION_FAILED | Broker rejected authentication. |
| ACCESS_DENIED | AMQP channel closed with 403. |
| QUEUE_NOT_FOUND | AMQP channel closed with 404. |
| BROKER_ERROR | Network, TLS, broker, RPC timeout, resource limit, or other operation failure. |
| SETTLEMENT_UNCERTAIN | Failure during ack/nack; outcome unknown. |
| INTERRUPTED | Poll sleep interrupted; thread interrupt flag restored. |

Error responses never include exception text, requests, or credentials.
The implementation does not log credentials or message bodies.

## Bounds and limitations

The poll deadline starts after connection/channel creation. It bounds waiting
between empty reads, not the entire HTTP request. A read started before that
deadline may finish afterward and return a message. Connection, handshake,
TLS handshake read, channel RPC, and NIO write enqueue timeouts are 5 seconds each; phases are
sequential and can add to total latency. Cleanup aborts the channel and then
connection on every exit; channel cleanup has the client's fixed 10-second close wait and connection
abort uses 1 second. Cleanup failure does not replace a completed settlement
result. Automatic connection and topology recovery are disabled.

At most 16 requests can own broker resources at once. Each connection uses NIO
with a write queue capacity of 16 and a 5-second enqueue timeout. Inbound message bodies
are limited to 1 MiB; oversized bodies produce BROKER_ERROR and are not settled.
Native HTTP parsing is subject to the local server and JVM resources; this
starter is not designed for hostile remote traffic. TLS uses the JVM's verified
SSLContext, never RabbitMQ's permissive no-argument TLS helper. Configure a
trusted CA with JVM trust-store settings if necessary.

The default tests use mocks for AMQP and real HTTP sockets. Broker integration
requires the explicit Maven profile. Live TLS, broker outages during settlement,
and HTTP loss remain unverified unless tested against your infrastructure.
