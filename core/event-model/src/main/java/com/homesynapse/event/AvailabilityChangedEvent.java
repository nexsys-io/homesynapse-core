/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.time.Instant;
import java.util.Objects;

/**
 * Event emitted when device availability status changes.
 *
 * <p>Valid status values: {@code "online"}, {@code "offline"}, {@code "unknown"}.
 * Priority: CRITICAL when transitioning to offline, NORMAL when to online. Doc 01 §4.3.
 *
 * <h2>Schema versions</h2>
 * <ul>
 *   <li><b>1</b> — {@code previousStatus}, {@code newStatus}.</li>
 *   <li><b>2</b> (J1 / LINK-READ-2 + IR-121, 2026-10-03) — adds five NULLABLE components:
 *       {@code reason} (the adapter's transition reason, lower-cased — {@code first_contact},
 *       {@code frame_received}, {@code ping_success}, {@code ping_timeout},
 *       {@code silence_timeout}, {@code leave}), {@code lastSeenAt} (the last
 *       device-originated evidence instant known at the transition) and the LAST link reading
 *       the adapter kept for the device — {@code lqi}, {@code rssiDbm} and {@code linkAt}
 *       (the receipt instant of the frame that delivered it). A dark device's event therefore
 *       says WHEN it was last heard and HOW its link read at that frame.</li>
 * </ul>
 *
 * <p>The upcast is the persistence codec's tolerant decode (Doc 01 §3.10): a version-1 row
 * reads as this record with the five additions {@code null}; a version-2 row read by a pre-J1
 * core ignores the extras. The additions are reference-typed so absence decodes to
 * {@code null}, and this canonical constructor is the record's ONLY creator — a secondary
 * constructor would conflict with Jackson's property-based creator resolution.
 *
 * <p><strong>Invariant:</strong> {@code lqi}, {@code rssiDbm} and {@code linkAt} are all
 * {@code null} or all set — a reading without its instant, or an instant without its reading,
 * is rejected. A {@code null} in any of the five additions is a VALUE, never an error.
 *
 * @param previousStatus the previous availability status, never {@code null} or blank
 * @param newStatus      the new availability status, never {@code null} or blank
 * @param reason         the transition reason token, or {@code null} (a version-1 row)
 * @param lastSeenAt     the last evidence instant known at the transition, or {@code null}
 * @param lqi            the last link reading's LQI (0..255), or {@code null}
 * @param rssiDbm        the last link reading's RSSI in dBm, or {@code null}
 * @param linkAt         the receipt instant of the frame that delivered the reading, or
 *                       {@code null}
 */
@EventType(EventTypes.AVAILABILITY_CHANGED)
public record AvailabilityChangedEvent(
        String previousStatus,
        String newStatus,
        String reason,
        Instant lastSeenAt,
        Integer lqi,
        Integer rssiDbm,
        Instant linkAt
) implements DomainEvent {

    /**
     * Constructs an AvailabilityChangedEvent with validation.
     *
     * @param previousStatus the previous availability status, not null or blank
     * @param newStatus      the new availability status, not null or blank
     * @param reason         the transition reason token, nullable
     * @param lastSeenAt     the last evidence instant, nullable
     * @param lqi            the last link reading's LQI, nullable
     * @param rssiDbm        the last link reading's RSSI in dBm, nullable
     * @param linkAt         the reading's frame instant, nullable
     */
    public AvailabilityChangedEvent {
        Objects.requireNonNull(previousStatus, "previousStatus cannot be null");
        if (previousStatus.isBlank()) {
            throw new IllegalArgumentException("previousStatus cannot be blank");
        }
        Objects.requireNonNull(newStatus, "newStatus cannot be null");
        if (newStatus.isBlank()) {
            throw new IllegalArgumentException("newStatus cannot be blank");
        }
        boolean anyLink = lqi != null || rssiDbm != null || linkAt != null;
        boolean wholeLink = lqi != null && rssiDbm != null && linkAt != null;
        if (anyLink && !wholeLink) {
            throw new IllegalArgumentException(
                    "lqi, rssiDbm and linkAt must be all null or all set");
        }
    }
}
