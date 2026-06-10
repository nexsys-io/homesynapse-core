/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Integration-scoped {@link ConfigurationAccess} implementation over a
 * validated {@link ConfigModel} (Doc 06 §8.4, Doc 05 §3.8).
 *
 * <p>Each instance is bound to one integration type at construction and
 * exposes only that integration's {@code integrations.{type}} section. An
 * unconfigured integration receives an empty section rather than a missing
 * one — zero-config is valid (INV-CE-02).</p>
 *
 * <p>The section values are captured from the supplied model at construction
 * time, matching the interface's immutability contract. A configuration
 * change to an integration's section is classified
 * {@code INTEGRATION_RESTART} by the reload pipeline (Doc 06 §3.3), which
 * restarts the adapter with a freshly scoped instance over the new model —
 * instances are never mutated in place.</p>
 *
 * <p>All {@code !secret} and {@code !env} values in the model are already
 * resolved by the loading pipeline before this class sees them.</p>
 */
final class ScopedConfigurationAccess implements ConfigurationAccess {

    private final Map<String, Object> sectionValues;

    /**
     * Creates a configuration access scoped to one integration's section of
     * the given model.
     *
     * @param integrationType the integration type identifier
     *                        (e.g., {@code "zigbee"}); never {@code null} or
     *                        blank
     * @param model           the validated configuration model to scope into;
     *                        never {@code null}
     * @throws NullPointerException     if {@code integrationType} or
     *                                  {@code model} is {@code null}
     * @throws IllegalArgumentException if {@code integrationType} is blank
     */
    ScopedConfigurationAccess(String integrationType, ConfigModel model) {
        Objects.requireNonNull(integrationType, "integrationType must not be null");
        if (integrationType.isBlank()) {
            throw new IllegalArgumentException("integrationType must not be blank");
        }
        Objects.requireNonNull(model, "model must not be null");

        ConfigSection section = model.sections()
                .get("integrations." + integrationType);
        // ConfigSection.values() is already unmodifiable (Map.copyOf in its
        // compact constructor); Map.of() covers the zero-config case.
        this.sectionValues = section != null ? section.values() : Map.of();
    }

    @Override
    public Map<String, Object> getConfig() {
        return sectionValues;
    }

    @Override
    public Optional<String> getString(String key) {
        Objects.requireNonNull(key, "key must not be null");
        return sectionValues.get(key) instanceof String value
                ? Optional.of(value)
                : Optional.empty();
    }

    @Override
    public Optional<Integer> getInt(String key) {
        Objects.requireNonNull(key, "key must not be null");
        return sectionValues.get(key) instanceof Integer value
                ? Optional.of(value)
                : Optional.empty();
    }

    @Override
    public Optional<Boolean> getBoolean(String key) {
        Objects.requireNonNull(key, "key must not be null");
        return sectionValues.get(key) instanceof Boolean value
                ? Optional.of(value)
                : Optional.empty();
    }
}
