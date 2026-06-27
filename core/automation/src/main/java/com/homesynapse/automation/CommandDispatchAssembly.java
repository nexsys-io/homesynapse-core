/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.Objects;
import java.util.Set;

import com.homesynapse.device.DeviceRegistry;
import com.homesynapse.device.EntityRegistry;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventPublisher;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.SubjectType;
import com.homesynapse.event.bus.Subscriber;
import com.homesynapse.event.bus.SubscriptionFilter;

/**
 * Public assembly seam that builds the co-located {@code command_dispatch_service} for the
 * composition root (OR-M7-WIRING) — mirroring {@link PendingCommandLedgerAssembly} and
 * {@link AutomationEngineAssembly}.
 *
 * <p>The {@link StandardCommandDispatchService} is one object presenting two interface views —
 * the {@link CommandDispatchService} surface (the in-process dispatch primitive + the
 * {@link CommandDispatchService#close() teardown} handle) and the bus {@link Subscriber} (for
 * registration). The composition root constructs the service's plain-value dependencies — the
 * {@link EntityRegistry} + {@link DeviceRegistry} (the two-hop entity&rarr;integration
 * resolution, AMD-95) and an {@link EventPublisher} — and calls
 * {@link #commandDispatchSubscriber(EntityRegistry, DeviceRegistry, EventPublisher)} to obtain
 * both views. It registers {@link Components#subscriber()} with {@link #subscriptionFilter()}
 * <em>after</em> the state projection has reached {@code LIVE} (the catch-up ordering invariant —
 * a dispatch subscriber must not act on the replay catch-up), and calls
 * {@link Components#service()}'s {@link CommandDispatchService#close() close()} in BOTH shutdown
 * branches (the mandatory paired teardown — the reverted-M7.3 lesson).</p>
 *
 * <p>The {@code command_issued} producer (the executor's emit) is the matching half of the
 * substrate-native command hop (§1 D1 / AMD-95). The subscriber dispatches in {@code LIVE} only
 * (D2 pure-function-replay).</p>
 */
public final class CommandDispatchAssembly {

    /** The stable subscriber id of the co-located dispatch service (Doc 07 §3.11.1). */
    public static final String SUBSCRIBER_ID = "command_dispatch_service";

    private CommandDispatchAssembly() {
        // Static seam — no instantiation.
    }

    /**
     * The two interface views of the one dispatch-service instance: the
     * {@link CommandDispatchService} surface (in-process primitive + teardown) and the bus
     * {@link Subscriber} (for registration).
     *
     * @param service    the dispatch service surface (and the {@code close()} teardown handle),
     *                   never {@code null}
     * @param subscriber the bus subscriber to register, never {@code null}
     */
    public record Components(CommandDispatchService service, Subscriber subscriber) {
    }

    /**
     * Builds the dispatch service (over a Tier-1 {@link StandardCommandValidator}) and returns its
     * {@link CommandDispatchService} and {@link Subscriber} views (the same instance).
     *
     * @param entityRegistry resolves a command target to its owning device (hop 1 of the
     *                       resolution; also the validator's capability source), never {@code null}
     * @param deviceRegistry resolves a device to its integration (hop 2), never {@code null}
     * @param publisher      the durable publish surface for {@code command_dispatched} /
     *                       {@code command_result}, never {@code null}
     * @return both interface views of the dispatch service; never {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public static Components commandDispatchSubscriber(EntityRegistry entityRegistry,
                                                      DeviceRegistry deviceRegistry,
                                                      EventPublisher publisher) {
        Objects.requireNonNull(entityRegistry, "entityRegistry");
        Objects.requireNonNull(deviceRegistry, "deviceRegistry");
        Objects.requireNonNull(publisher, "publisher");
        StandardCommandDispatchService service = new StandardCommandDispatchService(
                entityRegistry, deviceRegistry,
                new StandardCommandValidator(entityRegistry), publisher);
        return new Components(service, service);
    }

    /**
     * The dispatch subscriber's subscription filter (Doc 07 §3.11.1): the {@code command_issued}
     * event type, on entity subjects, at {@code NORMAL} minimum priority ({@code command_issued}
     * is a {@code NORMAL} event). Coalescing is disabled at registration ({@code coalesceExempt =
     * true}, correctness-critical — a coalesced {@code command_issued} would be a command that
     * never dispatched) — that is a {@code SubscriberInfo} flag, not part of the filter.
     *
     * @return the {@code command_dispatch_service} subscription filter, never {@code null}
     */
    public static SubscriptionFilter subscriptionFilter() {
        return new SubscriptionFilter(
                Set.of(EventTypes.COMMAND_ISSUED),
                EventPriority.NORMAL,
                SubjectType.ENTITY);
    }
}
