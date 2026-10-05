package com.example.functionsapi.functions.tcpserver;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class TcpServerHttpTest {
    @Autowired TestRestTemplate http;
    JsonNode invoke(String function,String body) {
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        var response = http.postForEntity("/"+function,new HttpEntity<>(body,headers),JsonNode.class);
        assertEquals(HttpStatus.OK,response.getStatusCode()); assertTrue(response.getBody().isObject()); return response.getBody();
    }
    @Test void serverAndConversationInteractThroughNativeHttp() {
        JsonNode server = invoke("tcpServer","{\"command\":\"start\",\"port\":0,\"rules\":[{\"regex\":\"PING\",\"response\":\"pong\"}]}");
        String id = server.path("serverId").asText();
        try {
            assertEquals("running",server.path("status").asText());
            JsonNode result = invoke("exchangeTcpConversation","""
                {"host":"localhost","port":%d,"steps":[
                {"id":"send","action":"send","text":"PING"},
                {"id":"read","action":"wait"},
                {"id":"branch","action":"fork","when":[{"regex":"pong","goto":"yes"}],"otherwise":"no"},
                {"id":"yes","action":"end"},{"id":"no","action":"end"}]}
                """.formatted(server.path("port").asInt()));
            assertEquals("yes",result.path("terminalStep").asText(),result.toString());
            assertEquals("pong",result.path("lastInput").asText());
        } finally {
            assertEquals("stopped",invoke("tcpServer","{\"command\":\"shutdown\",\"serverId\":\""+id+"\"}").path("status").asText());
        }
    }
    @Test void documentedAcculoadPcExamplesWorkTogether() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var start = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(
            new java.io.File("examples/tcp-server-accuload-pc.json"));
        start.put("port",0);
        JsonNode server = invoke("tcpServer",start.toString());
        assertEquals("running",server.path("status").asText(),server.toString());
        String id = server.path("serverId").asText();
        try {
            var conversation = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(
                new java.io.File("examples/tcp-accuload-pc.json"));
            conversation.put("port",server.path("port").asInt());
            JsonNode result = invoke("exchangeTcpConversation",conversation.toString());
            assertEquals("success",result.path("terminalStep").asText(),result.toString());
            assertEquals("13PV 01 001 1 Load Arm 1",result.path("lastInput").asText());
            assertEquals(10,result.path("executedSteps").asInt());
        } finally {
            invoke("tcpServer",mapper.createObjectNode().put("command","shutdown").put("serverId",id).toString());
        }
    }
    @Test void recipe01ExamplesRunAllCommandsAndReadbacksThroughHttp() throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var start=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(
            new java.io.File("examples/accuload-recipe-01-server.json"));
        var conversation=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(
            new java.io.File("examples/accuload-recipe-01-client.json"));
        assertEquals(start.path("port"),conversation.path("port"));
        // Exercise the real files and validation, using a disposable port to avoid local conflicts.
        start.put("port",0);
        JsonNode server=invoke("tcpServer",start.toString());
        assertEquals("running",server.path("status").asText(),server.toString());
        String id=server.path("serverId").asText();
        try {
            conversation.put("port",server.path("port").asInt());
            JsonNode result=invoke("exchangeTcpConversation",conversation.toString());
            assertEquals("completed",result.path("status").asText(),result.toString());
            assertEquals("success",result.path("terminalStep").asText(),result.toString());
            assertEquals(130,result.path("executedSteps").asInt());
            assertEquals("13PV AR 230 0 Clean Line Blend Adjust",result.path("lastInput").asText());
            JsonNode status=invoke("tcpServer",mapper.createObjectNode().put("command","status").put("serverId",id).toString());
            assertEquals(43,status.path("messages").asInt());
            assertEquals(43,status.path("responses").asInt());
        } finally {
            invoke("tcpServer",mapper.createObjectNode().put("command","shutdown").put("serverId",id).toString());
        }
    }

}
