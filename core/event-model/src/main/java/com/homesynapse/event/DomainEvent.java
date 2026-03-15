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
 * <p>This interface is currently non-sealed. It will become a {@code sealed} interface when
 * concrete payload records are defined in Phase 3, at which point the compiler will enforce
 * exhaustiveness in {@code switch} expressions over the permitted subtypes.</p>
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
