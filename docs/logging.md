# Function logging

All five public functions share invocation logging with a generated invocationId,
function name, durationMs, fixed status and error code. INFO records outcomes and
TCP listener lifecycle. TCP status polling uses DEBUG to avoid routine INFO noise.
DEBUG records validated counts, resource settings, safe error codes and cleanup
failures. TRACE records step types, frame byte counts, polling and confirmation
progress. Unexpected exceptions emit WARN with only their class name.

No level logs credentials, requests, message bodies, external response data,
headers, URLs, regular expressions, or arbitrary exception messages. Invocation
IDs correlate start and completion in logs; they are not acknowledgment tokens.
Request objects containing credentials also redact their string representations.

Default application logging is INFO. Enable package logging temporarily:

```sh
./mvnw spring-boot:run \
  -Dspring-boot.run.arguments='--logging.level.com.example.functionsapi=DEBUG'
```

Replace DEBUG with TRACE for detailed execution progress. Avoid enabling global
TRACE, which can expose content through framework or third-party logging.
Class Javadoc, method comments and inline comments describe public usage,
validation, resource ownership and protocol behavior. Each public function's
Javadoc points to a runnable example in examples/.
