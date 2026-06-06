/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the AMD-56 append-only code-bearing constructor pair on
 * {@link PermanentIntegrationException}. The no-code constructors permanently
 * yield {@code integration.permanent_failure} (AMD-56-INV-03); the code-bearing
 * constructors carry a supplied dotted-lowercase Register C code, rejecting
 * malformed codes.
 */
@DisplayName("PermanentIntegrationException")
class PermanentIntegrationExceptionTest {

    private static final String DEFAULT_CODE = "integration.permanent_failure";

    /** Default constructor required by JUnit 5 under {@code -Xlint:all -Werror}. */
    PermanentIntegrationExceptionTest() {
        // Defaults are sufficient.
    }

    @Test
    @DisplayName("message-only ctor yields the default error code")
    void messageOnly_defaultCode() {
        var ex = new PermanentIntegrationException("coordinator firmware unsupported");

        assertThat(ex.errorCode()).isEqualTo(DEFAULT_CODE);
        assertThat(ex.getMessage()).isEqualTo("coordinator firmware unsupported");
    }

    @Test
    @DisplayName("message+cause ctor yields the default error code and preserves the cause")
    void messageAndCause_defaultCode() {
        var cause = new RuntimeException("boom");
        var ex = new PermanentIntegrationException("init failed", cause);

        assertThat(ex.errorCode()).isEqualTo(DEFAULT_CODE);
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    @DisplayName("code-bearing ctor returns the supplied code")
    void codeBearing_returnsSuppliedCode() {
        var ex = new PermanentIntegrationException("integration.auth_failed", "token revoked");

        assertThat(ex.errorCode()).isEqualTo("integration.auth_failed");
        assertThat(ex.getMessage()).isEqualTo("token revoked");
    }

    @Test
    @DisplayName("code-bearing ctor with cause returns the supplied code and preserves the cause")
    void codeBearingWithCause_returnsSuppliedCode() {
        var cause = new IllegalStateException("expired");
        var ex = new PermanentIntegrationException("integration.auth_failed", "token revoked", cause);

        assertThat(ex.errorCode()).isEqualTo("integration.auth_failed");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    @DisplayName("suggestedHttpStatus stays 503")
    void suggestedHttpStatus_unchanged() {
        assertThat(new PermanentIntegrationException("x").suggestedHttpStatus()).isEqualTo(503);
        assertThat(new PermanentIntegrationException("integration.auth_failed", "x")
                .suggestedHttpStatus()).isEqualTo(503);
    }

    @Test
    @DisplayName("blank error code is rejected")
    void blankCode_throws() {
        assertThatThrownBy(() -> new PermanentIntegrationException("", "msg"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("errorCode must be a dotted lowercase code");
    }

    @Test
    @DisplayName("undotted code is rejected")
    void undottedCode_throws() {
        assertThatThrownBy(() -> new PermanentIntegrationException("nodot", "msg"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("errorCode must be a dotted lowercase code");
    }

    @Test
    @DisplayName("uppercase code is rejected")
    void uppercaseCode_throws() {
        assertThatThrownBy(() -> new PermanentIntegrationException("Integration.Auth_Failed", "msg"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("errorCode must be a dotted lowercase code");
    }
}
