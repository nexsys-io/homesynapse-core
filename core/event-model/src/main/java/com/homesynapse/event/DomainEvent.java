/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

/**
 * Marker interface for all event payloads carried by {@link EventEnvelope} (Doc 01 §4.1).
 *
 * <p>Every event type in the HomeSynapse taxonomy (Doc 01 §4.3) is represented by a record
 * that implements {@code DomainEvent}. The {@link EventEnvelope#payload()} field is typed as
 * {@code DomainEvent}, and subscribers use Java 21 pattern matching on subtypes for type-safe
 * dispatch:</p>
 *
 * <pre>{@code
 * switch (envelope.payload()) {
 *     case StateChanged sc  -> handleStateChanged(sc);
 *     case StateReported sr -> handleStateReported(sr);
 *     // ...
 * }
 * }</pre>
 *
 * <p>This interface is permanently non-sealed (AMD-33). {@code IntegrationLifecycleEvent}
 * in module {@code com.homesynapse.integration} extends {@code DomainEvent} from a different
 * JPMS module, and JEP 409 requires all permitted subtypes of a sealed type to reside in the
 * same module. Subscribers use pattern matching with a {@code default} branch rather than
 * exhaustive {@code switch}. Subsystem-level sealed hierarchies (e.g.,
 * {@code IntegrationLifecycleEvent}) provide exhaustive matching where it matters.</p>
 *
 * <p>{@link DegradedEvent} implements this interface for events whose payload could not be
 * upcast to the current schema version (Doc 01 §3.10), allowing diagnostic tools to process
 * failed upcasts alongside normal events.</p>
 *
 * @see EventEnvelope
 * @see DegradedEvent
 */
public interface DomainEvent {
}
