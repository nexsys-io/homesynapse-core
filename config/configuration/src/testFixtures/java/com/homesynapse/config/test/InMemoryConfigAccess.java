/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config.test;

import com.homesynapse.config.ConfigurationAccess;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mutable, thread-safe implementation of {@link ConfigurationAccess} for testing.
 *
 * <p>Backs configuration values with a {@link ConcurrentHashMap} and provides
 * builder-pattern construction for pre-loading values. All four
 * {@code ConfigurationAccess} methods are implemented with the following
 * type-safety contracts:</p>
 * <ul>
 *   <li>{@link #getString(String)} returns empty if the value is not a
 *       {@link String} instance — it does NOT call {@code toString()}.</li>
 *   <li>{@link #getInt(String)} handles {@link Number} types (not just
 *       {@link Integer}) via {@link Number#intValue()}, matching production
 *       behavior where SnakeYAML Engine may produce {@link Long} or
 *       {@link Double} for numeric YAML values.</li>
 *   <li>{@link #getBoolean(String)} returns empty if the value is not a
 *       {@link Boolean} instance.</li>
 * </ul>
 *
 * <p><strong>Thread safety:</strong> All operations are backed by
 * {@link ConcurrentHashMap}. No locks are needed — all operations are
 * single-key atomic (LTD-11 compliant).</p>
 *
 * <p>The {@link #put(String, Object)}, {@link #remove(String)}, and
 * {@link #clear()} methods are test-only facilities for modifying
 * configuration after construction. They are public because downstream
 * test code may need to modify config mid-test.</p>
 *
 * @see ConfigurationAccess
 * @see TestConfigFactory
 */
public final class InMemoryConfigAccess implements ConfigurationAccess {

    private final ConcurrentHashMap<String, Object> backingMap;

    /**
     * Creates a new {@code InMemoryConfigAccess} backed by the given map entries.
     *
     * @param initial the initial configuration values; defensively copied
     */
    private InMemoryConfigAccess(Map<String, Object> initial) {
        this.backingMap = new ConcurrentHashMap<>(initial);
    }

    // ──────────────────────────────────────────────────────────────────
    // Static factory methods
    // ──────────────────────────────────────────────────────────────────

    /**
     * Creates an empty {@code InMemoryConfigAccess} with no configuration values.
     *
     * @return a new empty instance
     */
    public static InMemoryConfigAccess empty() {
        return new InMemoryConfigAccess(Map.of());
    }

    /**
     * Creates an {@code InMemoryConfigAccess} with a single key-value pair.
     *
     * @param key   the configuration key; never {@code null}
     * @param value the configuration value; never {@code null}
     * @return a new instance with the given entry
     */
    public static InMemoryConfigAccess of(String key, Object value) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value, "value must not be null");
        return new InMemoryConfigAccess(Map.of(key, value));
    }

    /**
     * Creates an {@code InMemoryConfigAccess} pre-loaded with the given map.
     *
     * @param values the initial configuration values; defensively copied;
     *               never {@code null}
     * @return a new instance with the given entries
     */
    public static InMemoryConfigAccess of(Map<String, Object> values) {
        Objects.requireNonNull(values, "values must not be null");
        return new InMemoryConfigAccess(values);
    }

    /**
     * Returns a new {@link Builder} for fluent construction of an
     * {@code InMemoryConfigAccess}.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    // ──────────────────────────────────────────────────────────────────
    // ConfigurationAccess implementation
    // ──────────────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Returns an unmodifiable snapshot of the backing map. Mutations to
     * the backing map after this call are not reflected in the returned map.</p>
     */
    @Override
    public Map<String, Object> getConfig() {
        return Collections.unmodifiableMap(new HashMap<>(backingMap));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns empty if the value is not a {@link String} instance.
     * Does NOT call {@code toString()} on non-String values.</p>
     */
    @Override
    public Optional<String> getString(String key) {
        Objects.requireNonNull(key, "key must not be null");
        Object value = backingMap.get(key);
        return value instanceof String s ? Optional.of(s) : Optional.empty();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Handles {@link Number} types via {@link Number#intValue()}, not just
     * {@link Integer}. This matches production behavior where SnakeYAML Engine
     * may produce {@link Long} or {@link Double} for numeric YAML values.</p>
     */
    @Override
    public Optional<Integer> getInt(String key) {
        Objects.requireNonNull(key, "key must not be null");
        Object value = backingMap.get(key);
        if (value instanceof Number n) {
            return Optional.of(n.intValue());
        }
        return Optional.empty();
    }

    @Override
    public Optional<Boolean> getBoolean(String key) {
        Objects.requireNonNull(key, "key must not be null");
        Object value = backingMap.get(key);
        return value instanceof Boolean b ? Optional.of(b) : Optional.empty();
    }

    // ──────────────────────────────────────────────────────────────────
    // Test-only mutation methods
    // ──────────────────────────────────────────────────────────────────

    /**
     * Adds or updates a configuration value.
     *
     * <p><strong>Test-only.</strong> This method exists for test setup and
     * mid-test configuration changes. It is public because downstream test
     * code may need to modify configuration during a test.</p>
     *
     * @param key   the configuration key; never {@code null}
     * @param value the configuration value; never {@code null}
     */
    public void put(String key, Object value) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value, "value must not be null");
        backingMap.put(key, value);
    }

    /**
     * Removes a configuration value.
     *
     * <p><strong>Test-only.</strong> This method exists for test setup.</p>
     *
     * @param key the configuration key to remove; never {@code null}
     */
    public void remove(String key) {
        Objects.requireNonNull(key, "key must not be null");
        backingMap.remove(key);
    }

    /**
     * Removes all configuration values.
     *
     * <p><strong>Test-only.</strong> This method exists for test isolation
     * between {@code @BeforeEach} calls.</p>
     */
    public void clear() {
        backingMap.clear();
    }

    /**
     * Returns the number of configuration entries.
     *
     * @return entry count
     */
    public int size() {
        return backingMap.size();
    }

    @Override
    public String toString() {
        return "InMemoryConfigAccess[size=" + backingMap.size() + "]";
    }

    // ──────────────────────────────────────────────────────────────────
    // Builder
    // ──────────────────────────────────────────────────────────────────

    /**
     * Mutable builder for {@link InMemoryConfigAccess} instances with fluent API.
     *
     * <p>Accumulates key-value pairs and produces an {@code InMemoryConfigAccess}
     * on {@link #build()}.</p>
     */
    public static final class Builder {

        private final HashMap<String, Object> values = new HashMap<>();

        /** Creates a new empty builder. */
        Builder() {
            // Package-private — created via InMemoryConfigAccess.builder()
        }

        /**
         * Adds a key-value pair to the configuration.
         *
         * @param key   the configuration key; never {@code null}
         * @param value the configuration value; never {@code null}
         * @return this builder
         */
        public Builder put(String key, Object value) {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(value, "value must not be null");
            values.put(key, value);
            return this;
        }

        /**
         * Convenience method for adding a string value.
         *
         * @param key   the configuration key; never {@code null}
         * @param value the string value; never {@code null}
         * @return this builder
         */
        public Builder putString(String key, String value) {
            return put(key, value);
        }

        /**
         * Convenience method for adding an integer value.
         *
         * @param key   the configuration key; never {@code null}
         * @param value the integer value
         * @return this builder
         */
        public Builder putInt(String key, int value) {
            return put(key, value);
        }

        /**
         * Convenience method for adding a boolean value.
         *
         * @param key   the configuration key; never {@code null}
         * @param value the boolean value
         * @return this builder
         */
        public Builder putBoolean(String key, boolean value) {
            return put(key, value);
        }

        /**
         * Builds the {@link InMemoryConfigAccess} with all accumulated values.
         *
         * @return a new InMemoryConfigAccess
         */
        public InMemoryConfigAccess build() {
            return new InMemoryConfigAccess(values);
        }
    }
}
