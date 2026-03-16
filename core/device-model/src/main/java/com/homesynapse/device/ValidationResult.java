/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import java.util.List;

/**
 * Structured result from attribute and command validation operations.
 *
 * <p>Returned by {@link AttributeValidator} and {@link CommandValidator} to
 * indicate whether a value or command passed validation. When {@code valid}
 * is {@code true}, {@code errors} is empty. When {@code valid} is {@code false},
 * {@code errors} contains one or more {@link ValidationError} entries
 * describing each failure.</p>
 *
 * @param valid whether the validation passed
 * @param errors the list of validation failures; empty when {@code valid} is {@code true}; unmodifiable
 * @see ValidationError
 * @see AttributeValidator
 * @see CommandValidator
 * @since 1.0
 */
public record ValidationResult(
        boolean valid,
        List<ValidationError> errors
) { }
