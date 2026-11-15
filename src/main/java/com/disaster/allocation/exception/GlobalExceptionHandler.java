package com.disaster.allocation.exception;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** One place that turns exceptions into consistent JSON error bodies. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<?> api(ApiException e, HttpServletRequest request) {
        if ("/api/events/stream".equals(request.getRequestURI())
                && request.getHeader("Accept") != null
                && request.getHeader("Accept").contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
            // Before an SSE stream exists, communicate authentication/authorization failures
            // through the HTTP status. A JSON body is not a valid text/event-stream response.
            return ResponseEntity.status(e.status()).build();
        }
        return body(e.status(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException e) {
        StringBuilder sb = new StringBuilder();
        e.getBindingResult().getFieldErrors().forEach(f ->
                sb.append(sb.length() > 0 ? "; " : "").append(f.getField()).append(' ').append(f.getDefaultMessage()));
        return body(HttpStatus.BAD_REQUEST, sb.toString());
    }

    /** CHECK / UNIQUE / FK violations raised by PostgreSQL itself. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> integrity(DataIntegrityViolationException e) {
        return body(HttpStatus.CONFLICT, "Database constraint violated: " + e.getMostSpecificCause().getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> illegal(IllegalArgumentException e) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus s, String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", s.value());
        m.put("error", s.getReasonPhrase());
        m.put("message", msg);
        return ResponseEntity.status(s).body(m);
    }
}
