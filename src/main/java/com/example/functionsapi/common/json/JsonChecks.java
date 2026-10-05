package com.example.functionsapi.common.json;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
import java.nio.charset.StandardCharsets;

/** Strict structural checks used before any network side effects. */
public final class JsonChecks {
    /** Initializes owned dependencies and bounded resources. */
    private JsonChecks() { }
    /** Rejects nonobjects and unknown properties. */
    public static void fields(JsonNode node, Set<String> names) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException();
        node.fieldNames().forEachRemaining(k -> { if (!names.contains(k)) throw new IllegalArgumentException(); });
    }
    /** Reads a required UTF-8 string, including AMQP short-string byte bounds. */
    public static String text(JsonNode node, String key, int limit, boolean empty) {
        JsonNode v = node.get(key);
        if (v == null || !v.isTextual() || (!empty && v.textValue().isBlank())
                || !StandardCharsets.UTF_8.newEncoder().canEncode(v.textValue())
                || v.textValue().getBytes(StandardCharsets.UTF_8).length > limit) throw new IllegalArgumentException();
        return v.textValue();
    }
    /** Reads an integer without coercing strings, fractions, or booleans. */
    public static int integer(JsonNode node, String key, int fallback, int min, int max) {
        if (!node.has(key)) return fallback;
        JsonNode v = node.get(key);
        if (!v.isIntegralNumber() || !v.canConvertToInt() || v.intValue()<min || v.intValue()>max)
            throw new IllegalArgumentException();
        return v.intValue();
    }
    /** Restricts numeric hosts to IPv4 literals, with localhost mapped to loopback. */
    public static String host(String host) {
        if (host.equals("localhost")) return "127.0.0.1";
        if (!host.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) throw new IllegalArgumentException();
        for (String p : host.split("\\.")) if (Integer.parseInt(p)>255) throw new IllegalArgumentException();
        return host;
    }
    /** Bounds tree depth and node count without serializing secret-bearing values to logs. */
    public static void tree(JsonNode node) { count(node,0,new int[]{0}); }
    /** Rejects excessive depth or repeated nodes before allocating transformed copies. */
    private static void count(JsonNode node, int depth, int[] count) {
        if (depth>64 || ++count[0]>20000) throw new IllegalArgumentException();
        if (node.isContainerNode()) for (JsonNode child : node) count(child,depth+1,count);
    }
}
