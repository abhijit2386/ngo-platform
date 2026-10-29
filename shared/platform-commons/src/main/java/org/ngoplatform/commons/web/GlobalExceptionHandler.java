package org.ngoplatform.commons.web;

import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.List;
import org.ngoplatform.commons.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private ResponseEntity<ApiError> body(HttpStatus status, String code, String msg, List<ApiError.FieldViolation> fields) {
        return ResponseEntity.status(status).body(new ApiError(code, msg, status.value(),
                CorrelationIdFilter.current(), fields, Instant.now()));
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException e) {
        return body(e.status(), e.code(), e.getMessage(), List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e) {
        var fields = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new ApiError.FieldViolation(f.getField(), f.getCode(), f.getDefaultMessage())).toList();
        var global = e.getBindingResult().getGlobalErrors().stream()
                .map(g -> new ApiError.FieldViolation(g.getObjectName(), g.getCode(), g.getDefaultMessage())).toList();
        var all = new java.util.ArrayList<>(fields);
        all.addAll(global);
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "One or more fields are invalid", all);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiError> constraint(ConstraintViolationException e) {
        var fields = e.getConstraintViolations().stream()
                .map(v -> new ApiError.FieldViolation(v.getPropertyPath().toString(), "Invalid", v.getMessage())).toList();
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "One or more fields are invalid", fields);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class})
    ResponseEntity<ApiError> unreadable(Exception e) {
        return body(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Request could not be read", List.of());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ApiError> stale(OptimisticLockingFailureException e) {
        return body(HttpStatus.CONFLICT, "STALE_VERSION", "The record was changed by someone else. Reload and retry.", List.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> integrity(DataIntegrityViolationException e) {
        log.warn("Data integrity violation: {}", e.getMostSpecificCause().getClass().getSimpleName());
        return body(HttpStatus.CONFLICT, "DUPLICATE_OR_CONFLICT", "The request conflicts with existing data", List.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> denied(AccessDeniedException e) {
        return body(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "You do not have permission for this action", List.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> noRoute(NoResourceFoundException e) {
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found", List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> method(HttpRequestMethodNotSupportedException e) {
        return body(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Method not allowed", List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception e) {
        log.error("Unhandled error", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Something went wrong. Please retry.", List.of());
    }
}
