/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import com.homesynapse.platform.identity.EntityId;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Doc 03 §9's {@code state_store.staleness} block, parsed (IR-61b): the two values
 * {@link RegistryStalenessResolver} reads — the global default (source 3 of Doc 03 §3.8's
 * chain) and the per-entity overrides (source 1).
 *
 * <h2>The keys</h2>
 *
 * <ul>
 *   <li>{@code default_staleness_threshold} — a duration, or {@code null}: no global default,
 *       an entity with no override and no declared interval is never stale (the default —
 *       INV-CE-02);</li>
 *   <li>{@code staleness_overrides} — a map keyed by entity ULID string, each value a
 *       duration;</li>
 *   <li>{@code scan_interval_seconds} — accepted and ignored. The passive scanner is not
 *       built: {@code stale} is evaluated lazily on the read path (Doc 03 §3.8's carve-out,
 *       IR-61), so the key has no effect.</li>
 * </ul>
 *
 * <h2>One duration grammar</h2>
 *
 * <p>Every duration is ISO-8601 time-based ({@code PT20M}, {@code PT2H}), parsed with
 * {@link Duration#parse} exactly as the automation section's durations are: a calendar-day
 * form ({@code P1D}) is rejected (ambiguous across DST), and so is a zero or negative
 * duration. Doc 03 §9's example strings ({@code "10m"}, {@code "1h"}) are not accepted.</p>
 *
 * <h2>Failure</h2>
 *
 * <p>Configuration is a FATAL subsystem (Doc 12 §4): a malformed value, a value of the wrong
 * type or an unparseable ULID key throws {@link IllegalArgumentException} naming the full key
 * path ({@code state_store.staleness.staleness_overrides[<key>]}), so a boot on a bad value
 * stops with the key named and never runs on a silent default. A YAML {@code null} for the
 * block or for either key reads as absent.</p>
 *
 * @param defaultThreshold the global default; {@link Optional#empty()} for none; never
 *                         {@code null}
 * @param overrides        per-entity thresholds; copied, unmodifiable; never {@code null}
 * @see RegistryStalenessResolver
 * @since 1.0
 */
public record StalenessConfig(Optional<Duration> defaultThreshold,
        Map<EntityId, Duration> overrides) {

    /** No staleness block: no global default and no overrides (zero configuration). */
    public static final StalenessConfig NONE = new StalenessConfig(Optional.empty(), Map.of());

    private static final String BLOCK = "staleness";
    private static final String BLOCK_PATH = "state_store." + BLOCK;
    private static final String DEFAULT_KEY = "default_staleness_threshold";
    private static final String OVERRIDES_KEY = "staleness_overrides";
    private static final String ACCEPTED_FORM =
            "an ISO-8601 duration of the PT form (PT20M, PT2H)";

    /**
     * Validates and copies the components.
     *
     * @throws NullPointerException if a component, an override key or an override value is
     *                              {@code null}
     */
    public StalenessConfig {
        Objects.requireNonNull(defaultThreshold, "defaultThreshold");
        overrides = Map.copyOf(Objects.requireNonNull(overrides, "overrides"));
    }

    /**
     * Parses the {@code state_store} section's {@code staleness} block.
     *
     * @param section the {@code state_store} section of the loaded configuration; {@code null}
     *                or a section without a {@code staleness} block yields {@link #NONE}
     * @return the parsed block; never {@code null}
     * @throws IllegalArgumentException if a value is malformed or of the wrong type, or an
     *                                  override key is not an entity ULID — the message names
     *                                  the full key path
     */
    public static StalenessConfig fromStateStoreSection(Map<String, Object> section) {
        if (section == null) {
            return NONE;
        }
        Object block = section.get(BLOCK);
        if (block == null) {
            return NONE;
        }
        if (!(block instanceof Map<?, ?> staleness)) {
            throw new IllegalArgumentException(
                    BLOCK_PATH + " must be a mapping, got " + describe(block));
        }
        Object threshold = staleness.get(DEFAULT_KEY);
        Optional<Duration> defaultThreshold = (threshold == null)
                ? Optional.empty()
                : Optional.of(parseDuration(threshold, BLOCK_PATH + "." + DEFAULT_KEY));
        return new StalenessConfig(defaultThreshold, parseOverrides(staleness.get(OVERRIDES_KEY)));
    }

    private static Map<EntityId, Duration> parseOverrides(Object value) {
        String path = BLOCK_PATH + "." + OVERRIDES_KEY;
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> entries)) {
            throw new IllegalArgumentException(
                    path + " must be a mapping of entity ULID to duration, got " + describe(value));
        }
        Map<EntityId, Duration> overrides = new HashMap<>();
        for (Map.Entry<?, ?> entry : entries.entrySet()) {
            String key = String.valueOf(entry.getKey());
            String entryPath = path + "[" + key + "]";
            EntityId entityId;
            try {
                entityId = EntityId.parse(key);
            } catch (IllegalArgumentException notAUlid) {
                throw new IllegalArgumentException(entryPath + ": the key must be an entity ULID "
                        + "(26 Crockford base-32 characters) — " + notAUlid.getMessage(), notAUlid);
            }
            if (overrides.put(entityId, parseDuration(entry.getValue(), entryPath)) != null) {
                throw new IllegalArgumentException(entryPath + ": entity " + entityId
                        + " is overridden twice (ULID keys are case-insensitive)");
            }
        }
        return overrides;
    }

    /** The automation section's grammar ({@code AutomationDefinitionLoader.parseIso8601}). */
    private static Duration parseDuration(Object value, String path) {
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(
                    path + " must be " + ACCEPTED_FORM + ", got " + describe(value));
        }
        Duration duration;
        try {
            duration = Duration.parse(text);
        } catch (DateTimeParseException notIso) {
            throw new IllegalArgumentException(
                    path + " must be " + ACCEPTED_FORM + ", got \"" + text + "\"", notIso);
        }
        if (text.toUpperCase(Locale.ROOT).contains("D")) {
            throw new IllegalArgumentException(path + ": calendar-day durations (P..D) are "
                    + "rejected (ambiguous across DST); use " + ACCEPTED_FORM + ", got \""
                    + text + "\"");
        }
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(
                    path + " must be positive, got \"" + text + "\"");
        }
        return duration;
    }

    private static String describe(Object value) {
        return (value == null) ? "null" : value.getClass().getSimpleName() + " " + value;
    }
}
