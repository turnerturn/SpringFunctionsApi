package com.example.functionsapi.common.rabbitmq;

import com.rabbitmq.client.*;
import com.rabbitmq.client.impl.nio.NioParams;
import javax.net.ssl.SSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Verified TLS and bounded AMQP transport settings shared by fetch and publish. */
public final class RabbitConnections {
    private static final Logger log = LoggerFactory.getLogger(RabbitConnections.class);
    /** Initializes owned dependencies and bounded resources. */
    private RabbitConnections() { }

    /** Builds an unrecovered, request-owned connection factory without logging credentials. */
    public static ConnectionFactory create(String host, int port, String virtualHost,
            String username, String password, boolean tls) throws Exception {
        ConnectionFactory f = new ConnectionFactory();
        f.setHost(host); f.setPort(port); f.setVirtualHost(virtualHost);
        f.setUsername(username); f.setPassword(password);
        f.setAutomaticRecoveryEnabled(false); f.setTopologyRecoveryEnabled(false);
        f.setConnectionTimeout(5000); f.setHandshakeTimeout(5000); f.setChannelRpcTimeout(5000);
        f.useNio(); f.setNioParams(new NioParams().setWriteEnqueuingTimeoutInMs(5000).setWriteQueueCapacity(16));
        f.setShutdownTimeout(1000); f.setRequestedHeartbeat(10); f.setMaxInboundMessageBodySize(1048576);
        if (tls) { f.useSslProtocol(SSLContext.getDefault()); f.enableHostnameVerification(); }
        log.debug("AMQP transport configured tls={} recovery=false", tls);
        return f;
    }

    /** Attempts both cleanup operations, even when channel cleanup fails. */
    public static void close(Channel channel, Connection connection) {
        if (channel != null) try { channel.abort(); }
        catch (Exception ignored) { log.debug("AMQP channel cleanup failed"); }
        if (connection != null) try { connection.abort(1000); }
        catch (Exception ignored) { log.debug("AMQP connection cleanup failed"); }
        log.trace("AMQP request resources released");
    }
}
