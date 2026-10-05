package com.example.functionsapi.functions.sendrabbitmqmessage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.*;
import com.example.functionsapi.common.rabbitmq.RabbitConnections;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in broker verification; owns and deletes only its unique test queue. */
class RabbitPublisherIT {
    @Test void confirmsRoutingAndPreservesJsonAndHeaders() throws Exception {
        String password=System.getenv("RABBITMQ_PASSWORD"), username=System.getenv("RABBITMQ_IT_USERNAME");
        assertNotNull(password,"Set RABBITMQ_PASSWORD for rabbitmq-it");
        assertNotNull(username,"Set RABBITMQ_IT_USERNAME for rabbitmq-it");
        String host=System.getenv().getOrDefault("RABBITMQ_IT_HOST","localhost");
        int port=Integer.parseInt(System.getenv().getOrDefault("RABBITMQ_IT_PORT","5672"));
        ObjectMapper mapper=new ObjectMapper();
        var request=mapper.createObjectNode();
        request.putObject("broker").put("host",host).put("port",port).put("username",username).put("passwordEnv","RABBITMQ_PASSWORD");
        String queue="functions-api-publish-it-"+UUID.randomUUID();
        request.putObject("destination").put("queue",queue);
        var message=request.putObject("message");
        message.putObject("data").put("productCode","P001");
        message.putObject("headers").put("source","publisher-it");
        RabbitPublisher publisher=new RabbitPublisher(mapper);
        try (Connection connection=RabbitConnections.create(host,port,"/",username,password,false).newConnection();
             Channel channel=connection.createChannel()) {
            channel.queueDeclare(queue,false,false,false,null);
            try {
                assertEquals("published",publisher.publish(request).path("status").asText());
                GetResponse received=channel.basicGet(queue,true);
                assertNotNull(received);
                assertEquals("P001",mapper.readTree(received.getBody()).path("productCode").asText());
                assertEquals("publisher-it",received.getProps().getHeaders().get("source").toString());
                request.withObject("/destination").put("queue",queue+"-absent");
                assertEquals("UNROUTABLE",publisher.publish(request).path("code").asText());
            } finally { channel.queueDelete(queue); }
        }
    }
}
