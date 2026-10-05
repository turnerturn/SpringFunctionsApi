# sendRabbitMqMessage

POST `/sendRabbitMqMessage` with [the example](../examples/send-rabbitmq-message.json).
The public contract is `Function<JsonNode, JsonNode>`. All fields are validated
before opening a connection. Unknown properties are rejected.

`broker` requires host (localhost or IPv4), username, and passwordEnv, which must
be `RABBITMQ_PASSWORD`. Defaults: port 5672 (5671 with tls=true), virtualHost `/`, tls false.
TLS uses JVM trust and hostname verification. Passwords never belong in JSON.
`destination` is either `{ "queue": "existing-queue" }` (default exchange), or
`{ "exchange": "existing-exchange", "routingKey": "key" }`. Neither queues nor
exchanges are created. The empty exchange is allowed in the latter form.

`message` requires exactly one of data (any JSON, including null), text (UTF-8),
or bodyBase64. Default content types are application/json, text/plain, and
application/octet-stream respectively. Optional headers is a key/value object
containing scalar JSON values, arrays, or nested objects, bounded to 16 KiB.
Optional properties supports contentType, contentEncoding, deliveryMode (1 or 2;
default 2), correlationId, messageId, and expiration (nonnegative millisecond
integer string). AMQP short strings are bounded to 255 UTF-8 bytes.
Body size is at most 1 MiB. confirmTimeoutMs defaults to 5000, range 1–10000.
At most 16 concurrent publishes run. Connect, handshake, and channel RPC operations
have 5-second limits; cleanup has a 1-second connection shutdown limit. The
confirmation timeout is not an end-to-end request deadline.

Every publish uses mandatory routing and publisher confirms on its own channel.
A successful response is `{ "status":"published", "brokerConfirmed":true,
"bytes":42 }`. Bytes reflects the actual encoded body. Confirmation means the
broker accepted the publication; it does not mean a consumer processed it or
that non-durable queues survive restart. Persistence also depends on broker and
queue configuration.

Errors return `{ "status":"error", "code":"..." }`: INVALID_REQUEST, CREDENTIALS_NOT_CONFIGURED, BUSY,
AUTHENTICATION_FAILED, BROKER_ERROR, UNROUTABLE, PUBLISH_REJECTED,
PUBLISH_UNCERTAIN, or INTERRUPTED. A return means no destination was routed.
A timeout or connection loss after publishing begins is PUBLISH_UNCERTAIN:
the message may already exist. There are no automatic retries. Caller retries
can duplicate delivery; use application message identifiers and consumer
idempotency where needed. All owned resources close on every exit.

See [RabbitMQ publisher confirms](https://www.rabbitmq.com/docs/confirms).
Default verification uses mocks and actual HTTP JSON requests. The explicit
rabbitmq-it profile verifies publication against the configured existing broker.
Live TLS and connection loss during publication require additional broker tests.

## Troubleshooting the example

The committed example is validated in an actual HTTP test. A missing or empty
RABBITMQ_PASSWORD in the application process returns CREDENTIALS_NOT_CONFIGURED,
not INVALID_REQUEST. Set the environment before starting the application; exports
in a separate curl terminal do not change an existing Java process.

```sh
export JAVA_HOME=$(/usr/libexec/java_home -F -v 21)
export PATH="$JAVA_HOME/bin:$PATH"
read -r -s 'RABBITMQ_PASSWORD?Broker password: '
printf '\n'
export RABBITMQ_PASSWORD
./mvnw spring-boot:run
```

The password prompt above uses macOS zsh. Set the example's broker.username and
destination.queue to your configured account and existing queue. The environment
variable RABBITMQ_IT_USERNAME is for integration tests; it does not override the
username in this request. Use the native endpoint `/sendRabbitMqMessage`.
An incorrect password produces AUTHENTICATION_FAILED; a valid connection with
no routed queue produces UNROUTABLE. Restart with a rebuilt application to use
the new configuration error code. Malformed input still returns INVALID_REQUEST.
