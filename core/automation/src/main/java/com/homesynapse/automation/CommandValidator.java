/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.Map;
import java.util.Objects;

import com.homesynapse.platform.identity.EntityId;

/**
 * Validates a resolved command against the target entity's capability surface before the
 * {@link CommandDispatchService} hands it off to an integration adapter (Doc 07 §3.11.1).
 *
 * <p>Tier-1 floor: validation confirms the target entity exists and one of its capabilities
 * declares the command (feature-gated). Deep parameter-schema validation beyond
 * capability/command existence is out of scope here (the device model's own
 * {@code com.homesynapse.device.CommandValidator} owns full parameter validation; this
 * automation-side validator is the dispatch gate).</p>
 *
 * <p>Thread-safe. Implementations read the device/capability model and must be safe for
 * concurrent use from multiple virtual threads.</p>
 *
 * @see StandardCommandValidator
 * @see CommandDispatchService
 */
public interface CommandValidator {

    /**
     * Validates that {@code targetRef} supports {@code commandName}.
     *
     * @param targetRef   the target entity, never {@code null}
     * @param commandName the command to validate, never {@code null}
     * @param parameters  the command parameters, never {@code null}
     * @return a validation result; on failure the dispatch service emits
     *         {@code command_result} with status {@code "invalid"}
     */
    ValidationResult validate(EntityId targetRef, String commandName,
                              Map<String, Object> parameters);

    /**
     * The outcome of a {@link #validate} call: a validity flag and, on failure, a Register-C
     * reason. Automation-resident (distinct from {@code com.homesynapse.device.ValidationResult}).
     *
     * @param valid  whether the command is valid against the target's capabilities
     * @param reason the failure reason ({@code null} when {@link #valid()} is {@code true})
     */
    record ValidationResult(boolean valid, String reason) {

        /** A shared valid result (no reason). */
        private static final ValidationResult VALID = new ValidationResult(true, null);

        /**
         * A valid (accepted) result. Named {@code accepted()} rather than {@code valid()}
         * because a record component named {@code valid} already defines the accessor
         * {@code valid()} — a same-named static factory is a compile error.
         *
         * @return the shared valid result, never {@code null}
         */
        public static ValidationResult accepted() {
            return VALID;
        }

        /**
         * An invalid result carrying a Register-C reason.
         *
         * @param reason the failure reason, never {@code null} or blank
         * @return an invalid result, never {@code null}
         * @throws NullPointerException     if {@code reason} is {@code null}
         * @throws IllegalArgumentException if {@code reason} is blank
         */
        public static ValidationResult invalid(String reason) {
            Objects.requireNonNull(reason, "reason must not be null");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("reason must not be blank");
            }
            return new ValidationResult(false, reason);
        }
    }
}
