/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.util.List;
import java.util.Objects;

/**
 * Event-local mirror of the device-model {@code ConfirmationPolicy} — the
 * installed DP-a confirmation tuning, captured as adopted (AMD-99 §3). This is
 * the component that makes per-device confirmation behavior provable across
 * restarts, independent of what profile files say today.
 *
 * <p>NOT an event: no {@code EventType} annotation, does not implement
 * {@link DomainEvent}.</p>
 *
 * @param mode the {@code ConfirmationMode} enum name (e.g. {@code "EXACT_MATCH"}),
 *        never {@code null}
 * @param authoritativeAttributes the attribute keys monitored for confirmation,
 *        never {@code null}; order-preserving unmodifiable copy
 * @param defaultTolerance the numeric tolerance for TOLERANCE mode, {@code null}
 *        when not applicable; canonicalized per {@link PayloadMirrors#canonicalNumber}
 * @param defaultTimeoutMs the default confirmation timeout in milliseconds
 * @see CapabilityInstanceRef
 */
public record ConfirmationPolicyRef(
        String mode,
        List<String> authoritativeAttributes,
        Number defaultTolerance,
        long defaultTimeoutMs
) {

    /**
     * Validates required components, defensively copies the attribute list
     * (order-preserving — the domain component is an ordered {@code List}),
     * and canonicalizes the nullable tolerance.
     *
     * @throws NullPointerException if {@code mode} or
     *         {@code authoritativeAttributes} is {@code null}
     */
    public ConfirmationPolicyRef {
        Objects.requireNonNull(mode, "mode must not be null");
        Objects.requireNonNull(authoritativeAttributes,
                "authoritativeAttributes must not be null");
        authoritativeAttributes = List.copyOf(authoritativeAttributes);
        defaultTolerance = PayloadMirrors.canonicalNumber(defaultTolerance);
    }
}
