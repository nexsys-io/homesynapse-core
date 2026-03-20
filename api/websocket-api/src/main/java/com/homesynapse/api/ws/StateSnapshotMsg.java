/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.ws;

import java.util.List;
import java.util.Objects;

/**
 * Server-to-client initial state snapshot message
 * (wire type: {@code "state_snapshot"}).
 *
 * <p>Delivered when {@link SubscribeMsg#includeInitialState()} is
 * {@code true}. Provides the current state of all entities matching the
 * subscription's filter before event streaming begins.</p>
 *
 * <p>The {@code entities} list contains serialized entity state objects —
 * not typed domain objects — to avoid leaking domain types into the wire
 * protocol hierarchy. Phase 3 delivers {@code Map} or Jackson
 * {@code ObjectNode} instances per Doc 10 §4.2 JSON schema.</p>
 *
 * <p>The {@code viewPosition} indicates the State Store's projection version
 * at snapshot time. Event delivery begins from this position (or
 * {@code fromGlobalPosition} if specified and earlier), ensuring no state
 * transitions are missed.</p>
 *
 * <p>Thread-safe (immutable record with unmodifiable list).</p>
 *
 * @param id             {@code null} (server-initiated message)
 * @param subscriptionId the subscription that requested the snapshot,
 *                       never {@code null}
 * @param viewPosition   State Store projection version at snapshot time
 * @param entities       serialized entity state objects, never {@code null}
 *
 * @see SubscribeMsg#includeInitialState()
 * @see <a href="Doc 10 §4.2">State Snapshots</a>
 */
public record StateSnapshotMsg(
        Integer id,
        String subscriptionId,
        long viewPosition,
        List<Object> entities
) implements WsMessage {

    /**
     * Creates a new state snapshot message with validation and defensive copy.
     *
     * @throws NullPointerException if {@code subscriptionId} or {@code entities}
     *                              is {@code null}
     */
    public StateSnapshotMsg {
        Objects.requireNonNull(subscriptionId, "subscriptionId must not be null");
        Objects.requireNonNull(entities, "entities must not be null");
        entities = List.copyOf(entities);
    }
}
