package com.example.functionsapi.functions.tcpserver;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs one bounded loopback mock listener; diagnostics never contain protocol data. */
final class MockTcpServer implements Runnable {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(MockTcpServer.class);
    final String id;
    final int port;
    final AtomicInteger connections = new AtomicInteger(), messages = new AtomicInteger(),
        responses = new AtomicInteger(), errors = new AtomicInteger();
    volatile String reason, lastError;
    private volatile boolean active = true;
    private volatile boolean terminated;
    private volatile SocketChannel client;
    private final ServerDefinition definition;
    private final ServerSocketChannel listener;
    private final Selector selector;
    private final long deadline;
    private int receivedBytes, sentBytes;

    /** Initializes owned dependencies and bounded resources. */
    MockTcpServer(String id, ServerDefinition definition) throws IOException {
        this.id = id; this.definition = definition;
        deadline = System.nanoTime() + definition.timeoutMs() * 1_000_000L;
        selector = Selector.open();
        ServerSocketChannel opened = null;
        try {
            opened = ServerSocketChannel.open(); opened.configureBlocking(false);
            opened.bind(new InetSocketAddress("127.0.0.1",definition.port()),1);
            opened.register(selector,SelectionKey.OP_ACCEPT);
            port = ((InetSocketAddress) opened.getLocalAddress()).getPort(); listener = opened;
            log.info("Mock TCP started serverId={} port={} lifetimeMs={}",id,port,definition.timeoutMs());
        } catch (IOException | RuntimeException e) {
            try { if (opened != null) opened.close(); } finally { selector.close(); }
            throw e;
        }
    }
    /** Reports active status until listener and client cleanup completes. */
    boolean running() { return !terminated; }
    /** Retains the first shutdown reason and closes listener, client, and selector. */
    synchronized void stop(String why) {
        if (!active) return;
        reason = why; active = false;
        selector.wakeup();
        try { listener.close(); } catch (IOException ignored) { }
        SocketChannel socket = client;
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
        try { selector.close(); } catch (IOException ignored) { }
        terminated = true;
        log.info("Mock TCP stopped serverId={} reason={} messages={}",id,reason,messages.get());
    }
    /** Accepts bounded sequential sessions until a deadline, control, or resource limit. */
    @Override public void run() {
        try {
            while (active) {
                check();
                SocketChannel accepted = listener.accept();
                if (accepted == null) { await(listener,SelectionKey.OP_ACCEPT,deadline); continue; }
                client = accepted;
                if (!active) { accepted.close(); break; }
                connections.incrementAndGet();
                log.debug("Mock TCP accepted serverId={} connections={}",id,connections.get());
                listener.keyFor(selector).interestOps(0);
                try (accepted) {
                    accepted.configureBlocking(false);
                    serve(accepted);
                } catch (Failure e) {
                    if (e.code.equals("TOTAL_TIMEOUT") || e.code.equals("BYTE_LIMIT")) stop(e.code);
                    else if (active) { lastError = e.code; errors.incrementAndGet(); }
                } catch (IOException e) {
                    if (active) { lastError = "TCP_ERROR"; errors.incrementAndGet(); }
                } finally {
                    client = null;
                    if (active) listener.keyFor(selector).interestOps(SelectionKey.OP_ACCEPT);
                }
                if (active && connections.get() >= definition.maxConnections()) stop("CONNECTION_LIMIT");
            }
        } catch (Failure e) { stop(e.code); }
        catch (IOException | RuntimeException e) { stop("TCP_ERROR"); }
        finally {
            stop("APPLICATION_SHUTDOWN");
            try { selector.close(); } catch (IOException ignored) { }
        }
    }
    /** Processes complete strict UTF-8 lines and gives configured commands precedence. */
    private void serve(SocketChannel socket) throws IOException {
        ByteBuffer incoming = ByteBuffer.allocate(8192);
        incoming.limit(0);
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        long idleDeadline = operationDeadline();
        while (active) {
            check();
            if (System.nanoTime() >= idleDeadline) throw new Failure("IDLE_TIMEOUT");
            if (!incoming.hasRemaining()) {
                incoming.clear(); int count = socket.read(incoming); incoming.flip();
                if (count < 0) { if (line.size() > 0) throw new Failure("INCOMPLETE_FRAME"); return; }
                if (count == 0) { await(socket,SelectionKey.OP_READ,idleDeadline); continue; }
                receivedBytes += count;
                if (receivedBytes > 65536) throw new Failure("BYTE_LIMIT");
            }
            byte value = incoming.get();
            if (value != 10) {
                if (line.size() >= 8192) throw new Failure("FRAME_LIMIT");
                line.write(value); continue;
            }
            byte[] bytes = line.toByteArray(); line.reset();
            int length = bytes.length;
            if (length > 0 && bytes[length-1] == 13) length--;
            String input;
            try { input = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes,0,length)).toString(); }
            catch (CharacterCodingException e) { throw new Failure("INVALID_UTF8"); }
            messages.incrementAndGet();
            log.trace("Mock TCP line serverId={} bytes={} messages={}",id,length,messages.get());
            ServerDefinition.Rule matched = match(definition.commands(),input);
            if (matched == null) matched = match(definition.rules(),input);
            if (matched != null) {
                String response = matched.action().equals("echo") ? input : matched.response();
                if (matched.action().equals("shutdown")) {
                    try { if (response != null) send(socket,response); }
                    finally { stop("COMMAND_SHUTDOWN"); }
                    return;
                }
                if (response != null) send(socket,response);
            }
            if (messages.get() >= definition.maxMessages()) { stop("MESSAGE_LIMIT"); return; }
            idleDeadline = operationDeadline();
        }
    }
    private ServerDefinition.Rule match(java.util.List<ServerDefinition.Rule> rules,String input) throws Failure {
        for (ServerDefinition.Rule rule : rules) {
            check(); boolean matches = rule.pattern().matcher(input).matches(); check();
            if (matches) return rule;
        }
        return null;
    }
    /** Writes a complete UTF-8 line under time and byte limits. */
    private void send(SocketChannel socket,String response) throws IOException {
        byte[] bytes = (response + "\n").getBytes(StandardCharsets.UTF_8);
        if (sentBytes + bytes.length > 65536) throw new Failure("BYTE_LIMIT");
        ByteBuffer data = ByteBuffer.wrap(bytes);
        long end = operationDeadline();
        while (data.hasRemaining()) {
            check();
            if (System.nanoTime() >= end) throw new Failure("IDLE_TIMEOUT");
            if (socket.write(data) == 0) await(socket,SelectionKey.OP_WRITE,end);
        }
        sentBytes += bytes.length; responses.incrementAndGet();
    }
    /** Caps a read/write deadline by the remaining server lifetime. */
    private long operationDeadline() { return Math.min(deadline,System.nanoTime() + definition.idleTimeoutMs() * 1_000_000L); }
    /** Checks interruption and deadline expiry without clearing the interrupt flag. */
    private void check() throws Failure {
        if (!active) throw new Failure("STOPPED");
        if (Thread.currentThread().isInterrupted()) throw new Failure("APPLICATION_SHUTDOWN");
        if (System.nanoTime() >= deadline) throw new Failure("TOTAL_TIMEOUT");
    }
    /** Waits for readiness without infinite selector timeouts. */
    private void await(SelectableChannel channel,int operation,long end) throws IOException {
        check(); if (System.nanoTime() >= end) throw new Failure("IDLE_TIMEOUT");
        channel.register(selector,operation);
        selector.select(Math.max(1,(end-System.nanoTime()+999999)/1000000));
        selector.selectedKeys().clear(); check();
        if (System.nanoTime() >= end) throw new Failure("IDLE_TIMEOUT");
    }
    private static final class Failure extends IOException {
        final String code;
        Failure(String code) { this.code = code; }
    }
}
