package com.disaster.allocation.integration;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("auth-test")
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class OperationalEventsIT {
    @Autowired MockMvc mvc;

    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM_ADMIN", "DISPATCHER", "ORGANIZATION_MANAGER", "ORGANIZATION_MEMBER"})
    void authenticatedRolesCanOpenSseStream(String role) throws Exception {
        MvcResult result = mvc.perform(get("/api/events/stream")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .with(jwt().jwt(token -> {
                            token.subject(role.startsWith("ORGANIZATION_") ? "301" : role.equals("SYSTEM_ADMIN") ? "101" : "102")
                                    .claim("roles", List.of(role)).expiresAt(java.time.Instant.now().plusSeconds(300));
                            if (role.startsWith("ORGANIZATION_")) token.claim("organization_id", 301L);
                        })))
                .andExpect(request().asyncStarted())
                .andReturn();
        // A live emitter is intentionally left open here; MockMvc shuts down with its application context.
        org.junit.jupiter.api.Assertions.assertNotNull(result.getRequest().getAsyncContext());
    }

    @Test
    void streamRequiresAuthenticationAndTenantIdentity() throws Exception {
        mvc.perform(get("/api/events/stream").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/events/stream").accept(MediaType.TEXT_EVENT_STREAM)
                        .with(jwt().jwt(token -> token.subject("401").claim("roles", List.of("ORGANIZATION_MEMBER"))
                                .expiresAt(java.time.Instant.now().plusSeconds(300)))))
                .andExpect(status().isForbidden());
    }
}
