package com.example.functionsapi.functions.sendrabbitmqmessage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.function.Function;
import com.example.functionsapi.common.logging.FunctionLogging;

/**
 * Publishes one message to an existing AMQP destination using mandatory routing and confirms.
 * Usage: POST /sendRabbitMqMessage with examples/send-rabbitmq-message.json.
 * A confirm acknowledges the broker, not consumption by a downstream application.

 * <pre>{@code
 * curl -sS -H 'Content-Type: application/json' --data-binary @examples/send-rabbitmq-message.json \
 *   http://127.0.0.1:8080/sendRabbitMqMessage
 * }</pre>
 */
@Configuration
public class SendRabbitMqMessageFunction {
    /** Registers the request-owned publisher service for configuration and test seams. */
    @Bean
    public RabbitPublisher rabbitPublisher(ObjectMapper mapper) { return new RabbitPublisher(mapper); }

    /** Exposes the native JSON function without duplicating it with a controller. */
    @Bean
    public Function<JsonNode,JsonNode> sendRabbitMqMessage(RabbitPublisher publisher) {
        return FunctionLogging.wrap("sendRabbitMqMessage",publisher::publish);
    }
}
