package com.disaster.allocation.security;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.servlet.http.HttpServletRequest;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@Profile("!test | auth-test")
public class SecurityConfig {
    private static final Logger LOG = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    SecretKey jwtSecretKey(@Value("${dras.auth.jwt-secret:}") String configured,
                           Environment environment) {
        if (configured == null || configured.isBlank()) {
            for (String profile : environment.getActiveProfiles()) {
                if ("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile)) {
                    throw new IllegalStateException("DRAS_JWT_SECRET must be configured in production");
                }
            }
            byte[] ephemeral = new byte[32];
            new SecureRandom().nextBytes(ephemeral);
            LOG.warn("DRAS_JWT_SECRET is unset; generated an ephemeral development key. Existing sessions will not survive restart.");
            return new SecretKeySpec(ephemeral, "HmacSHA256");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(configured.trim());
            if (decoded.length < 32) throw new IllegalArgumentException("must contain at least 32 random bytes");
            return new SecretKeySpec(decoded, "HmacSHA256");
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("DRAS_JWT_SECRET must be base64-encoded random bytes (at least 32 bytes)", e);
        }
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSecretKey, JdbcTemplate jdbc) {
        JwtDecoder signatureDecoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey).macAlgorithm(MacAlgorithm.HS256).build();
        return token -> {
            var jwt = signatureDecoder.decode(token);
            java.time.Instant expiresAt = jwt.getExpiresAt();
            if (expiresAt == null) {
                throw new JwtException("Token expiration is required");
            }
            if (!expiresAt.isAfter(java.time.Instant.now())) {
                throw new JwtException("Token has expired");
            }
            java.util.Map<String, Object> account;
            try {
                account = jdbc.queryForMap("SELECT u.status,u.token_version,u.global_role,u.must_change_password,m.status AS membership_status,o.active AS organization_active "
                        + "FROM app_users u LEFT JOIN organization_memberships m ON m.user_id=u.id "
                        + "LEFT JOIN organizations o ON o.id=m.organization_id WHERE u.id=?",
                        Long.parseLong(jwt.getSubject()));
            } catch (org.springframework.dao.EmptyResultDataAccessException e) {
                throw new JwtException("Account is no longer available", e);
            }
            Number version = jwt.getClaim("token_version");
            Boolean mustChange = jwt.getClaim("must_change_password");
            if (!"ACTIVE".equals(account.get("status")) || version == null
                    || ((Number) account.get("token_version")).intValue() != version.intValue()
                    || mustChange == null || account.get("must_change_password") == null
                    || ((Boolean) account.get("must_change_password")).booleanValue() != mustChange.booleanValue()
                    || (account.get("global_role") == null && (!"ACTIVE".equals(account.get("membership_status"))
                    || !Boolean.TRUE.equals(account.get("organization_active"))))) {
                throw new JwtException("Session is no longer active");
            }
            return jwt;
        };
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper mapper) throws Exception {
        AuthenticationEntryPoint unauthorized = (request, response, exception) -> {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            if (isSseRequest(request)) return;
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(), Map.of(
                    "status", 401, "error", "Unauthorized", "message", "Authentication is required"));
        };
        AccessDeniedHandler forbidden = (request, response, exception) -> {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            if (isSseRequest(request)) return;
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(), Map.of(
                    "status", 403, "error", "Forbidden", "message", "Access is not permitted"));
        };

        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter jwtConverter = new JwtAuthenticationConverter();
        jwtConverter.setJwtGrantedAuthoritiesConverter(authorities);

        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/index.html", "/css/**", "/js/**", "/images/**", "/favicon.ico").permitAll()
                        .requestMatchers("/api/health").permitAll()
                        .requestMatchers("/api/auth/register", "/api/auth/login", "/api/auth/refresh", "/api/auth/logout",
                                "/api/auth/organizations", "/api/auth/organization-matches").permitAll()
                        .requestMatchers("/api/auth/change-password").authenticated()
                        .requestMatchers("/api/**").access((authentication, context) -> {
                            Object principal = authentication.get().getPrincipal();
                            if (!(principal instanceof org.springframework.security.oauth2.jwt.Jwt jwt)) {
                                return new org.springframework.security.authorization.AuthorizationDecision(false);
                            }
                            return new org.springframework.security.authorization.AuthorizationDecision(
                                    !Boolean.TRUE.equals(jwt.getClaim("must_change_password")));
                        })
                        .anyRequest().permitAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter)))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable);
        return http.build();
    }

    private static boolean isSseRequest(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return "/api/events/stream".equals(request.getRequestURI())
                && accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE);
    }
}
