package org.example.educatorweb.resourcegen.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

/**
 * Global exception handler that converts thrown exceptions into structured
 * {@link ErrorResponse} payloads with appropriate HTTP status codes.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record ErrorResponse(String errorCode, String message, Instant timestamp) {}

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Bad request: {}", ex.getMessage());
        ErrorResponse body = new ErrorResponse(
            "BAD_REQUEST",
            ex.getMessage() != null ? ex.getMessage() : "Invalid argument",
            Instant.now()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex) {
        log.error("Internal server error: {}", ex.getMessage(), ex);
        ErrorResponse body = new ErrorResponse(
            "INTERNAL_SERVER_ERROR",
            ex.getMessage() != null ? ex.getMessage() : "Unexpected error",
            Instant.now()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
