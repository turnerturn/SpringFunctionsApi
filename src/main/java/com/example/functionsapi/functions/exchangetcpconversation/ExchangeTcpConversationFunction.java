package com.example.functionsapi.functions.exchangetcpconversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.function.Function;

/** Runs JSON-defined TCP conversations.
 * Usage: POST /exchangeTcpConversation with examples/tcp-accuload-pc.json.
 * <pre>{@code
 * curl -sS -H 'Content-Type: application/json' --data-binary @examples/tcp-accuload-pc.json \
 *   http://127.0.0.1:8080/exchangeTcpConversation
 * }</pre>
 */
@Configuration
public class ExchangeTcpConversationFunction {
    /** Registers the native JSON conversation function with safe logging. */
    @Bean
    public Function<JsonNode, JsonNode> exchangeTcpConversation(ObjectMapper mapper) {
        ConversationRunner runner = new ConversationRunner(mapper);
        return com.example.functionsapi.common.logging.FunctionLogging.wrap("exchangeTcpConversation", runner::exchange);
    }
}
