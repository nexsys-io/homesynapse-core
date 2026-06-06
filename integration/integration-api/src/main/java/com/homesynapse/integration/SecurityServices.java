/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

import java.util.Objects;

/**
 * Aggregator for the security-related services an integration adapter may
 * receive, on {@link IntegrationContext#security()} (AMD-60 §2.2).
 *
 * <p>Per the NQ-1 doctrine, {@link IntegrationContext} grows only by
 * service-family aggregator fields — future security services (e.g., certificate
 * provisioning) become components of this record, and the context never grows for
 * them (AMD-60-INV-01). The aggregator itself is nullable on the context (gated by
 * {@link RequiredService#SECURITY}); inside the aggregator, declared services are
 * non-null (AMD-60-INV-02).</p>
 *
 * @param credentialRotator the sanctioned credential-rotation write path;
 *                          never {@code null}
 *
 * @see IntegrationContext#security()
 * @see CredentialRotator
 * @see RequiredService#SECURITY
 */
public record SecurityServices(CredentialRotator credentialRotator) {

    /**
     * Validates that the declared service is non-null (AMD-60-INV-02).
     */
    public SecurityServices {
        Objects.requireNonNull(credentialRotator, "credentialRotator must not be null");
    }
}
