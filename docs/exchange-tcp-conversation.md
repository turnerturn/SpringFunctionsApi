# exchangeTcpConversation

`POST /exchangeTcpConversation` with `Content-Type: application/json`.
The named Spring Cloud Function bean has the contract `Function<JsonNode, JsonNode>`.
Each call opens a fresh TCP client connection, runs the steps, then closes it.

## Wire format

Plain TCP, UTF-8, one line per message. `send` appends LF (`\n`); its text
must not contain CR or LF. `wait` reads through the next LF, excluding that
terminator and an optional preceding CR from the returned text. Empty lines are
valid. TCP fragmentation and multiple lines in one packet are handled correctly.
Invalid UTF-8, an oversized line, and EOF before a line terminator are errors.
No TLS or listener mode is implemented. Keep the HTTP API local as configured;
remote authentication and destination controls have not been designed.

## Request

See [the schema](exchange-tcp-conversation.schema.json) and
[the runnable request example](../examples/tcp-ping-pong.json).

| Field | Meaning |
| --- | --- |
| host | Required IPv4 literal or localhost (maps to 127.0.0.1). Avoids unbounded DNS resolution. |
| port | Required integer 1–65535. |
| steps | Required ordered array, 1–256 steps. |
| start | Optional initial step ID. Defaults to the first step. |
| connectTimeoutMs | 1–10000; default 3000. |
| totalTimeoutMs | 1–60000; default 10000. Overall monotonic deadline including connection, reads, writes, and step execution. |
| maxExecutions | 1–1024; default 256. Counts every executed step, including goto and end. |

Step IDs must be unique and contain 1–64 ASCII letters, digits, underscores,
or hyphens. All targets are validated before connecting. A target is the literal
ID, for example `"goto":"success"`; the `<<id>>` notation is not part of the JSON.
Unknown fields, invalid actions, missing targets, and invalid regexes are rejected.
Cycles are allowed and bounded by time and execution count.

## Actions

Steps normally run in array order. Reaching the end of the array completes the
conversation. `end` explicitly completes it at a named terminal step.

| action | Additional fields | Behavior |
| --- | --- | --- |
| send | text; optional timeoutMs | Send UTF-8 text plus LF. Empty text sends an empty line. |
| wait | optional timeoutMs, onTimeout | Read one line and replace lastInput. This waits for a reply, not a fixed delay. |
| fork | when, otherwise | Match the most recent successful wait and jump to a target. |
| goto | goto | Unconditionally jump to that ID. |
| end | none | Complete with this ID as terminalStep. |

`send` and `wait` timeoutMs default to 1000, range 1–60000, capped by the
remaining overall deadline. `wait.onTimeout` jumps only on a step read timeout;
it cannot bypass the overall deadline. A timed-out wait clears lastInput, so a
fork cannot accidentally match an earlier reply. Partial line bytes remain
buffered; another wait continues that same line. Sending after a timeout can
leave protocol state uncertain: the caller must define that transition deliberately.
A timeout without onTimeout returns STEP_TIMEOUT.

`fork.when` is an ordered array of `{"regex":"^pong$","goto":"success"}`
objects. The first whole-line match wins, otherwise the required `otherwise`
target is used. `fork` does not read the socket or create threads, and does not
wait for several possible replies: place `wait` before it. Fork before any
successful wait returns NO_INPUT. Patterns are RE2/J syntax, with inline flags
such as `(?i)` supported; lookaround and backreferences are unsupported.
There are at most 16 branches per fork, 128 patterns per conversation, 256
characters per pattern, 8192 regex characters total, and compiled program
limits of 4096 instructions per pattern and 65536 total. RE2/J 1.8 provides
linear-time matching, avoiding backtracking regex stalls. It is the only added
library; the existing Spring dependency versions are unchanged.

For PING/pong, the example sends PING, waits for a reply, and routes to success
when the whole reply is pong (ignoring case), or to unexpected otherwise.
Unexpected replies trigger a defined send followed by goto failed. A missing
reply routes to timed-out. These are application-defined outcomes, all reported
as completed when their terminal step is reached.

## Responses and errors

Valid invocations return HTTP 200 with a JSON object. Successful example:

```json
{"status":"completed","terminalStep":"success","executedSteps":4,"lastInput":"pong"}
```

Implicit completion omits terminalStep. lastInput is null if no successful wait
occurred, or if the last wait timed out. Sent data and full transcripts are not
returned or logged. lastInput is returned to the requesting caller; treat it as
potentially sensitive protocol data.

Errors return status=error, code, executedSteps, effectsMayHaveOccurred, and the
current step ID if execution began. They never include exception text, host,
send contents, or input text. Invalid JSON returns HTTP 400/INVALID_JSON through
the existing safe native-function binding advice.

| Code | Meaning |
| --- | --- |
| INVALID_REQUEST | Invalid definition; no TCP connection opened. |
| BUSY | All 16 TCP request slots are occupied. |
| TCP_ERROR | Connection refused or other network operation failure. |
| STEP_TIMEOUT | Connect, send, or wait exceeded its operation deadline. |
| TOTAL_TIMEOUT | Overall conversation deadline expired. |
| EXECUTION_LIMIT | Loop/step count reached maxExecutions. |
| NO_INPUT | A fork had no successful current wait result. |
| PEER_CLOSED | Remote system closed before a new line arrived. |
| INCOMPLETE_FRAME | Remote system closed with a partial line. |
| INVALID_UTF8 | Received line was not valid UTF-8. |
| FRAME_LIMIT | A received line exceeded 8192 bytes before LF. |
| BYTE_LIMIT | More than 65536 received bytes, or sending would exceed 65536 bytes including LF. |
| INTERRUPTED | Calling thread interrupted; interrupt flag preserved. |

Nonblocking Java NIO bounds connect/read/write waiting. A frame may have at
most 8192 bytes before LF (including CR if present); outgoing text may have
8192 UTF-8 bytes before the appended LF. Received byte accounting includes
buffered lines not yet consumed. Request-owned socket and selector are closed
on completion, failure, timeout, or interruption. Closing a socket is not a
protocol acknowledgment. EffectsMayHaveOccurred is conservative once a send
step was attempted. A completed write means bytes were accepted locally, not
that the peer processed them. No reconnects or automatic retries occur.

## Run and test

From spring-functions-api, after selecting Java 21:

```sh
./mvnw verify
./mvnw spring-boot:run
```

With your TCP line protocol server listening on the example's port 9000:

```sh
curl -sS -H 'Content-Type: application/json' \
  --data-binary @examples/tcp-ping-pong.json \
  http://127.0.0.1:8080/exchangeTcpConversation
```

Default tests create their own disposable loopback TCP servers, exercise the
native HTTP endpoint, and require no external TCP server or RabbitMQ broker.
Tests cover matching/fallback, goto, fragmentation/coalescing, timeout branches,
deadlines, loop bounds, malformed definitions, EOF, frame sizes, UTF-8, and cleanup.
Your actual remote protocol behavior remains unverified until run against it.
