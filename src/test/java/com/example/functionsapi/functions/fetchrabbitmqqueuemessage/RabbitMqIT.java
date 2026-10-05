package com.example.functionsapi.functions.fetchrabbitmqqueuemessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
class RabbitMqIT {
    @Test void fetchRequeueThenAcknowledgeExistingDisposableQueue() throws Exception {
        String password=System.getenv("RABBITMQ_PASSWORD"), username=System.getenv("RABBITMQ_IT_USERNAME");
        assertNotNull(password,"Set RABBITMQ_PASSWORD for rabbitmq-it"); assertNotNull(username,"Set RABBITMQ_IT_USERNAME for rabbitmq-it");
        String host=System.getenv().getOrDefault("RABBITMQ_IT_HOST","localhost");
        int port=Integer.parseInt(System.getenv().getOrDefault("RABBITMQ_IT_PORT","5672"));
        ObjectMapper mapper=new ObjectMapper(); FetchRabbitMqQueueMessageFunction fn=new FetchRabbitMqQueueMessageFunction(mapper);
        String queue="functions-api-it-"+UUID.randomUUID();
        var request=mapper.createObjectNode().put("host",host).put("port",port).put("username",username).put("passwordEnv","RABBITMQ_PASSWORD").put("queue",queue).put("settlement","requeue");
        ConnectionFactory factory=fn.factory(fn.validate(request));
        try (Connection connection=factory.newConnection(); Channel channel=connection.createChannel()) {
            channel.queueDeclare(queue,false,false,false,null);
            try {
                channel.confirmSelect();
                channel.basicPublish("",queue,new AMQP.BasicProperties.Builder().headers(Map.of("key","value")).build(),"test".getBytes());
                channel.waitForConfirmsOrDie(5000);
                request.put("pollTimeoutMs",2000);
                assertEquals("message",fn.fetch(request).path("status").asText());
                request.put("settlement","acknowledge"); assertEquals("message",fn.fetch(request).path("status").asText());
                assertEquals("empty",fn.fetch(request).path("status").asText());
            } finally { channel.queueDelete(queue); }
        }
    }
}
