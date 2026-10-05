package com.example.functionsapi.functions.transformjsondata;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class TransformationEngineTest {
    final ObjectMapper mapper=new ObjectMapper();
    TransformationEngine engine(String origins) { return new TransformationEngine(mapper,new ReferenceClient(mapper,origins)); }
    JsonNode run(String json) throws Exception { return engine("").transform(mapper.readTree(json)); }
    @Test void decoratesMatchesAndOverwritesMissesWithExplicitNullWithoutMutatingInput() throws Exception {
        JsonNode request=mapper.readTree("""
            {"data":{"products":[{"productCode":"P1"},{"productCode":"missing","acculoadProductDirectoryPosition":99}]},"steps":[
            {"type":"reference","id":"directory","value":{"products":[{"productCode":"P1","position":3}]}},
            {"type":"decorate","items":"/products","reference":"directory","referenceItems":"/products",
             "itemKey":"/productCode","referenceKey":"/productCode","assign":{"/acculoadProductDirectoryPosition":"/position"}}]}
            """);
        JsonNode result=engine("").transform(request);
        assertEquals("transformed",result.path("status").asText(),result.toString());
        assertEquals(3,result.at("/data/products/0/acculoadProductDirectoryPosition").asInt());
        assertTrue(result.at("/data/products/1/acculoadProductDirectoryPosition").isNull());
        assertEquals(99,request.at("/data/products/1/acculoadProductDirectoryPosition").asInt());
    }
    @Test void templateAndMapPreserveTypedBindings() throws Exception {
        JsonNode result=run("""
            {"data":{"products":[{"productCode":"A","amount":2}]},"steps":[
            {"type":"map","items":"/products","bindings":{"code":"/productCode","quantity":"/amount"},
             "template":{"code":"<code>","quantity":"<quantity>"}},
            {"type":"template","bindings":{"items":"/products","missing":"/absent"},
             "template":{"result":"<items>","missing":"<missing>"}}]}
            """);
        assertEquals(2,result.at("/data/result/0/quantity").asInt()); assertTrue(result.at("/data/missing").isNull());
    }
    @Test void bindMergeCopySetAndRemoveCompose() throws Exception {
        JsonNode result=run("""
            {"data":{"metadata":{"keep":1,"nested":{"old":true}},"delete":1},"steps":[
            {"type":"reference","id":"api","value":{"metadata":{"nested":{"new":true}},"code":7}},
            {"type":"merge","path":"/metadata","reference":"api","from":"/metadata"},
            {"type":"bind","path":"/referenceCode","reference":"api","from":"/code"},
            {"type":"copy","path":"/copied","from":"/referenceCode"},
            {"type":"set","path":"/new/field","value":null},{"type":"remove","path":"/delete"}]}
            """);
        assertEquals("transformed",result.path("status").asText(),result.toString());
        assertTrue(result.at("/data/metadata/nested/old").asBoolean()); assertTrue(result.at("/data/metadata/nested/new").asBoolean());
        assertEquals(7,result.at("/data/copied").asInt()); assertTrue(result.at("/data/new/field").isNull()); assertTrue(result.at("/data/delete").isMissingNode());
    }
    @Test void duplicateReferenceKeysFailRatherThanPickingArbitrarily() throws Exception {
        JsonNode result=run("""
            {"data":{"products":[{"productCode":"A"}]},"steps":[
            {"type":"reference","id":"r","value":[{"code":"A"},{"code":"A"}]},
            {"type":"decorate","items":"/products","reference":"r","referenceItems":"",
             "itemKey":"/productCode","referenceKey":"/code","assign":{"/position":"$index"}}]}
            """);
        assertEquals("AMBIGUOUS_REFERENCE",result.path("code").asText()); assertFalse(result.has("data"));
    }
    @Test void invalidLateStepPreventsAllFetches() throws Exception {
        try (Api api=new Api("{}",200,0)) {
            ObjectNode request=mapper.createObjectNode(); request.set("data",mapper.createObjectNode());
            request.set("steps",mapper.readTree("[{\"type\":\"fetch\",\"id\":\"r\",\"baseUrl\":\""+api.origin()+"\",\"path\":\"/api\"},{\"type\":\"executeScript\"}]"));
            assertEquals("INVALID_REQUEST",engine(api.origin()).transform(request).path("code").asText()); assertEquals(0,api.calls.get());
        }
    }
    @Test void fetchesJsonUsingEncodedPathParameters() throws Exception {
        try (Api api=new Api("{\"products\":[{\"productCode\":\"A\",\"position\":2}]}",200,0)) {
            ObjectNode request=fetchRequest(api,"json");
            JsonNode result=engine(api.origin()).transform(request);
            assertEquals(2,result.at("/data/products/0/position").asInt(),result.toString());
            assertEquals("/api/acculoads/10.0.0.1/productDirectory",api.path);
        }
    }
    @Test void parsesXmlAndCastsDirectoryPositionExplicitly() throws Exception {
        try (Api api=new Api("<directory><product code=\"A\"><position>2</position></product><product code=\"B\"><position>3</position></product></directory>",200,0)) {
            ObjectNode request=fetchRequest(api,"xml");
            ObjectNode decorate=(ObjectNode)request.get("steps").get(1);
            decorate.put("referenceItems","/directory/product").put("referenceKey","/@code");
            decorate.set("assign",mapper.readTree("{\"/position\":{\"from\":\"/position\",\"type\":\"integer\"}}"));
            JsonNode result=engine(api.origin()).transform(request);
            assertTrue(result.at("/data/products/0/position").isIntegralNumber(),result.toString());
            assertEquals(2,result.at("/data/products/0/position").asInt());
        }
    }
    @Test void disallowsDtdAndExternalEntities() throws Exception {
        try (Api api=new Api("<!DOCTYPE directory [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]><directory>&secret;</directory>",200,0)) {
            JsonNode result=engine(api.origin()).transform(fetchRequest(api,"xml"));
            assertEquals("REFERENCE_PARSE_ERROR",result.path("code").asText(),result.toString());
            assertFalse(result.toString().contains("root:"));
        }
    }
    @Test void deniesUnknownOriginsAndDoesNotFollowRedirects() throws Exception {
        try (Api api=new Api("ignored",302,0)) {
            assertEquals("INVALID_REQUEST",engine("").transform(fetchRequest(api,"json")).path("code").asText());
            assertEquals(0,api.calls.get());
            assertEquals("REFERENCE_HTTP_ERROR",engine(api.origin()).transform(fetchRequest(api,"json")).path("code").asText());
            assertEquals(1,api.calls.get());
        }
    }
    @Test void enforcesBodyTimeoutAndSizeAndParseErrors() throws Exception {
        try (Api api=new Api("{}",200,300)) {
            ObjectNode request=fetchRequest(api,"json"); ((ObjectNode)request.get("steps").get(0)).put("timeoutMs",50);
            assertEquals("REFERENCE_TIMEOUT",engine(api.origin()).transform(request).path("code").asText());
        }
        try (Api api=new Api("x".repeat(1048577),200,0)) {
            assertEquals("REFERENCE_SIZE_LIMIT",engine(api.origin()).transform(fetchRequest(api,"json")).path("code").asText());
        }
        try (Api api=new Api("{",200,0)) {
            assertEquals("REFERENCE_PARSE_ERROR",engine(api.origin()).transform(fetchRequest(api,"json")).path("code").asText());
        }
    }
    @Test void validatesTemplatesAndPointers() throws Exception {
        assertEquals("INVALID_REQUEST",run("{\"data\":{},\"steps\":[{\"type\":\"set\",\"path\":\"/bad~2\",\"value\":1}]}").path("code").asText());
        assertEquals("INVALID_REQUEST",run("{\"data\":{},\"steps\":[{\"type\":\"template\",\"bindings\":{},\"template\":\"<missing>\"}]}").path("code").asText());
    }
    @Test void boundsExpandedBindingsBeforeCreatingLargeOutput() throws Exception {
        ObjectNode request=mapper.createObjectNode();
        request.putObject("data").put("large","x".repeat(100000));
        ObjectNode step=request.putArray("steps").addObject().put("type","template");
        step.putObject("bindings").put("large","/large");
        step.putArray("template").add("<large>").add("<large>");
        JsonNode result=engine("").transform(request);
        assertEquals("DATA_LIMIT",result.path("code").asText());
        assertFalse(result.has("data"));
    }
    @Test void committedInlineExamplesExecuteWithExplicitNullMisses() throws Exception {
        JsonNode request=mapper.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("examples/transform-products.json")));
        JsonNode result=engine("").transform(request);
        assertEquals(3,result.at("/data/products/0/acculoadProductDirectoryPosition").asInt());
        assertTrue(result.at("/data/products/1/acculoadProductDirectoryPosition").isNull());
        request=mapper.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("examples/transform-map-merge-bind.json")));
        result=engine("").transform(request);
        assertTrue(result.at("/data/enabled").asBoolean());
        assertEquals("Terminal A",result.at("/data/site").asText());
        assertEquals("P001",result.at("/data/products/0/code").asText());
    }

    @Test void recipeOrderExampleEnrichesByNameAndPreservesOrderFields() throws Exception {
        ObjectNode request=(ObjectNode)mapper.readTree(java.nio.file.Files.readString(
            java.nio.file.Path.of("examples/transform-recipe-order.json")));
        JsonNode original=request.deepCopy();
        JsonNode result=engine("").transform(request);
        JsonNode expected=mapper.readTree(java.nio.file.Files.readString(
            java.nio.file.Path.of("examples/transform-recipe-order-response.json")));
        assertEquals(expected,result);
        assertEquals(original,request);
        request.withObject("/data/recipe").put("name","Unconfigured Recipe");
        result=engine("").transform(request);
        assertTrue(result.at("/data/recipe/ingredients").isNull());
        assertEquals("Bake when collected",result.at("/data/recipe/notes").asText());
    }
    @Test void recipeApiExampleFetchesAllRecipesWithEncodedSelection() throws Exception {
        ObjectNode offline=(ObjectNode)mapper.readTree(java.nio.file.Files.readString(
            java.nio.file.Path.of("examples/transform-recipe-order.json")));
        try (Api api=new Api(offline.at("/steps/0/value").toString(),200,0)) {
            ObjectNode request=(ObjectNode)mapper.readTree(java.nio.file.Files.readString(
                java.nio.file.Path.of("examples/transform-recipe-order-api.json")));
            ((ObjectNode)request.get("steps").get(0)).put("baseUrl",api.origin());
            JsonNode result=engine(api.origin()).transform(request);
            assertEquals("transformed",result.path("status").asText(),result.toString());
            assertEquals("Pizza dough",result.at("/data/recipe/ingredients/0").asText());
            assertEquals("/recipes",api.path);
            assertEquals("limit=0&select=name%2Cingredients",api.query);
        }
    }
    @Test void dnsOriginAndQueryPolicyAreValidatedBeforeFetching() throws Exception {
        ObjectNode request=(ObjectNode)mapper.readTree(java.nio.file.Files.readString(
            java.nio.file.Path.of("examples/transform-recipe-order-api.json")));
        ReferenceClient client=new ReferenceClient(mapper,"https://dummyjson.com");
        assertEquals("https://dummyjson.com:443/recipes?limit=0&select=name%2Cingredients",
            client.resolve(request.get("steps").get(0),request.get("data")).toString());
        assertEquals("INVALID_REQUEST",engine("").transform(request).path("code").asText());
        ((ObjectNode)request.get("steps").get(0)).put("baseUrl","https://dummyjson.com@untrusted.example");
        assertEquals("INVALID_REQUEST",engine("https://dummyjson.com").transform(request).path("code").asText());
    }
    @Test void callerDeadlineIncludesStalledDnsOrConnectionWork() {
        java.util.concurrent.CountDownLatch started=new java.util.concurrent.CountDownLatch(1);
        ReferenceClient stalled=new ReferenceClient(mapper,"https://dummyjson.com") {
            @Override JsonNode fetchBlocking(URI uri,String format,long end) {
                started.countDown();
                try { Thread.sleep(5000); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return mapper.createObjectNode();
            }
        };
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(1),() -> {
            var failure=assertThrows(JsonMapping.MappingFailure.class,
                () -> stalled.fetch(URI.create("https://dummyjson.com/recipes"),"json",50,System.nanoTime()+5_000_000_000L));
            assertEquals("REFERENCE_TIMEOUT",failure.code);
            assertEquals(0,started.getCount());
        });
    }
    ObjectNode fetchRequest(Api api,String format) throws Exception {
        return (ObjectNode)mapper.readTree("""
            {"data":{"ip":"10.0.0.1","products":[{"productCode":"A"}]},"steps":[
            {"type":"fetch","id":"directory","baseUrl":"%s","path":"/api/acculoads/{ip}/productDirectory","parameters":{"ip":"/ip"},"format":"%s"},
            {"type":"decorate","items":"/products","reference":"directory","referenceItems":"/products","itemKey":"/productCode","referenceKey":"/productCode","assign":{"/position":"/position"}}]}
            """.formatted(api.origin(),format));
    }
    static final class Api implements AutoCloseable {
        final HttpServer server; final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();
        final java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger(); volatile String path; volatile String query;
        Api(String body,int code,int delay) throws Exception {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); server.setExecutor(executor);
            server.createContext("/",exchange -> {
                calls.incrementAndGet(); path=exchange.getRequestURI().getPath(); query=exchange.getRequestURI().getRawQuery();
                try {
                    if (delay>0) Thread.sleep(delay); byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
                    if (code==302) exchange.getResponseHeaders().set("Location","http://127.0.0.1:1/");
                    exchange.sendResponseHeaders(code,bytes.length); exchange.getResponseBody().write(bytes);
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            }); server.start();
        }
        String origin() { return "http://127.0.0.1:"+server.getAddress().getPort(); }
        public void close() { server.stop(0); executor.shutdownNow(); }
    }
}
