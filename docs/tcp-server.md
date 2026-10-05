# tcpServer

Native Spring Cloud Function endpoint: `POST /tcpServer`, JSON input and output
(`Function<JsonNode, JsonNode>`). Provides a short-lived, local TCP mock server.
It uses the same newline-delimited UTF-8 wire format as exchangeTcpConversation.

## Start, inspect, and stop

Start with `{"command":"start"}` plus optional configuration below. Start
binds synchronously, starts a background worker, and returns immediately with
status=running, a generated serverId, host=127.0.0.1, and the actual port.
Port 0 selects an available ephemeral port. Binding failures return an error.
Very short lifetimes may expire before the start response is received.

Use `{"command":"status","serverId":"RETURNED_ID"}` for metrics, or
`{"command":"shutdown","serverId":"RETURNED_ID"}` to close the listener
and any active client. Shutdown is idempotent for a retained server ID.
It stops only that mock server, never the Spring application, broker, or another
process. Configuration is immutable after start; shut down and start again to
change rules. Unknown fields are rejected before binding.

| Start field | Default | Range or behavior |
| --- | --- | --- |
| port | 0 | 0–65535; 0 means choose an available port. |
| timeoutMs | 30000 | 1–60000; overall monotonic server lifetime, starting before bind. Cannot be extended by traffic. |
| idleTimeoutMs | 1000 | 1–10000; maximum time to assemble each complete input line or write a response, bounded by remaining lifetime. Partial traffic does not extend it. |
| maxConnections | 16 | 1–64; total accepted connections over the server's lifetime. |
| maxMessages | 256 | 1–1024; total complete input lines across all clients, including unmatched lines. |
| commands | [] | Up to 32 regex command definitions. |
| rules | [] | Up to 64 regex mock response definitions. |

The bind address is fixed to IPv4 loopback, 127.0.0.1. No external interface,
TLS, authentication, or remote deployment controls are implemented.

## Commands and rules

Commands are checked first, then rules, in array order. First whole-line match
wins. The LF delimiter and optional preceding CR are removed before matching.
Regex syntax is RE2/J, including inline flags such as `(?i)`. Backreferences and
lookaround are unsupported. Each regex has at most 256 characters and 4096
compiled instructions; all regexes together have at most 8192 characters and
65536 instructions. Patterns are compiled and validated before binding.

```json
{
  "command": "start",
  "port": 9000,
  "timeoutMs": 30000,
  "commands": [
    {"regex": "(?i)^SHUTDOWN$", "action": "shutdown", "response": "bye"},
    {"regex": "^ECHO.*$", "action": "echo"}
  ],
  "rules": [
    {"regex": "(?i)^PING$", "response": "pong"},
    {"regex": "^HELLO$", "response": "world"}
  ]
}
```

- echo replies with the entire matching input line, without prefix removal or
  capture substitution. It cannot specify response.
- shutdown optionally replies with its literal response, then closes the entire
  mock server even if writing that reply fails. Without response, it closes without a reply.
- A mock rule sends its literal response. No capture interpolation occurs.
- Unmatched input produces no response and the connection remains open until
  another line, idle timeout, limit, or shutdown.

All responses append LF. Empty response sends an empty line. CR/LF within a
configured response is rejected. Multiple messages in a TCP packet and
fragmented/multibyte UTF-8 lines are handled independently of packet boundaries.

## Responses

Status snapshots contain status (running or stopped), serverId, host, port,
connections, messages, responses, and clientErrors. A stopped server also has
reason; lastError appears if a client error has occurred. Counters are snapshots
and may advance immediately after a response. No input, output, regexes, or full
transcripts are included in status or logged by the implementation.

Stop reasons: MANUAL_SHUTDOWN, COMMAND_SHUTDOWN, TOTAL_TIMEOUT,
CONNECTION_LIMIT, MESSAGE_LIMIT, BYTE_LIMIT, APPLICATION_SHUTDOWN, or TCP_ERROR.
The first stop reason is retained. Shutdown on application teardown closes all
mock listeners and clients.

Validation/control errors are JSON objects with status=error and code:
INVALID_REQUEST, BUSY, PORT_UNAVAILABLE, TCP_ERROR, SERVER_NOT_FOUND, or
SERVER_MANAGER_CLOSED. Valid invocations return HTTP 200. Malformed HTTP JSON
uses the existing safe binding advice (HTTP 400/INVALID_JSON). No exception text
or protocol bodies are included in error responses.

## Bounds and cleanup

At most 16 servers run at once. Each has one bounded background worker and
serves one active client at a time, with listen backlog 1. Other clients may be
queued by the operating system; their read deadlines begin when accepted.
The worker pool has 16 threads and at most 16 queued tasks; saturation returns
BUSY. At most 64 server records are retained. Older stopped IDs may be evicted
and subsequently return SERVER_NOT_FOUND. Definitions and IDs are process-local
and disappear when the application restarts.

Each input line is limited to 8192 bytes before LF, including optional CR;
each configured response has at most 8192 UTF-8 bytes before appended LF.
Received and sent bytes are independently limited to 65536 over the server's
entire lifetime. Received accounting includes buffered bytes beyond the current
line. Hitting a byte, message, connection, or lifetime limit closes the server.

Invalid UTF-8, oversized frames, partial-line EOF, idle timeout, or client
network errors close that client and allow a subsequent connection while the
server is within its global limits. They increment clientErrors and set lastError
(INVALID_UTF8, FRAME_LIMIT, INCOMPLETE_FRAME, IDLE_TIMEOUT, or TCP_ERROR).
Clean EOF simply closes that session. No automatic retries occur. Successful
writes are accepted locally, not confirmed as processed by the remote peer.
Socket and selector resources are closed on shutdown and application teardown.

## Example with the conversation client

From spring-functions-api, after selecting Java 21:

```sh
./mvnw spring-boot:run
```

In another terminal, start the mock listener on port 9000:

```sh
curl -sS -H 'Content-Type: application/json' \
  --data-binary @examples/tcp-server-start.json \
  http://127.0.0.1:8080/tcpServer
curl -sS -H 'Content-Type: application/json' \
  --data-binary @examples/tcp-ping-pong.json \
  http://127.0.0.1:8080/exchangeTcpConversation
```

Run the conversation within the server's 30-second lifetime. Substitute the
returned serverId in examples/tcp-server-status.json and
examples/tcp-server-shutdown.json, then POST either file to /tcpServer.
Use port 0 and the returned port if 9000 is occupied.

Default `./mvnw verify` uses disposable local listeners and tests mock rules,
echo, shutdown, expiry, malformed frames, bounds, cleanup, and actual HTTP
interaction between tcpServer and exchangeTcpConversation. No external server
or running RabbitMQ broker is required. External protocol interoperability and
remote network behavior remain unverified.
