package com.deploymate.config;

import com.deploymate.model.DeployException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DeployException.class)
    public ResponseEntity<Map<String, Object>> handleDeploy(DeployException ex) {
        log.error("DeployException [{}] repo={}: {}", ex.getCode(), ex.getRepo(), ex.getMessage());

        int status = switch (ex.getCode()) {
            case CONFLICT      -> 409;
            case NOT_FOUND     -> 404;
            case AUTH_FAILED   -> 401;
            case INVALID_INPUT -> 400;
            default            -> 500;
        };

        var body = new LinkedHashMap<String, Object>();
        body.put("error",   ex.getCode().name());
        body.put("message", ex.getMessage());
        if (ex.getRepo() != null) body.put("repo", ex.getRepo());

        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        var errors = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
            .toList();

        log.warn("Validation failed: {}", errors);

        var body = new LinkedHashMap<String, Object>();
        body.put("error",   "INVALID_INPUT");
        body.put("details", errors);

        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(HttpMessageNotReadableException ex) {
        log.warn("Unreadable request body: {}", ex.getMessage());
        var body = new LinkedHashMap<String, Object>();
        body.put("error",   "INVALID_INPUT");
        body.put("message", "Request body is missing or malformed");
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneric(Exception ex) {
        // Log full stack trace server-side but never expose it to clients
        log.error("Unexpected error", ex);
        return ResponseEntity.internalServerError().body(Map.of(
            "error",   "INTERNAL_ERROR",
            "message", "An unexpected error occurred. Check the server log for details."
        ));
    }
}
