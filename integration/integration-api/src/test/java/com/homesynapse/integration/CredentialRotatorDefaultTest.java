/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * Verifies that the single-secret {@link CredentialRotator#rotate(String, String)}
 * convenience default delegates to {@link CredentialRotator#rotate(Map)} with a
 * one-entry map (AMD-60 §2.1).
 */
@DisplayName("CredentialRotator default method")
class CredentialRotatorDefaultTest {

    /** Default constructor required by JUnit 5 under {@code -Xlint:all -Werror}. */
    CredentialRotatorDefaultTest() {
        // Defaults are sufficient.
    }

    /** Records the {@code Map} passed to the primary {@code rotate(Map)} method. */
    private static final class RecordingRotator implements CredentialRotator {
        private Map<String, String> lastSecrets;

        RecordingRotator() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Override
        public void rotate(Map<String, String> secrets) {
            this.lastSecrets = secrets;
        }
    }

    @Test
    @DisplayName("rotate(key, value) delegates to rotate(Map.of(key, value))")
    void singleKeyRotate_delegatesToMapRotate() {
        RecordingRotator rotator = new RecordingRotator();

        rotator.rotate("access_token", "abc123");

        assertThat(rotator.lastSecrets).isEqualTo(Map.of("access_token", "abc123"));
    }
}
