package com.example.functionsapi.common.logging;

import ch.qos.logback.classic.*;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;

class FunctionLoggingTest {
    @Test void tracesNeverContainPayloadOrExceptionMessage() throws Exception {
        Logger logger=(Logger)LoggerFactory.getLogger(FunctionLogging.class);
        Level previous=logger.getLevel(); ListAppender<ILoggingEvent> appender=new ListAppender<>(); appender.start();
        logger.setLevel(Level.TRACE); logger.addAppender(appender);
        ObjectMapper mapper=new ObjectMapper();
        try {
            var input=mapper.readTree("{\"password\":\"credential-sentinel\",\"data\":\"body-sentinel\"}");
            FunctionLogging.wrap("transformJsonData",n -> mapper.createObjectNode().put("status","transformed").set("data",n)).apply(input);
            assertThrows(IllegalStateException.class,() -> FunctionLogging.wrap("test",n -> { throw new IllegalStateException("credential-sentinel"); }).apply(input));
            String output=appender.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("",(a,b) -> a+b);
            assertFalse(output.contains("credential-sentinel")); assertFalse(output.contains("body-sentinel"));
            assertTrue(output.contains("durationMs=")); assertTrue(output.contains("invocation="));
            assertTrue(appender.list.stream().noneMatch(e -> e.getThrowableProxy()!=null));
        } finally { logger.detachAppender(appender); logger.setLevel(previous); appender.stop(); }
    }
}
