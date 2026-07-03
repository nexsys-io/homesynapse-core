/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.runtime;

import com.homesynapse.config.ConfigurationAccess;
import com.homesynapse.device.EntityRegistry;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventPublisher;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.SubjectType;
import com.homesynapse.event.bus.Subscriber;
import com.homesynapse.event.bus.SubscriptionFilter;
import com.homesynapse.state.StateQueryService;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Public assembly seam that builds the integration spine — the
 * {@link IntegrationSupervisor} implementation and its command-routing bus
 * {@link Subscriber} — for the composition root, without promoting the
 * concrete {@link StandardIntegrationSupervisor} or
 * {@link CommandRoutingSubscriber} to public (mirroring
 * {@code PendingCommandLedgerAssembly}).
 *
 * <p>The two collaborators share supervisor state: the router resolves each
 * LIVE {@code command_dispatched} to its owning adapter's command executor
 * through the supervisor's package-private routing seam. The returned
 * {@link Components#subscriber()} is the ONLY exported view of the router.</p>
 *
 * <p><strong>Composition contract (DP-3, DP-12/B7).</strong> This module has
 * NO JSON library: the {@code parameterDecoder} (the {@code
 * command_issued.parameters} JSON string → parameter map) is injected from the
 * composition root, which sources it from the persistence module's
 * {@code ObjectMapper} — the exact M7.4b serializer pattern in the opposite
 * direction. The {@code configAccessFactory} yields a per-integration-scoped
 * {@link ConfigurationAccess} for each {@code integrationType} (the config
 * module's {@code ConfigurationAccess.scoped(...)} over the live config
 * model); the supervisor calls it once per adapter at context construction.</p>
 */
public final class IntegrationSupervisorAssembly {

    /**
     * The stable subscriber id of the integration spine's routing subscriber —
     * one service, one snake_case id (the {@code automation_engine} precedent).
     */
    public static final String SUBSCRIBER_ID = "integration_supervisor";

    private IntegrationSupervisorAssembly() {
        // Static seam — no instantiation.
    }

    /**
     * The two views of the integration spine: the supervisor (lifecycle +
     * health surface, held by the composition root for Phase-6 start and
     * teardown) and the routing bus subscriber (registered with
     * {@link #subscriptionFilter()}).
     *
     * @param supervisor the supervisor view; never {@code null}
     * @param subscriber the command-routing bus subscriber; never {@code null}
     */
    public record Components(IntegrationSupervisor supervisor, Subscriber subscriber) {
    }

    /**
     * The router's subscription filter (DP-2): BOTH {@code command_issued}
     * (populates the causation join cache) and {@code command_dispatched} (the
     * LIVE dispatch trigger), on entity subjects, accepting every priority tier
     * — {@code command_dispatched} is published DIAGNOSTIC. Coalescing is
     * disabled at registration ({@code coalesceExempt = true} on the
     * {@code SubscriberInfo}, not part of this filter): a coalesced
     * {@code command_dispatched} is a command that never reaches the device.
     *
     * @return the {@code integration_supervisor} subscription filter; never {@code null}
     */
    public static SubscriptionFilter subscriptionFilter() {
        return new SubscriptionFilter(
                Set.of(EventTypes.COMMAND_ISSUED, EventTypes.COMMAND_DISPATCHED),
                EventPriority.DIAGNOSTIC,
                SubjectType.ENTITY);
    }

    /**
     * Builds ONE supervisor and ONE routing subscriber sharing supervisor state.
     *
     * @param publisher           the durable publish surface for lifecycle events and
     *                            failure {@code command_result}s; never {@code null}
     * @param entityRegistry      the real entity registry for adapter context
     *                            composition (DP-12); never {@code null}
     * @param stateQueryService   the real state query surface for adapter context
     *                            composition (DP-12); never {@code null}
     * @param configAccessFactory yields the per-integration-scoped
     *                            {@link ConfigurationAccess} for an
     *                            {@code integrationType} (B7); never {@code null}
     * @param parameterDecoder    decodes {@code command_issued.parameters} (a JSON
     *                            object string) to the adapter's parameter map;
     *                            empty/{@code "{}"}/null-safe → {@code Map.of()}
     *                            (DP-3); never {@code null}
     * @param clock               the injected clock (NO_DIRECT_TIME_ACCESS); never
     *                            {@code null}
     * @return the two views of the integration spine; never {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public static Components integrationSupervisor(
            EventPublisher publisher,
            EntityRegistry entityRegistry,
            StateQueryService stateQueryService,
            Function<String, ConfigurationAccess> configAccessFactory,
            Function<String, Map<String, Object>> parameterDecoder,
            Clock clock) {
        Objects.requireNonNull(publisher, "publisher");
        Objects.requireNonNull(entityRegistry, "entityRegistry");
        Objects.requireNonNull(stateQueryService, "stateQueryService");
        Objects.requireNonNull(configAccessFactory, "configAccessFactory");
        Objects.requireNonNull(parameterDecoder, "parameterDecoder");
        Objects.requireNonNull(clock, "clock");
        StandardIntegrationSupervisor supervisor = new StandardIntegrationSupervisor(
                publisher, entityRegistry, stateQueryService, configAccessFactory, clock,
                StandardIntegrationSupervisor.DEFAULT_CLOSE_GRACE);
        CommandRoutingSubscriber subscriber = new CommandRoutingSubscriber(
                supervisor, parameterDecoder, publisher, clock);
        return new Components(supervisor, subscriber);
    }

    /**
     * Fast supervisor stop for the composition root's ungraceful-shutdown branch
     * (W4): interrupts adapters and skips grace-period waits and stopped-event
     * publishes — the {@code InProcessEventBus.abandon()} concrete-class
     * precedent, reached through this same-package gateway because the frozen
     * 9-method {@link IntegrationSupervisor} interface carries no fast path.
     * Falls back to {@link IntegrationSupervisor#stop()} for a foreign
     * implementation.
     *
     * @param supervisor the supervisor to abandon; never {@code null}
     */
    public static void abandon(IntegrationSupervisor supervisor) {
        Objects.requireNonNull(supervisor, "supervisor");
        if (supervisor instanceof StandardIntegrationSupervisor standard) {
            standard.abandon();
        } else {
            supervisor.stop();
        }
    }
}
