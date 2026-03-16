/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

import java.util.Objects;
import java.util.Set;

import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.SubjectType;

/**
 * Immutable filter that determines which events a subscriber receives from the
 * {@link EventBus}.
 *
 * <p>A subscription filter is a conjunction of up to three criteria. An event envelope
 * must satisfy <em>all</em> non-trivial criteria to pass the filter:</p>
 * <ol>
 *   <li><strong>Event type:</strong> If {@link #eventTypes()} is non-empty, only events
 *       whose {@link EventEnvelope#eventType()} is contained in the set are accepted.
 *       An empty set means "accept all event types."</li>
 *   <li><strong>Minimum priority:</strong> Only events at or above the
 *       {@link #minimumPriority()} tier are accepted. {@link EventPriority} is ordered
 *       {@link EventPriority#CRITICAL CRITICAL} (ordinal 0) &gt;
 *       {@link EventPriority#NORMAL NORMAL} (ordinal 1) &gt;
 *       {@link EventPriority#DIAGNOSTIC DIAGNOSTIC} (ordinal 2). A minimum priority of
 *       {@code NORMAL} accepts {@code CRITICAL} and {@code NORMAL} but rejects
 *       {@code DIAGNOSTIC}.</li>
 *   <li><strong>Subject type:</strong> If {@link #subjectTypeFilter()} is non-null, only
 *       events whose {@link EventEnvelope#subjectRef() subjectRef} has a matching
 *       {@link SubjectType} are accepted. A {@code null} subject type filter means
 *       "accept all subject types."</li>
 * </ol>
 *
 * <p>This record is thread-safe. The {@link #eventTypes()} set is defensively copied via
 * {@link Set#copyOf(java.util.Collection)} in the compact constructor, ensuring immutability
 * regardless of what the caller retains.</p>
 *
 * @param eventTypes        the set of event type strings this subscriber wants; an empty set
 *                          means "all types." Never {@code null}. Defensively copied.
 * @param minimumPriority   the minimum priority tier to receive — events with a lower
 *                          priority (higher ordinal) are rejected. Never {@code null}.
 * @param subjectTypeFilter restrict delivery to events whose subject belongs to a specific
 *                          {@link SubjectType} category. {@code null} means "all subject
 *                          types."
 * @see EventBus
 * @see SubscriberInfo
 * @see <a href="Doc 01 §3.4">Subscription Model</a>
 */
public record SubscriptionFilter(
        Set<String> eventTypes,
        EventPriority minimumPriority,
        SubjectType subjectTypeFilter
) {

    /**
     * Validates and defensively copies all filter components.
     *
     * @throws NullPointerException if {@code eventTypes} or {@code minimumPriority} is
     *                              {@code null}
     */
    public SubscriptionFilter {
        Objects.requireNonNull(eventTypes, "eventTypes must not be null");
        Objects.requireNonNull(minimumPriority, "minimumPriority must not be null");
        eventTypes = Set.copyOf(eventTypes);
    }

    /**
     * Returns a filter that matches all events — empty event type set,
     * {@link EventPriority#DIAGNOSTIC DIAGNOSTIC} minimum priority (accepts everything),
     * and no subject type restriction.
     *
     * @return a permissive filter that matches every event envelope
     */
    public static SubscriptionFilter all() {
        return new SubscriptionFilter(Set.of(), EventPriority.DIAGNOSTIC, null);
    }

    /**
     * Returns a filter that matches specific event types at
     * {@link EventPriority#DIAGNOSTIC DIAGNOSTIC} minimum priority (accepts all priorities)
     * with no subject type restriction.
     *
     * @param eventTypes the event type strings to match
     * @return a filter matching only the specified event types
     * @throws NullPointerException if {@code eventTypes} is {@code null}
     */
    public static SubscriptionFilter forTypes(String... eventTypes) {
        Objects.requireNonNull(eventTypes, "eventTypes must not be null");
        return new SubscriptionFilter(Set.of(eventTypes), EventPriority.DIAGNOSTIC, null);
    }

    /**
     * Returns a filter that matches all event types at or above the given priority tier,
     * with no subject type restriction.
     *
     * @param minimumPriority the minimum priority tier to accept
     * @return a filter matching all event types at the given priority or above
     * @throws NullPointerException if {@code minimumPriority} is {@code null}
     */
    public static SubscriptionFilter forPriority(EventPriority minimumPriority) {
        Objects.requireNonNull(minimumPriority, "minimumPriority must not be null");
        return new SubscriptionFilter(Set.of(), minimumPriority, null);
    }

    /**
     * Tests whether the given event envelope passes this filter.
     *
     * <p>An envelope matches if and only if all of the following hold:</p>
     * <ol>
     *   <li>{@link #eventTypes()} is empty, or it contains
     *       {@link EventEnvelope#eventType() envelope.eventType()}.</li>
     *   <li>The envelope's {@link EventEnvelope#priority() priority} ordinal is less than
     *       or equal to this filter's {@link #minimumPriority()} ordinal (i.e., the
     *       envelope's priority is at or above the minimum tier).</li>
     *   <li>{@link #subjectTypeFilter()} is {@code null}, or it equals the envelope's
     *       {@link EventEnvelope#subjectRef() subjectRef}
     *       {@link com.homesynapse.event.SubjectRef#type() type}.</li>
     * </ol>
     *
     * @param envelope the event envelope to test against this filter
     * @return {@code true} if the envelope satisfies all filter criteria
     * @throws NullPointerException if {@code envelope} is {@code null}
     */
    public boolean matches(EventEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope must not be null");

        if (!eventTypes.isEmpty() && !eventTypes.contains(envelope.eventType())) {
            return false;
        }

        if (envelope.priority().ordinal() > minimumPriority.ordinal()) {
            return false;
        }

        if (subjectTypeFilter != null && subjectTypeFilter != envelope.subjectRef().type()) {
            return false;
        }

        return true;
    }
}
