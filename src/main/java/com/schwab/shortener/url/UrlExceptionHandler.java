package com.schwab.shortener.url;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class UrlExceptionHandler {

    @ExceptionHandler(UrlExceptions.NotFound.class)
    public ResponseEntity<Map<String, Object>> notFound(UrlExceptions.NotFound e) {
        return problem(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(UrlExceptions.Expired.class)
    public ResponseEntity<Map<String, Object>> expired(UrlExceptions.Expired e) {
        return problem(HttpStatus.GONE, e.getMessage());
    }

    @ExceptionHandler(UrlExceptions.AliasTaken.class)
    public ResponseEntity<Map<String, Object>> aliasTaken(UrlExceptions.AliasTaken e) {
        return problem(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(UrlExceptions.GenerationExhausted.class)
    public ResponseEntity<Map<String, Object>> exhausted(UrlExceptions.GenerationExhausted e) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("invalid request");
        return problem(HttpStatus.BAD_REQUEST, detail);
    }

    private static ResponseEntity<Map<String, Object>> problem(HttpStatus status, String detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("detail", detail);
        return ResponseEntity.status(status).body(body);
    }
}
