package com.capstone.bookstore.common;

import java.util.List;

import org.springframework.http.HttpStatus;

/** A business or request error that maps directly to an {@link ErrorResponse}. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final ErrorCode code;
    private final List<FieldError> fieldErrors;

    public ApiException(HttpStatus status, ErrorCode code, String message) {
        this(status, code, message, List.of());
    }

    public ApiException(HttpStatus status, ErrorCode code, String message, List<FieldError> fieldErrors) {
        super(message);
        this.status = status;
        this.code = code;
        this.fieldErrors = fieldErrors;
    }

    public static ApiException validation(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "Request validation failed",
                List.of(new FieldError(field, message)));
    }

    public static ApiException authenticationFailed() {
        return new ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.AUTHENTICATION_FAILED,
                "Invalid credentials or token");
    }

    public static ApiException emailAlreadyRegistered() {
        return new ApiException(HttpStatus.CONFLICT, ErrorCode.EMAIL_ALREADY_REGISTERED,
                "Email is already registered");
    }

    public static ApiException badRequest(ErrorCode code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException conflict(ErrorCode code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, message);
    }

    public static ApiException duplicate(String message) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCode.DUPLICATE_RESOURCE, message);
    }

    public static ApiException concurrentModification() {
        return new ApiException(HttpStatus.CONFLICT, ErrorCode.CONCURRENT_MODIFICATION,
                "The resource was changed by someone else. Reload it and try again");
    }

    public HttpStatus getStatus() { return status; }
    public ErrorCode getCode() { return code; }
    public List<FieldError> getFieldErrors() { return fieldErrors; }
}
