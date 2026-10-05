package com.example.functionsapi.functions.fetchrabbitmqqueuemessage;
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
class FunctionHttpTest {
    @Autowired TestRestTemplate http;
    @MockitoSpyBean FetchRabbitMqQueueMessageFunction fn;
    @Test void nativeHttpBindingAndResponse() throws Exception {
        ConnectionFactory factory=mock(ConnectionFactory.class); Connection connection=mock(Connection.class); Channel channel=mock(Channel.class);
        doReturn("test-only").when(fn).secret("RABBITMQ_PASSWORD"); doReturn(factory).when(fn).factory(any());
        when(factory.newConnection()).thenReturn(connection); when(connection.createChannel()).thenReturn(channel);
        when(channel.basicGet("q",false)).thenReturn(new GetResponse(new Envelope(7,true,"","q"),new AMQP.BasicProperties.Builder().headers(java.util.Map.of("source","test")).build(),"hello".getBytes(java.nio.charset.StandardCharsets.UTF_8),0));
        HttpHeaders headers=new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        String body="{\"host\":\"localhost\",\"queue\":\"q\",\"username\":\"test\",\"passwordEnv\":\"RABBITMQ_PASSWORD\",\"settlement\":\"requeue\"}";
        ResponseEntity<JsonNode> response=http.postForEntity("/fetchRabbitMqQueueMessage",new HttpEntity<>(body,headers),JsonNode.class);
        assertEquals(HttpStatus.OK,response.getStatusCode()); assertEquals("message",response.getBody().path("status").asText(), response.getBody().toString());
        assertEquals("test",response.getBody().at("/message/headers/source").asText()); verify(channel).basicNack(7,false,true);
        when(channel.basicGet("q",false)).thenReturn(null);
        ResponseEntity<JsonNode> empty=http.postForEntity("/fetchRabbitMqQueueMessage",new HttpEntity<>(body,headers),JsonNode.class);
        assertEquals("empty",empty.getBody().path("status").asText());
        assertEquals(HttpStatus.BAD_REQUEST,http.postForEntity("/fetchRabbitMqQueueMessage",new HttpEntity<>("{",headers),String.class).getStatusCode());
        ResponseEntity<JsonNode> invalid=http.postForEntity("/fetchRabbitMqQueueMessage",new HttpEntity<>("{}",headers),JsonNode.class);
        assertEquals("INVALID_REQUEST",invalid.getBody().path("code").asText());
    }
}
