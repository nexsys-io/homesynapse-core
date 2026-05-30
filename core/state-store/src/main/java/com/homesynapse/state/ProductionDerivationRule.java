/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import com.homesynapse.device.AttributeValue;
import com.homesynapse.device.StringValue;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.StateChangedEvent;
import com.homesynapse.event.StateReportedEvent;

import java.util.List;
import java.util.Objects;

/**
 * Production {@link DerivationRule} — the change-detect strategy that turns an
 * inbound {@code state_reported} into a derived {@code state_changed} when the
 * reported value differs from the entity's prior canonical value
 * (REC-28, plan §4.2; lifted from the M3.5a {@code EchoStateRule} fixture).
 *
 * <p>Reached from outside the package through the public static factory
 * {@link DerivationRule#production()} (DEC-M3-16 gateway, mirroring
 * {@link StateQueryService#materialized}, {@link StateCheckpointSource#stub()},
 * and {@link AtomicCheckpointSink#viewOnly}). The composition root wires it into
 * {@link StateProjection#create}; the projection publishes the returned draft on
 * LIVE so the materialized {@code attributes} map is populated and automation
 * triggers wake (plan §4.2 — the rule, not the advancer, is the publishing
 * seam).</p>
 *
 * <h2>Change-detect (string semantics only)</h2>
 *
 * <p>For an inbound {@code state_reported}:</p>
 * <ol>
 *   <li>{@code oldValue} is the prior canonical value for the reported
 *       {@code attributeKey} ({@link StringValue#value()} when the stored value
 *       is a {@code StringValue}, otherwise {@link AttributeValue#rawValue()}'s
 *       {@code toString()}), or {@code null} when no prior value exists.</li>
 *   <li>If {@code oldValue} equals {@code newValue}, no event is derived
 *       (returns {@link List#of()}).</li>
 *   <li>Otherwise a single {@link StateChangedEvent} is emitted — wrapped in an
 *       {@link EventDraft} that inherits {@code eventTime}, {@code subjectRef},
 *       and {@code actorRef} from the inbound envelope and links back to it via
 *       {@code triggeredBy = envelope.eventId()}.</li>
 * </ol>
 *
 * <p>Comparison and the {@link StateChangedEvent} {@code oldValue}/{@code
 * newValue} are {@code String} (the existing serialized form). Typed comparison
 * (REC-90) and {@code QuantityValue}/{@code ArrayValue} (M4.B3) are deliberately
 * out of scope — those types do not exist yet.</p>
 *
 * <h2>Contract compliance (INV-PROJ-01, AMD-41 §3.2.1)</h2>
 *
 * <p>The rule is a pure function of {@code (priorState, envelope)}: derived
 * {@code eventTime} inherits from the inbound envelope, never {@code Instant.now()}
 * (AMD-50 §2.4 removed the clock from {@link DerivationContext} entirely, so there
 * is no clock to read). It never publishes, never mutates the {@link StateStore},
 * and performs no I/O or randomness. Identical inputs always yield identical
 * drafts — the determinism (AMD-50-INV-03) that lets the reconciliation backfill
 * re-execute it during a replay-from-zero rebuild without diverging. The
 * projection owns publication (LIVE only) and state mutation.</p>
 *
 * @see DerivationRule#production()
 * @see DerivationRule
 * @see StateProjection
 */
final class ProductionDerivationRule implements DerivationRule {

    /**
     * Package-private constructor. Reached only through
     * {@link DerivationRule#production()}; the rule is stateless and may be
     * shared freely.
     */
    ProductionDerivationRule() {
        // Stateless; no initialization required.
    }

    @Override
    public List<EventDraft> evaluate(DerivationContext context) {
        EventEnvelope env = context.envelope();
        if (!(env.payload() instanceof StateReportedEvent sr)) {
            return List.of();
        }

        String key = sr.attributeKey();
        String newValue = sr.value();
        String oldValue = priorValue(context.priorState(), key);
        if (Objects.equals(oldValue, newValue)) {
            return List.of();
        }

        String oldNonNull = (oldValue == null) ? "" : oldValue;
        StateChangedEvent payload = new StateChangedEvent(
                key, oldNonNull, newValue, env.eventId());
        EventDraft draft = new EventDraft(
                EventTypes.STATE_CHANGED,
                1,
                env.eventTime(),     // inherit; never Instant.now() (INV-PROJ-01)
                env.subjectRef(),
                EventPriority.NORMAL,
                EventOrigin.SYSTEM,
                payload,
                env.actorRef(),      // inherit actor attribution from the cause
                null);               // no idempotency key for derived events
        return List.of(draft);
    }

    /**
     * Returns the prior canonical value (string form) for the given attribute
     * key, or {@code null} when no prior state or no prior value exists.
     */
    private static String priorValue(EntityState prior, String key) {
        if (prior == null) {
            return null;
        }
        AttributeValue value = prior.attributes().get(key);
        if (value == null) {
            return null;
        }
        return (value instanceof StringValue sv) ? sv.value() : value.rawValue().toString();
    }
}
