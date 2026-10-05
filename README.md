# Spring Functions API

Standalone local HTTP API in the HelloWorld repository. Requires Java 21.

Verified stack: Spring Boot 3.5.15, Spring Cloud BOM 2025.0.3,
Spring Cloud Function 4.3.4, Jackson 2.21.4, RabbitMQ Java client 5.25.0,
Maven Wrapper 3.3.4 with Maven 3.9.16. Tested with Microsoft OpenJDK
21.0.8+9-LTS. Dependencies use Boot and Cloud management.

The skeleton was downloaded from Spring Initializr into a temporary directory.
The compatible Boot/Cloud releases were selected using the
[official release announcement](https://spring.io/blog/2026/06/11/spring-cloud-2025-0-3-aka-northfields-has-been-released/).

From the repository root on macOS:

```sh
cd spring-functions-api
export JAVA_HOME=$(/usr/libexec/java_home -F -v 21)
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw verify
```

On another platform select an installed Java 21 JDK before running Maven.
The default build does not require a broker. It tests validation, polling,
settlement, cleanup, and actual HTTP JSON requests and responses.

Set `RABBITMQ_PASSWORD` in your process environment using your secret manager.
Then run:

```sh
./mvnw spring-boot:run
curl -sS http://127.0.0.1:8080/actuator/health
curl -sS -H 'Content-Type: application/json' \
  --data-binary @examples/fetch-immediate.json \
  http://127.0.0.1:8080/fetchRabbitMqQueueMessage
```

Edit the credential-free example's username and existing queue for your broker.
The server binds to 127.0.0.1:8080 and exposes only Actuator health.
It has no authentication or destination authorization. Keep it local until those
controls are explicitly designed. RabbitMQ and TCP functions support IPv4 literals and localhost. Transform API
fetches additionally support explicitly allowlisted DNS hosts with bounded caller
waits and a capped network worker pool.

For an existing broker, including Podman, set `RABBITMQ_PASSWORD` and
`RABBITMQ_IT_USERNAME`. Optional `RABBITMQ_IT_HOST` and `RABBITMQ_IT_PORT`
default to localhost and 5672. Tests do not start containers or change users.

```sh
podman ps
./mvnw -Prabbitmq-it verify
```
```
export RABBITMQ_IT_USERNAME=guest
export RABBITMQ_PASSWORD=secret
export RABBITMQ_IT_HOST=localhost
export RABBITMQ_IT_PORT=5672
```
This explicit profile fails if credentials are missing or the broker is
unavailable. It creates a unique disposable queue and deletes only that queue.
A test account needs permission to create, publish to, fetch from, and delete
its test queue. Production fetching never creates or deletes queues.
TLS live-broker behavior requires a separately configured TLS broker and is
not covered by this integration test.

Verification on October 4, 2026: `./mvnw verify` passed all 16 default tests.
`./mvnw -Prabbitmq-it verify` passed those tests plus the broker integration test
against the existing Podman broker. The test fetched, requeued, acknowledged,
and checked the empty result, then deleted its disposable queue. Live TLS and
connection loss during settlement remain unverified.

See the [function contract](docs/fetch-rabbitmq-queue-message.md),
[request schema](docs/fetch-rabbitmq-queue-message.schema.json), and
[examples](examples/).

## TCP server and conversation: AccuLoad PC example

`tcpServer` starts a local mock TCP listener. `exchangeTcpConversation` connects
as a TCP client and executes send, wait, regex fork, goto, and end steps.
Both use newline-delimited UTF-8. From spring-functions-api, start the HTTP API:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -F -v 21)
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw spring-boot:run
```

In another terminal, change to the same spring-functions-api directory. First
start the TCP server with rules matching the AccuLoad conversation:

```sh
curl -sS -H 'Content-Type: application/json' \
  --data-binary @examples/tcp-server-accuload-pc.json \
  http://127.0.0.1:8080/tcpServer
```

This returns a serverId and listens on 127.0.0.1:9000 for up to 60 seconds.
Then run the conversation before the server expires:

```sh
curl -sS -H 'Content-Type: application/json' \
  --data-binary @examples/tcp-accuload-pc.json \
  http://127.0.0.1:8080/exchangeTcpConversation
```

The example follows section 4 (PC) of your supplied AccuLoad command reference:
assign Recipe 1 to Arm 1, exit program mode with LO, then read the assignment
back with PV. The two-character `13` prefix is your local routing identifier,
not part of the native AccuLoad command. The mock retains it in every response.

| Sent line | Mock response | Next step on exact match |
| --- | --- | --- |
| `13PC 01 001 1` | `13PC 01 001 1 Load Arm 1` | Send LO. |
| `13LO` | `13OK` | Send PV readback. |
| `13PV 01 001` | `13PV 01 001 1 Load Arm 1` | Complete at success. |

The PC and PV command/response contents come from your supplied examples.
The native LO response in your reference is `OK`; `13OK` is an explicit mock
assumption about your local prefix convention. Adjust both the mock rule and
conversation match if your local service returns unprefixed `OK` instead.
Spacing is matched exactly, including `Load Arm 1`.

Each send is followed by wait and fork. A matching reply advances to the next
command; any other line ends at unexpected-reply, and a wait timeout ends at
timed-out. No command is retried. Success returns:

```json
{
  "status": "completed",
  "terminalStep": "success",
  "executedSteps": 10,
  "lastInput": "13PV 01 001 1 Load Arm 1"
}
```

Check terminalStep as well as status: unexpected-reply and timed-out are also
completed scripted outcomes. Network and other runtime failures return
status=error with a code. This mock sends canned replies; it does not change or
persist AccuLoad configuration, verify device program mode or alarms, or model
physical equipment. No real AccuLoad has been contacted. The LF framing and
local prefix are assumptions for the local TCP service, not a verified native
AccuLoad wire protocol. The example targets only the local mock.

To inspect or stop the mock, substitute the serverId returned by the start call:

```sh
TCP_SERVER_ID='REPLACE_WITH_RETURNED_SERVER_ID'
curl -sS -H 'Content-Type: application/json' \
  --data-binary "{\"command\":\"status\",\"serverId\":\"$TCP_SERVER_ID\"}" \
  http://127.0.0.1:8080/tcpServer
curl -sS -H 'Content-Type: application/json' \
  --data-binary "{\"command\":\"shutdown\",\"serverId\":\"$TCP_SERVER_ID\"}" \
  http://127.0.0.1:8080/tcpServer
```

Alternatively, send the line SHUTDOWN over the TCP connection to receive bye
and stop this mock listener. That command controls the mock, not the AccuLoad
or Spring application. HTTP shutdown is available without a TCP client.
If port 9000 is occupied, change the port in both example files, or start with
port 0 and use the returned port in the conversation.

Commands take precedence over mock rules; within each array the first
whole-line regex match wins. This AccuLoad mock has no echo fallback, so an
unmatched input gets no reply. Echo configuration remains demonstrated in
[the general server example](examples/tcp-server-start.json). The original
[PING/pong conversation](examples/tcp-ping-pong.json) is also retained.

See the [conversation contract](docs/exchange-tcp-conversation.md),
[conversation schema](docs/exchange-tcp-conversation.schema.json),
[server contract](docs/tcp-server.md), and [server schema](docs/tcp-server.schema.json)
for timeout defaults, resource limits, echo/shutdown behavior, and error codes.

Regex matching uses RE2/J 1.8, pinned because it is not Spring-managed, as
published in the [official release](https://github.com/google/re2j/releases/tag/re2j-1.8).
The existing Boot, Cloud, Jackson, and RabbitMQ dependency set is unchanged.
Default verification uses disposable loopback TCP servers and needs no external
protocol server or running RabbitMQ broker.

Verified on October 5, 2026 with `./mvnw -Dtest=TcpServerHttpTest test`:
both HTTP tests passed, including the PC/LO/PV example files used together with
a disposable mock listener. The returned outcome was success with the expected
PV readback. This verifies the local examples, not actual AccuLoad behavior.

## RabbitMQ publishing and configured transformations

`sendRabbitMqMessage` publishes to an existing queue or exchange with mandatory
routing and publisher confirms. Set RABBITMQ_PASSWORD from your existing broker's
configured credentials **before starting the API in that same terminal**, and edit
the example username and destination. A missing application password returns
CREDENTIALS_NOT_CONFIGURED; RABBITMQ_IT_USERNAME does not override the JSON username:

```sh
curl -sS -H 'Content-Type: application/json' \
  --data-binary @examples/send-rabbitmq-message.json \
  http://127.0.0.1:8080/sendRabbitMqMessage
```

`transformJsonData` supports map, merge, bind, templates and keyed decoration
using inline references or bounded GET requests returning JSON or XML. Its primary
example enriches a recipe order: fetch DummyJSON recipes, match recipe.name, and
set recipe.ingredients while preserving all other order fields.

Try the offline example first:

```sh
curl -sS -H 'Content-Type: application/json' \
  --data-binary @examples/transform-recipe-order.json \
  http://127.0.0.1:8080/transformJsonData
```

For the real API, allow its origin in the same terminal that starts the app:

```sh
export TRANSFORM_ALLOWED_ORIGINS='https://dummyjson.com'
./mvnw spring-boot:run
```

Then, in another terminal in spring-functions-api:

```sh
curl -sS -H 'Content-Type: application/json' \
  --data-binary @examples/transform-recipe-order-api.json \
  http://127.0.0.1:8080/transformJsonData
```

The example requests all recipes with limit=0 and selects name and ingredients.
Matching names receive the ingredients array; missing matches receive explicit
null. See [the recipe-order walkthrough](docs/transform-recipe-order.md) and
[expected offline response](examples/transform-recipe-order-response.json).
The original product-directory JSON/XML and map/merge/bind examples remain
available for other integrations.

See [publishing](docs/send-rabbitmq-message.md),
[transformation steps and XML normalization](docs/transform-json-data.md),
[schemas](docs/), and [safe INFO/DEBUG/TRACE logging](docs/logging.md).
All functions have class and method documentation and usage examples. Existing
fetching and TCP protocols retain their contracts.

Verification on October 5, 2026 for these additions:
`./mvnw -B -ntp verify javadoc:javadoc -Ddoclint=all,-missing` passed 70 default
tests (no RabbitMQ required), built the application JAR and generated Javadoc.
All 19 schema/example JSON files parse, and `git diff --check` passes.
The explicit `./mvnw -B -ntp -Prabbitmq-it verify` run passed the 70 default
tests, then both fetch and publish broker integration tests failed with
AuthenticationFailure / ACCESS_REFUSED using the user-supplied account. The
broker was reachable but rejected authentication before test queues were created.
Successful live broker verification remains blocked on valid configured credentials.
The existing Podman broker container was started and no accounts were changed.
Live TLS, uncertain publication during connection loss and the real external
product-directory API remain unverified.


---

curl -sS -H 'Content-Type: application/json' \
  --data-binary @examples/accuload-recipe-01-server.json \
  http://127.0.0.1:8080/tcpServer

    curl -sS -H 'Content-Type: application/json' \
  --data-binary @examples/accuload-recipe-01-client.json \
  http://127.0.0.1:8080/exchangeTcpConversation
## Complete Recipe 01 mock conversation

Start the server before the client, from spring-functions-api with the HTTP API
running. Both examples use TCP port 9000:

```sh
curl -sS -H 'Content-Type: application/json' \
  --data-binary @examples/accuload-recipe-01-server.json \
  http://127.0.0.1:8080/tcpServer
curl -sS -H 'Content-Type: application/json' \
  --data-binary @examples/accuload-recipe-01-client.json \
  http://127.0.0.1:8080/exchangeTcpConversation
```

The mock lives for 60 seconds, with a 10-second idle timeout. Run the client
promptly; its total budget is 55 seconds. It sends 21 PC assignments, LO, then
21 PV readbacks. All 43 server replies match the client's success branches.
Success returns terminalStep=success, executedSteps=130, and lastInput=
`13PV AR 230 0 Clean Line Blend Adjust`. Rejection, unexpected-reply and timeout
branches remain explicit. The mock returns canned data and does not persist
recipe writes or verify the native AccuLoad wire protocol.

The function limits now permit 64 mock response rules and 256 conversation
steps, retaining existing timeout and regex budgets. The examples previously
exceeded rule/step counts and timeout limits, despite valid JSON syntax.
Restart the HTTP application after rebuilding to use the updated limits.

Verified October 5, 2026 with `./mvnw -B -ntp verify`: all 71 tests passed,
including both Recipe 01 files exercised together through native HTTP. The
conversation reached success after 130 steps and exchanged all 43 replies.

Recipe-order verification on October 5, 2026: `./mvnw -B -ntp verify` passed
77 tests. The live DummyJSON HTTPS example also succeeded through the built
application: four steps enriched the matching recipe with six ingredients while
preserving order fields. The temporary verification application was stopped.
