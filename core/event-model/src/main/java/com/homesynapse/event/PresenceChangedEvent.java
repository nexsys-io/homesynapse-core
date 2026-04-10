/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.util.Objects;

/**
 * Event emitted when derived presence state is updated by Presence Projection.
 * <p>
 * Valid state values: "home", "away", "unknown".
 * Priority: NORMAL
 * Doc 01 §4.3
 */
@EventType(EventTypes.PRESENCE_CHANGED)
public record PresenceChangedEvent(
        String previousState,
        String newState
) implements DomainEvent {

    /**
     * Constructs a PresenceChangedEvent with validation.
     *
     * @param previousState the previous presence state, not null or blank
     * @param newState      the new presence state, not null or blank
     */
    public PresenceChangedEvent {
        Objects.requireNonNull(previousState, "previousState cannot be null");
        if (previousState.isBlank()) {
            throw new IllegalArgumentException("previousState cannot be blank");
        }
        Objects.requireNonNull(newState, "newState cannot be null");
        if (newState.isBlank()) {
            throw new IllegalArgumentException("newState cannot be blank");
        }
    }
}
