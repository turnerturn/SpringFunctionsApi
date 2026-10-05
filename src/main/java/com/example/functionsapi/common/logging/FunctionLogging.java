package com.example.functionsapi.common.logging;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.UUID;
import java.util.function.Function;

/** Payload-free invocation diagnostics shared by every public function. */
public final class FunctionLogging {
    private static final Logger log = LoggerFactory.getLogger(FunctionLogging.class);
    /** Initializes owned dependencies and bounded resources. */
    private FunctionLogging() { }

    /** Wraps an invocation with a random correlation ID and safe outcome metadata. */
    public static Function<JsonNode, JsonNode> wrap(String name, Function<JsonNode, JsonNode> delegate) {
        return input -> {
            String id = UUID.randomUUID().toString();
            long started = System.nanoTime();
            // Status polling is frequent; its lifecycle summary belongs at DEBUG.
            boolean polling = name.equals("tcpServer") && input != null && input.isObject()
                    && input.path("command").asText().equals("status");
            log.atTrace().addArgument(name).addArgument(id).log("function={} invocation={} started");
            try {
                JsonNode result = delegate.apply(input);
                String status = result == null ? "none" : result.path("status").asText("none");
                String code = result == null ? "none" : result.path("code").asText("none");
                (polling ? log.atDebug() : log.atInfo()).addArgument(name).addArgument(id)
                        .addArgument(status).addArgument(code).addArgument((System.nanoTime()-started)/1_000_000)
                        .log("function={} invocation={} status={} code={} durationMs={}");
                return result;
            } catch (RuntimeException e) {
                // Exception messages and stack traces may embed credentials or protocol data.
                log.warn("function={} invocation={} failed category={}", name, id, e.getClass().getSimpleName());
                throw e;
            }
        };
    }
}
