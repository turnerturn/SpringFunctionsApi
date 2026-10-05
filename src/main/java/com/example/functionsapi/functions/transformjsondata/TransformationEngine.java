package com.example.functionsapi.functions.transformjsondata;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.example.functionsapi.common.json.JsonChecks;
import java.net.URI;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.slf4j.*;

/** Ordered, bounded JSON pipeline with reusable API references and strict lookup semantics. */
final class TransformationEngine {
    private static final Logger log=LoggerFactory.getLogger(TransformationEngine.class);
    private final ObjectMapper mapper;
    private final ReferenceClient client;
    private final Semaphore slots=new Semaphore(16);
    /** Initializes owned dependencies and bounded resources. */
    TransformationEngine(ObjectMapper mapper,ReferenceClient client) { this.mapper=mapper; this.client=client; }

    /** Validates every step before fetching, transforms a copy, and never returns partial data on failure. */
    JsonNode transform(JsonNode request) {
        if (!slots.tryAcquire()) return error("BUSY",-1);
        int stepIndex=-1;
        try {
            Map<Integer,URI> urls;
            try { urls=validate(request); }
            catch (Exception e) { return error("INVALID_REQUEST",-1); }
            long deadline=System.nanoTime()+JsonChecks.integer(request,"totalTimeoutMs",10000,1,30000)*1_000_000L;
            JsonNode data=request.get("data").deepCopy();
            Map<String,JsonNode> references=new HashMap<>();
            log.debug("Transformation validated steps={}",request.get("steps").size());
            for (JsonNode step : request.get("steps")) {
                stepIndex++; check(deadline);
                String type=step.path("type").asText();
                log.trace("Transformation step index={} type={}",stepIndex,type);
                switch (type) {
                    case "reference" -> references.put(step.path("id").asText(),step.get("value").deepCopy());
                    case "fetch" -> references.put(step.path("id").asText(),client.fetch(urls.get(stepIndex),
                        step.path("format").asText("json"),JsonChecks.integer(step,"timeoutMs",3000,1,10000),deadline));
                    case "set" -> data=JsonMapping.set(data,step.path("path").asText(),step.get("value"));
                    case "copy" -> data=JsonMapping.set(data,step.path("path").asText(),JsonMapping.get(data,step.path("from").asText()));
                    case "remove" -> data=JsonMapping.remove(data,step.path("path").asText());
                    case "bind" -> data=JsonMapping.set(data,step.path("path").asText(),JsonMapping.get(
                        references.get(step.path("reference").asText()),step.path("from").asText()));
                    case "merge" -> {
                        JsonNode source=step.has("reference")?references.get(step.get("reference").asText()):data;
                        data=JsonMapping.set(data,step.path("path").asText(),JsonMapping.merge(
                            JsonMapping.get(data,step.path("path").asText()),JsonMapping.get(source,step.path("from").asText()),0));
                    }
                    case "template" -> data=JsonMapping.template(step.get("template"),bindings(step.get("bindings"),data,references));
                    case "map" -> {
                        JsonNode items=JsonMapping.get(data,step.path("items").asText());
                        if (!items.isArray() || items.size()>4096) throw new JsonMapping.MappingFailure("DATA_SHAPE_ERROR");
                        ArrayNode mapped=mapper.createArrayNode();
                        // Check accumulated output during expansion, rather than after constructing the whole array.
                        for (JsonNode item : items) {
                            check(deadline);
                            mapped.add(JsonMapping.template(step.get("template"),bindings(step.get("bindings"),item,references)));
                            limit(mapped);
                        }
                        data=JsonMapping.set(data,step.path("items").asText(),mapped);
                    }
                    case "decorate" -> decorate(step,data,references,deadline);
                    default -> throw new IllegalStateException();
                }
                limit(data); check(deadline);
            }
            return mapper.createObjectNode().put("status","transformed").put("executedSteps",stepIndex+1).set("data",data);
        } catch (JsonMapping.MappingFailure e) { return error(e.code,stepIndex); }
        catch (IllegalArgumentException e) { return error("DATA_LIMIT",stepIndex); }
        catch (Exception e) { return error("TRANSFORM_ERROR",stepIndex); }
        finally { slots.release(); }
    }
    /** Checks syntax, reference ordering, template variables, and all API URLs before execution. */
    private Map<Integer,URI> validate(JsonNode request) throws Exception {
        JsonChecks.fields(request,Set.of("data","steps","totalTimeoutMs"));
        if (!request.has("data")) throw new IllegalArgumentException(); limit(request);
        JsonChecks.integer(request,"totalTimeoutMs",10000,1,30000);
        JsonNode steps=request.get("steps");
        if (steps==null || !steps.isArray() || steps.size()>32) throw new IllegalArgumentException();
        Set<String> ids=new HashSet<>(); Map<Integer,URI> urls=new HashMap<>(); int index=0,fetches=0;
        for (JsonNode step : steps) {
            String type=JsonChecks.text(step,"type",32,false);
            Set<String> allowed=switch (type) {
                case "reference" -> Set.of("type","id","value");
                case "fetch" -> Set.of("type","id","baseUrl","path","parameters","query","format","timeoutMs");
                case "decorate" -> Set.of("type","items","reference","referenceItems","itemKey","referenceKey","assign","indexBase");
                case "set" -> Set.of("type","path","value");
                case "copy" -> Set.of("type","path","from");
                case "remove" -> Set.of("type","path");
                case "bind","merge" -> Set.of("type","path","from","reference");
                case "template" -> Set.of("type","bindings","template");
                case "map" -> Set.of("type","items","bindings","template");
                default -> throw new IllegalArgumentException();
            };
            JsonChecks.fields(step,allowed);
            if (type.equals("fetch") || type.equals("reference")) {
                String id=JsonChecks.text(step,"id",64,false);
                if (!id.matches("[A-Za-z0-9_-]+") || !ids.add(id)) throw new IllegalArgumentException();
                if (type.equals("reference") && !step.has("value")) throw new IllegalArgumentException();
                if (type.equals("fetch")) {
                    if (++fetches>8) throw new IllegalArgumentException();
                    JsonChecks.text(step,"baseUrl",1024,false); JsonChecks.text(step,"path",2048,false);
                    if (step.has("format") && !Set.of("json","xml").contains(JsonChecks.text(step,"format",8,false))) throw new IllegalArgumentException();
                    JsonChecks.integer(step,"timeoutMs",3000,1,10000);
                    if (step.has("parameters")) {
                        JsonNode params=step.get("parameters");
                        if (!params.isObject() || params.size()>16) throw new IllegalArgumentException();
                        var entries=params.fields();
                        while (entries.hasNext()) { var e=entries.next(); if (!e.getKey().matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException(); pointer(e.getValue()); }
                    }
                    urls.put(index,client.resolve(step,request.get("data")));
                }
            }
            if (step.has("reference") && !ids.contains(JsonChecks.text(step,"reference",64,false))) throw new IllegalArgumentException();
            for (String name : List.of("items","path","from","referenceItems","itemKey","referenceKey"))
                if (step.has(name) && !type.equals("fetch")) pointer(step.get(name));
            if (type.equals("set") && !step.has("value")) throw new IllegalArgumentException();
            if (Set.of("set","copy","remove","merge","bind").contains(type)) requiredPointer(step,"path");
            if (Set.of("copy","merge","bind").contains(type)) requiredPointer(step,"from");
            if (type.equals("bind") && !step.has("reference")) throw new IllegalArgumentException();
            if (type.equals("decorate")) {
                for (String name : List.of("items","referenceItems","itemKey","referenceKey")) requiredPointer(step,name);
                if (!step.has("reference")) throw new IllegalArgumentException();
                JsonChecks.integer(step,"indexBase",0,0,1);
                JsonNode assign=step.get("assign");
                if (assign==null || !assign.isObject() || assign.isEmpty() || assign.size()>32) throw new IllegalArgumentException();
                var entries=assign.fields();
                while (entries.hasNext()) {
                    var e=entries.next(); JsonMapping.pointer(e.getKey()); if (e.getKey().isEmpty()) throw new IllegalArgumentException();
                    assignment(e.getValue());
                }
            }
            if (type.equals("template") || type.equals("map")) {
                if (!step.has("template")) throw new IllegalArgumentException();
                if (type.equals("map")) requiredPointer(step,"items");
                JsonNode binding=step.get("bindings");
                if (binding==null || !binding.isObject() || binding.size()>64) throw new IllegalArgumentException();
                Map<String,JsonNode> dummy=new HashMap<>();
                var entries=binding.fields();
                while (entries.hasNext()) {
                    var e=entries.next(); if (!e.getKey().matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException();
                    if (e.getValue().isTextual()) pointer(e.getValue());
                    else {
                        JsonChecks.fields(e.getValue(),Set.of("reference","path"));
                        if (!ids.contains(JsonChecks.text(e.getValue(),"reference",64,false))) throw new IllegalArgumentException();
                        requiredPointer(e.getValue(),"path");
                    }
                    dummy.put(e.getKey(),NullNode.instance);
                }
                JsonMapping.template(step.get("template"),dummy);
            }
            index++;
        }
        return urls;
    }
    /** Joins scalar product keys and writes every configured field, including explicit null misses. */
    private void decorate(JsonNode step,JsonNode data,Map<String,JsonNode> refs,long deadline) {
        JsonNode items=JsonMapping.get(data,step.path("items").asText());
        JsonNode directory=JsonMapping.get(refs.get(step.path("reference").asText()),step.path("referenceItems").asText());
        if (!items.isArray() || items.size()>4096 || !(directory.isArray() || directory.isObject()) || directory.size()>4096)
            throw new JsonMapping.MappingFailure("DATA_SHAPE_ERROR");
        List<JsonNode> records=directory.isArray()?new ArrayList<>():List.of(directory);
        if (directory.isArray()) directory.forEach(records::add);
        Map<JsonNode,Integer> matches=new HashMap<>();
        for (int i=0;i<records.size();i++) {
            check(deadline); JsonNode key=JsonMapping.get(records.get(i),step.path("referenceKey").asText());
            if (!key.isValueNode()) throw new JsonMapping.MappingFailure("DATA_SHAPE_ERROR");
            if (!key.isNull() && matches.putIfAbsent(key,i)!=null) throw new JsonMapping.MappingFailure("AMBIGUOUS_REFERENCE");
        }
        for (JsonNode item : items) {
            check(deadline); if (!item.isObject()) throw new JsonMapping.MappingFailure("DATA_SHAPE_ERROR");
            JsonNode key=JsonMapping.get(item,step.path("itemKey").asText());
            if (!key.isValueNode()) throw new JsonMapping.MappingFailure("DATA_SHAPE_ERROR");
            Integer match=matches.get(key);
            var entries=step.get("assign").fields();
            while (entries.hasNext()) {
                var e=entries.next(); JsonNode spec=e.getValue();
                String from=spec.isTextual()?spec.asText():spec.path("from").asText();
                JsonNode value=match==null?NullNode.instance:(from.equals("$index")?
                    IntNode.valueOf(match+step.path("indexBase").asInt(0)):JsonMapping.get(records.get(match),from));
                // A missing match always overwrites stale values with JSON null, independent of casting.
                if (!value.isNull() && spec.isObject()) value=cast(value,spec.path("type").asText("value"));
                JsonMapping.set(item,e.getKey(),value);
            }
        }
    }
    /** Resolves template variables from the current item/data or a named immutable reference. */
    private Map<String,JsonNode> bindings(JsonNode spec,JsonNode data,Map<String,JsonNode> refs) {
        Map<String,JsonNode> result=new HashMap<>();
        spec.fields().forEachRemaining(e -> result.put(e.getKey(),e.getValue().isTextual()?
            JsonMapping.get(data,e.getValue().asText()):JsonMapping.get(refs.get(e.getValue().path("reference").asText()),e.getValue().path("path").asText())));
        return result;
    }
    /** Validates an assignment source pointer or directory index and an optional explicit type cast. */
    private void assignment(JsonNode value) {
        String from;
        if (value.isTextual()) from=value.asText();
        else { JsonChecks.fields(value,Set.of("from","type")); from=JsonChecks.text(value,"from",1024,true);
            if (value.has("type") && !Set.of("value","string","integer","number","boolean").contains(JsonChecks.text(value,"type",16,false))) throw new IllegalArgumentException(); }
        if (!from.equals("$index")) JsonMapping.pointer(from);
    }
    /** Converts XML text or JSON scalars explicitly rather than silently coercing lookup keys. */
    private JsonNode cast(JsonNode value,String type) {
        if (type.equals("value")) return value;
        if (!value.isValueNode()) throw new JsonMapping.MappingFailure("DATA_SHAPE_ERROR");
        try {
            return switch (type) {
                case "string" -> TextNode.valueOf(value.asText());
                case "integer" -> LongNode.valueOf(Long.parseLong(value.asText()));
                case "number" -> { double v=Double.parseDouble(value.asText()); if (!Double.isFinite(v)) throw new NumberFormatException(); yield DoubleNode.valueOf(v); }
                case "boolean" -> { if (!Set.of("true","false").contains(value.asText())) throw new NumberFormatException(); yield BooleanNode.valueOf(Boolean.parseBoolean(value.asText())); }
                default -> value;
            };
        } catch (NumberFormatException e) { throw new JsonMapping.MappingFailure("DATA_SHAPE_ERROR"); }
    }
    /** Requires a pointer field, allowing the empty pointer to address the root. */
    private void requiredPointer(JsonNode node,String name) { if (!node.has(name)) throw new IllegalArgumentException(); pointer(node.get(name)); }
    /** Checks pointer values without coercion. */
    private void pointer(JsonNode node) { if (!node.isTextual()) throw new IllegalArgumentException(); JsonMapping.pointer(node.asText()); }
    /** Limits both object complexity and serialized request/output size to 1 MiB. */
    private void limit(JsonNode node) throws Exception { JsonChecks.tree(node); if (mapper.writeValueAsBytes(node).length>1048576) throw new JsonMapping.MappingFailure("DATA_LIMIT"); }
    /** Checks the monotonic overall budget and preserves interruption state. */
    private void check(long deadline) {
        if (Thread.currentThread().isInterrupted()) throw new JsonMapping.MappingFailure("INTERRUPTED");
        if (System.nanoTime()>=deadline) throw new JsonMapping.MappingFailure("TOTAL_TIMEOUT");
    }
    /** Reports only a fixed code and numeric step index, never partial transformed data. */
    private JsonNode error(String code,int index) {
        log.debug("Transformation outcome code={} stepIndex={}",code,index);
        ObjectNode out=mapper.createObjectNode().put("status","error").put("code",code);
        if (index>=0) out.put("step",index); return out;
    }
}
