/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

/**
 * Maps an attribute to the confirmation logic that the Pending Command Ledger
 * uses to evaluate whether a command succeeded.
 *
 * <p>Each command definition may declare one or more expected outcomes. After
 * a command is dispatched, the ledger monitors the listed attributes and
 * evaluates reported values against the associated {@link Expectation}.</p>
 *
 * <p>Defined in Doc 02 §3.8.</p>
 *
 * @param attributeKey the attribute key to monitor for confirmation, never {@code null}
 * @param expectation the evaluation strategy for this attribute, never {@code null}
 * @param timeoutMs the maximum time in milliseconds to wait for confirmation of this attribute
 * @see CommandDefinition#expectedOutcomes()
 * @see Expectation
 * @since 1.0
 */
public record ExpectedOutcome(
        String attributeKey,
        Expectation expectation,
        long timeoutMs
) { }
