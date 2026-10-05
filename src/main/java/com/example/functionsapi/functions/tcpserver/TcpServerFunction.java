package com.example.functionsapi.functions.tcpserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.function.Function;

/** Controls bounded loopback TCP mock listeners.
 * Usage: POST /tcpServer with examples/tcp-server-accuload-pc.json; retain serverId.
 * <pre>{@code
 * curl -sS -H 'Content-Type: application/json' --data-binary @examples/tcp-server-start.json \
 *   http://127.0.0.1:8080/tcpServer
 * }</pre>
 */
@Configuration
public class TcpServerFunction {
    @Bean(destroyMethod = "close")
    public TcpServerManager tcpServerManager(ObjectMapper mapper) { return new TcpServerManager(mapper); }
    /** Registers the native JSON function with safe invocation logging. */
    @Bean
    public Function<JsonNode, JsonNode> tcpServer(TcpServerManager manager) { return com.example.functionsapi.common.logging.FunctionLogging.wrap("tcpServer", manager::invoke); }
}
