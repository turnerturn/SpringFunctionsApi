package com.example.functionsapi.functions.exchangetcpconversation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.channels.*;

/** Request-owned nonblocking transport; TCP packet boundaries are never message boundaries. */
final class TcpSession implements AutoCloseable {
    static final class Failure extends IOException {
        final String code;
        Failure(String code) { this.code = code; }
    }
    private final SocketChannel socket;
    private final Selector selector;
    private final long totalDeadline;
    private final ByteBuffer incoming = ByteBuffer.allocate(8192);
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private int received, sent;

    /** Initializes owned dependencies and bounded resources. */
    TcpSession(long deadline) throws IOException {
        totalDeadline = deadline;
        selector = Selector.open();
        SocketChannel opened = null;
        try {
            opened = SocketChannel.open(); opened.configureBlocking(false); socket = opened;
        } catch (IOException | RuntimeException e) {
            try { if (opened != null) opened.close(); }
            finally { selector.close(); }
            throw e;
        }
        incoming.limit(0);
    }
    /** Connects within the operation and overall deadlines. */
    void connect(String host, int port, int timeout) throws IOException {
        long deadline = deadline(timeout);
        check(deadline);
        if (!socket.connect(new InetSocketAddress(host, port))) {
            while (!socket.finishConnect()) await(SelectionKey.OP_CONNECT, deadline);
        }
    }
    /** Writes a complete UTF-8 line under time and byte limits. */
    void send(String text, int timeout) throws IOException {
        long deadline = deadline(timeout);
        byte[] bytes = (text + "\n").getBytes(StandardCharsets.UTF_8);
        if (sent + bytes.length > 65536) throw new Failure("BYTE_LIMIT");
        ByteBuffer data = ByteBuffer.wrap(bytes);
        check(deadline);
        while (data.hasRemaining()) {
            check(deadline);
            if (socket.write(data) == 0) await(SelectionKey.OP_WRITE, deadline);
        }
        sent += bytes.length;
    }
    /** Assembles one strict UTF-8 line, retaining partial bytes across step timeouts. */
    String receive(int timeout) throws IOException {
        long deadline = deadline(timeout);
        while (true) {
            check(deadline);
            if (!incoming.hasRemaining()) {
                incoming.clear();
                int count = socket.read(incoming);
                incoming.flip();
                if (count < 0) throw new Failure(line.size() == 0 ? "PEER_CLOSED" : "INCOMPLETE_FRAME");
                if (count == 0) { await(SelectionKey.OP_READ, deadline); continue; }
                received += count;
                if (received > 65536) throw new Failure("BYTE_LIMIT");
            }
            byte next = incoming.get();
            if (next == '\n') {
                byte[] bytes = line.toByteArray();
                line.reset();
                int length = bytes.length;
                if (length > 0 && bytes[length - 1] == '\r') length--;
                try {
                    return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, 0, length)).toString();
                } catch (CharacterCodingException e) { throw new Failure("INVALID_UTF8"); }
            }
            if (line.size() >= 8192) throw new Failure("FRAME_LIMIT");
            line.write(next);
        }
    }
    /** Checks the monotonic overall conversation budget. */
    void checkTotal() throws Failure { check(totalDeadline); }
    /** Caps operation time by the remaining overall budget. */
    private long deadline(int timeout) { return Math.min(totalDeadline, System.nanoTime() + timeout * 1_000_000L); }
    /** Checks interruption and deadline expiry without clearing the interrupt flag. */
    private void check(long deadline) throws Failure {
        if (Thread.currentThread().isInterrupted()) throw new Failure("INTERRUPTED");
        long now = System.nanoTime();
        if (now >= totalDeadline) throw new Failure("TOTAL_TIMEOUT");
        if (now >= deadline) throw new Failure("STEP_TIMEOUT");
    }
    /** Waits for readiness without infinite selector timeouts. */
    private void await(int operation, long deadline) throws IOException {
        check(deadline);
        socket.register(selector, operation);
        selector.select(Math.max(1L, (deadline - System.nanoTime() + 999999L) / 1000000L));
        selector.selectedKeys().clear();
        check(deadline);
    }
    /** Closes all owned resources, including application teardown paths. */
    @Override public void close() throws IOException {
        try { socket.close(); } finally { selector.close(); }
    }
}
