package com.disaster.allocation.security;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class SecurityConfigJwtDecoderTest {
    private final SecretKey signingKey = new SecretKeySpec(new byte[32], "HmacSHA256");
    private final NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(signingKey));
    private final SecurityConfig config = new SecurityConfig();
    private final JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);

    @Test
    void decoderRejectsSignedTokenWithoutExpiration() {
        String token = encode(JwtClaimsSet.builder().subject("1").issuedAt(Instant.now()).build());

        assertThrows(JwtException.class, () -> config.jwtDecoder(signingKey, jdbc).decode(token));
        org.mockito.Mockito.verifyNoInteractions(jdbc);
    }

    @Test
    void decoderRejectsExpiredSignedToken() {
        Instant now = Instant.now();
        String token = encode(JwtClaimsSet.builder().subject("1").issuedAt(now.minusSeconds(60))
                .expiresAt(now.minusSeconds(1)).build());

        assertThrows(JwtException.class, () -> config.jwtDecoder(signingKey, jdbc).decode(token));
        org.mockito.Mockito.verifyNoInteractions(jdbc);
    }

    private String encode(JwtClaimsSet claims) {
        return encoder.encode(JwtEncoderParameters.from(
                org.springframework.security.oauth2.jwt.JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
