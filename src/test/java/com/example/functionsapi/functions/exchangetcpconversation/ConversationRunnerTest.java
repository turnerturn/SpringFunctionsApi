package com.example.functionsapi.functions.exchangetcpconversation;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ConversationRunnerTest {
    final ObjectMapper mapper = new ObjectMapper();
    final ConversationRunner runner = new ConversationRunner(mapper);
    ObjectNode request(int port, String steps) throws Exception {
        ObjectNode request = mapper.createObjectNode().put("host", "localhost").put("port", port);
        request.set("steps", mapper.readTree(steps));
        return request;
    }
    static final String PING = """
        [{"id":"ping","action":"send","text":"PING"},
         {"id":"reply","action":"wait"},
         {"id":"route","action":"fork","when":[{"regex":"(?i)pong","goto":"success"}],"otherwise":"unexpected"},
         {"id":"success","action":"end"},{"id":"unexpected","action":"end"}]
        """;
    @Test void pingPongMatchesAndClosesConnection() throws Exception { ping("pong", "success"); }
    @Test void unexpectedReplyUsesFallback() throws Exception { ping("ERROR", "unexpected"); }
    void ping(String reply, String expected) throws Exception {
        try (Peer peer = new Peer(socket -> {
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            assertEquals("PING", reader.readLine());
            socket.getOutputStream().write((reply + "\n").getBytes(StandardCharsets.UTF_8));
            assertEquals(-1, socket.getInputStream().read());
        })) {
            JsonNode result = runner.exchange(request(peer.port(), PING));
            assertEquals("completed", result.path("status").asText(), result.toString());
            assertEquals(expected, result.path("terminalStep").asText());
            assertEquals(reply, result.path("lastInput").asText());
            assertEquals(4, result.path("executedSteps").asInt());
        }
    }
    @Test void gotoSkipsSteps() throws Exception {
        try (Peer peer = new Peer(socket -> assertEquals(-1, socket.getInputStream().read()))) {
            JsonNode result = runner.exchange(request(peer.port(), """
                [{"id":"jump","action":"goto","goto":"done"},
                 {"id":"skip","action":"send","text":"DO NOT SEND"},{"id":"done","action":"end"}]
                """));
            assertEquals("done", result.path("terminalStep").asText());
            assertEquals(2, result.path("executedSteps").asInt());
        }
    }
    @Test void readsFragmentedUtf8AndRetainsCoalescedLines() throws Exception {
        try (Peer peer = new Peer(socket -> {
            byte[] body = "héllo\r\nsecond\n".getBytes(StandardCharsets.UTF_8);
            for (byte b : body) { socket.getOutputStream().write(b); socket.getOutputStream().flush(); }
        })) {
            JsonNode result = runner.exchange(request(peer.port(), """
                [{"id":"one","action":"wait"},{"id":"two","action":"wait"},{"id":"done","action":"end"}]
                """));
            assertEquals("second", result.path("lastInput").asText(), result.toString());
        }
    }
    @Test void timeoutBranchRetainsPartialFrameForLaterWait() throws Exception {
        try (Peer peer = new Peer(socket -> {
            socket.getOutputStream().write("po".getBytes(StandardCharsets.UTF_8));
            Thread.sleep(150);
            socket.getOutputStream().write("ng\n".getBytes(StandardCharsets.UTF_8));
        })) {
            JsonNode result = runner.exchange(request(peer.port(), """
                [{"id":"first","action":"wait","timeoutMs":25,"onTimeout":"again"},
                 {"id":"again","action":"wait","timeoutMs":1000},{"id":"done","action":"end"}]
                """));
            assertEquals("pong", result.path("lastInput").asText(), result.toString());
        }
    }
    @Test void stepTimeoutClosesSocket() throws Exception {
        try (Peer peer = new Peer(socket -> assertEquals(-1, socket.getInputStream().read()))) {
            JsonNode result = runner.exchange(request(peer.port(), "[{\"id\":\"read\",\"action\":\"wait\",\"timeoutMs\":25}]"));
            assertEquals("STEP_TIMEOUT", result.path("code").asText());
        }
    }
    @Test void totalDeadlineCannotBeExtendedByStep() throws Exception {
        try (Peer peer = new Peer(socket -> assertEquals(-1, socket.getInputStream().read()))) {
            ObjectNode request = request(peer.port(), "[{\"id\":\"read\",\"action\":\"wait\",\"timeoutMs\":1000}]");
            request.put("totalTimeoutMs", 100);
            assertEquals("TOTAL_TIMEOUT", runner.exchange(request).path("code").asText());
        }
    }
    @Test void gotoCycleStopsAtExecutionLimit() throws Exception {
        try (Peer peer = new Peer(socket -> assertEquals(-1, socket.getInputStream().read()))) {
            ObjectNode request = request(peer.port(), "[{\"id\":\"loop\",\"action\":\"goto\",\"goto\":\"loop\"}]");
            request.put("maxExecutions", 4);
            JsonNode result = runner.exchange(request);
            assertEquals("EXECUTION_LIMIT", result.path("code").asText());
            assertEquals(4, result.path("executedSteps").asInt());
        }
    }
    @Test void forkWithoutWaitFailsClearly() throws Exception {
        try (Peer peer = new Peer(socket -> assertEquals(-1, socket.getInputStream().read()))) {
            assertEquals("NO_INPUT", runner.exchange(request(peer.port(), """
                [{"id":"route","action":"fork","when":[{"regex":"pong","goto":"done"}],"otherwise":"done"},
                 {"id":"done","action":"end"}]
                """)).path("code").asText());
        }
    }
    @Test void peerClosureIsNotTimeout() throws Exception {
        try (Peer peer = new Peer(socket -> { })) {
            assertEquals("PEER_CLOSED", runner.exchange(request(peer.port(), "[{\"id\":\"read\",\"action\":\"wait\"}]")).path("code").asText());
        }
    }
    @Test void partialEofFailsClearly() throws Exception {
        try (Peer peer = new Peer(socket -> socket.getOutputStream().write('x'))) {
            assertEquals("INCOMPLETE_FRAME", runner.exchange(request(peer.port(), "[{\"id\":\"read\",\"action\":\"wait\"}]")).path("code").asText());
        }
    }
    @Test void oversizedFrameIsBounded() throws Exception {
        try (Peer peer = new Peer(socket -> socket.getOutputStream().write(new byte[8193]))) {
            assertEquals("FRAME_LIMIT", runner.exchange(request(peer.port(), "[{\"id\":\"read\",\"action\":\"wait\"}]")).path("code").asText());
        }
    }
    @Test void invalidUtf8IsRejected() throws Exception {
        try (Peer peer = new Peer(socket -> socket.getOutputStream().write(new byte[]{(byte)0xff,10}))) {
            assertEquals("INVALID_UTF8", runner.exchange(request(peer.port(), "[{\"id\":\"read\",\"action\":\"wait\"}]")).path("code").asText());
        }
    }
    @Test void invalidDefinitionsFailBeforeConnection() throws Exception {
        for (String steps : new String[]{
            "[]", "[{\"id\":\"x\",\"action\":\"goto\",\"goto\":\"missing\"}]",
            "[{\"id\":\"x\",\"action\":\"end\"},{\"id\":\"x\",\"action\":\"end\"}]",
            "[{\"id\":\"x\",\"action\":\"send\",\"text\":\"a\\nb\"}]",
            "[{\"id\":\"x\",\"action\":\"wait\",\"timeoutMs\":0}]",
            "[{\"id\":\"x\",\"action\":\"fork\",\"when\":[{\"regex\":\"[\",\"goto\":\"x\"}],\"otherwise\":\"x\"}]",
            "[{\"id\":\"x\",\"action\":\"fork\",\"when\":[{\"regex\":\"(a)\\\\1\",\"goto\":\"x\"}],\"otherwise\":\"x\"}]"
        }) assertEquals("INVALID_REQUEST", runner.exchange(request(1, steps)).path("code").asText(), steps);
        ObjectNode r = request(1, "[{\"id\":\"x\",\"action\":\"end\"}]");
        r.put("unknown", true); assertEquals("INVALID_REQUEST", runner.exchange(r).path("code").asText());
    }
    @Test void sentEffectsAreReportedOnFailure() throws Exception {
        try (Peer peer = new Peer(socket -> {
            assertEquals("PING", new BufferedReader(new InputStreamReader(socket.getInputStream())).readLine());
        })) {
            JsonNode result = runner.exchange(request(peer.port(), PING));
            assertEquals("PEER_CLOSED", result.path("code").asText());
            assertTrue(result.path("effectsMayHaveOccurred").asBoolean());
        }
    }
    @Test void exampleValidates() throws Exception {
        ConversationDefinition.parse(mapper.readTree(new File("examples/tcp-ping-pong.json")));
    }
    @FunctionalInterface interface Handler { void run(Socket socket) throws Exception; }
    static final class Peer implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        final Future<?> task;
        Peer(Handler handler) throws IOException {
            server.setSoTimeout(3000);
            task = executor.submit(() -> {
                try (Socket socket = server.accept()) { socket.setSoTimeout(3000); handler.run(socket); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
        }
        int port() { return server.getLocalPort(); }
        @Override public void close() throws Exception {
            try { task.get(4, TimeUnit.SECONDS); }
            finally { server.close(); executor.shutdownNow(); }
        }
    }
}
