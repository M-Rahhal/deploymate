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
    public ResponseEntity<Map<String, Object>> handleDeployException(DeployException ex) {
        log.error("DeployException [{}] repo={}: {}", ex.getCode(), ex.getRepo(), ex.getMessage());

        int httpStatus = switch (ex.getCode()) {
            case CONFLICT      -> 409;
            case NOT_FOUND     -> 404;
            case AUTH_FAILED   -> 401;
            case INVALID_INPUT -> 400;
            default            -> 500;
        };

        LinkedHashMap<String, Object> responseBody = new LinkedHashMap<>();
        responseBody.put("error",   ex.getCode().name());
        responseBody.put("message", ex.getMessage());
        if (ex.getRepo() != null) responseBody.put("repo", ex.getRepo());

        return ResponseEntity.status(httpStatus).body(responseBody);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleBindingValidationException(MethodArgumentNotValidException ex) {
        List<String> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
            .toList();

        log.warn("Validation failed: {}", fieldErrors);

        LinkedHashMap<String, Object> responseBody = new LinkedHashMap<>();
        responseBody.put("error",   "INVALID_INPUT");
        responseBody.put("details", fieldErrors);

        return ResponseEntity.badRequest().body(responseBody);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableRequestBodyException(HttpMessageNotReadableException ex) {
        log.warn("Unreadable request body: {}", ex.getMessage());
        LinkedHashMap<String, Object> responseBody = new LinkedHashMap<>();
        responseBody.put("error",   "INVALID_INPUT");
        responseBody.put("message", "Request body is missing or malformed");
        return ResponseEntity.badRequest().body(responseBody);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolationException(ConstraintViolationException ex) {
        List<String> violations = ex.getConstraintViolations().stream()
            .map(cv -> cv.getPropertyPath() + ": " + cv.getMessage())
            .toList();
        log.warn("Constraint violation: {}", violations);
        LinkedHashMap<String, Object> responseBody = new LinkedHashMap<>();
        responseBody.put("error",   "INVALID_INPUT");
        responseBody.put("details", violations);
        return ResponseEntity.badRequest().body(responseBody);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingRequestParameterException(MissingServletRequestParameterException ex) {
        log.warn("Missing request parameter: {}", ex.getMessage());
        LinkedHashMap<String, Object> responseBody = new LinkedHashMap<>();
        responseBody.put("error",   "INVALID_INPUT");
        responseBody.put("message", "Required parameter '" + ex.getParameterName() + "' is missing");
        return ResponseEntity.badRequest().body(responseBody);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpectedException(Exception ex) {
        log.error("Unexpected error", ex);
        return ResponseEntity.internalServerError().body(Map.of(
            "error",   "INTERNAL_ERROR",
            "message", "An unexpected error occurred. Check the server log for details."
        ));
    }
}
