package com.example.functionsapi.functions.transformjsondata;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransformHttpTest {
    @Autowired TestRestTemplate http;
    @Test void bindsNativeDataAndReturnsTransformedObject() {
        HttpHeaders headers=new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        String body="{\"data\":{\"hello\":1},\"steps\":[{\"type\":\"set\",\"path\":\"/added\",\"value\":null}]}";
        var response=http.postForEntity("/transformJsonData",new HttpEntity<>(body,headers),JsonNode.class);
        assertEquals(HttpStatus.OK,response.getStatusCode()); assertTrue(response.getBody().isObject());
        assertEquals("transformed",response.getBody().path("status").asText());
        assertTrue(response.getBody().at("/data/added").isNull());
    }
    @Test void recipeOrderExampleUsesNativeHttpJsonContract() throws Exception {
        HttpHeaders headers=new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        String body=java.nio.file.Files.readString(java.nio.file.Path.of("examples/transform-recipe-order.json"));
        var response=http.postForEntity("/transformJsonData",new HttpEntity<>(body,headers),JsonNode.class);
        assertEquals(HttpStatus.OK,response.getStatusCode());
        JsonNode expected=new com.fasterxml.jackson.databind.ObjectMapper().readTree(java.nio.file.Files.readString(
            java.nio.file.Path.of("examples/transform-recipe-order-response.json")));
        assertEquals(expected,response.getBody());
    }

}
