package com.example.functionsapi.functions.exchangetcpconversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.concurrent.Semaphore;

/** Executes validated TCP steps with no automatic retries and guaranteed socket cleanup. */
final class ConversationRunner {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ConversationRunner.class);
    private final ObjectMapper mapper;
    private final Semaphore slots = new Semaphore(16);
    /** Initializes owned dependencies and bounded resources. */
    ConversationRunner(ObjectMapper mapper) { this.mapper = mapper; }

    /** Executes a bounded conversation, returning sanitized errors and closing transport. */
    JsonNode exchange(JsonNode request) {
        ConversationDefinition definition;
        try { definition = ConversationDefinition.parse(request); }
        catch (IllegalArgumentException e) { return error("INVALID_REQUEST", null, 0, false); }
        if (!slots.tryAcquire()) return error("BUSY", null, 0, false);
        String current = definition.start(), lastInput = null;
        int executions = 0;
        boolean effects = false;
        TcpSession session = null;
        try {
            log.debug("Conversation validated stepCount={}",definition.order().size());
            session = new TcpSession(System.nanoTime() + definition.totalTimeoutMs() * 1_000_000L);
            session.connect(definition.host(), definition.port(), definition.connectTimeoutMs());
            while (current != null) {
                session.checkTotal();
                if (executions >= definition.maxExecutions()) throw new TcpSession.Failure("EXECUTION_LIMIT");
                ConversationDefinition.Step step = definition.steps().get(current);
                executions++;
                log.trace("Conversation execution={} action={}",executions,step.action());
                String next = next(definition, current);
                switch (step.action()) {
                    case "send" -> { effects = true; session.send(step.text(), step.timeoutMs()); }
                    case "wait" -> {
                        try { lastInput = session.receive(step.timeoutMs()); }
                        catch (TcpSession.Failure e) {
                            // Keep buffered partial bytes in the session for a later wait.
                            // Clear lastInput so forks cannot accidentally match an earlier reply.
                            if (!e.code.equals("STEP_TIMEOUT") || step.onTimeout() == null) throw e;
                            lastInput = null;
                            next = step.onTimeout();
                        }
                    }
                    case "fork" -> {
                        if (lastInput == null) throw new TcpSession.Failure("NO_INPUT");
                        next = step.otherwise();
                        for (ConversationDefinition.Branch branch : step.branches()) {
                            session.checkTotal();
                            boolean matched = branch.regex().matcher(lastInput).matches();
                            session.checkTotal();
                            if (matched) { next = branch.target(); break; }
                        }
                    }
                    case "goto" -> next = step.target();
                    case "end" -> {
                        ObjectNode result = mapper.createObjectNode().put("status", "completed")
                                .put("terminalStep", current).put("executedSteps", executions);
                        if (lastInput == null) result.putNull("lastInput"); else result.put("lastInput", lastInput);
                        return result;
                    }
                    default -> throw new IllegalStateException();
                }
                current = next;
            }
            ObjectNode result = mapper.createObjectNode().put("status", "completed").put("executedSteps", executions);
            if (lastInput == null) result.putNull("lastInput"); else result.put("lastInput", lastInput);
            return result;
        } catch (TcpSession.Failure e) {
            return error(e.code, current, executions, effects);
        } catch (IOException e) {
            return error(Thread.currentThread().isInterrupted() ? "INTERRUPTED" : "TCP_ERROR", current, executions, effects);
        } finally {
            if (session != null) try { session.close(); } catch (IOException ignored) { log.debug("Conversation cleanup failed"); }
            slots.release();
        }
    }
    /** Selects the next sequential step when no explicit jump applies. */
    private String next(ConversationDefinition definition, String id) {
        int index = definition.order().indexOf(id) + 1;
        return index == definition.order().size() ? null : definition.order().get(index);
    }
    /** Returns a fixed error code without exposing exception messages or payloads. */
    private ObjectNode error(String code, String step, int count, boolean effects) {
        log.debug("Conversation outcome code={} executions={}",code,count);
        ObjectNode result = mapper.createObjectNode().put("status", "error").put("code", code)
                .put("executedSteps", count).put("effectsMayHaveOccurred", effects);
        if (step != null) result.put("step", step);
        return result;
    }
}
