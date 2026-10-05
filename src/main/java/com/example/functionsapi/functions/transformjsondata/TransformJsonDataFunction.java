package com.example.functionsapi.functions.transformjsondata;

import com.fasterxml.jackson.databind.*;
import com.example.functionsapi.common.logging.FunctionLogging;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import java.util.function.Function;

/**
 * Applies configured mapping, binding, merging, and reference enrichment to a copied JSON tree.
 * Usage: POST /transformJsonData with examples/transform-recipe-order.json or transform-products-api.json.
 * This is an AWS-inspired declarative pipeline, not an AWS input-transformer compatibility layer.

 * <pre>{@code
 * curl -sS -H 'Content-Type: application/json' --data-binary @examples/transform-recipe-order.json \
 *   http://127.0.0.1:8080/transformJsonData
 * }</pre>
 */
@Configuration
public class TransformJsonDataFunction {
    /** Registers a native function with operator-controlled external API destinations. */
    @Bean
    public Function<JsonNode,JsonNode> transformJsonData(ObjectMapper mapper,
            @Value("${functions.transform-json-data.allowed-origins:http://127.0.0.1:8090}") String origins) {
        TransformationEngine engine=new TransformationEngine(mapper,new ReferenceClient(mapper,origins));
        return FunctionLogging.wrap("transformJsonData",engine::transform);
    }
}
