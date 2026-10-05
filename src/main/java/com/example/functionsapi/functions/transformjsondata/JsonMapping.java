package com.example.functionsapi.functions.transformjsondata;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** RFC 6901 pointer mutations and typed template bindings; no executable expressions. */
final class JsonMapping {
    /** Initializes owned dependencies and bounded resources. */
    private JsonMapping() { }
    /** Validates a bounded pointer and all escape sequences before execution. */
    static String pointer(String p) {
        if (p.length()>1024 || (!p.isEmpty() && !p.startsWith("/")))
            throw new IllegalArgumentException();
        for (int i=0;i<p.length();i++) if (p.charAt(i)=='~') {
            if (++i>=p.length() || (p.charAt(i)!='0' && p.charAt(i)!='1')) throw new IllegalArgumentException();
        }
        com.fasterxml.jackson.core.JsonPointer.compile(p); return p;
    }
    /** Reads missing values as explicit JSON null, preserving existing JSON types. */
    static JsonNode get(JsonNode node,String p) { JsonNode v=node.at(p); return v.isMissingNode()?NullNode.instance:v; }
    /** Sets a pointer, creating object parents; array indices must already exist. */
    static JsonNode set(JsonNode root,String p,JsonNode value) {
        if (p.isEmpty()) return value.deepCopy();
        List<String> tokens=tokens(p); JsonNode parent=root;
        for (int i=0;i<tokens.size()-1;i++) {
            String key=tokens.get(i);
            if (parent.isObject()) {
                JsonNode child=parent.get(key);
                if (child==null) child=((ObjectNode)parent).putObject(key);
                parent=child;
            } else if (parent.isArray()) parent=parent.get(index(key,parent.size()));
            else throw new MappingFailure("DATA_SHAPE_ERROR");
        }
        String key=tokens.getLast();
        if (parent.isObject()) ((ObjectNode)parent).set(key,value.deepCopy());
        else if (parent.isArray()) ((ArrayNode)parent).set(index(key,parent.size()),value.deepCopy());
        else throw new MappingFailure("DATA_SHAPE_ERROR");
        return root;
    }
    /** Removes an object field or existing array element; missing object paths are no-ops. */
    static JsonNode remove(JsonNode root,String p) {
        if (p.isEmpty()) return NullNode.instance;
        List<String> tokens=tokens(p); JsonNode parent=root;
        for (int i=0;i<tokens.size()-1;i++) {
            if (parent.isObject()) parent=parent.get(tokens.get(i));
            else if (parent.isArray()) parent=parent.get(index(tokens.get(i),parent.size()));
            else return root;
            if (parent==null) return root;
        }
        String key=tokens.getLast();
        if (parent.isObject()) ((ObjectNode)parent).remove(key);
        else if (parent.isArray()) ((ArrayNode)parent).remove(index(key,parent.size()));
        return root;
    }
    /** Deep-merges objects; incoming arrays/scalars/null replace the destination value. */
    static JsonNode merge(JsonNode existing,JsonNode incoming,int depth) {
        if (depth>64) throw new MappingFailure("DATA_LIMIT");
        if (!existing.isObject() || !incoming.isObject()) return incoming.deepCopy();
        ObjectNode out=existing.deepCopy();
        incoming.fields().forEachRemaining(e -> out.set(e.getKey(),merge(getField(out,e.getKey()),e.getValue(),depth+1)));
        return out;
    }
    /** Expands whole-string {@code <name>} placeholders as JSON values rather than string interpolation. */
    static JsonNode template(JsonNode template,Map<String,JsonNode> bindings) {
        Budget budget=new Budget();
        return expand(template,bindings,budget);
    }
    /** Counts expanded values before copying so repeated bindings cannot exhaust memory. */
    private static JsonNode expand(JsonNode template,Map<String,JsonNode> bindings,Budget budget) {
        if (template.isTextual() && template.textValue().matches("<[A-Za-z0-9_-]+>")) {
            String name=template.textValue().substring(1,template.textValue().length()-1);
            if (!bindings.containsKey(name)) throw new IllegalArgumentException();
            budget.add(bindings.get(name),0); return bindings.get(name).deepCopy();
        }
        budget.charge(1,template.isTextual()?template.textValue().length()*6L:16);
        if (template.isObject()) {
            ObjectNode out=JsonNodeFactory.instance.objectNode();
            template.fields().forEachRemaining(e -> { budget.charge(0,e.getKey().length()*6L+4); out.set(e.getKey(),expand(e.getValue(),bindings,budget)); }); return out;
        }
        if (template.isArray()) { ArrayNode out=JsonNodeFactory.instance.arrayNode(); template.forEach(v -> out.add(expand(v,bindings,budget))); return out; }
        return template.deepCopy();
    }
    /** Conservative expansion accounting bounds nodes and escaped JSON bytes. */
    private static final class Budget {
        private int nodes;
        private long bytes;
        void charge(int count,long size) {
            nodes+=count; bytes+=size;
            if (nodes>20000 || bytes>1048576) throw new MappingFailure("DATA_LIMIT");
        }
        void add(JsonNode value,int depth) {
            if (depth>64) throw new MappingFailure("DATA_LIMIT");
            charge(1,value.isTextual()?value.textValue().length()*6L:32);
            if (value.isObject()) value.fieldNames().forEachRemaining(k -> charge(0,k.length()*6L+4));
            if (value.isContainerNode()) for (JsonNode child:value) add(child,depth+1);
        }
    }
    /** Gets a map field without interpreting it as a JSON pointer. */
    private static JsonNode getField(JsonNode node,String key) { return node.has(key)?node.get(key):NullNode.instance; }
    /** Decodes pointer tokens once, preserving escaped slashes and tildes in keys. */
    private static List<String> tokens(String p) {
        return Arrays.stream(p.substring(1).split("/",-1)).map(v -> v.replace("~1","/").replace("~0","~")).toList();
    }
    /** Rejects append markers, negative indexes, leading zeros, and absent array entries. */
    private static int index(String token,int size) {
        if (!token.matches("0|[1-9][0-9]{0,8}")) throw new MappingFailure("DATA_SHAPE_ERROR");
        int index=Integer.parseInt(token);
        if (index>=size) throw new MappingFailure("DATA_SHAPE_ERROR"); return index;
    }
    /** Carries a fixed public code, never the source data or external exception text. */
    static final class MappingFailure extends RuntimeException {
        final String code;
        MappingFailure(String code) { this.code=code; }
    }
}
