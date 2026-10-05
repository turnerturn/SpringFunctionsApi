package com.example.functionsapi.functions.fetchrabbitmqqueuemessage;

import com.fasterxml.jackson.databind.*;
import com.rabbitmq.client.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.IOException;
import java.util.Map;

class FetchRabbitMqQueueMessageTest {
    ObjectMapper mapper=new ObjectMapper();
    FetchRabbitMqQueueMessageFunction fn;
    ConnectionFactory factory; Connection connection; Channel channel;
    JsonNode request;
    @BeforeEach void setup() throws Exception {
        fn=spy(new FetchRabbitMqQueueMessageFunction(mapper));
        doReturn("test-only").when(fn).secret("RABBITMQ_PASSWORD");
        factory=mock(ConnectionFactory.class); connection=mock(Connection.class); channel=mock(Channel.class);
        doReturn(factory).when(fn).factory(any());
        when(factory.newConnection()).thenReturn(connection); when(connection.createChannel()).thenReturn(channel);
        request=mapper.readTree("{\"host\":\"localhost\",\"queue\":\"q\",\"username\":\"test\",\"passwordEnv\":\"RABBITMQ_PASSWORD\",\"settlement\":\"acknowledge\"}");
    }
    GetResponse message() {
        return new GetResponse(new Envelope(42,false,"","q"),new AMQP.BasicProperties.Builder()
            .headers(Map.of("key",LongStringHelperForTest.value(),"nested",Map.of("number",3))).build(),new byte[]{0,1,2},0);
    }
    @Test void validatesBeforeConnecting() throws Exception {
        for (String patch : new String[]{"{\"pollTimeoutMs\":30001}","{\"pollTimeoutMs\":1.5}","{\"port\":0}","{\"tls\":\"true\"}","{\"passwordEnv\":\"HOME\"}","{\"settlement\":\"delete\"}","{\"unknown\":true}","{\"host\":\"999.0.0.1\"}"}) {
            JsonNode copy=request.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode)copy).setAll((com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(patch));
            assertEquals("INVALID_REQUEST",fn.fetch(copy).path("code").asText());
        }
        assertEquals("INVALID_REQUEST",fn.fetch(mapper.readTree("[]")).path("code").asText());
        verifyNoInteractions(factory);
    }
    @Test void immediateEmptyCloses() throws Exception {
        assertEquals("empty",fn.fetch(request).path("status").asText());
        verify(channel,times(1)).basicGet("q",false); verify(channel).abort(); verify(connection).abort(1000);
    }
    @Test void acknowledgeMapsHeadersAndBodyOnSameChannel() throws Exception {
        when(channel.basicGet("q",false)).thenReturn(message());
        JsonNode result=fn.fetch(request);
        assertEquals("message",result.path("status").asText());
        assertEquals("AQ==",mapper.valueToTree(new byte[]{1}).asText());
        assertEquals("AAEC",result.at("/message/bodyBase64").asText());
        assertEquals("value",result.at("/message/headers/key").asText());
        assertEquals(3,result.at("/message/headers/nested/number").asInt());
        assertFalse(result.toString().contains("deliveryTag"));
        verify(channel).basicAck(42,false); verify(channel,never()).basicNack(anyLong(),anyBoolean(),anyBoolean());
        verify(connection).abort(1000);
    }
    @Test void requeues() throws Exception {
        ((com.fasterxml.jackson.databind.node.ObjectNode)request).put("settlement","requeue");
        when(channel.basicGet("q",false)).thenReturn(message());
        assertEquals("requeue",fn.fetch(request).path("settlement").asText());
        verify(channel).basicNack(42,false,true); verify(channel,never()).basicAck(anyLong(),anyBoolean());
    }
    @Test void pollsUntilMessage() throws Exception {
        ((com.fasterxml.jackson.databind.node.ObjectNode)request).put("pollTimeoutMs",500);
        when(channel.basicGet("q",false)).thenReturn(null,message());
        assertEquals("message",fn.fetch(request).path("status").asText()); verify(channel,times(2)).basicGet("q",false);
    }
    @Test void deadlineStopsPolling() throws Exception {
        ((com.fasterxml.jackson.databind.node.ObjectNode)request).put("pollTimeoutMs",25);
        // Advance the monotonic clock to the deadline during the first pause.
        doReturn(0L, 0L, 25_000_000L).when(fn).nanoTime();
        doNothing().when(fn).pause(anyLong());
        assertEquals("empty",fn.fetch(request).path("status").asText());
        verify(fn).pause(25L);
        verify(channel,times(1)).basicGet("q",false);
        verify(channel).abort(); verify(connection).abort(1000);
    }
    @Test void settlementFailureIsUncertainAndNeverRetried() throws Exception {
        when(channel.basicGet("q",false)).thenReturn(message()); doThrow(new IOException("secret")).when(channel).basicAck(42,false);
        assertEquals("SETTLEMENT_UNCERTAIN",fn.fetch(request).path("code").asText());
        verify(channel,times(1)).basicAck(42,false); verify(channel).abort(); verify(connection).abort(1000);
    }
    @Test void brokerFailureIsNotEmptyAndCloses() throws Exception {
        when(channel.basicGet("q",false)).thenThrow(new IOException("credential"));
        assertEquals("BROKER_ERROR",fn.fetch(request).path("code").asText()); verify(connection).abort(1000);
    }
    @Test void failedChannelCreationClosesConnection() throws Exception {
        when(connection.createChannel()).thenThrow(new IOException()); fn.fetch(request); verify(connection).abort(1000);
    }
    @Test void interruptionRestoresFlagAndCloses() throws Exception {
        ((com.fasterxml.jackson.databind.node.ObjectNode)request).put("pollTimeoutMs",500);
        Thread.currentThread().interrupt();
        try { assertEquals("INTERRUPTED",fn.fetch(request).path("code").asText()); assertTrue(Thread.currentThread().isInterrupted()); }
        finally { Thread.interrupted(); }
        verify(connection).abort(1000);
    }
    @Test void missingSecretFailsBeforeConnection() throws Exception {
        doReturn(null).when(fn).secret("RABBITMQ_PASSWORD");
        assertEquals("INVALID_REQUEST",fn.fetch(request).path("code").asText()); verifyNoInteractions(factory);
    }
    @Test void queueAndPermissionErrorsRemainDistinct() throws Exception {
        for (int code : new int[]{404,403}) {
            AMQP.Channel.Close close=new AMQP.Channel.Close.Builder().replyCode(code).replyText("private").classId(60).methodId(70).build();
            doThrow(new java.io.IOException(new ShutdownSignalException(false,false,close,channel))).when(channel).basicGet("q",false);
            assertEquals(code==404 ? "QUEUE_NOT_FOUND" : "ACCESS_DENIED",fn.fetch(request).path("code").asText());
        }
    }
    @Test void factoryHasBoundedOperationsAndVerifiedTls() throws Exception {
        doCallRealMethod().when(fn).factory(any());
        ((com.fasterxml.jackson.databind.node.ObjectNode)request).put("tls",true);
        ConnectionFactory real=fn.factory(fn.validate(request));
        assertTrue(real.isSSL()); assertFalse(real.isAutomaticRecoveryEnabled()); assertFalse(real.isTopologyRecoveryEnabled());
        assertEquals(5000,real.getConnectionTimeout()); assertEquals(5000,real.getHandshakeTimeout());
        assertEquals(5000,real.getNioParams().getWriteEnqueuingTimeoutInMs());
        assertEquals(16,real.getNioParams().getWriteQueueCapacity());
        assertEquals(5000,real.getChannelRpcTimeout()); assertEquals(5671,real.getPort());
    }
    @Test void cleanupFailureDoesNotChangeSettledResult() throws Exception {
        when(channel.basicGet("q",false)).thenReturn(message()); doThrow(new IOException()).when(channel).abort();
        assertEquals("message",fn.fetch(request).path("status").asText()); verify(connection).abort(1000);
    }
    static class LongStringHelperForTest {
        static LongString value() { return com.rabbitmq.client.impl.LongStringHelper.asLongString("value"); }
    }
}
