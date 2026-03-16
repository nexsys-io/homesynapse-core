/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import java.util.List;

/**
 * Governs how the Pending Command Ledger confirms command execution
 * for a capability instance.
 *
 * <p>A confirmation policy specifies the comparison mode, which attributes
 * are authoritative for confirmation, an optional numeric tolerance, and
 * the default timeout window. Each {@link CapabilityInstance} carries its
 * own confirmation policy.</p>
 *
 * <p>Defined in Doc 02 §4.3.</p>
 *
 * @param mode the confirmation comparison strategy, never {@code null}
 * @param authoritativeAttributes the attribute keys monitored for confirmation, never {@code null}; unmodifiable
 * @param defaultTolerance the numeric tolerance for {@link ConfirmationMode#TOLERANCE} mode, {@code null} when not applicable
 * @param defaultTimeoutMs the default confirmation timeout in milliseconds
 * @see ConfirmationMode
 * @see Expectation
 * @see CapabilityInstance
 * @since 1.0
 */
public record ConfirmationPolicy(
        ConfirmationMode mode,
        List<String> authoritativeAttributes,
        Number defaultTolerance,
        long defaultTimeoutMs
) { }
