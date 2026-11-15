package com.disaster.allocation.controller;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.disaster.allocation.model.AuthDto.ApprovalInput;
import com.disaster.allocation.model.AuthDto.ApprovalSummary;
import com.disaster.allocation.model.AuthDto.RoleInput;
import com.disaster.allocation.model.AuthDto.UserSummary;
import com.disaster.allocation.model.AuthDto.NewOrganizationApprovalInput;
import com.disaster.allocation.repository.AuthRepository;
import com.disaster.allocation.service.AuthService;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class AdminUserController {
    private final AuthRepository repository;
    private final AuthService auth;
    private final JdbcTemplate jdbc;

    public AdminUserController(AuthRepository repository, AuthService auth, JdbcTemplate jdbc) {
        this.repository = repository;
        this.auth = auth;
        this.jdbc = jdbc;
    }

    @GetMapping("/approvals")
    public List<ApprovalSummary> approvals() { return repository.approvals(); }

    @PostMapping("/approvals/{id}/approve")
    public Map<String, String> approve(@PathVariable long id, @Valid @RequestBody ApprovalInput input,
                                       @AuthenticationPrincipal Jwt jwt) {
        if (!auth.review(id, actor(jwt), true, input.organizationRole(), input.organizationId(), input.reviewNote())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Approval request not found or already reviewed");
        }
        return Map.of("status", "APPROVED");
    }

    @PostMapping("/approvals/{id}/approve-new-organization")
    public Map<String, Object> approveWithNewOrganization(@PathVariable long id,
            @Valid @RequestBody NewOrganizationApprovalInput input, @AuthenticationPrincipal Jwt jwt) {
        long organizationId = auth.approveWithNewOrganization(id, actor(jwt), input.organizationRole(), input.reviewNote(),
                new com.disaster.allocation.model.Dto.OrganizationInput(input.name(), input.orgType(), input.location(),
                        input.contactInfo(), true));
        return Map.of("status", "APPROVED", "organizationId", organizationId);
    }

    @PostMapping("/approvals/{id}/reject")
    public Map<String, String> reject(@PathVariable long id, @Valid @RequestBody ApprovalInput input,
                                      @AuthenticationPrincipal Jwt jwt) {
        if (!auth.review(id, actor(jwt), false, null, null, input.reviewNote())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Approval request not found or already reviewed");
        }
        return Map.of("status", "REJECTED");
    }

    @GetMapping("/users")
    public List<UserSummary> users() { return repository.users(); }

    @GetMapping("/audit-logs")
    public List<Map<String, Object>> auditLogs(@RequestParam(defaultValue = "100") int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 250));
        return jdbc.queryForList("SELECT a.id,a.action,a.target_type AS \"targetType\",a.target_id AS \"targetId\","
                + "a.details,a.created_at AS \"createdAt\",u.email AS \"actorEmail\",u.full_name AS \"actorName\" "
                + "FROM audit_logs a LEFT JOIN app_users u ON u.id=a.actor_user_id ORDER BY a.created_at DESC,a.id DESC LIMIT ?", safeLimit);
    }

    @PutMapping("/users/{id}/role")
    @Transactional
    public Map<String, String> role(@PathVariable long id, @Valid @RequestBody RoleInput input,
                                     @AuthenticationPrincipal Jwt jwt) {
        Map<String, Object> current;
        try { current = jdbc.queryForMap("SELECT status,global_role FROM app_users WHERE id=? FOR UPDATE", id); }
        catch (org.springframework.dao.EmptyResultDataAccessException e) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"); }
        if (!"ACTIVE".equals(current.get("status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Pending and disabled accounts must be handled through the approval workflow");
        }
        if ("SYSTEM_ADMIN".equals(current.get("global_role")) && !"SYSTEM_ADMIN".equals(input.globalRole())) {
            Long otherAdmins = jdbc.queryForObject("SELECT count(*) FROM app_users WHERE global_role='SYSTEM_ADMIN' AND status='ACTIVE' AND id<>?", Long.class, id);
            if (otherAdmins != null && otherAdmins == 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The final active system administrator cannot be demoted");
            }
        }
        String global = input.globalRole();
        String orgRole = input.organizationRole();
        if (global != null && !List.of("SYSTEM_ADMIN", "DISPATCHER").contains(global)) {
            throw new IllegalArgumentException("Invalid global role");
        }
        if (orgRole != null && !List.of("ORGANIZATION_MANAGER", "ORGANIZATION_MEMBER").contains(orgRole)) {
            throw new IllegalArgumentException("Invalid organization role");
        }
        if (global == null && orgRole == null) throw new IllegalArgumentException("A role is required");
        if (global != null && (orgRole != null || input.organizationId() != null)) {
            throw new IllegalArgumentException("Assign exactly one primary role");
        }
        int changed = jdbc.update("UPDATE app_users SET global_role=?,token_version=token_version+1,updated_at=now() WHERE id=?",
                global, id);
        if (changed == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        if (global != null) jdbc.update("DELETE FROM organization_memberships WHERE user_id=?", id);
        if (orgRole != null) {
            if (input.organizationId() == null || !repository.activeOrganizationExists(input.organizationId())) {
                throw new IllegalArgumentException("An active organization is required for an organization role");
            }
            jdbc.update("INSERT INTO organization_memberships(user_id,organization_id,role,status) VALUES (?,?,?,'ACTIVE') "
                            + "ON CONFLICT(user_id) DO UPDATE SET role=EXCLUDED.role,organization_id=EXCLUDED.organization_id,status='ACTIVE',updated_at=now()",
                    id, input.organizationId(), orgRole);
        }
        repository.audit(actor(jwt), "ROLE_CHANGED", "USER", String.valueOf(id),
                "{\"globalRole\":" + jsonString(global) + ",\"organizationRole\":" + jsonString(orgRole) + "}");
        return Map.of("status", "UPDATED");
    }

    private static long actor(Jwt jwt) { return Long.parseLong(jwt.getSubject()); }
    private static String jsonString(String value) { return value == null ? "null" : "\"" + value + "\""; }
}
