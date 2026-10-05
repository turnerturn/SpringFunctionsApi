package com.example.functionsapi.functions.exchangetcpconversation;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConversationHttpTest {
    @Autowired TestRestTemplate http;
    @Test void nativeEndpointBindsAndReturnsAnObject() throws Exception {
        ConversationRunnerTest helper = new ConversationRunnerTest();
        try (ConversationRunnerTest.Peer peer = new ConversationRunnerTest.Peer(socket -> {
            assertEquals("PING", new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream())).readLine());
            socket.getOutputStream().write("pong\n".getBytes(StandardCharsets.UTF_8));
        })) {
            HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
            var response = http.postForEntity("/exchangeTcpConversation",
                new HttpEntity<>(helper.request(peer.port(), ConversationRunnerTest.PING).toString(), headers), JsonNode.class);
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertTrue(response.getBody().isObject(), response.toString());
            assertEquals("success", response.getBody().path("terminalStep").asText());
            var invalid = http.postForEntity("/exchangeTcpConversation", new HttpEntity<>("{}", headers), JsonNode.class);
            assertEquals("INVALID_REQUEST", invalid.getBody().path("code").asText());
            assertEquals(HttpStatus.BAD_REQUEST, http.postForEntity("/exchangeTcpConversation", new HttpEntity<>("{", headers), String.class).getStatusCode());
        }
    }
}
