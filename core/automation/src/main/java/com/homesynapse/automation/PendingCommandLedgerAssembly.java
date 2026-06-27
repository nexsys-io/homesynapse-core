/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.time.Clock;
import java.util.Objects;
import java.util.Set;

import com.homesynapse.device.EntityRegistry;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventPublisher;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.SubjectType;
import com.homesynapse.event.bus.Subscriber;
import com.homesynapse.event.bus.SubscriptionFilter;

/**
 * Public assembly seam that builds the {@code pending_command_ledger} for the composition root
 * (AB-3 lifecycle) without promoting the concrete {@link StandardPendingCommandLedger} to
 * public — mirroring {@link AutomationEngineAssembly} and {@link RunManagerAssembly}.
 *
 * <p>{@code StandardPendingCommandLedger} is package-private: it implements the
 * {@code com.homesynapse.event.bus} {@link Subscriber} contract (the {@code requires event.bus}
 * edge is used only by the subscriber adapters), and is one object presenting two interface
 * views — the {@link PendingCommandLedger} query surface (for the future REST/causal-read
 * consumers) and the bus {@link Subscriber} (for registration). The composition root constructs
 * the ledger's plain-value dependencies — an {@link EventPublisher}, the {@link EntityRegistry}
 * (capability resolution on {@code command_issued}), an injected {@link Clock} (REC-156/167),
 * and the default confirmation window (AMD-90: 30000 ms; REC-161 calibration) — and calls
 * {@link #pendingCommandLedger(EventPublisher, EntityRegistry, Clock, long)} to obtain the
 * three views, then subscribes {@link Components#subscriber()} with {@link #subscriptionFilter()}
 * after the state projection has reached {@code LIVE} (the catch-up ordering invariant) and drives
 * {@link Components#expirationTick()} from a periodic scheduler (M7.4c — the deadline sweep).</p>
 */
public final class PendingCommandLedgerAssembly {

    /** The stable subscriber id of the pending-command ledger (Doc 07 §3.11.2). */
    public static final String SUBSCRIBER_ID = "pending_command_ledger";

    /**
     * The default confirmation window when a {@code command_issued} carries no positive
     * {@code confirmationTimeoutMs} (AMD-90 — {@code default_confirmation_timeout_ms} 30000;
     * REC-161 calibration; the V1 single global default per the launch-scope record).
     */
    public static final long DEFAULT_CONFIRMATION_TIMEOUT_MS = 30_000L;

    private PendingCommandLedgerAssembly() {
        // Static seam — no instantiation.
    }

    /**
     * The three views of the one ledger instance: the {@link PendingCommandLedger} query surface,
     * the bus {@link Subscriber}, and the deadline-sweep tick.
     *
     * <p>The {@code expirationTick} is the ledger's package-private {@code pollExpirations()}
     * exposed as a {@link Runnable} (the same package can bind the method reference) so the
     * composition root can drive it from a periodic scheduler WITHOUT promoting an operational
     * tick onto the query-only {@link PendingCommandLedger} interface. Each invocation compares the
     * injected clock to every in-flight deadline and times out the expired ones — deterministic
     * under a stepped clock, never a wall-clock sleep (REC-156/167).</p>
     *
     * @param ledger         the query surface (command status lookups), never {@code null}
     * @param subscriber     the bus subscriber to register, never {@code null}
     * @param expirationTick the deadline-sweep tick to schedule periodically, never {@code null}
     */
    public record Components(PendingCommandLedger ledger, Subscriber subscriber,
                            Runnable expirationTick) {
    }

    /**
     * Builds the ledger and returns its {@link PendingCommandLedger} query, {@link Subscriber},
     * and {@link Runnable} deadline-sweep views (all the same instance).
     *
     * @param publisher                    the durable publish surface for {@code state_confirmed}
     *                                     / {@code command_confirmation_timed_out}, never
     *                                     {@code null}
     * @param entityRegistry               resolves a command target to its capability, never
     *                                     {@code null}
     * @param clock                        the injected clock (REC-156/167), never {@code null}
     * @param defaultConfirmationTimeoutMs the fallback confirmation window in milliseconds,
     *                                     {@code > 0}
     * @return the three interface views of the ledger; never {@code null}
     * @throws NullPointerException     if {@code publisher}, {@code entityRegistry}, or
     *                                  {@code clock} is {@code null}
     * @throws IllegalArgumentException if {@code defaultConfirmationTimeoutMs <= 0}
     */
    public static Components pendingCommandLedger(EventPublisher publisher,
                                                 EntityRegistry entityRegistry, Clock clock,
                                                 long defaultConfirmationTimeoutMs) {
        Objects.requireNonNull(publisher, "publisher");
        Objects.requireNonNull(entityRegistry, "entityRegistry");
        Objects.requireNonNull(clock, "clock");
        StandardPendingCommandLedger ledger = new StandardPendingCommandLedger(
                publisher, entityRegistry, clock, defaultConfirmationTimeoutMs);
        return new Components(ledger, ledger, ledger::pollExpirations);
    }

    /**
     * The ledger's subscription filter (Doc 07 §3.11.2): the four command/state correlation
     * event types, on entity subjects, accepting every priority tier ({@code state_reported} is
     * {@code DIAGNOSTIC}). Coalescing is disabled at registration ({@code coalesceExempt = true},
     * correctness-critical per Doc 01 §3.6) — that is a {@code SubscriberInfo} flag, not part of
     * the filter.
     *
     * @return the {@code pending_command_ledger} subscription filter, never {@code null}
     */
    public static SubscriptionFilter subscriptionFilter() {
        return new SubscriptionFilter(
                Set.of(EventTypes.COMMAND_ISSUED, EventTypes.COMMAND_RESULT,
                        EventTypes.STATE_REPORTED, EventTypes.STATE_CONFIRMED),
                EventPriority.DIAGNOSTIC,
                SubjectType.ENTITY);
    }
}
