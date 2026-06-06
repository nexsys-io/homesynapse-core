/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * Verifies the {@link SecurityServices} aggregator (AMD-60 §2.2). Declared
 * services inside the aggregator are non-null (AMD-60-INV-02).
 */
@DisplayName("SecurityServices")
class SecurityServicesTest {

    /** Default constructor required by JUnit 5 under {@code -Xlint:all -Werror}. */
    SecurityServicesTest() {
        // Defaults are sufficient.
    }

    @Test
    @DisplayName("null credentialRotator is rejected at construction")
    void nullCredentialRotator_throws() {
        assertThatThrownBy(() -> new SecurityServices(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("credentialRotator");
    }

    @Test
    @DisplayName("a valid aggregator exposes its credentialRotator")
    void validConstruction_exposesRotator() {
        CredentialRotator rotator = (Map<String, String> secrets) -> { /* no-op */ };

        SecurityServices services = new SecurityServices(rotator);

        assertThat(services.credentialRotator()).isSameAs(rotator);
    }
}
