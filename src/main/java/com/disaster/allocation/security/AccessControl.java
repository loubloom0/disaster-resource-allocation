package com.disaster.allocation.security;

import java.util.Collection;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import com.disaster.allocation.exception.ApiException;

/** Shared server-side role and organization checks. Never trusts organization IDs supplied by a tenant. */
@Component
public class AccessControl {
    private final boolean testProfile;
    public AccessControl(Environment env) { this.testProfile = env.acceptsProfiles(Profiles.of("test & !auth-test")); }

    public boolean isGlobalOperator() {
        if (testProfile) return true;
        Collection<String> roles = roles();
        return roles.contains("SYSTEM_ADMIN") || roles.contains("DISPATCHER");
    }

    public boolean hasRole(String role) { return testProfile || roles().contains(role); }
    public boolean isAdministrator() { return hasRole("SYSTEM_ADMIN"); }
    public boolean isOrganizationManager() { return hasRole("ORGANIZATION_MANAGER"); }

    public void requireGlobalOperator() {
        if (!isGlobalOperator()) throw ApiException.forbidden("This operation requires a dispatcher or system administrator");
    }

    public void requireAdministrator() {
        if (!testProfile && !roles().contains("SYSTEM_ADMIN")) throw ApiException.forbidden("This operation requires a system administrator");
    }

    public void requireOrganizationManager(long organizationId) {
        if (testProfile || isAdministrator()) return;
        Long own = organizationId();
        if (own == null || own != organizationId || !isOrganizationManager()) {
            throw ApiException.forbidden("Organization manager role required");
        }
    }

    public void requireOrganization(long organizationId) {
        if (testProfile || isGlobalOperator()) return;
        Long own = organizationId();
        if (own == null || own != organizationId) throw ApiException.forbidden("Organization access is not permitted");
    }

    public Long organizationId() {
        Object principal = authentication().getPrincipal();
        if (principal instanceof Jwt jwt) {
            Object organization = jwt.getClaim("organization_id");
            return organization instanceof Number number ? number.longValue() : null;
        }
        return null;
    }

    public Long userId() {
        Object principal = authentication().getPrincipal();
        if (principal instanceof Jwt jwt) return Long.valueOf(jwt.getSubject());
        return null;
    }

    private Collection<String> roles() {
        Object principal = authentication().getPrincipal();
        if (!(principal instanceof Jwt jwt)) return java.util.List.of();
        Object claim = jwt.getClaim("roles");
        if (claim instanceof Collection<?> values) return values.stream().map(String::valueOf).toList();
        return java.util.List.of();
    }
    private Authentication authentication() { return SecurityContextHolder.getContext().getAuthentication(); }
}
