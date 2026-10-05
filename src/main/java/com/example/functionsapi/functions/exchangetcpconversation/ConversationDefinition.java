package com.example.functionsapi.functions.exchangetcpconversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.re2j.Pattern;
import java.util.*;

/** Validated immutable TCP steps and graph targets with bounded regex programs. */
record ConversationDefinition(String host, int port, int connectTimeoutMs, int totalTimeoutMs,
        int maxExecutions, String start, Map<String, Step> steps, List<String> order) {
    /** A regex match and its validated destination step. */
    record Branch(Pattern regex, String target) { }
    /** One validated conversation instruction. */
    record Step(String id, String action, String text, int timeoutMs, String target,
                String otherwise, String onTimeout, List<Branch> branches) { }

    /** Validates the complete configuration and references before execution. */
    static ConversationDefinition parse(JsonNode request) {
        fields(request, Set.of("host", "port", "connectTimeoutMs", "totalTimeoutMs", "maxExecutions", "start", "steps"));
        String host = text(request, "host", 255);
        if (host.equals("localhost")) host = "127.0.0.1";
        if (!host.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) throw new IllegalArgumentException();
        for (String octet : host.split("\\.")) if (Integer.parseInt(octet) > 255) throw new IllegalArgumentException();
        int port = integer(request, "port", -1, 1, 65535);
        JsonNode steps = request.get("steps");
        if (steps == null || !steps.isArray() || steps.isEmpty() || steps.size() > 256) throw new IllegalArgumentException();
        Map<String, Step> byId = new LinkedHashMap<>();
        int patternCount = 0, regexBytes = 0, programSize = 0;
        for (JsonNode step : steps) {
            String action = text(step, "action", 16);
            Set<String> allowed = switch (action) {
                case "send" -> Set.of("id", "action", "text", "timeoutMs");
                case "wait" -> Set.of("id", "action", "timeoutMs", "onTimeout");
                case "fork" -> Set.of("id", "action", "when", "otherwise");
                case "goto" -> Set.of("id", "action", "goto");
                case "end" -> Set.of("id", "action");
                default -> throw new IllegalArgumentException();
            };
            fields(step, allowed);
            String id = id(step, "id");
            String body = null, target = null, otherwise = null, onTimeout = null;
            List<Branch> branches = new ArrayList<>();
            int timeout = integer(step, "timeoutMs", 1000, 1, 60000);
            if (action.equals("send")) {
                JsonNode v = step.get("text");
                if (v == null || !v.isTextual()) throw new IllegalArgumentException();
                body = v.textValue();
                if (body.contains("\n") || body.contains("\r")
                        || !java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(body)
                        || body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 8192)
                    throw new IllegalArgumentException();
            }
            if (action.equals("goto")) target = id(step, "goto");
            if (action.equals("wait") && step.has("onTimeout")) onTimeout = id(step, "onTimeout");
            if (action.equals("fork")) {
                otherwise = id(step, "otherwise");
                JsonNode when = step.get("when");
                if (when == null || !when.isArray() || when.isEmpty() || when.size() > 16) throw new IllegalArgumentException();
                for (JsonNode branch : when) {
                    fields(branch, Set.of("regex", "goto"));
                    String regex = text(branch, "regex", 256);
                    regexBytes += regex.length();
                    if (++patternCount > 128 || regexBytes > 8192) throw new IllegalArgumentException();
                    Pattern compiled;
                    try { compiled = Pattern.compile(regex); }
                    catch (com.google.re2j.PatternSyntaxException e) { throw new IllegalArgumentException(); }
                    programSize += compiled.programSize();
                    if (compiled.programSize() > 4096 || programSize > 65536) throw new IllegalArgumentException();
                    branches.add(new Branch(compiled, id(branch, "goto")));
                }
            }
            if (byId.putIfAbsent(id, new Step(id, action, body, timeout, target, otherwise, onTimeout, List.copyOf(branches))) != null)
                throw new IllegalArgumentException();
        }
        for (Step step : byId.values()) {
            for (String ref : Arrays.asList(step.target(), step.otherwise(), step.onTimeout()))
                if (ref != null && !byId.containsKey(ref)) throw new IllegalArgumentException();
            for (Branch branch : step.branches()) if (!byId.containsKey(branch.target())) throw new IllegalArgumentException();
        }
        String start = request.has("start") ? id(request, "start") : byId.keySet().iterator().next();
        if (!byId.containsKey(start)) throw new IllegalArgumentException();
        return new ConversationDefinition(host, port, integer(request, "connectTimeoutMs", 3000, 1, 10000),
                integer(request, "totalTimeoutMs", 10000, 1, 60000),
                integer(request, "maxExecutions", 256, 1, 1024), start, Map.copyOf(byId), List.copyOf(byId.keySet()));
    }
    /** Rejects nonobjects and unknown configuration fields. */
    private static void fields(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException();
        node.fieldNames().forEachRemaining(name -> { if (!allowed.contains(name)) throw new IllegalArgumentException(); });
    }
    /** Reads bounded nonblank configuration text. */
    private static String text(JsonNode node, String name, int max) {
        JsonNode value = node == null ? null : node.get(name);
        if (value == null || !value.isTextual() || value.textValue().isBlank() || value.textValue().length() > max)
            throw new IllegalArgumentException();
        return value.textValue();
    }
    /** Restricts step IDs and jump targets to safe ASCII identifiers. */
    private static String id(JsonNode node, String name) {
        String value = text(node, name, 64);
        if (!value.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
        return value;
    }
    /** Checks integer types and documented limits without coercion. */
    private static int integer(JsonNode node, String name, int fallback, int min, int max) {
        if (!node.has(name)) { if (fallback < min) throw new IllegalArgumentException(); return fallback; }
        JsonNode value = node.get(name);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < min || value.intValue() > max)
            throw new IllegalArgumentException();
        return value.intValue();
    }
}
