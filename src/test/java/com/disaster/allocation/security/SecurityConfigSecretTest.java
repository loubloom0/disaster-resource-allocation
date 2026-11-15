package com.disaster.allocation.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Base64;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SecurityConfigSecretTest {
    private final SecurityConfig config = new SecurityConfig();

    @Test
    void productionRequiresJwtSecret() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> config.jwtSecretKey("", environment("prod")));

        assertFalse(error.getMessage().contains("DRAS_JWT_SECRET="));
    }

    @Test
    void productionRejectsInvalidAndInsufficientSecretsWithoutEchoingThem() {
        MockEnvironment production = environment("production");
        String invalid = "this-is-not-base64!";
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        IllegalStateException invalidError = assertThrows(IllegalStateException.class,
                () -> config.jwtSecretKey(invalid, production));
        IllegalStateException shortError = assertThrows(IllegalStateException.class,
                () -> config.jwtSecretKey(shortKey, production));

        assertFalse(invalidError.getMessage().contains(invalid));
        assertFalse(shortError.getMessage().contains(shortKey));
    }

    @Test
    void productionUsesTheConfiguredStable256BitKey() {
        String configured = Base64.getEncoder().encodeToString(new byte[32]);
        MockEnvironment production = environment("prod");

        SecretKey first = config.jwtSecretKey(configured, production);
        SecretKey afterRestart = config.jwtSecretKey(configured, production);

        assertArrayEquals(first.getEncoded(), afterRestart.getEncoded());
    }

    @Test
    void developmentMayGenerateProcessLocalKeyWhenUnset() {
        MockEnvironment development = environment("dev");

        SecretKey first = config.jwtSecretKey("", development);
        SecretKey anotherProcess = config.jwtSecretKey("", development);

        assertEquals(32, first.getEncoded().length);
        assertNotEquals(java.util.HexFormat.of().formatHex(first.getEncoded()),
                java.util.HexFormat.of().formatHex(anotherProcess.getEncoded()));
    }

    private static MockEnvironment environment(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return environment;
    }
}
