/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

import java.util.Map;

/**
 * Sanctioned write path for an integration to persist rotated credentials after
 * a re-authentication flow (AMD-60 §2.1). Reached through
 * {@link SecurityServices} on {@link IntegrationContext}, gated by
 * {@link RequiredService#SECURITY}.
 *
 * <p><strong>Integration-scoped (LTD-17).</strong> An adapter can rotate only its
 * own integration's secrets — the M9 implementation injects the calling adapter's
 * {@code IntegrationId} scoping, mirroring the filtered {@code EntityRegistry}.
 * The adapter never names another integration's section.</p>
 *
 * @see SecurityServices
 * @see IntegrationContext#security()
 * @see RequiredService#SECURITY
 */
public interface CredentialRotator {

    /**
     * Atomically replaces the secrets stored under the given keys for this
     * integration's configuration section.
     *
     * <p>All entries land in one durable, all-or-nothing write — an OAuth
     * access+refresh token pair can never be torn (AMD-60-INV-03). The write is
     * durable before this method returns. Integration-scoped (LTD-17): only this
     * integration's secrets may be rotated.</p>
     *
     * @param secrets the secret key/value pairs to persist; never {@code null},
     *                never empty; every key must be declared in this integration's
     *                configuration schema
     * @throws IllegalArgumentException if {@code secrets} is empty or any key is
     *                                  unknown to this integration's declared
     *                                  configuration schema
     */
    void rotate(Map<String, String> secrets);

    /**
     * Single-secret convenience that delegates to {@link #rotate(Map)} with a
     * one-entry map.
     *
     * @param secretKey      the secret key to persist; must be declared in this
     *                       integration's configuration schema
     * @param newSecretValue the new secret value
     * @throws IllegalArgumentException if {@code secretKey} is unknown to this
     *                                  integration's declared configuration schema
     */
    default void rotate(String secretKey, String newSecretValue) {
        rotate(Map.of(secretKey, newSecretValue));
    }
}
