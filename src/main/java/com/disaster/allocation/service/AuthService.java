package com.disaster.allocation.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.disaster.allocation.exception.ApiException;
import com.disaster.allocation.model.AuthDto.PublicUser;
import com.disaster.allocation.model.AuthDto.OrganizationMatch;
import com.disaster.allocation.model.AuthDto.ChangePasswordInput;
import com.disaster.allocation.model.AuthDto.RegisterInput;
import com.disaster.allocation.model.AuthDto.Session;
import com.disaster.allocation.model.Dto.OrganizationInput;
import com.disaster.allocation.repository.AuthRepository;
import com.disaster.allocation.repository.AuthRepository.User;
import com.disaster.allocation.repository.AuthRepository.RefreshToken;
import com.disaster.allocation.repository.AuthRepository.PendingApproval;

@Service
public class AuthService {
    private final AuthRepository users;
    private final PasswordEncoder passwords;
    private final JwtEncoder jwtEncoder;
    private final OrganizationService organizations;
    private final SecureRandom random = new SecureRandom();
    private final long accessSeconds;
    private final long refreshSeconds;

    public AuthService(AuthRepository users, PasswordEncoder passwords, JwtEncoder jwtEncoder, OrganizationService organizations,
                       @Value("${dras.auth.access-token-seconds:900}") long accessSeconds,
                       @Value("${dras.auth.refresh-token-days:14}") long refreshDays) {
        this.users = users;
        this.passwords = passwords;
        this.jwtEncoder = jwtEncoder;
        this.organizations = organizations;
        this.accessSeconds = accessSeconds;
        this.refreshSeconds = Math.multiplyExact(refreshDays, 86400L);
    }

    @Transactional
    public void register(RegisterInput input) {
        if (input.confirmPassword() == null || !input.password().equals(input.confirmPassword())) {
            throw ApiException.badRequest("Password and confirmation must match");
        }
        String email = input.email().trim().toLowerCase();
        String role = input.requestedRole() == null || input.requestedRole().isBlank()
                ? "ORGANIZATION_MEMBER" : input.requestedRole();
        if (!List.of("SYSTEM_ADMIN", "DISPATCHER", "ORGANIZATION_MEMBER", "ORGANIZATION_MANAGER").contains(role)) {
            throw ApiException.badRequest("Choose one of the available registration roles");
        }
        Long organizationId = input.organizationId();
        String requestedName = null;
        Long matchedOrganizationId = null;
        if ("ORGANIZATION_MEMBER".equals(role)) {
            if (organizationId == null) throw ApiException.badRequest("Select an active organization");
            if (!users.organizationExists(organizationId)) throw ApiException.notFound("Organization", organizationId);
            if (!users.activeOrganizationExists(organizationId)) {
                throw ApiException.badRequest("The selected organization is missing or inactive");
            }
        } else if ("ORGANIZATION_MANAGER".equals(role)) {
            if (organizationId != null) throw ApiException.badRequest("Manager applications cannot self-assign an existing organization");
            requestedName = input.requestedOrganizationName() == null ? "" : input.requestedOrganizationName().trim();
            if (requestedName.length() < 3) throw ApiException.badRequest("Enter an organization name with at least 3 characters");
            List<OrganizationMatch> matches = users.organizationMatches(requestedName);
            matchedOrganizationId = matches.isEmpty() ? null : matches.get(0).id();
        } else if (organizationId != null || (input.requestedOrganizationName() != null && !input.requestedOrganizationName().isBlank())) {
            throw ApiException.badRequest("Privileged-role requests cannot include an organization association");
        }
        if (users.byEmail(email).isPresent()) throw ApiException.conflict("An account with this email already exists");
        users.register(email, passwords.encode(input.password()), input.fullName().trim(), input.contactInfo(), role,
                organizationId, requestedName, matchedOrganizationId);
    }

    public List<Map<String, Object>> organizationsForRegistration() { return users.organizationsForRegistration(); }

    public List<OrganizationMatch> organizationMatches(String name) {
        return users.organizationMatches(name == null ? "" : name.trim());
    }

    @Transactional
    public Issued login(String email, String rawPassword) {
        User user = users.byEmail(email == null ? "" : email.trim()).orElse(null);
        if (user == null || !passwords.matches(rawPassword, user.passwordHash())) {
            users.loginFailure(email);
            throw ApiException.unauthorized("Email or password is incorrect");
        }
        if (!"ACTIVE".equals(user.status()) || (user.globalRole() == null
                && (!"ACTIVE".equals(user.membershipStatus()) || !user.organizationActive()))) {
            users.loginFailure(email);
            throw ApiException.forbidden("Account is pending approval or disabled");
        }
        return issue(user);
    }

    @Transactional
    public Issued refresh(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) throw ApiException.unauthorized("Refresh session is missing");
        String hash = hash(rawToken);
        RefreshToken token = users.refreshByHash(hash).orElse(null);
        if (token == null) throw ApiException.unauthorized("Refresh session is invalid or expired");
        if (token.revokedAt() != null) {
            users.revokeAllRefresh(token.userId());
            users.refreshReuse(token.userId());
            throw ApiException.unauthorized("Refresh session has already been used");
        }
        if (!token.expiresAt().isAfter(Instant.now())) throw ApiException.unauthorized("Refresh session is expired");
        User user = users.byId(token.userId()).orElseThrow(() -> ApiException.unauthorized("Account no longer exists"));
        if (!"ACTIVE".equals(user.status()) || (user.globalRole() == null
                && (!"ACTIVE".equals(user.membershipStatus()) || !user.organizationActive()))) {
            throw ApiException.unauthorized("Account or organization membership is not active");
        }
        Issued issued = issue(user);
        users.revokeRefresh(hash, issued.refreshTokenId());
        return issued;
    }

    @Transactional
    public void logout(String rawToken, Long actor) {
        if (rawToken != null && !rawToken.isBlank()) users.revokeRefresh(hash(rawToken), null);
        users.audit(actor, "LOGOUT", "USER", actor == null ? null : actor.toString(), "{}");
    }

    public PublicUser me(long id) {
        User u = users.byId(id).orElseThrow(() -> ApiException.unauthorized("Account no longer exists"));
        return publicUser(u);
    }

    @Transactional
    public Issued changePassword(long userId, ChangePasswordInput input) {
        User current = users.byId(userId).orElseThrow(() -> ApiException.unauthorized("Account no longer exists"));
        if (!passwords.matches(input.currentPassword(), current.passwordHash())) {
            throw ApiException.unauthorized("Current password is incorrect");
        }
        if (!input.newPassword().equals(input.confirmPassword())) {
            throw ApiException.badRequest("New password and confirmation must match");
        }
        if (passwords.matches(input.newPassword(), current.passwordHash())) {
            throw ApiException.badRequest("Choose a new password different from the temporary password");
        }
        users.updatePassword(userId, passwords.encode(input.newPassword()));
        users.revokeAllRefreshInTransaction(userId);
        User updated = users.byId(userId).orElseThrow(() -> ApiException.unauthorized("Account no longer exists"));
        users.audit(userId, "PASSWORD_CHANGED", "USER", String.valueOf(userId), "{}");
        return issue(updated);
    }

    public List<String> rolesFor(long id) {
        User user = users.byId(id).orElseThrow(() -> ApiException.unauthorized("Account no longer exists"));
        return roles(user);
    }

    @Transactional
    public boolean review(long approvalId, long reviewer, boolean approve, String role, Long organizationId, String note) {
        PendingApproval application = users.pendingApproval(approvalId).orElse(null);
        if (application == null) return false;
        if (application.userId() == reviewer) throw ApiException.forbidden("You cannot review your own registration request");
        if (!approve) {
            boolean changed = users.decideApproval(approvalId, reviewer, false, null, null, note);
            if (changed) users.audit(reviewer, "USER_REJECTED", "APPROVAL", String.valueOf(approvalId), "{}");
            return changed;
        }
        String approvedRole = role == null ? application.requestedRole() : role;
        boolean organizationRole = List.of("ORGANIZATION_MEMBER", "ORGANIZATION_MANAGER").contains(approvedRole);
        Long approvedOrg;
        if (organizationRole) {
            if (!List.of("ORGANIZATION_MEMBER", "ORGANIZATION_MANAGER").contains(application.requestedRole())) {
                throw ApiException.badRequest("A privileged-role request cannot be approved as an organization role");
            }
            approvedOrg = organizationId == null ? application.requestedOrganizationId() : organizationId;
            if (approvedOrg == null) throw ApiException.badRequest("Select an organization before approving this application");
            if (!users.activeOrganizationExists(approvedOrg)) throw ApiException.badRequest("The approved organization must be active");
        } else {
            if (!List.of("SYSTEM_ADMIN", "DISPATCHER").contains(approvedRole)
                    || !approvedRole.equals(application.requestedRole()) || organizationId != null) {
                throw ApiException.badRequest("Privileged approvals must match the requested global role and have no organization");
            }
            approvedOrg = null;
        }
        boolean changed = users.decideApproval(approvalId, reviewer, true, approvedRole, approvedOrg, note);
        if (!changed) return false;
        users.audit(reviewer, "USER_APPROVED", "APPROVAL", String.valueOf(approvalId), "{}");
        return true;
    }

    @Transactional
    public long approveWithNewOrganization(long approvalId, long reviewer, String role, String note,
                                          OrganizationInput organization) {
        PendingApproval application = users.pendingApproval(approvalId)
                .orElseThrow(() -> ApiException.notFound("Pending approval", approvalId));
        if (application.userId() == reviewer) throw ApiException.forbidden("You cannot review your own registration request");
        if (!"ORGANIZATION_MANAGER".equals(application.requestedRole())) {
            throw ApiException.badRequest("Only organization manager applications can request new organization creation");
        }
        String proposed = application.requestedOrganizationName();
        if (!normalize(proposed).equals(normalize(organization.name()))) {
            throw ApiException.badRequest("The organization name must match the applicant's requested name");
        }
        List<OrganizationMatch> matches = users.organizationMatches(proposed);
        if (matches.stream().anyMatch(match -> "EXACT".equals(match.matchKind()))) {
            throw ApiException.conflict("An organization with this name already exists; associate the applicant with it instead");
        }
        var created = organizations.create(new OrganizationInput(organization.name().trim(), organization.orgType(),
                organization.location().trim(), organization.contactInfo(), true));
        String approvedRole = role == null ? "ORGANIZATION_MANAGER" : role;
        boolean changed = users.decideApproval(approvalId, reviewer, true, approvedRole, created.id(), note);
        if (!changed) throw ApiException.notFound("Pending approval", approvalId);
        users.audit(reviewer, "USER_APPROVED", "APPROVAL", String.valueOf(approvalId),
                "{\"approvedOrganizationId\":" + created.id() + "}");
        return created.id();
    }

    public List<String> roles(User user) {
        if (user.globalRole() != null) return List.of(user.globalRole());
        return user.organizationRole() == null ? List.of() : List.of(user.organizationRole());
    }

    private Issued issue(User user) {
        Instant now = Instant.now();
        List<String> roles = roles(user);
        JwtClaimsSet.Builder claimsBuilder = JwtClaimsSet.builder().issuer("dras").issuedAt(now).expiresAt(now.plusSeconds(accessSeconds))
                .subject(String.valueOf(user.id())).claim("email", user.email()).claim("name", user.fullName())
                .claim("roles", roles).claim("token_version", user.tokenVersion())
                .claim("must_change_password", user.mustChangePassword());
        if (user.organizationId() != null) claimsBuilder.claim("organization_id", user.organizationId());
        JwtClaimsSet claims = claimsBuilder.build();
        String access = jwtEncoder.encode(JwtEncoderParameters.from(
                org.springframework.security.oauth2.jwt.JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        byte[] bytes = new byte[48]; random.nextBytes(bytes);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        long tokenId = users.saveRefresh(user.id(), hash(refresh), now.plusSeconds(refreshSeconds));
        return new Issued(new Session(access, "Bearer", accessSeconds, publicUser(user)), refresh, tokenId);
    }

    private PublicUser publicUser(User u) {
        return new PublicUser(u.id(), u.email(), u.fullName(), u.status(),
                u.globalRole() == null ? u.organizationRole() : u.globalRole(), u.organizationId(), u.mustChangePassword());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public record Issued(Session session, String refreshToken, long refreshTokenId) { }
}
