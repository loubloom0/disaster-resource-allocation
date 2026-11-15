package com.disaster.allocation.realtime;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.disaster.allocation.controller.OperationalEventsController;
import com.disaster.allocation.security.SecurityConfig;

@WebMvcTest(OperationalEventsController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "dras.auth.jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class OperationalEventsWebMvcTest {
    @Autowired MockMvc mvc;
    @Autowired JwtEncoder jwtEncoder;
    @MockBean OperationalEventService events;
    @MockBean JdbcTemplate jdbc;
    private final List<SseEmitter> openStreams = new ArrayList<>();

    @BeforeEach
    void allowControllerToOpenEmitter() throws Exception {
        org.mockito.Mockito.when(events.connect(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> {
                    SseEmitter emitter = new SseEmitter(0L);
                    openStreams.add(emitter);
                    return emitter;
                });
    }

    @AfterEach
    void closeEmitters() { openStreams.forEach(SseEmitter::complete); }

    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM_ADMIN", "DISPATCHER", "ORGANIZATION_MANAGER", "ORGANIZATION_MEMBER"})
    void authenticatedRoleCanOpenTheProtectedStream(String role) throws Exception {
        mvc.perform(get("/api/events/stream").accept(MediaType.TEXT_EVENT_STREAM)
                        .with(jwt().jwt(token -> tokenForRole(token, role))))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());
    }

    @Test
    void anonymousRequestCannotOpenTheStream() throws Exception {
        mvc.perform(get("/api/events/stream").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
    }

    @Test
    void invalidBearerTokenIsRejectedBeforeOpeningTheSseStream() throws Exception {
        mvc.perform(get("/api/events/stream").accept(MediaType.TEXT_EVENT_STREAM)
                        .header("Authorization", "Bearer not-a-valid-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
    }

    @Test
    void validSignedBearerTokenCanOpenTheProtectedSseStream() throws Exception {
        Instant now = Instant.now();
        org.mockito.Mockito.doReturn(Map.of("status", "ACTIVE", "token_version", 1,
                        "global_role", "SYSTEM_ADMIN", "must_change_password", false))
                .when(jdbc).queryForMap(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(Object[].class));
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("dras").subject("101")
                .issuedAt(now).expiresAt(now.plusSeconds(300)).claim("roles", List.of("SYSTEM_ADMIN"))
                .claim("token_version", 1).claim("must_change_password", false).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(
                org.springframework.security.oauth2.jwt.JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();

        mvc.perform(get("/api/events/stream").accept(MediaType.TEXT_EVENT_STREAM)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());
    }

    @Test
    void streamRejectsMissingAndExpiredExpirationWithoutJsonNegotiationFailure() throws Exception {
        mvc.perform(get("/api/events/stream").accept(MediaType.TEXT_EVENT_STREAM)
                        .with(jwt().jwt(token -> token.subject("301").claim("roles", List.of("ORGANIZATION_MEMBER"))
                                .claim("organization_id", 301L))))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));

        mvc.perform(get("/api/events/stream").accept(MediaType.TEXT_EVENT_STREAM)
                        .with(jwt().jwt(token -> token.subject("301").issuedAt(Instant.now().minusSeconds(600))
                                .expiresAt(Instant.now().minusSeconds(1)).claim("roles", List.of("ORGANIZATION_MEMBER"))
                                .claim("organization_id", 301L))))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
    }

    @Test
    void missingTenantWithValidExpirationIsForbiddenWithoutJsonNegotiationFailure() throws Exception {
        mvc.perform(get("/api/events/stream").accept(MediaType.TEXT_EVENT_STREAM)
                        .with(jwt().jwt(token -> token.subject("301").issuedAt(Instant.now())
                                .expiresAt(Instant.now().plusSeconds(300))
                                .claim("roles", List.of("ORGANIZATION_MEMBER")))))
                .andExpect(status().isForbidden())
                .andExpect(content().string(""));
    }

    private static void tokenForRole(Jwt.Builder token, String role) {
        Instant now = Instant.now();
        token.subject(role.startsWith("ORGANIZATION_") ? "301" : "101")
                .issuedAt(now).expiresAt(now.plusSeconds(300)).claim("roles", List.of(role));
        if (role.startsWith("ORGANIZATION_")) token.claim("organization_id", 301L);
    }
}
