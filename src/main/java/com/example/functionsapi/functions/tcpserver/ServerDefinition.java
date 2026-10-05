package com.example.functionsapi.functions.tcpserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.re2j.Pattern;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Validated immutable mock-server configuration and ordered command/response rules. */
record ServerDefinition(int port, int timeoutMs, int idleTimeoutMs, int maxConnections,
                        int maxMessages, List<Rule> commands, List<Rule> rules) {
    /** Ordered linear-time regex rule with an optional fixed response. */
    record Rule(Pattern pattern, String action, String response) { }
    /** Validates the complete configuration and references before execution. */
    static ServerDefinition parse(JsonNode node) {
        fields(node, Set.of("command", "port", "timeoutMs", "idleTimeoutMs", "maxConnections", "maxMessages", "commands", "rules"));
        List<Rule> commands = parseRules(node.get("commands"), true);
        List<Rule> rules = parseRules(node.get("rules"), false);
        int chars = 0, size = 0;
        for (Rule rule : java.util.stream.Stream.concat(commands.stream(), rules.stream()).toList()) {
            chars += rule.pattern().pattern().length(); size += rule.pattern().programSize();
        }
        if (chars > 8192 || size > 65536) throw new IllegalArgumentException();
        return new ServerDefinition(integer(node,"port",0,0,65535), integer(node,"timeoutMs",30000,1,60000),
            integer(node,"idleTimeoutMs",1000,1,10000), integer(node,"maxConnections",16,1,64),
            integer(node,"maxMessages",256,1,1024), commands, rules);
    }
    /** Compiles bounded linear-time regexes and validates response text. */
    private static List<Rule> parseRules(JsonNode array, boolean commands) {
        if (array == null) return List.of();
        if (!array.isArray() || array.size() > (commands ? 32 : 64)) throw new IllegalArgumentException();
        List<Rule> out = new ArrayList<>();
        for (JsonNode node : array) {
            fields(node, commands ? Set.of("regex","action","response") : Set.of("regex","response"));
            String regex = text(node,"regex",256);
            String action = commands ? text(node,"action",16) : "respond";
            if (commands && !Set.of("echo","shutdown").contains(action)) throw new IllegalArgumentException();
            String response = null;
            if (!commands || node.has("response")) {
                JsonNode value = node.get("response");
                if (value == null || !value.isTextual()) throw new IllegalArgumentException();
                response = value.textValue();
                if (response.contains("\r") || response.contains("\n")
                    || !StandardCharsets.UTF_8.newEncoder().canEncode(response)
                    || response.getBytes(StandardCharsets.UTF_8).length > 8192) throw new IllegalArgumentException();
            }
            if (action.equals("echo") && response != null) throw new IllegalArgumentException();
            Pattern compiled;
            try { compiled = Pattern.compile(regex); }
            catch (com.google.re2j.PatternSyntaxException e) { throw new IllegalArgumentException(); }
            if (compiled.programSize() > 4096) throw new IllegalArgumentException();
            out.add(new Rule(compiled, action, response));
        }
        return List.copyOf(out);
    }
    /** Rejects nonobjects and unknown configuration fields. */
    static void fields(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException();
        node.fieldNames().forEachRemaining(name -> { if (!allowed.contains(name)) throw new IllegalArgumentException(); });
    }
    /** Reads bounded nonblank configuration text. */
    static String text(JsonNode node, String name, int max) {
        JsonNode value = node.get(name);
        if (value == null || !value.isTextual() || value.textValue().isBlank() || value.textValue().length() > max)
            throw new IllegalArgumentException();
        return value.textValue();
    }
    /** Checks integer types and documented limits without coercion. */
    private static int integer(JsonNode node, String name, int fallback, int min, int max) {
        if (!node.has(name)) return fallback;
        JsonNode value = node.get(name);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < min || value.intValue() > max)
            throw new IllegalArgumentException();
        return value.intValue();
    }
}
