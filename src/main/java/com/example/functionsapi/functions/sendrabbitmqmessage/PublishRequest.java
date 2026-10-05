package com.example.functionsapi.functions.sendrabbitmqmessage;

import com.fasterxml.jackson.databind.*;
import com.rabbitmq.client.*;
import com.example.functionsapi.common.json.JsonChecks;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

/** Validated publish configuration; never log this object or its credential-bearing fields. */
record PublishRequest(String host,int port,String virtualHost,String username,String password,boolean tls,
                      String exchange,String routingKey,byte[] body,AMQP.BasicProperties properties,int confirmTimeoutMs) {
    /** Validates all JSON and serializes the body before a broker connection is opened. */
    static PublishRequest parse(JsonNode n,ObjectMapper mapper,Function<String,String> secrets) throws Exception {
        JsonChecks.fields(n,Set.of("broker","destination","message","confirmTimeoutMs")); JsonChecks.tree(n);
        JsonNode b=n.get("broker"), d=n.get("destination"), m=n.get("message");
        JsonChecks.fields(b,Set.of("host","port","virtualHost","username","passwordEnv","tls"));
        String host=JsonChecks.host(JsonChecks.text(b,"host",255,false));
        String env=JsonChecks.text(b,"passwordEnv",255,false);
        if (!env.equals("RABBITMQ_PASSWORD")) throw new IllegalArgumentException();
        if (b.has("tls") && !b.get("tls").isBoolean()) throw new IllegalArgumentException();
        boolean tls=b.path("tls").asBoolean(false);
        JsonChecks.fields(d,Set.of("queue","exchange","routingKey"));
        String exchange,key;
        if (d.has("queue")) {
            if (d.size()!=1) throw new IllegalArgumentException();
            exchange=""; key=JsonChecks.text(d,"queue",255,false);
        } else { exchange=JsonChecks.text(d,"exchange",255,true); key=JsonChecks.text(d,"routingKey",255,true); }
        JsonChecks.fields(m,Set.of("data","text","bodyBase64","headers","properties"));
        if ((m.has("data")?1:0)+(m.has("text")?1:0)+(m.has("bodyBase64")?1:0)!=1) throw new IllegalArgumentException();
        byte[] body; String contentType;
        if (m.has("data")) { body=mapper.writeValueAsBytes(m.get("data")); contentType="application/json"; }
        else if (m.has("text")) { body=JsonChecks.text(m,"text",1048576,true).getBytes(StandardCharsets.UTF_8); contentType="text/plain"; }
        else { body=Base64.getDecoder().decode(JsonChecks.text(m,"bodyBase64",1398104,true)); contentType="application/octet-stream"; }
        if (body.length>1048576) throw new IllegalArgumentException();
        Map<String,Object> headers=new LinkedHashMap<>();
        if (m.has("headers")) {
            if (!m.get("headers").isObject() || mapper.writeValueAsBytes(m.get("headers")).length>16384) throw new IllegalArgumentException();
            m.get("headers").fields().forEachRemaining(e -> {
                if (e.getKey().getBytes(StandardCharsets.UTF_8).length>255) throw new IllegalArgumentException();
                headers.put(e.getKey(),header(e.getValue()));
            });
        }
        JsonNode props=m.has("properties")?m.get("properties"):mapper.createObjectNode();
        JsonChecks.fields(props,Set.of("contentType","contentEncoding","deliveryMode","correlationId","messageId","expiration"));
        AMQP.BasicProperties.Builder builder=new AMQP.BasicProperties.Builder().headers(headers)
            .contentType(props.has("contentType")?JsonChecks.text(props,"contentType",255,false):contentType)
            .deliveryMode(JsonChecks.integer(props,"deliveryMode",2,1,2));
        if (!m.has("bodyBase64")) builder.contentEncoding("utf-8");
        if (props.has("contentEncoding")) builder.contentEncoding(JsonChecks.text(props,"contentEncoding",255,false));
        if (props.has("correlationId")) builder.correlationId(JsonChecks.text(props,"correlationId",255,true));
        if (props.has("messageId")) builder.messageId(JsonChecks.text(props,"messageId",255,true));
        if (props.has("expiration")) {
            String expiration=JsonChecks.text(props,"expiration",20,false);
            if (!expiration.matches("[0-9]+") || new java.math.BigInteger(expiration).bitLength()>63) throw new IllegalArgumentException();
            builder.expiration(expiration);
        }
        // Validate the entire request before resolving server-side credential configuration.
        int port=JsonChecks.integer(b,"port",tls?5671:5672,1,65535);
        String virtualHost=b.has("virtualHost")?JsonChecks.text(b,"virtualHost",255,false):"/";
        String username=JsonChecks.text(b,"username",255,false);
        int timeout=JsonChecks.integer(n,"confirmTimeoutMs",5000,1,10000);
        String password=secrets.apply(env);
        if (password==null || password.isEmpty()) throw new MissingCredentials();
        return new PublishRequest(host,port,
            virtualHost,username,password,tls,exchange,key,body,builder.build(),timeout);
    }
    /** Distinguishes absent application credentials from malformed caller JSON without secret values. */
    static final class MissingCredentials extends RuntimeException { }
    /** Converts JSON headers into supported AMQP field-table values without string coercion. */
    private static Object header(JsonNode n) {
        if (n.isNull()) return null;
        if (n.isTextual()) return n.textValue();
        if (n.isBoolean()) return n.booleanValue();
        if (n.isIntegralNumber() && n.canConvertToLong()) return n.longValue();
        if (n.isFloatingPointNumber() && Double.isFinite(n.doubleValue())) return n.doubleValue();
        if (n.isArray()) { List<Object> result=new ArrayList<>(); n.forEach(v -> result.add(header(v))); return result; }
        if (n.isObject()) {
            Map<String,Object> result=new LinkedHashMap<>();
            n.fields().forEachRemaining(e -> {
                if (e.getKey().getBytes(StandardCharsets.UTF_8).length>255) throw new IllegalArgumentException();
                result.put(e.getKey(),header(e.getValue()));
            }); return result;
        }
        throw new IllegalArgumentException();
    }
    /** Prevents accidental credential disclosure through generated record diagnostics. */
    @Override public String toString() { return "PublishRequest[redacted]"; }
}
