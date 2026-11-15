package com.disaster.allocation.controller;

import java.util.List;

import jakarta.validation.Valid;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.disaster.allocation.exception.ApiException;
import com.disaster.allocation.model.AuthDto.MemberStatusInput;
import com.disaster.allocation.model.AuthDto.OrganizationMemberSummary;
import com.disaster.allocation.security.AccessControl;
import com.disaster.allocation.service.AuditService;

@RestController
@RequestMapping("/api/organizations/{organizationId}/members")
public class OrganizationMemberController {
    private final JdbcTemplate jdbc;
    private final AccessControl access;
    private final AuditService audit;

    public OrganizationMemberController(JdbcTemplate jdbc, AccessControl access, AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
    }

    @GetMapping
    public List<OrganizationMemberSummary> list(@PathVariable long organizationId) {
        access.requireOrganizationManager(organizationId);
        return jdbc.query("SELECT u.id,u.email,u.full_name,u.status AS account_status,m.status AS membership_status,m.role,m.created_at FROM organization_memberships m "
                        + "JOIN app_users u ON u.id=m.user_id WHERE m.organization_id=? ORDER BY m.created_at,u.id",
                (rs, row) -> new OrganizationMemberSummary(rs.getLong("id"), rs.getString("email"),
                        rs.getString("full_name"), rs.getString("account_status"), rs.getString("membership_status"), rs.getString("role"),
                        rs.getTimestamp("created_at").toInstant()), organizationId);
    }

    @PutMapping("/{userId}/status")
    public void updateStatus(@PathVariable long organizationId, @PathVariable long userId,
                             @Valid @RequestBody MemberStatusInput input,
                             @AuthenticationPrincipal Jwt jwt) {
        access.requireOrganizationManager(organizationId);
        if (access.userId() != null && access.userId() == userId) {
            throw ApiException.badRequest("You cannot change your own organization membership status");
        }
        String allowedRoles = access.isAdministrator() ? "('ORGANIZATION_MANAGER','ORGANIZATION_MEMBER')" : "('ORGANIZATION_MEMBER')";
        int updated = jdbc.update("UPDATE organization_memberships SET status=?,updated_at=now() "
                        + "WHERE user_id=? AND organization_id=? AND role IN " + allowedRoles,
                input.status(), userId, organizationId);
        if (updated == 0) throw ApiException.notFound("Organization member", userId);
        jdbc.update("UPDATE app_users SET token_version=token_version+1,updated_at=now() WHERE id=?", userId);
        audit.record("ORGANIZATION_MEMBERSHIP_STATUS_CHANGED", "USER", userId,
                java.util.Map.of("organizationId", organizationId, "status", input.status()));
    }
}
