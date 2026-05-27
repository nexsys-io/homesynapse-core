/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.it;

import com.homesynapse.event.AvailabilityChangedEvent;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.StateReportedEvent;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.platform.identity.EntityId;

/**
 * M3.7 static factory helpers (Research 3 REC-18, REC-22) for the most
 * common test event drafts.
 *
 * <p>Tests publish events via
 * {@code harness.eventPublisher().publishRoot(TestEvents.stateReported(id, ...))}
 * rather than building 9-field {@link EventDraft}s inline. The factories
 * encode sensible defaults
 * ({@link EventPriority#NORMAL NORMAL} or {@link EventPriority#DIAGNOSTIC},
 * {@link EventOrigin#DEVICE_AUTONOMOUS DEVICE_AUTONOMOUS},
 * {@code eventTime=null}, {@code actorRef=null}, {@code idempotencyKey=null}).
 *
 * <p>This class shares NO code with M3.4a's
 * {@link IntegrationTestHarness}-era test event utilities — this is the
 * HTTP-aware E2E surface.</p>
 *
 * @see EventDraft
 */
final class TestEvents {

    private TestEvents() {
        // utility class
    }

    /**
     * Builds a {@code state_reported} draft for the given entity carrying the
     * supplied attribute key/value pair.
     *
     * @param entityId      the entity whose state was reported; never {@code null}
     * @param attributeKey  attribute name (e.g. {@code "power"}); never blank
     * @param value         canonical serialized value (e.g. {@code "on"});
     *                      never {@code null}
     * @return an {@link EventDraft} suitable for {@code publishRoot()}
     * @see StateReportedEvent
     */
    static EventDraft stateReported(EntityId entityId,
                                    String attributeKey,
                                    String value) {
        return new EventDraft(
                EventTypes.STATE_REPORTED,
                1,
                /* eventTime */ null,
                SubjectRef.entity(entityId),
                EventPriority.DIAGNOSTIC,
                EventOrigin.DEVICE_AUTONOMOUS,
                new StateReportedEvent(attributeKey, value, null, null, null),
                /* actorRef */ null,
                /* idempotencyKey */ null);
    }

    /**
     * Builds an {@code availability_changed} draft for the given entity.
     *
     * @param entityId       the entity whose availability changed
     * @param previousStatus previous availability status ({@code "online"},
     *                       {@code "offline"}, {@code "unknown"})
     * @param newStatus      new availability status
     * @return an {@link EventDraft} suitable for {@code publishRoot()}
     * @see AvailabilityChangedEvent
     */
    static EventDraft availabilityChanged(EntityId entityId,
                                          String previousStatus,
                                          String newStatus) {
        return new EventDraft(
                EventTypes.AVAILABILITY_CHANGED,
                1,
                /* eventTime */ null,
                SubjectRef.entity(entityId),
                EventPriority.NORMAL,
                EventOrigin.DEVICE_AUTONOMOUS,
                new AvailabilityChangedEvent(previousStatus, newStatus),
                /* actorRef */ null,
                /* idempotencyKey */ null);
    }
}
