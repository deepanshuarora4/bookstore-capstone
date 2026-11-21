package com.capstone.bookstore.common;

import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * The single error body used by every endpoint (see {@code ErrorResponse} in openapi.yaml).
 * {@code fieldErrors} is only present for VALIDATION_FAILED; null values are omitted from the JSON.
 */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        ErrorCode code,
        String message,
        String path,
        List<FieldError> fieldErrors) {

    public static ErrorResponse of(HttpStatus status, ErrorCode code, String message, String path) {
        return of(status, code, message, path, null);
    }

    public static ErrorResponse of(HttpStatus status, ErrorCode code, String message, String path,
                                   List<FieldError> fieldErrors) {
        return new ErrorResponse(Instant.now(), status.value(), status.getReasonPhrase(), code, message, path,
                fieldErrors == null || fieldErrors.isEmpty() ? null : fieldErrors);
    }
}
