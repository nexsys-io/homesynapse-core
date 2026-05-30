/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import com.homesynapse.device.AttributeSchema;
import com.homesynapse.device.AttributeValue;
import com.homesynapse.device.StringValue;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.StateChangedEvent;
import com.homesynapse.event.StateReportedEvent;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.event.SubjectType;
import com.homesynapse.platform.identity.EntityId;

import java.util.List;
import java.util.Objects;

/**
 * Production {@link DerivationRule} — the typed change-detect strategy that turns an inbound
 * {@code state_reported} into a derived {@code state_changed} when the reported value
 * represents a real change from the entity's prior canonical value (AMD-51 / M4.0b-3).
 *
 * <p>Reached from outside the package through the public static factories
 * {@link DerivationRule#production()} (string-semantics, empty schema resolver) and
 * {@link DerivationRule#production(AttributeValueComparator, ComparisonPolicy,
 * AttributeSchemaResolver)} (typed, wired at the composition root with the standard
 * capability schemas) — both DEC-M3-16 gateways.</p>
 *
 * <h2>Typed change-detect (AMD-51-INV-05 / DP-H)</h2>
 *
 * <p>For an inbound {@code state_reported}, the rule:</p>
 * <ol>
 *   <li>resolves the {@link AttributeSchema} for the reported {@code attributeKey} (or
 *       {@code null} when the resolver knows no schema — string-compare fallback);</li>
 *   <li><strong>reconstructs both operands</strong> to the schema-declared typed
 *       {@link AttributeValue} via {@link AttributeValueReconstructor} — the inbound
 *       {@code value} (+ event {@code unit} for QUANTITY) <em>and</em> the prior, which is
 *       always a {@code StringValue} (or {@code null}) in the materialized state, reconstructed
 *       by the identical parse (its QUANTITY unit is the schema canonical unit);</li>
 *   <li>asks the {@link AttributeValueComparator} whether to emit (typed equality with the
 *       pinned float/quantity epsilon, the order-sensitive array compare, and the asymmetric
 *       Degraded / null-prior rules folded in);</li>
 *   <li>when the comparator emits, constructs the <strong>same String</strong>
 *       {@link StateChangedEvent} the pre-typed rule built — {@code oldValue} = the stringified
 *       prior (empty when none), {@code newValue} = {@code StateReportedEvent.value()}, linked
 *       via {@code triggeredBy = envelope.eventId()}.</li>
 * </ol>
 *
 * <p>The typed values are transient: the materialized attribute and the emitted payload stay
 * {@code String} (the typed payload is AMD-52, deliberately staged — §2.7). Reconstruction is
 * a separate step from the {@code AttributeValueUpcaster} SPI (left unchanged).</p>
 *
 * <h2>Contract compliance (INV-PROJ-01, AMD-50-INV-03, AMD-41 §3.2.1)</h2>
 *
 * <p>The rule is a pure function of {@code (priorState, envelope)} plus the injected immutable
 * schema snapshot: derived {@code eventTime} inherits from the inbound envelope, never
 * {@code Instant.now()} (there is no clock in {@link DerivationContext}). It never publishes,
 * never mutates the {@link StateStore}, and performs no I/O (beyond reconstruction diagnostics)
 * or randomness. Identical inputs always yield identical drafts — the determinism the
 * reconciliation backfill relies on when it re-executes the rule during the 2&rarr;3
 * replay-from-zero rebuild (AMD-50-INV-03). The projection owns publication (LIVE only) and
 * state mutation.</p>
 *
 * @see DerivationRule#production()
 * @see DerivationRule#production(AttributeValueComparator, ComparisonPolicy, AttributeSchemaResolver)
 * @see AttributeValueComparator
 * @see AttributeValueReconstructor
 * @see StateProjection
 */
final class ProductionDerivationRule implements DerivationRule {

    private final AttributeValueComparator comparator;
    private final ComparisonPolicy policy;
    private final AttributeSchemaResolver schemas;
    private final AttributeValueReconstructor reconstructor;

    /**
     * Package-private constructor. Reached only through the {@code DerivationRule.production}
     * factories; the rule is stateless (apart from its injected immutable collaborators) and
     * may be shared freely.
     *
     * @param comparator the typed change-detection comparator; never {@code null}
     * @param policy     the float/quantity comparison tolerances; never {@code null}
     * @param schemas    the schema resolver (immutable snapshot); never {@code null}
     */
    ProductionDerivationRule(AttributeValueComparator comparator, ComparisonPolicy policy,
                             AttributeSchemaResolver schemas) {
        this.comparator = Objects.requireNonNull(comparator, "comparator");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.schemas = Objects.requireNonNull(schemas, "schemas");
        this.reconstructor = new AttributeValueReconstructor();
    }

    @Override
    public List<EventDraft> evaluate(DerivationContext context) {
        EventEnvelope env = context.envelope();
        if (!(env.payload() instanceof StateReportedEvent sr)) {
            return List.of();
        }

        String key = sr.attributeKey();
        EntityId entityId = entityIdOrNull(env.subjectRef());
        AttributeSchema schema = schemas.resolve(key).orElse(null);

        // Reconstruct BOTH operands to the schema-declared typed variant (AMD-51-INV-05).
        AttributeValue inboundTyped =
                reconstructor.reconstruct(sr.value(), sr.unit(), schema, key, entityId);
        AttributeValue priorTyped =
                reconstructPrior(context.priorState(), key, schema, entityId);

        if (!comparator.changed(priorTyped, inboundTyped, policy)) {
            return List.of();
        }

        // DP-G / §2.7: the StateChangedEvent payload stays String (the typed payload is
        // AMD-52). oldValue is the stringified prior (empty when none); newValue is the
        // reported value verbatim — identical to the pre-typed rule's construction.
        String oldNonNull = priorStringForm(context.priorState(), key);
        StateChangedEvent payload = new StateChangedEvent(
                key, oldNonNull, sr.value(), env.eventId());
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
     * Reconstructs the prior canonical value to its schema-declared variant, or returns
     * {@code null} when there is no prior state or no prior value for the key (the comparator
     * treats a {@code null} prior as a first report). The materialized prior is always a
     * {@link StringValue} (§1.2), reconstructed by the identical schema-driven parse with the
     * schema canonical unit (the stored QUANTITY magnitude is already canonical).
     */
    private AttributeValue reconstructPrior(EntityState prior, String key,
                                            AttributeSchema schema, EntityId entityId) {
        if (prior == null) {
            return null;
        }
        AttributeValue priorValue = prior.attributes().get(key);
        if (priorValue == null) {
            return null;
        }
        String priorSerialized = (priorValue instanceof StringValue sv)
                ? sv.value()
                : String.valueOf(priorValue.rawValue());
        String priorUnit = (schema != null) ? schema.canonicalUnitSymbol() : null;
        return reconstructor.reconstruct(priorSerialized, priorUnit, schema, key, entityId);
    }

    /**
     * Returns the prior canonical value in string form for the {@link StateChangedEvent}
     * payload (DP-G), or {@code ""} when no prior state or no prior value exists.
     */
    private static String priorStringForm(EntityState prior, String key) {
        if (prior == null) {
            return "";
        }
        AttributeValue value = prior.attributes().get(key);
        if (value == null) {
            return "";
        }
        return (value instanceof StringValue sv) ? sv.value() : String.valueOf(value.rawValue());
    }

    /**
     * Extracts the subject entity id for reconstruction diagnostics, or {@code null} when the
     * subject is not an entity.
     */
    private static EntityId entityIdOrNull(SubjectRef ref) {
        return (ref.type() == SubjectType.ENTITY) ? new EntityId(ref.id()) : null;
    }
}
