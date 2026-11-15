package com.disaster.allocation.controller;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.disaster.allocation.exception.ApiException;
import com.disaster.allocation.realtime.OperationalEventService;

@RestController
@RequestMapping("/api/events")
public class OperationalEventsController {
    private static final List<String> ALLOWED_ROLES = List.of(
            "SYSTEM_ADMIN", "DISPATCHER", "ORGANIZATION_MANAGER", "ORGANIZATION_MEMBER");

    private final OperationalEventService events;

    public OperationalEventsController(OperationalEventService events) { this.events = events; }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@AuthenticationPrincipal Jwt jwt, HttpServletResponse response) {
        if (jwt == null) throw ApiException.unauthorized("Authentication is required");
        Object claim = jwt.getClaim("roles");
        if (!(claim instanceof Collection<?> roles)) throw ApiException.forbidden("A supported DRAS role is required");
        List<String> matched = roles.stream().map(String::valueOf).filter(ALLOWED_ROLES::contains).toList();
        if (matched.size() != 1) throw ApiException.forbidden("Exactly one supported DRAS role is required");

        long userId;
        try { userId = Long.parseLong(jwt.getSubject()); }
        catch (RuntimeException error) { throw ApiException.unauthorized("Authenticated user identity is invalid"); }
        Object organizationClaim = jwt.getClaim("organization_id");
        Long organizationId = organizationClaim instanceof Number number ? number.longValue() : null;
        Instant expiresAt = jwt.getExpiresAt();
        if (expiresAt == null || !expiresAt.isAfter(Instant.now())) {
            throw ApiException.unauthorized("A valid token expiration is required");
        }

        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        try {
            return events.connect(new OperationalEventService.Identity(userId, matched.getFirst(), organizationId, expiresAt));
        } catch (IllegalArgumentException error) {
            throw ApiException.forbidden(error.getMessage());
        }
    }
}
