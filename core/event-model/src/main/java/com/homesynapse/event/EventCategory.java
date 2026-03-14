/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

package com.homesynapse.event;

/**
 * Consent-scope categories for event classification.
 *
 * <p>Every event is classified into one or more categories that define the boundaries
 * for crypto-shredding (INV-PD-07), future access-control policies, and subscription
 * filtering (Doc 01 §4.4). Categories are architectural — they are fixed at compile
 * time and cannot be extended by integrations or users.</p>
 *
 * <p>The {@code EventPublisher} populates the event's category set at creation time
 * using a static, compile-time mapping from event type to category set. An event type
 * may map to one or more categories (e.g., {@code command_issued} maps to both
 * {@link #DEVICE_STATE} and {@link #AUTOMATION}).</p>
 *
 * <p><strong>Storage:</strong> Stored as a JSON array of category strings in the
 * {@code event_category} column of the domain event store.</p>
 *
 * @see <a href="Doc 01 §4.4">Event Category Taxonomy</a>
 */
public enum EventCategory {

    /**
     * Device attribute changes, discovery, adoption, commands, and availability.
     *
     * <p>The operational state of physical devices and their logical entities.</p>
     */
    DEVICE_STATE("device_state"),

    /**
     * Energy metering, tariff events, and grid interaction.
     *
     * <p>Energy consumption, production, and grid-interactive events. Subject to
     * energy data sovereignty (INV-EI-04).</p>
     */
    ENERGY("energy"),

    /**
     * Presence signals and presence state changes.
     *
     * <p>Occupancy and person-location data. Among the most privacy-sensitive
     * categories — reveals daily routines.</p>
     */
    PRESENCE("presence"),

    /**
     * Temperature, humidity, air quality, and light level readings.
     *
     * <p>Ambient environmental sensor readings. May correlate with occupancy when
     * combined with presence data.</p>
     */
    ENVIRONMENTAL("environmental"),

    /**
     * Lock state, alarm state, camera motion, and access events.
     *
     * <p>Physical security device events.</p>
     */
    SECURITY("security"),

    /**
     * Automation triggers, completions, conflicts, and disablements.
     *
     * <p>Automation execution lifecycle. Reveals behavioral patterns when correlated
     * with {@link #DEVICE_STATE}.</p>
     */
    AUTOMATION("automation"),

    /**
     * Device availability, integration health, battery, and signal quality.
     *
     * <p>Device and integration operational health. Not privacy-sensitive in isolation.</p>
     */
    DEVICE_HEALTH("device_health"),

    /**
     * Startup, shutdown, configuration, migration, and system health.
     *
     * <p>Platform infrastructure events. Not privacy-sensitive.</p>
     */
    SYSTEM("system");

    private final String wireValue;

    EventCategory(String wireValue) {
        this.wireValue = wireValue;
    }

    /**
     * Returns the wire-format string used in JSON serialization and SQLite storage.
     *
     * <p>The wire value is a lowercase, underscore-separated identifier matching the
     * category name in the design documentation and the JSON array stored in the
     * {@code event_category} column.</p>
     *
     * @return the wire-format category string, never {@code null}
     */
    public String wireValue() {
        return wireValue;
    }

    /**
     * Resolves an {@code EventCategory} from its wire-format string.
     *
     * @param wireValue the wire-format string (e.g., {@code "device_state"})
     * @return the matching {@code EventCategory}
     * @throws IllegalArgumentException if no category matches the given wire value
     */
    public static EventCategory fromWireValue(String wireValue) {
        for (EventCategory category : values()) {
            if (category.wireValue.equals(wireValue)) {
                return category;
            }
        }
        throw new IllegalArgumentException("Unknown EventCategory wire value: " + wireValue);
    }
}
