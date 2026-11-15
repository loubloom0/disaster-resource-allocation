package com.disaster.allocation.repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.disaster.allocation.model.AuthDto.ApprovalSummary;
import com.disaster.allocation.model.AuthDto.OrganizationMatch;
import com.disaster.allocation.model.AuthDto.UserSummary;

@Repository
public class AuthRepository {
    private final JdbcTemplate jdbc;

    public AuthRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean activeOrganizationExists(long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM organizations WHERE id=? AND active=true)", Boolean.class, id));
    }

    public boolean organizationExists(long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM organizations WHERE id=?)", Boolean.class, id));
    }

    public List<Map<String, Object>> organizationsForRegistration() {
        return jdbc.queryForList("SELECT id,name,org_type AS \"orgType\" FROM organizations WHERE active=true ORDER BY name,id");
    }

    public List<OrganizationMatch> organizationMatches(String requestedName) {
        String normalized = normalizeOrganizationName(requestedName);
        if (normalized.length() < 3) return List.of();
        List<OrganizationMatch> matches = jdbc.query(
                "SELECT id,name,org_type FROM organizations WHERE active=true ORDER BY name,id",
                (rs, row) -> {
                    String name = rs.getString("name");
                    String candidate = normalizeOrganizationName(name);
                    int distance = editDistance(normalized, candidate);
                    double similarity = 1d - (double) distance / Math.max(normalized.length(), candidate.length());
                    String kind = normalized.equals(candidate) ? "EXACT"
                            : normalized.contains(candidate) || candidate.contains(normalized) || similarity >= .78d ? "POSSIBLE" : null;
                    return kind == null ? null : new OrganizationMatch(rs.getLong("id"), name,
                            rs.getString("org_type"), kind);
                });
        return matches.stream().filter(java.util.Objects::nonNull)
                .sorted(java.util.Comparator.comparing((OrganizationMatch m) -> "EXACT".equals(m.matchKind()) ? 0 : 1)
                        .thenComparing(OrganizationMatch::name))
                .limit(5).toList();
    }

    private static String normalizeOrganizationName(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static int editDistance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int[] current = new int[b.length() + 1]; current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            }
            previous = current;
        }
        return previous[b.length()];
    }

    public boolean emailExists(String email) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM app_users WHERE lower(email)=lower(?))",
                Boolean.class, email));
    }

    public void insertInitialGlobalAccount(String email, String passwordHash, String name, String role) {
        jdbc.update("INSERT INTO app_users(email,password_hash,full_name,global_role,status,must_change_password) "
                        + "VALUES (?,?,?,?, 'ACTIVE', true)",
                email.toLowerCase(java.util.Locale.ROOT), passwordHash, name, role);
    }

    public long register(String email, String passwordHash, String name, String contact, String requestedRole,
                         Long organizationId, String requestedOrganizationName, Long matchedOrganizationId) {
        Long id = jdbc.queryForObject("INSERT INTO app_users(email,password_hash,full_name,contact_info) VALUES (?,?,?,?) RETURNING id",
                Long.class, email, passwordHash, name, contact);
        if (organizationId != null) {
            jdbc.update("INSERT INTO organization_memberships(user_id,organization_id,role,status) VALUES (?,?,?,'PENDING_APPROVAL')",
                    id, organizationId, requestedRole);
        }
        jdbc.update("INSERT INTO account_approval_requests(user_id,organization_id,requested_role,requested_organization_name,matched_organization_id) "
                        + "VALUES (?,?,?,?,?)",
                id, organizationId, requestedRole, requestedOrganizationName, matchedOrganizationId);
        audit(null, "ACCOUNT_REGISTRATION", "USER", String.valueOf(id), "{}");
        return id;
    }

    public Optional<User> byEmail(String email) {
        return jdbc.query("SELECT u.id,u.email,u.password_hash,u.full_name,u.contact_info,u.global_role,u.status,u.token_version,u.must_change_password,"
                + "m.organization_id,m.role AS organization_role,m.status AS membership_status,o.active AS organization_active "
                + "FROM app_users u LEFT JOIN organization_memberships m ON m.user_id=u.id LEFT JOIN organizations o ON o.id=m.organization_id WHERE lower(u.email)=lower(?)",
                (rs, row) -> new User(rs.getLong("id"), rs.getString("email"), rs.getString("password_hash"),
                        rs.getString("full_name"), rs.getString("contact_info"), rs.getString("global_role"),
                        rs.getString("status"), rs.getInt("token_version"), rs.getBoolean("must_change_password"),
                        (Long) rs.getObject("organization_id"), rs.getString("organization_role"), rs.getString("membership_status"),
                        rs.getObject("organization_active") != null && rs.getBoolean("organization_active")), email)
                .stream().findFirst();
    }

    public Optional<User> byId(long id) {
        return jdbc.query("SELECT u.id,u.email,u.password_hash,u.full_name,u.contact_info,u.global_role,u.status,u.token_version,u.must_change_password,"
                + "m.organization_id,m.role AS organization_role,m.status AS membership_status,o.active AS organization_active "
                + "FROM app_users u LEFT JOIN organization_memberships m ON m.user_id=u.id LEFT JOIN organizations o ON o.id=m.organization_id WHERE u.id=?",
                (rs, row) -> new User(rs.getLong("id"), rs.getString("email"), rs.getString("password_hash"),
                        rs.getString("full_name"), rs.getString("contact_info"), rs.getString("global_role"),
                        rs.getString("status"), rs.getInt("token_version"), rs.getBoolean("must_change_password"), (Long) rs.getObject("organization_id"),
                        rs.getString("organization_role"), rs.getString("membership_status"),
                        rs.getObject("organization_active") != null && rs.getBoolean("organization_active")), id).stream().findFirst();
    }

    public long saveRefresh(long userId, String hash, Instant expiry) {
        return jdbc.queryForObject("INSERT INTO refresh_tokens(user_id,token_hash,expires_at) VALUES (?,?,?) RETURNING id",
                Long.class, userId, hash, java.time.OffsetDateTime.ofInstant(expiry, java.time.ZoneOffset.UTC));
    }

    public Optional<RefreshToken> refreshByHash(String hash) {
        return jdbc.query("SELECT id,user_id,expires_at,revoked_at FROM refresh_tokens WHERE token_hash=?",
                (rs, row) -> new RefreshToken(rs.getLong("id"), rs.getLong("user_id"),
                        rs.getTimestamp("expires_at").toInstant(), rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant()),
                hash).stream().findFirst();
    }

    public void revokeRefresh(String hash, Long replacementId) {
        jdbc.update("UPDATE refresh_tokens SET revoked_at=now(),replaced_by=? WHERE token_hash=? AND revoked_at IS NULL", replacementId, hash);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeAllRefresh(long userId) {
        jdbc.update("UPDATE refresh_tokens SET revoked_at=now() WHERE user_id=? AND revoked_at IS NULL", userId);
    }

    public void revokeAllRefreshInTransaction(long userId) {
        jdbc.update("UPDATE refresh_tokens SET revoked_at=now() WHERE user_id=? AND revoked_at IS NULL", userId);
    }

    public void updatePassword(long userId, String passwordHash) {
        jdbc.update("UPDATE app_users SET password_hash=?,must_change_password=false,token_version=token_version+1,updated_at=now() WHERE id=?",
                passwordHash, userId);
    }

    public void audit(Long actor, String action, String type, String target, String json) {
        jdbc.update("INSERT INTO audit_logs(actor_user_id,action,target_type,target_id,details) VALUES (?,?,?,?,?::jsonb)",
                actor, action, type, target, json);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void loginFailure(String email) {
        audit(null, "LOGIN_FAILURE", "ACCOUNT", email == null ? "unknown" : email.toLowerCase(), "{}");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void refreshReuse(long userId) {
        audit(userId, "REFRESH_REUSE_DETECTED", "USER", String.valueOf(userId), "{}");
    }

    public List<ApprovalSummary> approvals() {
        List<ApprovalRow> rows = jdbc.query("SELECT a.id,a.user_id,u.email,u.full_name,u.contact_info,a.organization_id,"
                + "a.requested_organization_name,a.matched_organization_id,a.requested_role,a.created_at "
                + "FROM account_approval_requests a JOIN app_users u ON u.id=a.user_id "
                + "WHERE a.status='PENDING' ORDER BY a.created_at,a.id",
                (rs, row) -> new ApprovalRow(rs.getLong("id"), rs.getLong("user_id"), rs.getString("email"),
                        rs.getString("full_name"), rs.getString("contact_info"), (Long) rs.getObject("organization_id"),
                        rs.getString("requested_organization_name"), (Long) rs.getObject("matched_organization_id"),
                        rs.getString("requested_role"), rs.getTimestamp("created_at").toInstant()));
        return rows.stream().map(row -> new ApprovalSummary(row.id(), row.userId(), row.email(), row.fullName(),
                row.contactInfo(), row.organizationId(), row.organizationName(), row.matchedOrganizationId(), row.role(),
                row.organizationName() == null ? List.of() : organizationMatches(row.organizationName()), row.createdAt())).toList();
    }

    public Optional<PendingApproval> pendingApproval(long id) {
        return jdbc.query("SELECT user_id,requested_role,organization_id,requested_organization_name FROM account_approval_requests "
                        + "WHERE id=? AND status='PENDING'",
                (rs, row) -> new PendingApproval(rs.getLong("user_id"), rs.getString("requested_role"),
                        (Long) rs.getObject("organization_id"), rs.getString("requested_organization_name")), id)
                .stream().findFirst();
    }

    public List<UserSummary> users() {
        return jdbc.query("SELECT u.id,u.email,u.full_name,u.status,u.global_role,m.role org_role,m.organization_id,u.created_at "
                + "FROM app_users u LEFT JOIN organization_memberships m ON m.user_id=u.id ORDER BY u.created_at DESC",
                (rs, row) -> new UserSummary(rs.getLong("id"), rs.getString("email"), rs.getString("full_name"),
                        rs.getString("status"), rs.getString("global_role"), rs.getString("org_role"),
                        (Long) rs.getObject("organization_id"), rs.getTimestamp("created_at").toInstant()));
    }

    @Transactional
    public boolean decideApproval(long approvalId, long reviewer, boolean approve, String role, Long organizationId, String note) {
        List<Long> ids = jdbc.query("SELECT user_id FROM account_approval_requests WHERE id=? AND status='PENDING' FOR UPDATE",
                (rs, row) -> rs.getLong(1), approvalId);
        if (ids.isEmpty()) return false;
        long userId = ids.get(0);
        if (approve) {
            String requestedRole = jdbc.queryForObject("SELECT requested_role FROM account_approval_requests WHERE id=?", String.class, approvalId);
            boolean organizationRole = List.of("ORGANIZATION_MANAGER", "ORGANIZATION_MEMBER").contains(role);
            if (organizationRole) {
                if (!List.of("ORGANIZATION_MANAGER", "ORGANIZATION_MEMBER").contains(requestedRole)) {
                    throw new IllegalArgumentException("A privileged-role request cannot be approved as an organization role");
                }
                if (organizationId == null || !activeOrganizationExists(organizationId)) {
                    throw new IllegalArgumentException("Approval requires an active organization association");
                }
            } else if (!List.of("SYSTEM_ADMIN", "DISPATCHER").contains(role)
                    || !role.equals(requestedRole) || organizationId != null) {
                throw new IllegalArgumentException("Privileged approval must match the requested role and have no organization");
            }
            jdbc.update("UPDATE account_approval_requests SET status='APPROVED',reviewed_by=?,reviewed_at=now(),review_note=?,approved_role=?,approved_organization_id=? WHERE id=?",
                    reviewer, note, role, organizationId, approvalId);
            if (organizationRole) {
                jdbc.update("UPDATE app_users SET global_role=NULL,status='ACTIVE',updated_at=now(),token_version=token_version+1 WHERE id=?", userId);
                jdbc.update("INSERT INTO organization_memberships(user_id,organization_id,role,status) VALUES (?,?,?,'ACTIVE') "
                                + "ON CONFLICT(user_id) DO UPDATE SET organization_id=EXCLUDED.organization_id,role=EXCLUDED.role,status='ACTIVE',updated_at=now()",
                        userId, organizationId, role);
            } else {
                jdbc.update("UPDATE app_users SET global_role=?,status='ACTIVE',updated_at=now(),token_version=token_version+1 WHERE id=?", role, userId);
            }
        } else {
            jdbc.update("UPDATE account_approval_requests SET status='REJECTED',reviewed_by=?,reviewed_at=now(),review_note=? WHERE id=?",
                    reviewer, note, approvalId);
            jdbc.update("UPDATE app_users SET status='DISABLED',updated_at=now(),token_version=token_version+1 WHERE id=?", userId);
            jdbc.update("UPDATE organization_memberships SET status='DISABLED',updated_at=now() WHERE user_id=?", userId);
        }
        return true;
    }

    public void setUserRoles(long id, String globalRole, String organizationRole, Long organizationId, String status) {
        jdbc.update("UPDATE app_users SET global_role=?,status=COALESCE(?,status),token_version=token_version+1,updated_at=now() WHERE id=?",
                globalRole, status, id);
        if (organizationRole != null) {
            jdbc.update("UPDATE organization_memberships SET role=?,organization_id=COALESCE(?,organization_id),updated_at=now() WHERE user_id=?",
                    organizationRole, organizationId, id);
        }
    }

    public record User(long id, String email, String passwordHash, String fullName, String contactInfo,
                       String globalRole, String status, int tokenVersion, boolean mustChangePassword, Long organizationId,
                       String organizationRole, String membershipStatus, boolean organizationActive) { }
    public record RefreshToken(long id, long userId, Instant expiresAt, Instant revokedAt) { }

    public record PendingApproval(long userId, String requestedRole, Long requestedOrganizationId,
                                  String requestedOrganizationName) { }

    private record ApprovalRow(long id, long userId, String email, String fullName, String contactInfo,
                               Long organizationId, String organizationName, Long matchedOrganizationId,
                               String role, Instant createdAt) { }
}
