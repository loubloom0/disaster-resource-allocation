package com.disaster.allocation.exception;

import org.springframework.http.HttpStatus;

/** Business-rule failure that maps to a specific HTTP status. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    public static ApiException notFound(String what, long id) {
        return new ApiException(HttpStatus.NOT_FOUND, what + " " + id + " not found");
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }
}
