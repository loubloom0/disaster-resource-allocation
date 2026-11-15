package com.disaster.allocation.integration;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.List;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("auth-test")
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class AuthIntegrationIT extends DrasIntegrationBase {
    @Autowired MockMvc mvc;
    private String email;
    private String reviewerEmail;
    private final java.util.List<Long> createdOrganizations = new java.util.ArrayList<>();

    @Test
    void registrationApprovalLoginAndRotatingRefreshAreEnforced() throws Exception {
        email = "auth-" + UUID.randomUUID() + "@example.test";
        long organizationId = jdbc.queryForObject("SELECT id FROM organizations WHERE active=true ORDER BY id LIMIT 1", Long.class);
        mvc.perform(get("/api/auth/organizations")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").exists()).andExpect(jsonPath("$[0].name").exists())
                .andExpect(jsonPath("$[0].orgType").exists()).andExpect(jsonPath("$[0].contactInfo").doesNotExist());
        mvc.perform(get("/api/dashboard/stats")).andExpect(status().isUnauthorized());
        String registration = "{\"fullName\":\"Auth Test\",\"email\":\"" + email
                + "\",\"contactInfo\":\"auth-test-contact\",\"password\":\"test-password-long-123\",\"confirmPassword\":\"test-password-long-123\","
                + "\"requestedRole\":\"ORGANIZATION_MEMBER\",\"organizationId\":" + organizationId + "}";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(registration))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));

        long userId = jdbc.queryForObject("SELECT id FROM app_users WHERE email=?", Long.class, email);
        assertEquals("PENDING_APPROVAL", jdbc.queryForObject("SELECT status FROM app_users WHERE id=?", String.class, userId));
        assertEquals(organizationId, jdbc.queryForObject("SELECT organization_id FROM organization_memberships WHERE user_id=?", Long.class, userId));
        assertEquals("ORGANIZATION_MEMBER", jdbc.queryForObject("SELECT role FROM organization_memberships WHERE user_id=?", String.class, userId));
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM account_approval_requests WHERE user_id=?", String.class, userId));
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(registration))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"test-password-long-123\"}"))
                .andExpect(status().isForbidden());
        jdbc.update("UPDATE app_users SET status='ACTIVE' WHERE id=?", userId);
        jdbc.update("UPDATE organization_memberships SET role='ORGANIZATION_MANAGER',status='ACTIVE' WHERE user_id=?", userId);

        MvcResult login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"test-password-long-123\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Set-Cookie", containsString("HttpOnly")))
                .andExpect(jsonPath("$.user.role").value("ORGANIZATION_MANAGER")).andReturn();
        String access = com.jayway.jsonpath.JsonPath.read(login.getResponse().getContentAsString(), "$.accessToken");
        String oldCookie = cookieValue(login.getResponse().getHeader("Set-Cookie"));

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.email").value(email));
        MvcResult refreshed = mvc.perform(post("/api/auth/refresh").cookie(new Cookie("dras_refresh", oldCookie)))
                .andExpect(status().isOk()).andReturn();
        String newCookie = cookieValue(refreshed.getResponse().getHeader("Set-Cookie"));
        mvc.perform(post("/api/auth/refresh").cookie(new Cookie("dras_refresh", oldCookie)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").cookie(new Cookie("dras_refresh", newCookie)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void registrationRejectsMissingInvalidAndInactiveOrganizations() throws Exception {
        String prefix = "{\"fullName\":\"Auth Test\",\"email\":\"";
        String suffix = "\",\"password\":\"test-password-long-123\",\"confirmPassword\":\"test-password-long-123\",\"requestedRole\":\"ORGANIZATION_MEMBER\"";
        long inactiveId = jdbc.queryForObject("INSERT INTO organizations(name,org_type,location,active) VALUES (?,?,?,false) RETURNING id",
                Long.class, "Auth Inactive " + UUID.randomUUID(), "SHELTER", "Test only");
        String inactiveEmail = "inactive-" + UUID.randomUUID() + "@example.test";
        try {
            mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                            .content(prefix + inactiveEmail + suffix + ",\"organizationId\":" + inactiveId + "}"))
                    .andExpect(status().isBadRequest());
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM app_users WHERE email=?", Integer.class, inactiveEmail));
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id=?", inactiveId);
        }

        String invalidEmail = "invalid-" + UUID.randomUUID() + "@example.test";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(prefix + invalidEmail + suffix + ",\"organizationId\":9223372036854775807}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(prefix + "missing-org-" + UUID.randomUUID() + "@example.test" + suffix + "}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void managerRegistrationWaitsForOrganizationAssociationAndAdminApproval() throws Exception {
        email = "manager-" + UUID.randomUUID() + "@example.test";
        String requestedName = "City General Hospitl";
        String registration = "{\"fullName\":\"Manager Applicant\",\"email\":\"" + email
                + "\",\"contactInfo\":\"auth-test-contact\",\"password\":\"manager-test-password-123\",\"confirmPassword\":\"manager-test-password-123\","
                + "\"requestedRole\":\"ORGANIZATION_MANAGER\",\"requestedOrganizationName\":\"" + requestedName + "\"}";
        mvc.perform(get("/api/auth/organization-matches").param("name", requestedName))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].name").value("City General Hospital"));
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(registration))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
        long userId = jdbc.queryForObject("SELECT id FROM app_users WHERE email=?", Long.class, email);
        long approvalId = jdbc.queryForObject("SELECT id FROM account_approval_requests WHERE user_id=?", Long.class, userId);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM organization_memberships WHERE user_id=?", Integer.class, userId));
        mvc.perform(get("/api/admin/approvals").with(jwt().jwt(j -> j.subject(String.valueOf(userId))
                        .claim("roles", List.of("SYSTEM_ADMIN"))).authorities(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"email\":\"" + email + "\"")))
                .andExpect(content().string(containsString("\"contactInfo\":\"auth-test-contact\"")))
                .andExpect(content().string(containsString("City General Hospital")));
        reviewerEmail = "manager-reviewer-" + UUID.randomUUID() + "@example.test";
        long reviewerId = jdbc.queryForObject("INSERT INTO app_users(email,password_hash,full_name,global_role,status) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, reviewerEmail, "unused-test-hash", "Test Reviewer", "SYSTEM_ADMIN", "ACTIVE");
        mvc.perform(post("/api/admin/approvals/{id}/approve", approvalId)
                        .with(jwt().jwt(j -> j.subject(String.valueOf(reviewerId)).claim("roles", List.of("SYSTEM_ADMIN")))
                                .authorities(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"organizationRole\":\"ORGANIZATION_MANAGER\",\"organizationId\":1,\"reviewNote\":\"Verified\"}"))
                .andExpect(status().isOk());
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM app_users WHERE id=?", String.class, userId));
        assertEquals("ORGANIZATION_MANAGER", jdbc.queryForObject("SELECT role FROM organization_memberships WHERE user_id=?", String.class, userId));
        assertEquals(1L, jdbc.queryForObject("SELECT approved_organization_id FROM account_approval_requests WHERE id=?", Long.class, approvalId));
    }

    @Test
    void systemAdminSignupCreatesPendingRequestAndRequiresExistingAdminApproval() throws Exception {
        email = "admin-request-" + UUID.randomUUID() + "@example.test";
        String body = "{\"fullName\":\"Admin Applicant\",\"email\":\"" + email
                + "\",\"password\":\"test-password-long-123\",\"confirmPassword\":\"test-password-long-123\",\"requestedRole\":\"SYSTEM_ADMIN\"}";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
        long userId = jdbc.queryForObject("SELECT id FROM app_users WHERE email=?", Long.class, email);
        long approvalId = jdbc.queryForObject("SELECT id FROM account_approval_requests WHERE user_id=?", Long.class, userId);
        assertEquals("PENDING_APPROVAL", jdbc.queryForObject("SELECT status FROM app_users WHERE id=?", String.class, userId));
        assertEquals("SYSTEM_ADMIN", jdbc.queryForObject("SELECT requested_role FROM account_approval_requests WHERE id=?", String.class, approvalId));
        assertEquals(null, jdbc.queryForObject("SELECT global_role FROM app_users WHERE id=?", String.class, userId));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM organization_memberships WHERE user_id=?", Integer.class, userId));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"test-password-long-123\"}"))
                .andExpect(status().isForbidden());
        String selfReview = "{\"organizationRole\":\"SYSTEM_ADMIN\",\"reviewNote\":\"self review\"}";
        mvc.perform(post("/api/admin/approvals/{id}/approve", approvalId)
                        .with(jwt().jwt(j -> j.subject(String.valueOf(userId)).claim("roles", List.of("SYSTEM_ADMIN")))
                                .authorities(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON).content(selfReview))
                .andExpect(status().isForbidden());
        String adminEmail = "review-admin-" + UUID.randomUUID() + "@example.test";
        long adminId = jdbc.queryForObject("INSERT INTO app_users(email,password_hash,full_name,global_role,status) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, adminEmail, "unused-test-hash", "Test Reviewer", "SYSTEM_ADMIN", "ACTIVE");
        try {
            mvc.perform(post("/api/admin/approvals/{id}/approve", approvalId)
                            .with(jwt().jwt(j -> j.subject(String.valueOf(adminId)).claim("roles", List.of("SYSTEM_ADMIN")))
                                    .authorities(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"organizationRole\":\"SYSTEM_ADMIN\",\"reviewNote\":\"Test approved\"}"))
                    .andExpect(status().isOk());
            assertEquals("SYSTEM_ADMIN", jdbc.queryForObject("SELECT global_role FROM app_users WHERE id=?", String.class, userId));
            assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM app_users WHERE id=?", String.class, userId));
            assertEquals("SYSTEM_ADMIN", jdbc.queryForObject("SELECT approved_role FROM account_approval_requests WHERE id=?", String.class, approvalId));
        } finally { reviewerEmail = adminEmail; }
    }

    @Test
    void dispatcherSignupRemainsPendingAndOnlyAdminCanApproveIt() throws Exception {
        email = "dispatcher-request-" + UUID.randomUUID() + "@example.test";
        String body = "{\"fullName\":\"Dispatcher Applicant\",\"email\":\"" + email
                + "\",\"password\":\"test-password-long-123\",\"confirmPassword\":\"test-password-long-123\",\"requestedRole\":\"DISPATCHER\"}";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
        long userId = jdbc.queryForObject("SELECT id FROM app_users WHERE email=?", Long.class, email);
        long approvalId = jdbc.queryForObject("SELECT id FROM account_approval_requests WHERE user_id=?", Long.class, userId);
        assertEquals("PENDING_APPROVAL", jdbc.queryForObject("SELECT status FROM app_users WHERE id=?", String.class, userId));
        assertEquals("DISPATCHER", jdbc.queryForObject("SELECT requested_role FROM account_approval_requests WHERE id=?", String.class, approvalId));
        assertEquals(null, jdbc.queryForObject("SELECT global_role FROM app_users WHERE id=?", String.class, userId));
        mvc.perform(get("/api/admin/approvals").with(jwt().jwt(j -> j.subject(String.valueOf(userId)).claim("roles", List.of("DISPATCHER")))
                        .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isForbidden());
        String adminEmail = "review-admin-" + UUID.randomUUID() + "@example.test";
        long adminId = jdbc.queryForObject("INSERT INTO app_users(email,password_hash,full_name,global_role,status) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, adminEmail, "unused-test-hash", "Test Reviewer", "SYSTEM_ADMIN", "ACTIVE");
        try {
            mvc.perform(post("/api/admin/approvals/{id}/approve", approvalId)
                            .with(jwt().jwt(j -> j.subject(String.valueOf(adminId)).claim("roles", List.of("SYSTEM_ADMIN")))
                                    .authorities(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"organizationRole\":\"DISPATCHER\",\"reviewNote\":\"Test approved\"}"))
                    .andExpect(status().isOk());
            assertEquals("DISPATCHER", jdbc.queryForObject("SELECT global_role FROM app_users WHERE id=?", String.class, userId));
            assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM app_users WHERE id=?", String.class, userId));
        } finally { reviewerEmail = adminEmail; }
    }

    @Test
    void provisionedAccountMustChangePasswordBeforeUsingProtectedApis() throws Exception {
        email = "forced-change-" + UUID.randomUUID() + "@example.test";
        String oldPassword = "temporary-password-123";
        String newPassword = "replacement-password-456";
        String hash = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode(oldPassword);
        long userId = jdbc.queryForObject("INSERT INTO app_users(email,password_hash,full_name,global_role,status,must_change_password) "
                        + "VALUES (?,?,?,?,?,?) RETURNING id",
                Long.class, email, hash, "Forced Password Change", "DISPATCHER", "ACTIVE", true);
        MvcResult login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + oldPassword + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.mustChangePassword").value(true)).andReturn();
        String oldAccess = com.jayway.jsonpath.JsonPath.read(login.getResponse().getContentAsString(), "$.accessToken");
        mvc.perform(get("/api/dashboard/stats").header("Authorization", "Bearer " + oldAccess)).andExpect(status().isForbidden());
        MvcResult changed = mvc.perform(post("/api/auth/change-password").header("Authorization", "Bearer " + oldAccess)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + oldPassword + "\",\"newPassword\":\"" + newPassword
                                + "\",\"confirmPassword\":\"" + newPassword + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.mustChangePassword").value(false)).andReturn();
        assertEquals(false, jdbc.queryForObject("SELECT must_change_password FROM app_users WHERE id=?", Boolean.class, userId));
        String newAccess = com.jayway.jsonpath.JsonPath.read(changed.getResponse().getContentAsString(), "$.accessToken");
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + newAccess)).andExpect(status().isOk());
        assertThrows(org.springframework.security.authentication.AuthenticationServiceException.class, () ->
                mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + oldAccess)));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + newPassword + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.mustChangePassword").value(false));
    }
    @Test
    void managerRegistrationCannotSelfAssignAnExistingOrganization() throws Exception {
        email = "manager-self-assign-" + UUID.randomUUID() + "@example.test";
        String registration = "{\"fullName\":\"Manager Applicant\",\"email\":\"" + email
                + "\",\"password\":\"manager-test-password-123\",\"confirmPassword\":\"manager-test-password-123\","
                + "\"requestedRole\":\"ORGANIZATION_MANAGER\",\"organizationId\":1,\"requestedOrganizationName\":\"New Clinic\"}";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(registration))
                .andExpect(status().isBadRequest());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM app_users WHERE email=?", Integer.class, email));
    }
    @Test
    void administratorCanCreateRequestedOrganizationAndApproveManagerInOneTransaction() throws Exception {
        email = "manager-new-" + UUID.randomUUID() + "@example.test";
        String requestedName = "Auth Review Org " + UUID.randomUUID().toString().substring(0, 8);
        String registration = "{\"fullName\":\"Manager Applicant\",\"email\":\"" + email
                + "\",\"password\":\"manager-test-password-123\",\"confirmPassword\":\"manager-test-password-123\","
                + "\"requestedRole\":\"ORGANIZATION_MANAGER\",\"requestedOrganizationName\":\"" + requestedName + "\"}";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(registration))
                .andExpect(status().isAccepted());
        long userId = jdbc.queryForObject("SELECT id FROM app_users WHERE email=?", Long.class, email);
        long approvalId = jdbc.queryForObject("SELECT id FROM account_approval_requests WHERE user_id=?", Long.class, userId);
        String approval = "{\"name\":\"" + requestedName + "\",\"orgType\":\"HOSPITAL\",\"location\":\"Test-only location\","
                + "\"contactInfo\":\"test-contact\",\"organizationRole\":\"ORGANIZATION_MANAGER\",\"reviewNote\":\"Verified by integration test\"}";
        reviewerEmail = "manager-reviewer-" + UUID.randomUUID() + "@example.test";
        long reviewerId = jdbc.queryForObject("INSERT INTO app_users(email,password_hash,full_name,global_role,status) VALUES (?,?,?,?,?) RETURNING id",
                Long.class, reviewerEmail, "unused-test-hash", "Test Reviewer", "SYSTEM_ADMIN", "ACTIVE");
        mvc.perform(post("/api/admin/approvals/{id}/approve-new-organization", approvalId)
                        .with(jwt().jwt(j -> j.subject(String.valueOf(reviewerId)).claim("roles", List.of("SYSTEM_ADMIN")))
                                .authorities(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON).content(approval))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        long organizationId = jdbc.queryForObject("SELECT id FROM organizations WHERE name=?", Long.class, requestedName);
        createdOrganizations.add(organizationId);
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM app_users WHERE id=?", String.class, userId));
        assertEquals(organizationId, jdbc.queryForObject("SELECT organization_id FROM organization_memberships WHERE user_id=?", Long.class, userId));
        assertEquals("ORGANIZATION_MANAGER", jdbc.queryForObject("SELECT approved_role FROM account_approval_requests WHERE id=?", String.class, approvalId));
    }

    @AfterEach
    void cleanup() {
        if (email != null) jdbc.update("DELETE FROM app_users WHERE email=?", email);
        if (reviewerEmail != null) jdbc.update("DELETE FROM app_users WHERE email=?", reviewerEmail);
        for (long organizationId : createdOrganizations) jdbc.update("DELETE FROM organizations WHERE id=?", organizationId);
        super.cleanup();
    }

    private static String cookieValue(String header) {
        return java.util.Arrays.stream(header.split(";"))
                .map(String::trim).filter(v -> v.startsWith("dras_refresh="))
                .map(v -> v.substring("dras_refresh=".length())).findFirst().orElseThrow();
    }
}
