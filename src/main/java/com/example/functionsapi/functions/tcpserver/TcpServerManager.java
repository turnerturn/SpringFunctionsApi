package com.example.functionsapi.functions.tcpserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.BindException;
import java.util.*;
import java.util.concurrent.*;

/** Owns process-local mock listeners, bounded workers, and lifecycle snapshots. */
public final class TcpServerManager implements AutoCloseable {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(TcpServerManager.class);
    private final ObjectMapper mapper;
    private final Map<String, MockTcpServer> servers = new LinkedHashMap<>();
    private final ExecutorService executor = new ThreadPoolExecutor(16,16,0,TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(16),Thread.ofPlatform().daemon().name("tcp-mock-",0).factory(),
        new ThreadPoolExecutor.AbortPolicy());
    private boolean closed;
    /** Initializes owned dependencies and bounded resources. */
    public TcpServerManager(ObjectMapper mapper) { this.mapper = mapper; }

    /** Dispatches a validated start, status, or shutdown operation. */
    public synchronized JsonNode invoke(JsonNode request) {
        try {
            String command = ServerDefinition.text(request,"command",16);
            if (command.equals("start")) {
                ServerDefinition definition = ServerDefinition.parse(request);
                if (closed) return error("SERVER_MANAGER_CLOSED");
                if (servers.values().stream().filter(MockTcpServer::running).count() >= 16) return error("BUSY");
                // Retain at most 64 active/recent server records; completed IDs may expire.
                if (servers.size() >= 64) {
                    String oldest = servers.entrySet().stream().filter(e -> !e.getValue().running()).findFirst().orElseThrow().getKey();
                    servers.remove(oldest);
                }
                String id = UUID.randomUUID().toString();
                MockTcpServer server = new MockTcpServer(id, definition);
                try { executor.submit(server); }
                catch (RejectedExecutionException e) { server.stop("APPLICATION_SHUTDOWN"); return error("BUSY"); }
                servers.put(id, server);
                return snapshot(server);
            }
            if (!Set.of("status","shutdown").contains(command)) return error("INVALID_REQUEST");
            ServerDefinition.fields(request, Set.of("command","serverId"));
            String id = ServerDefinition.text(request,"serverId",64);
            MockTcpServer server = servers.get(id);
            if (server == null) return error("SERVER_NOT_FOUND");
            if (command.equals("shutdown")) server.stop("MANUAL_SHUTDOWN");
            return snapshot(server);
        } catch (IllegalArgumentException | NullPointerException e) { return error("INVALID_REQUEST"); }
        catch (BindException e) { return error("PORT_UNAVAILABLE"); }
        catch (IOException e) { return error("TCP_ERROR"); }
    }
    /** Returns lifecycle counters without rules or protocol data. */
    private ObjectNode snapshot(MockTcpServer s) {
        ObjectNode out = mapper.createObjectNode().put("status",s.running() ? "running" : "stopped")
            .put("serverId",s.id).put("host","127.0.0.1").put("port",s.port)
            .put("connections",s.connections.get()).put("messages",s.messages.get())
            .put("responses",s.responses.get()).put("clientErrors",s.errors.get());
        if (s.reason != null) out.put("reason",s.reason);
        if (s.lastError != null) out.put("lastError",s.lastError);
        return out;
    }
    /** Returns a fixed error code without exposing exception messages or payloads. */
    private ObjectNode error(String code) { log.debug("Mock TCP control outcome code={}",code); return mapper.createObjectNode().put("status","error").put("code",code); }
    /** Closes all owned resources, including application teardown paths. */
    @Override public synchronized void close() {
        closed = true;
        servers.values().forEach(s -> s.stop("APPLICATION_SHUTDOWN"));
        executor.shutdownNow();
    }
}
