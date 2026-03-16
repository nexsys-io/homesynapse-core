/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.util.Objects;

import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.IntegrationId;
import com.homesynapse.platform.identity.PersonId;
import com.homesynapse.platform.identity.SystemId;
import com.homesynapse.platform.identity.Ulid;

/**
 * Identifies the subject of an event — the domain object the event is "about."
 *
 * <p>Every event envelope carries a subject reference (Doc 01 §4.1 {@code subject_ref}).
 * The subject reference pairs a {@link Ulid} with a {@link SubjectType} discriminator so
 * that the Event Bus can resolve the subject's type category at append time for subscription
 * filter evaluation (Doc 01 §3.4) without requiring a registry lookup.</p>
 *
 * <p>The {@code id} field holds the subject's ULID. The {@code type} field classifies the
 * subject. Together they enable type-safe construction through the static factory methods
 * while storing a uniform representation in the event envelope.</p>
 *
 * <p><strong>Storage:</strong> In SQLite, the ULID is stored in the {@code subject_ref}
 * column as {@code BLOB(16)}. The subject type is derivable from the typed identity and
 * may be denormalized into a separate column by the persistence layer for efficient
 * bus-side filtering.</p>
 *
 * @param id   the ULID of the subject, never {@code null}
 * @param type the subject type category, never {@code null}
 * @see SubjectType
 */
public record SubjectRef(Ulid id, SubjectType type) {

    /**
     * Validates that both fields are non-null.
     *
     * @throws NullPointerException if {@code id} or {@code type} is {@code null}
     */
    public SubjectRef {
        Objects.requireNonNull(id, "SubjectRef id must not be null");
        Objects.requireNonNull(type, "SubjectRef type must not be null");
    }

    /**
     * Creates a subject reference for an entity.
     *
     * @param entityId the entity identifier, never {@code null}
     * @return a new {@code SubjectRef} with type {@link SubjectType#ENTITY}
     */
    public static SubjectRef entity(EntityId entityId) {
        Objects.requireNonNull(entityId, "entityId must not be null");
        return new SubjectRef(entityId.value(), SubjectType.ENTITY);
    }

    /**
     * Creates a subject reference for a device.
     *
     * @param deviceId the device identifier, never {@code null}
     * @return a new {@code SubjectRef} with type {@link SubjectType#DEVICE}
     */
    public static SubjectRef device(DeviceId deviceId) {
        Objects.requireNonNull(deviceId, "deviceId must not be null");
        return new SubjectRef(deviceId.value(), SubjectType.DEVICE);
    }

    /**
     * Creates a subject reference for an integration adapter.
     *
     * @param integrationId the integration identifier, never {@code null}
     * @return a new {@code SubjectRef} with type {@link SubjectType#INTEGRATION}
     */
    public static SubjectRef integration(IntegrationId integrationId) {
        Objects.requireNonNull(integrationId, "integrationId must not be null");
        return new SubjectRef(integrationId.value(), SubjectType.INTEGRATION);
    }

    /**
     * Creates a subject reference for an automation rule.
     *
     * @param automationId the automation identifier, never {@code null}
     * @return a new {@code SubjectRef} with type {@link SubjectType#AUTOMATION}
     */
    public static SubjectRef automation(AutomationId automationId) {
        Objects.requireNonNull(automationId, "automationId must not be null");
        return new SubjectRef(automationId.value(), SubjectType.AUTOMATION);
    }

    /**
     * Creates a subject reference for the system instance.
     *
     * @param systemId the system identifier, never {@code null}
     * @return a new {@code SubjectRef} with type {@link SubjectType#SYSTEM}
     */
    public static SubjectRef system(SystemId systemId) {
        Objects.requireNonNull(systemId, "systemId must not be null");
        return new SubjectRef(systemId.value(), SubjectType.SYSTEM);
    }

    /**
     * Creates a subject reference for a person.
     *
     * @param personId the person identifier, never {@code null}
     * @return a new {@code SubjectRef} with type {@link SubjectType#PERSON}
     */
    public static SubjectRef person(PersonId personId) {
        Objects.requireNonNull(personId, "personId must not be null");
        return new SubjectRef(personId.value(), SubjectType.PERSON);
    }

    @Override
    public String toString() {
        return type.name().toLowerCase() + ":" + id;
    }
}
