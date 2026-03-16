/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

/**
 * A single validation failure with the field name, reason for rejection,
 * and the value that was rejected.
 *
 * <p>Used within {@link ValidationResult} to provide structured error
 * reporting from {@link AttributeValidator} and {@link CommandValidator}.</p>
 *
 * @param field the name of the field that failed validation, never {@code null}
 * @param reason the human-readable reason for the validation failure, never {@code null}
 * @param rejectedValue the string representation of the value that was rejected, never {@code null}
 * @see ValidationResult
 * @since 1.0
 */
public record ValidationError(
        String field,
        String reason,
        String rejectedValue
) { }
