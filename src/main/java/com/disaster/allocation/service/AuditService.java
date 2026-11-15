package com.disaster.allocation.service;

import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AuditService(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    public Long actorId() {
        if (SecurityContextHolder.getContext().getAuthentication() == null) return null;
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return principal instanceof Jwt jwt ? Long.valueOf(jwt.getSubject()) : null;
    }

    public void record(String action, String targetType, Object targetId, Map<String, ?> details) {
        try {
            jdbc.update("INSERT INTO audit_logs(actor_user_id,action,target_type,target_id,details) VALUES (?,?,?,?,?::jsonb)",
                    actorId(), action, targetType, targetId == null ? null : String.valueOf(targetId), mapper.writeValueAsString(details));
        } catch (JsonProcessingException e) { throw new IllegalArgumentException("Could not serialize audit event", e); }
    }
}
