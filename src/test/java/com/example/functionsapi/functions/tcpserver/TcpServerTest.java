package com.example.functionsapi.functions.tcpserver;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class TcpServerTest {
    final ObjectMapper mapper = new ObjectMapper();
    final TcpServerManager manager = new TcpServerManager(mapper);
    @AfterEach void cleanup() { manager.close(); }
    ObjectNode startRequest() {
        return mapper.createObjectNode().put("command","start").put("port",0).put("timeoutMs",5000);
    }
    JsonNode control(String command,String id) { return manager.invoke(mapper.createObjectNode().put("command",command).put("serverId",id)); }
    JsonNode start(String rules,String commands) throws Exception {
        ObjectNode request = startRequest();
        request.set("rules",mapper.readTree(rules)); request.set("commands",mapper.readTree(commands));
        JsonNode result = manager.invoke(request);
        assertEquals("running",result.path("status").asText(),result.toString()); return result;
    }
    Socket connect(JsonNode server) throws IOException {
        Socket socket = new Socket("127.0.0.1",server.path("port").asInt()); socket.setSoTimeout(2000); return socket;
    }
    void send(Socket socket,String text) throws IOException { socket.getOutputStream().write((text+"\n").getBytes(StandardCharsets.UTF_8)); }
    String read(Socket socket) throws IOException { return new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8)).readLine(); }
    JsonNode stopped(String id) throws Exception {
        long end = System.nanoTime()+2_000_000_000L;
        JsonNode status;
        do { status = control("status",id); if (!status.path("status").asText().equals("running")) return status; Thread.sleep(5); }
        while (System.nanoTime()<end);
        fail("Server did not stop within 2 seconds"); return status;
    }
    @Test void orderedWholeLineRulesAndEchoCommand() throws Exception {
        JsonNode server = start("[{\"regex\":\"^PING$\",\"response\":\"pong\"},{\"regex\":\".*\",\"response\":\"fallback\"}]",
            "[{\"regex\":\"^ECHO.*$\",\"action\":\"echo\"}]");
        try (Socket socket = connect(server)) {
            send(socket,"PING"); assertEquals("pong",read(socket));
            send(socket,"prefixPING"); assertEquals("fallback",read(socket));
            send(socket,"ECHO hello"); assertEquals("ECHO hello",read(socket));
        }
        String id = server.path("serverId").asText();
        long deadline = System.nanoTime()+2_000_000_000L;
        JsonNode status;
        do { status = control("status",id); if (status.path("responses").asInt() == 3) break; Thread.sleep(5); }
        while (System.nanoTime() < deadline);
        assertEquals(3,status.path("messages").asInt()); assertEquals(3,status.path("responses").asInt());
    }
    @Test void shutdownCommandRepliesThenClosesListenerAndClient() throws Exception {
        JsonNode server = start("[]","[{\"regex\":\"QUIT\",\"action\":\"shutdown\",\"response\":\"bye\"}]");
        try (Socket socket = connect(server)) {
            send(socket,"QUIT"); assertEquals("bye",read(socket)); assertNull(read(socket));
        }
        JsonNode status = stopped(server.path("serverId").asText()); assertEquals("COMMAND_SHUTDOWN",status.path("reason").asText());
        assertThrows(IOException.class,()->connect(server));
    }
    @Test void manualShutdownIsIdempotentAndUnblocksIdleClient() throws Exception {
        JsonNode server = start("[]","[]"); String id = server.path("serverId").asText();
        try (Socket socket = connect(server)) {
            assertEquals("stopped",control("shutdown",id).path("status").asText());
            // Closing before accept may produce TCP reset instead of EOF; both unblock the client.
            try { assertNull(read(socket)); }
            catch (java.net.SocketException closed) { assertTrue(closed.getMessage().toLowerCase().contains("reset")); }
            assertEquals("MANUAL_SHUTDOWN",control("shutdown",id).path("reason").asText());
        }
        assertThrows(IOException.class,()->connect(server));
    }
    @Test void lifetimeExpiresWithoutClients() throws Exception {
        ObjectNode request = startRequest().put("timeoutMs",50); JsonNode server = manager.invoke(request);
        assertEquals("TOTAL_TIMEOUT",stopped(server.path("serverId").asText()).path("reason").asText());
        assertThrows(IOException.class,()->connect(server));
    }
    @Test void lifetimeExpiresEvenWithIdleClient() throws Exception {
        JsonNode server = manager.invoke(startRequest().put("timeoutMs",200).put("idleTimeoutMs",1000));
        try (Socket socket = connect(server)) { assertNull(read(socket)); }
        assertEquals("TOTAL_TIMEOUT",stopped(server.path("serverId").asText()).path("reason").asText());
    }
    @Test void idleClientClosesAndNextConnectionWorks() throws Exception {
        ObjectNode request = startRequest().put("idleTimeoutMs",50);
        request.set("rules",mapper.readTree("[{\"regex\":\"PING\",\"response\":\"pong\"}]"));
        JsonNode server = manager.invoke(request);
        try (Socket socket = connect(server)) { assertNull(read(socket)); }
        try (Socket socket = connect(server)) { send(socket,"PING"); assertEquals("pong",read(socket)); }
    }
    @Test void malformedAndOversizedFramesCloseOnlyThatClient() throws Exception {
        JsonNode server = start("[]","[{\"regex\":\".*\",\"action\":\"echo\"}]");
        try (Socket socket = connect(server)) { socket.getOutputStream().write(new byte[]{(byte)255,10}); assertNull(read(socket)); }
        try (Socket socket = connect(server)) { socket.getOutputStream().write(new byte[8193]); assertNull(read(socket)); }
        try (Socket socket = connect(server)) { send(socket,"ok"); assertEquals("ok",read(socket)); }
        assertEquals("running",control("status",server.path("serverId").asText()).path("status").asText());
    }
    @Test void messageLimitStopsServer() throws Exception {
        ObjectNode request = startRequest().put("maxMessages",1);
        request.set("rules",mapper.readTree("[{\"regex\":\"PING\",\"response\":\"pong\"}]"));
        JsonNode server = manager.invoke(request);
        try (Socket socket = connect(server)) { send(socket,"PING"); assertEquals("pong",read(socket)); }
        assertEquals("MESSAGE_LIMIT",stopped(server.path("serverId").asText()).path("reason").asText());
    }
    @Test void connectionLimitStopsServer() throws Exception {
        JsonNode server = manager.invoke(startRequest().put("maxConnections",1));
        try (Socket ignored = connect(server)) { }
        assertEquals("CONNECTION_LIMIT",stopped(server.path("serverId").asText()).path("reason").asText());
    }
    @Test void portConflictAndUnknownIdsHaveSafeErrors() throws Exception {
        JsonNode server = start("[]","[]");
        assertEquals("PORT_UNAVAILABLE",manager.invoke(startRequest().put("port",server.path("port").asInt())).path("code").asText());
        assertEquals("SERVER_NOT_FOUND",control("shutdown","missing").path("code").asText());
    }
    @Test void invalidRequestsNeverStartServers() throws Exception {
        for (String invalid : new String[]{"null","{}","{\"command\":\"start\",\"timeoutMs\":60001}",
            "{\"command\":\"start\",\"host\":\"0.0.0.0\"}",
            "{\"command\":\"start\",\"rules\":[{\"regex\":\"[\",\"response\":\"x\"}]}",
            "{\"command\":\"start\",\"commands\":[{\"regex\":\"x\",\"action\":\"delete\"}]}",
            "{\"command\":\"start\",\"rules\":[{\"regex\":\"x\",\"response\":\"a\\nb\"}]}"}) {
            assertEquals("INVALID_REQUEST",manager.invoke(mapper.readTree(invalid)).path("code").asText(),invalid);
        }
    }
    @Test void capacityAndApplicationCleanupAreBounded() throws Exception {
        JsonNode[] servers = new JsonNode[16];
        for (int i=0;i<16;i++) { servers[i]=manager.invoke(startRequest()); assertEquals("running",servers[i].path("status").asText()); }
        assertEquals("BUSY",manager.invoke(startRequest()).path("code").asText());
        manager.close();
        for (JsonNode server : servers) assertThrows(IOException.class,()->connect(server));
        assertEquals("SERVER_MANAGER_CLOSED",manager.invoke(startRequest()).path("code").asText());
    }
}
