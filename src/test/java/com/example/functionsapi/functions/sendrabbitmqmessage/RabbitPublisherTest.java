package com.example.functionsapi.functions.sendrabbitmqmessage;

import com.fasterxml.jackson.databind.*;
import com.rabbitmq.client.*;
import org.junit.jupiter.api.*;
import java.io.IOException;
import java.util.concurrent.TimeoutException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RabbitPublisherTest {
    ObjectMapper mapper=new ObjectMapper(); RabbitPublisher publisher;
    ConnectionFactory factory; Connection connection; Channel channel; JsonNode request;
    @BeforeEach void setup() throws Exception {
        publisher=spy(new RabbitPublisher(mapper)); doReturn("secret-sentinel").when(publisher).secret("RABBITMQ_PASSWORD");
        factory=mock(ConnectionFactory.class); connection=mock(Connection.class); channel=mock(Channel.class);
        doReturn(factory).when(publisher).factory(any()); when(factory.newConnection()).thenReturn(connection);
        when(connection.createChannel()).thenReturn(channel); when(channel.waitForConfirms(5000)).thenReturn(true);
        request=mapper.readTree("""
            {"broker":{"host":"localhost","username":"reader","passwordEnv":"RABBITMQ_PASSWORD"},
             "destination":{"queue":"existing"},"message":{"data":{"hello":"world"},"headers":{"attempt":2}}}
            """);
    }
    @Test void confirmsMandatoryPublishAndAlwaysCleansUp() throws Exception {
        assertEquals("published",publisher.publish(request).path("status").asText());
        var order=inOrder(channel,connection); order.verify(channel).confirmSelect();
        order.verify(channel).basicPublish(eq(""),eq("existing"),eq(true),any(),aryEq(mapper.writeValueAsBytes(request.at("/message/data"))));
        order.verify(channel).waitForConfirms(5000); order.verify(channel).abort(); order.verify(connection).abort(1000);
    }
    @Test void unroutableIsNotReportedAsPublished() throws Exception {
        java.util.concurrent.atomic.AtomicReference<ReturnCallback> callback=new java.util.concurrent.atomic.AtomicReference<>();
        doAnswer(call -> { callback.set(call.getArgument(0)); return null; }).when(channel).addReturnListener(any(ReturnCallback.class));
        when(channel.waitForConfirms(5000)).thenAnswer(call -> { callback.get().handle(new Return(312,"private","","existing",null,new byte[0])); return true; });
        assertEquals("UNROUTABLE",publisher.publish(request).path("code").asText());
    }
    @Test void timeoutIsUncertainAndNeverRetried() throws Exception {
        when(channel.waitForConfirms(5000)).thenThrow(new TimeoutException("secret"));
        assertEquals("PUBLISH_UNCERTAIN",publisher.publish(request).path("code").asText());
        verify(channel,times(1)).basicPublish(anyString(),anyString(),eq(true),any(),any()); verify(connection).abort(1000);
    }
    @Test void brokerNackIsRejected() throws Exception {
        when(channel.waitForConfirms(5000)).thenReturn(false);
        assertEquals("PUBLISH_REJECTED",publisher.publish(request).path("code").asText());
    }
    @Test void invalidPayloadAndSecretFailBeforeConnecting() throws Exception {
        var invalid=request.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode)invalid.get("message")).put("text","ambiguous");
        assertEquals("INVALID_REQUEST",publisher.publish(invalid).path("code").asText());
        doReturn(null).when(publisher).secret("RABBITMQ_PASSWORD");
        assertEquals("CREDENTIALS_NOT_CONFIGURED",publisher.publish(request).path("code").asText());
        doReturn("").when(publisher).secret("RABBITMQ_PASSWORD");
        assertEquals("CREDENTIALS_NOT_CONFIGURED",publisher.publish(request).path("code").asText());
        assertEquals("INVALID_REQUEST",publisher.publish(invalid).path("code").asText());
        verifyNoInteractions(factory);
    }
    @Test void initializationAndCleanupFailuresAreHandled() throws Exception {
        when(connection.createChannel()).thenThrow(new IOException("secret"));
        assertEquals("BROKER_ERROR",publisher.publish(request).path("code").asText()); verify(connection).abort(1000);
    }
    @Test void headersAndDestinationValidationAreStrict() throws Exception {
        PublishRequest parsed=PublishRequest.parse(request,mapper,name -> "secret");
        assertEquals(2L,parsed.properties().getHeaders().get("attempt"));
        assertEquals("application/json",parsed.properties().getContentType()); assertEquals(2,parsed.properties().getDeliveryMode());
        assertFalse(parsed.toString().contains("secret"));
        var invalid=request.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode)invalid.get("destination")).put("exchange","other");
        assertEquals("INVALID_REQUEST",publisher.publish(invalid).path("code").asText());
    }
    private static byte[] aryEq(byte[] bytes) { return org.mockito.AdditionalMatchers.aryEq(bytes); }
}
