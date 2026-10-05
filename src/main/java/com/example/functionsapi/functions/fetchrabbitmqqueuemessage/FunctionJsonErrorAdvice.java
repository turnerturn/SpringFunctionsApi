package com.example.functionsapi.functions.fetchrabbitmqqueuemessage;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "org.springframework.cloud.function.web")
public class FunctionJsonErrorAdvice {
    @ExceptionHandler({ClassCastException.class, MessageConversionException.class,
        HttpMessageNotReadableException.class})
    /** Returns a safe binding error without echoing malformed input. */
    public ResponseEntity<Map<String, String>> invalidJson() {
        return ResponseEntity.badRequest().body(Map.of("status", "error", "code", "INVALID_JSON"));
    }
}
