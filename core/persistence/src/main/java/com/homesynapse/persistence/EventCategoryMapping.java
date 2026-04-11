/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.homesynapse.event.EventCategory;
import com.homesynapse.event.EventTypes;

import java.util.List;
import java.util.Map;

/**
 * Static, compile-time mapping from canonical {@code eventType} strings to the
 * list of {@link EventCategory} values that classify them (Doc 01 §4.4).
 *
 * <p>Every event published via {@link com.homesynapse.event.EventPublisher} must
 * carry a non-empty categories list on its {@link com.homesynapse.event.EventEnvelope}.
 * The publisher derives the list from the draft's {@code eventType} string using
 * this lookup — callers never set categories directly, preserving the invariant
 * that {@code eventType → categories} is a single source of truth (Doc 01 §4.1).</p>
 *
 * <p><strong>Ordering.</strong> Each mapped list is an unmodifiable {@link List}
 * whose iteration order matches the order in which the categories were declared
 * in Doc 01 §4.4. The order is significant for subscriber filter dispatch
 * (Doc 01 §3.4) — the first listed category is the "primary" and the rest are
 * secondary, which is what the bus displays in its diagnostic dashboards. Do
 * not reorder without updating the doc and downstream dashboards.</p>
 *
 * <p><strong>Default for unknown types.</strong> The production event set is not
 * closed — integration-defined event types use a dotted namespace
 * ({@code {integration}.{type}}) that is registered at runtime and is not present
 * in this table at compile time. Rather than refuse to publish such events (which
 * would break integrations) or throw at the hot append path (which would make
 * the persistence layer brittle), the lookup falls back to
 * {@link EventCategory#SYSTEM} for any {@code eventType} not in the table. SYSTEM
 * is the most conservative category from the consent and crypto-shredding
 * perspective (INV-PD-07): it is non-privacy-sensitive and is retained under the
 * baseline retention policy. Integration authors who need a more specific
 * classification should add the mapping to this table in a follow-up PR.</p>
 *
 * <p><strong>Thread-safety.</strong> The table is immutable after class
 * initialization — all lists are {@link List#of} instances and the backing
 * {@link Map} is a {@link Map#copyOf} wrapper. Safe for concurrent reads from any
 * number of virtual threads.</p>
 *
 * <p>Package-private — internal persistence infrastructure, not public API.</p>
 *
 * @see EventCategory
 * @see com.homesynapse.event.EventTypes
 */
final class EventCategoryMapping {

    /** The fallback category for {@code eventType} strings not present in the table. */
    private static final List<EventCategory> DEFAULT_CATEGORIES =
            List.of(EventCategory.SYSTEM);

    /**
     * The {@code eventType → categories} lookup table derived from Doc 01 §4.4.
     *
     * <p>Entries are declared in the same order as the Glossary to keep casual
     * reviewers oriented when comparing code to doc. New entries should be
     * added alongside their sibling entries, not appended at the bottom.</p>
     */
    private static final Map<String, List<EventCategory>> TABLE;

    static {
        // LinkedHashMap would preserve insertion order, but Map.copyOf produces an
        // unmodifiable map with hashed lookup — which is what we want at steady
        // state. Insertion order only matters for source-level readability.
        TABLE = Map.ofEntries(
                // ─── Command lifecycle (device + automation) ──────────────────
                Map.entry(EventTypes.COMMAND_ISSUED,
                        List.of(EventCategory.DEVICE_STATE, EventCategory.AUTOMATION)),
                Map.entry(EventTypes.COMMAND_DISPATCHED,
                        List.of(EventCategory.DEVICE_STATE, EventCategory.AUTOMATION)),
                Map.entry(EventTypes.COMMAND_RESULT,
                        List.of(EventCategory.DEVICE_STATE, EventCategory.AUTOMATION)),
                Map.entry(EventTypes.COMMAND_CONFIRMATION_TIMED_OUT,
                        List.of(EventCategory.DEVICE_STATE, EventCategory.AUTOMATION)),

                // ─── State lifecycle (device) ─────────────────────────────────
                Map.entry(EventTypes.STATE_REPORTED,
                        List.of(EventCategory.DEVICE_STATE)),
                Map.entry(EventTypes.STATE_REPORT_REJECTED,
                        List.of(EventCategory.DEVICE_STATE)),
                Map.entry(EventTypes.STATE_CHANGED,
                        List.of(EventCategory.DEVICE_STATE)),
                Map.entry(EventTypes.STATE_CONFIRMED,
                        List.of(EventCategory.DEVICE_STATE)),

                // ─── Device lifecycle and health ──────────────────────────────
                Map.entry(EventTypes.DEVICE_DISCOVERED,
                        List.of(EventCategory.DEVICE_STATE, EventCategory.DEVICE_HEALTH)),
                Map.entry(EventTypes.DEVICE_ADOPTED,
                        List.of(EventCategory.DEVICE_STATE, EventCategory.DEVICE_HEALTH)),
                Map.entry(EventTypes.DEVICE_REMOVED,
                        List.of(EventCategory.DEVICE_STATE, EventCategory.DEVICE_HEALTH)),
                Map.entry(EventTypes.AVAILABILITY_CHANGED,
                        List.of(EventCategory.DEVICE_HEALTH)),

                // ─── Automation lifecycle ─────────────────────────────────────
                Map.entry(EventTypes.AUTOMATION_TRIGGERED,
                        List.of(EventCategory.AUTOMATION)),
                Map.entry(EventTypes.AUTOMATION_COMPLETED,
                        List.of(EventCategory.AUTOMATION)),

                // ─── Presence ─────────────────────────────────────────────────
                Map.entry(EventTypes.PRESENCE_SIGNAL,
                        List.of(EventCategory.PRESENCE)),
                Map.entry(EventTypes.PRESENCE_CHANGED,
                        List.of(EventCategory.PRESENCE)),

                // ─── System lifecycle and config ──────────────────────────────
                Map.entry(EventTypes.SYSTEM_STARTED,
                        List.of(EventCategory.SYSTEM)),
                Map.entry(EventTypes.SYSTEM_STOPPED,
                        List.of(EventCategory.SYSTEM)),
                Map.entry(EventTypes.CONFIG_CHANGED,
                        List.of(EventCategory.SYSTEM)),
                Map.entry(EventTypes.CONFIG_ERROR,
                        List.of(EventCategory.SYSTEM)),
                Map.entry(EventTypes.STORAGE_PRESSURE_CHANGED,
                        List.of(EventCategory.SYSTEM, EventCategory.DEVICE_HEALTH)),
                Map.entry(EventTypes.TELEMETRY_SUMMARY,
                        List.of(EventCategory.SYSTEM, EventCategory.DEVICE_HEALTH)),

                // ─── Integration lifecycle (Doc 05 §4.4) ──────────────────────
                Map.entry(EventTypes.INTEGRATION_STARTED,
                        List.of(EventCategory.SYSTEM, EventCategory.DEVICE_HEALTH)),
                Map.entry(EventTypes.INTEGRATION_STOPPED,
                        List.of(EventCategory.SYSTEM, EventCategory.DEVICE_HEALTH)),
                Map.entry(EventTypes.INTEGRATION_HEALTH_CHANGED,
                        List.of(EventCategory.SYSTEM, EventCategory.DEVICE_HEALTH)),
                Map.entry(EventTypes.INTEGRATION_RESTARTED,
                        List.of(EventCategory.SYSTEM, EventCategory.DEVICE_HEALTH)),
                Map.entry(EventTypes.INTEGRATION_RESOURCE_EXCEEDED,
                        List.of(EventCategory.SYSTEM, EventCategory.DEVICE_HEALTH))
        );
    }

    private EventCategoryMapping() {
        // Utility class — non-instantiable
    }

    /**
     * Returns the (non-empty) category list classifying the given
     * {@code eventType} string per Doc 01 §4.4.
     *
     * <p>Unknown types — including integration-defined types with dotted
     * namespaces — are mapped to {@code [SYSTEM]} per the fallback rationale
     * documented on the class. The returned list is never {@code null},
     * never empty, and is safe to pass directly to the
     * {@link com.homesynapse.event.EventEnvelope} constructor.</p>
     *
     * @param eventType the canonical {@code eventType} string; never {@code null}
     * @return an unmodifiable, non-empty list of categories
     * @throws NullPointerException if {@code eventType} is {@code null}
     */
    static List<EventCategory> categoriesFor(String eventType) {
        if (eventType == null) {
            throw new NullPointerException("eventType must not be null");
        }
        List<EventCategory> mapped = TABLE.get(eventType);
        return mapped != null ? mapped : DEFAULT_CATEGORIES;
    }

    /**
     * Returns the number of event types explicitly mapped in the table (excluding
     * the fallback). Exposed for diagnostic assertions in unit tests — the count
     * should match Doc 01 §4.4's enumerated entries.
     *
     * @return the explicit mapping count
     */
    static int explicitMappingCount() {
        return TABLE.size();
    }
}
