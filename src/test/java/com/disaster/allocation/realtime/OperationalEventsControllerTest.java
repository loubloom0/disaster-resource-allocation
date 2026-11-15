package com.disaster.allocation.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import jakarta.servlet.http.HttpServletResponse;

import com.disaster.allocation.controller.OperationalEventsController;
import com.disaster.allocation.exception.ApiException;

class OperationalEventsControllerTest {
    private OperationalEventService events;
    private OperationalEventsController controller;
    private HttpServletResponse response;

    @BeforeEach
    void setUp() {
        events = new OperationalEventService();
        controller = new OperationalEventsController(events);
        response = org.mockito.Mockito.mock(HttpServletResponse.class);
    }

    @AfterEach
    void tearDown() { events.shutdown(); }

    @Test
    void acceptsAllFourAuthenticatedRolesUsingJwtIdentityClaims() {
        assertNotNull(controller.stream(jwt(1, "SYSTEM_ADMIN", null), response));
        assertNotNull(controller.stream(jwt(2, "DISPATCHER", null), response));
        assertNotNull(controller.stream(jwt(3, "ORGANIZATION_MANAGER", 40L), response));
        assertNotNull(controller.stream(jwt(4, "ORGANIZATION_MEMBER", 40L), response));
        assertEquals(4, events.connectedClientCount());
    }

    @Test
    void rejectsMissingPrincipalUnsupportedRoleAndTenantWithoutOrganization() {
        assertThrows(ApiException.class, () -> controller.stream(null, response));
        assertThrows(ApiException.class, () -> controller.stream(jwt(5, "SYSTEM_ADMIN", null, "ORGANIZATION_MEMBER"), response));
        assertThrows(ApiException.class, () -> controller.stream(jwt(6, "ORGANIZATION_MEMBER", null), response));
        assertEquals(0, events.connectedClientCount());
    }

    private static Jwt jwt(long userId, String role, Long organizationId) {
        return jwt(userId, role, organizationId, role);
    }

    private static Jwt jwt(long userId, String role, Long organizationId, String... roles) {
        Instant now = Instant.now();
        Jwt.Builder builder = Jwt.withTokenValue("test-token").header("alg", "none")
                .subject(Long.toString(userId)).issuedAt(now).expiresAt(now.plusSeconds(300))
                .claim("roles", List.of(roles));
        if (organizationId != null) builder.claim("organization_id", organizationId);
        return builder.build();
    }
}
