package com.example.functionsapi.functions.fetchrabbitmqqueuemessage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rabbitmq.client.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

/** Fetches an existing queue message with explicit acknowledgment or requeue.
 * Usage: POST /fetchRabbitMqQueueMessage with examples/fetch-immediate.json.
 * Credentials, requests, and message bodies are never written to logs.
 * <pre>{@code
 * curl -sS -H 'Content-Type: application/json' --data-binary @examples/fetch-immediate.json \
 *   http://127.0.0.1:8080/fetchRabbitMqQueueMessage
 * }</pre>
 */
@Configuration
public class FetchRabbitMqQueueMessageFunction {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(FetchRabbitMqQueueMessageFunction.class);
    private final ObjectMapper mapper;
    private final Semaphore slots = new Semaphore(16);
    /** Initializes owned dependencies and bounded resources. */
    public FetchRabbitMqQueueMessageFunction(ObjectMapper mapper) { this.mapper = mapper; }
    /** Registers the native JSON fetch function with safe logging. */
    @Bean
    public Function<JsonNode, JsonNode> fetchRabbitMqQueueMessage() { return com.example.functionsapi.common.logging.FunctionLogging.wrap("fetchRabbitMqQueueMessage", this::fetch); }

    record Request(String host, int port, String virtualHost, String username, String password,
                   String queue, boolean tls, String settlement, int timeout) {
        /** Redacts credentials from generated diagnostics. */
        @Override public String toString() { return "Request[redacted]"; }
    }
    /** Validates all request fields before any network operation. */
    Request validate(JsonNode n) {
        if (n == null || !n.isObject()) throw new IllegalArgumentException();
        Set<String> fields = Set.of("host", "port", "virtualHost", "username", "passwordEnv", "queue", "tls", "settlement", "pollTimeoutMs");
        n.fieldNames().forEachRemaining(k -> { if (!fields.contains(k)) throw new IllegalArgumentException(); });
        String host = required(n, "host"), queue = required(n, "queue"), user = required(n, "username");
        if (!(host.equals("localhost") || host.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"))) throw new IllegalArgumentException();
        if (!host.equals("localhost")) for (String octet : host.split("\\.")) if (Integer.parseInt(octet)>255) throw new IllegalArgumentException();
        if (host.equals("localhost")) host="127.0.0.1";
        String env = required(n, "passwordEnv");
        if (!Set.of("RABBITMQ_PASSWORD").contains(env)) throw new IllegalArgumentException();
        String password = secret(env);
        if (password == null || password.isEmpty()) throw new IllegalArgumentException();
        String settlement = required(n, "settlement");
        if (!Set.of("acknowledge", "requeue").contains(settlement)) throw new IllegalArgumentException();
        if (n.has("tls") && !n.get("tls").isBoolean()) throw new IllegalArgumentException();
        boolean tls = n.path("tls").asBoolean(false);
        return new Request(host, integer(n,"port",tls ? 5671 : 5672,1,65535),
            n.has("virtualHost") ? required(n,"virtualHost") : "/", user,password,queue,tls,settlement,
            integer(n,"pollTimeoutMs",0,0,30000));
    }
    /** Reads a required nonblank bounded string. */
    private String required(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || !v.isTextual() || v.textValue().isBlank() || v.textValue().length()>255) throw new IllegalArgumentException();
        return v.textValue();
    }
    /** Checks integer types and documented limits without coercion. */
    private int integer(JsonNode n,String field,int fallback,int min,int max) {
        if (!n.has(field)) return fallback;
        JsonNode v=n.get(field);
        if (!v.isIntegralNumber() || !v.canConvertToInt() || v.intValue()<min || v.intValue()>max) throw new IllegalArgumentException();
        return v.intValue();
    }
    /** Resolves a previously allowlisted credential environment variable. */
    String secret(String name) { return System.getenv(name); }
    /** Creates verified and bounded request-owned AMQP transport. */
    ConnectionFactory factory(Request r) throws Exception {
        return com.example.functionsapi.common.rabbitmq.RabbitConnections.create(
            r.host(),r.port(),r.virtualHost(),r.username(),r.password(),r.tls());
    }
    /** Provides the monotonic clock seam for deterministic deadline tests. */
    long nanoTime() { return System.nanoTime(); }
    /** Waits between reads while allowing interruption. */
    void pause(long millis) throws InterruptedException { Thread.sleep(millis); }

    /** Fetches and settles once on the receiving channel and always cleans resources. */
    JsonNode fetch(JsonNode input) {
        Request r;
        try { r=validate(input); } catch (IllegalArgumentException e) { return error("INVALID_REQUEST"); }
        if (!slots.tryAcquire()) return error("BUSY");
        Connection connection=null; Channel channel=null;
        boolean settling=false;
        try {
            log.debug("Fetch validated pollingMs={} settlement={}",r.timeout(),r.settlement());
            connection=factory(r).newConnection(); channel=connection.createChannel();
            long deadline=nanoTime()+r.timeout()*1_000_000L;
            GetResponse message;
            while (true) {
                log.trace("Fetch attempting manual-ack basic.get");
                message=channel.basicGet(r.queue(),false);
                if (message!=null) break;
                long remaining=deadline-nanoTime();
                if (remaining<=0) return mapper.createObjectNode().put("status","empty");
                pause(Math.min(100, Math.max(1,remaining/1_000_000)));
                if (nanoTime()>=deadline) return mapper.createObjectNode().put("status","empty");
            }
            ObjectNode result=map(message,r.settlement());
            log.debug("Fetch received bytes={}",message.getBody().length);
            // Settlement must use the same channel that received this delivery.
            settling=true;
            if (r.settlement().equals("acknowledge")) channel.basicAck(message.getEnvelope().getDeliveryTag(),false);
            else channel.basicNack(message.getEnvelope().getDeliveryTag(),false,true);
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); return error("INTERRUPTED");
        } catch (Exception e) {
            if (settling) return error("SETTLEMENT_UNCERTAIN");
            if (e instanceof AuthenticationFailureException) return error("AUTHENTICATION_FAILED");
            Throwable cause=e;
            while (cause!=null) {
                if (cause instanceof ShutdownSignalException signal && signal.getReason() instanceof AMQP.Channel.Close close) {
                    if (close.getReplyCode()==404) return error("QUEUE_NOT_FOUND");
                    if (close.getReplyCode()==403) return error("ACCESS_DENIED");
                }
                cause=cause.getCause();
            }
            return error("BROKER_ERROR");
        } finally {
            com.example.functionsapi.common.rabbitmq.RabbitConnections.close(channel,connection);
            slots.release();
        }
    }
    /** Maps body and nested headers without logging their values. */
    ObjectNode map(GetResponse m,String settlement) {
        ObjectNode result=mapper.createObjectNode().put("status","message").put("settlement",settlement);
        ObjectNode message=result.putObject("message");
        message.put("bodyBase64",Base64.getEncoder().encodeToString(m.getBody()));
        message.put("redelivered",m.getEnvelope().isRedeliver()).put("exchange",m.getEnvelope().getExchange())
            .put("routingKey",m.getEnvelope().getRoutingKey()).put("remainingMessageCount",m.getMessageCount());
        AMQP.BasicProperties props=m.getProps();
        ObjectNode properties=message.putObject("properties");
        properties.put("contentType",props.getContentType()); properties.put("contentEncoding",props.getContentEncoding());
        properties.put("correlationId",props.getCorrelationId()); properties.put("replyTo",props.getReplyTo());
        properties.put("messageId",props.getMessageId()); properties.put("type",props.getType());
        properties.put("appId",props.getAppId()); properties.put("userId",props.getUserId());
        properties.put("expiration",props.getExpiration());
        properties.set("deliveryMode",mapper.valueToTree(props.getDeliveryMode()));
        properties.set("priority",mapper.valueToTree(props.getPriority()));
        properties.set("timestamp",mapper.valueToTree(props.getTimestamp()==null ? null : props.getTimestamp().getTime()));
        ObjectNode headers=message.putObject("headers");
        if (props.getHeaders()!=null) props.getHeaders().forEach((k,v)->headers.set(k,mapper.valueToTree(header(v))));
        return result;
    }
    /** Converts AMQP header values into JSON-safe representations. */
    private Object header(Object value) {
        if (value instanceof LongString s) return s.toString();
        if (value instanceof byte[] b) return Map.of("base64",Base64.getEncoder().encodeToString(b));
        if (value instanceof Map<?,?> m) { Map<String,Object> out=new LinkedHashMap<>(); m.forEach((k,v)->out.put(k.toString(),header(v))); return out; }
        if (value instanceof List<?> l) return l.stream().map(this::header).toList();
        return value;
    }
    /** Returns a fixed error code without exposing exception messages or payloads. */
    private ObjectNode error(String code) { log.debug("Fetch outcome code={}",code); return mapper.createObjectNode().put("status","error").put("code",code); }
}
