package com.example.functionsapi.functions.sendrabbitmqmessage;

import com.fasterxml.jackson.databind.JsonNode;
import com.rabbitmq.client.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.http.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class PublishHttpTest {
    @Autowired TestRestTemplate http;
    @MockitoSpyBean RabbitPublisher publisher;
    @Test void bindsNativeJsonAndPublishesMandatory() throws Exception {
        ConnectionFactory factory=mock(ConnectionFactory.class); Connection connection=mock(Connection.class); Channel channel=mock(Channel.class);
        doReturn("secret").when(publisher).secret("RABBITMQ_PASSWORD"); doReturn(factory).when(publisher).factory(any());
        when(factory.newConnection()).thenReturn(connection); when(connection.createChannel()).thenReturn(channel);
        when(channel.waitForConfirms(5000)).thenReturn(true);
        HttpHeaders headers=new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        String body="""
            {"broker":{"host":"localhost","username":"reader","passwordEnv":"RABBITMQ_PASSWORD"},
             "destination":{"queue":"existing"},"message":{"text":"hello"}}
            """;
        var response=http.postForEntity("/sendRabbitMqMessage",new HttpEntity<>(body,headers),JsonNode.class);
        assertEquals(HttpStatus.OK,response.getStatusCode()); assertTrue(response.getBody().isObject());
        assertEquals("published",response.getBody().path("status").asText());
        verify(channel).basicPublish(eq(""),eq("existing"),eq(true),any(),any());
    }
    @Test void examplePublishesAndMissingEnvironmentIsReportedAsConfigurationError() throws Exception {
        ConnectionFactory factory=mock(ConnectionFactory.class);
        Connection connection=mock(Connection.class); Channel channel=mock(Channel.class);
        doReturn("test-only-credential").when(publisher).secret("RABBITMQ_PASSWORD");
        doReturn(factory).when(publisher).factory(any());
        when(factory.newConnection()).thenReturn(connection);
        when(connection.createChannel()).thenReturn(channel);
        when(channel.waitForConfirms(5000)).thenReturn(true);
        String body=java.nio.file.Files.readString(java.nio.file.Path.of("examples/send-rabbitmq-message.json"));
        HttpHeaders headers=new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        var response=http.postForEntity("/sendRabbitMqMessage",new HttpEntity<>(body,headers),JsonNode.class);
        assertEquals("published",response.getBody().path("status").asText(),response.getBody().toString());
        clearInvocations(factory);
        doReturn(null).when(publisher).secret("RABBITMQ_PASSWORD");
        response=http.postForEntity("/sendRabbitMqMessage",new HttpEntity<>(body,headers),JsonNode.class);
        assertEquals("CREDENTIALS_NOT_CONFIGURED",response.getBody().path("code").asText());
        verifyNoInteractions(factory);
    }

}
