package com.deploymate.config;

import com.deploymate.model.DeployException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

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

        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("error",   ex.getCode().name());
        body.put("message", ex.getMessage());
        if (ex.getRepo() != null) body.put("repo", ex.getRepo());

        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
            .toList();

        log.warn("Validation failed: {}", errors);

        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("error",   "INVALID_INPUT");
        body.put("details", errors);

        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(HttpMessageNotReadableException ex) {
        log.warn("Unreadable request body: {}", ex.getMessage());
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("error",   "INVALID_INPUT");
        body.put("message", "Request body is missing or malformed");
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException ex) {
        List<String> errors = ex.getConstraintViolations().stream()
            .map(cv -> cv.getPropertyPath() + ": " + cv.getMessage())
            .toList();
        log.warn("Constraint violation: {}", errors);
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("error",   "INVALID_INPUT");
        body.put("details", errors);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException ex) {
        log.warn("Missing request parameter: {}", ex.getMessage());
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("error",   "INVALID_INPUT");
        body.put("message", "Required parameter '" + ex.getParameterName() + "' is missing");
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneric(Exception ex) {
        log.error("Unexpected error", ex);
        return ResponseEntity.internalServerError().body(Map.of(
            "error",   "INTERNAL_ERROR",
            "message", "An unexpected error occurred. Check the server log for details."
        ));
    }
}
