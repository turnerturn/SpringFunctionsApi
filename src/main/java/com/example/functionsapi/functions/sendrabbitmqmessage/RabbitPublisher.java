package com.example.functionsapi.functions.sendrabbitmqmessage;

import com.fasterxml.jackson.databind.*;
import com.rabbitmq.client.*;
import com.example.functionsapi.common.rabbitmq.RabbitConnections;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.*;

/** Request-owned publisher; never retries an uncertain publish or creates broker topology. */
final class RabbitPublisher {
    private static final Logger log=LoggerFactory.getLogger(RabbitPublisher.class);
    private final ObjectMapper mapper;
    private final Semaphore slots=new Semaphore(16);
    /** Initializes owned dependencies and bounded resources. */
    RabbitPublisher(ObjectMapper mapper) { this.mapper=mapper; }
    /** Resolves only an allowlisted environment credential reference. */
    String secret(String name) { return System.getenv(name); }
    /** Creates the shared verified and bounded RabbitMQ transport. */
    ConnectionFactory factory(PublishRequest r) throws Exception {
        return RabbitConnections.create(r.host(),r.port(),r.virtualHost(),r.username(),r.password(),r.tls());
    }
    /** Publishes once, then distinguishes unroutable, negative, and uncertain confirmations. */
    JsonNode publish(JsonNode input) {
        PublishRequest r;
        try { r=PublishRequest.parse(input,mapper,this::secret); }
        catch (PublishRequest.MissingCredentials e) { return error("CREDENTIALS_NOT_CONFIGURED"); }
        catch (Exception e) { return error("INVALID_REQUEST"); }
        if (!slots.tryAcquire()) return error("BUSY");
        Connection connection=null; Channel channel=null;
        boolean attempted=false;
        try {
            log.debug("Publish validated bytes={} confirmTimeoutMs={}",r.body().length,r.confirmTimeoutMs());
            connection=factory(r).newConnection(); channel=connection.createChannel();
            AtomicBoolean returned=new AtomicBoolean();
            // Mandatory returns precede positive confirms; capture metadata only, never returned bodies.
            channel.addReturnListener((ReturnCallback) message -> returned.set(true));
            channel.confirmSelect();
            attempted=true;
            channel.basicPublish(r.exchange(),r.routingKey(),true,r.properties(),r.body());
            log.trace("Publish enqueued; awaiting broker confirmation");
            boolean confirmed=channel.waitForConfirms(r.confirmTimeoutMs());
            if (returned.get()) return error("UNROUTABLE");
            if (!confirmed) return error("PUBLISH_REJECTED");
            return mapper.createObjectNode().put("status","published").put("brokerConfirmed",true).put("bytes",r.body().length);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); return error(attempted?"PUBLISH_UNCERTAIN":"INTERRUPTED");
        } catch (Exception e) {
            if (attempted) return error("PUBLISH_UNCERTAIN");
            return error(e instanceof AuthenticationFailureException?"AUTHENTICATION_FAILED":"BROKER_ERROR");
        } finally { RabbitConnections.close(channel,connection); slots.release(); }
    }
    /** Returns a code without exception messages, destinations, or credentials. */
    private JsonNode error(String code) { log.debug("Publish outcome code={}",code); return mapper.createObjectNode().put("status","error").put("code",code); }
}
